package httpapi

import (
	"encoding/json"
	"io"
	"net/http"
	"slices"
	"strings"
	"time"
)

// findSessionLocked returns the stored row of one session, or -1 when the session has no row
// (which is always the case for a default session that was never renamed). Callers hold s.mu.
func (s *chatStore) findSessionLocked(botID, sessionID string) int {
	return slices.IndexFunc(s.sessions, func(session Session) bool {
		return session.BotID == botID && session.ID == sessionID
	})
}

// hasSessionLocked reports whether one session exists: the default session always does, a custom
// session only once it was created. Callers hold s.mu.
func (s *chatStore) hasSessionLocked(botID, sessionID string) bool {
	return sessionID == defaultSessionID || s.findSessionLocked(botID, sessionID) >= 0
}

// sessionCountLocked counts the sessions of one role including the default session slot, so the
// capacity check never lets a role exceed maxSessionsPerBot conversations. Callers hold s.mu.
func (s *chatStore) sessionCountLocked(botID string) int {
	count := 1
	for _, session := range s.sessions {
		if session.BotID == botID && session.ID != defaultSessionID {
			count++
		}
	}
	return count
}

// defaultSessionLocked synthesizes the default session of one role from its messages, so a role
// that only ever used the default session still lists it. Callers hold s.mu.
func (s *chatStore) defaultSessionLocked(botID string) Session {
	createdAt, updatedAt := "", ""
	for _, m := range s.messages {
		if m.BotID != botID || m.SessionID != defaultSessionID {
			continue
		}
		if createdAt == "" {
			createdAt = m.CreatedAt
		}
		updatedAt = m.CreatedAt
	}
	return Session{
		ID: defaultSessionID, BotID: botID, Title: defaultSessionTitle,
		CreatedAt: createdAt, UpdatedAt: updatedAt,
	}
}

// sessionsForBotLocked lists the sessions of one role: the default session first, then the custom
// sessions by most recent activity. Callers hold s.mu.
func (s *chatStore) sessionsForBotLocked(botID string) []Session {
	custom := make([]Session, 0, len(s.sessions))
	stored := -1
	for i, session := range s.sessions {
		if session.BotID != botID {
			continue
		}
		if session.ID == defaultSessionID {
			stored = i
			continue
		}
		custom = append(custom, session)
	}
	slices.SortStableFunc(custom, func(a, b Session) int {
		return compareSessionActivity(a.UpdatedAt, b.UpdatedAt)
	})
	result := make([]Session, 0, len(custom)+1)
	if stored >= 0 {
		result = append(result, s.sessions[stored])
	} else {
		result = append(result, s.defaultSessionLocked(botID))
	}
	return append(result, custom...)
}

// compareSessionActivity orders two sessions newest first. A stamp that is not a timestamp keeps
// its creation order behind every dated session.
func compareSessionActivity(a, b string) int {
	at, aErr := time.Parse(time.RFC3339Nano, a)
	bt, bErr := time.Parse(time.RFC3339Nano, b)
	switch {
	case aErr == nil && bErr == nil:
		return bt.Compare(at)
	case aErr == nil:
		return -1
	case bErr == nil:
		return 1
	default:
		return strings.Compare(b, a)
	}
}

// touchSession stamps the newest activity of one stored session. An implicit default session has
// no row to stamp, so its activity is always derived from its messages.
func touchSession(sessions []Session, botID, sessionID, at string) []Session {
	index := slices.IndexFunc(sessions, func(session Session) bool {
		return session.BotID == botID && session.ID == sessionID
	})
	if index < 0 || at == "" {
		return sessions
	}
	next := slices.Clone(sessions)
	next[index].UpdatedAt = at
	return next
}

// listSessions reports every conversation of one role, including the implicit default session.
func (s *chatStore) listSessions(w http.ResponseWriter, r *http.Request) {
	botID, _, ok := queryBotID(r, false)
	if !ok {
		apiError(w, 400, "invalid_bot_id")
		return
	}
	if _, ok := s.bots.resolve(w, botID); !ok {
		return
	}
	s.mu.Lock()
	sessions := s.sessionsForBotLocked(botID)
	s.mu.Unlock()
	writeJSON(w, 200, map[string]any{"sessions": sessions})
}

// createSession stores one custom session. Repeating the same request is idempotent, so a client
// that lost the response can retry instead of inventing a new id.
func (s *chatStore) createSession(w http.ResponseWriter, r *http.Request) {
	if rejectBotQuery(w, r) {
		return
	}
	raw, err := io.ReadAll(http.MaxBytesReader(w, r.Body, taskBodyLimit))
	fields, ok := scopedBody(raw, []string{"id"}, []string{"title"})
	var input struct {
		ID    string `json:"id"`
		Title string `json:"title"`
	}
	if err != nil || !ok || json.Unmarshal(raw, &input) != nil ||
		!taskIDPattern.MatchString(input.ID) || input.ID == defaultSessionID {
		apiError(w, 400, "invalid_session")
		return
	}
	title := newSessionTitle
	if fields["title"] != nil && input.Title != "" {
		title = input.Title
		if !validSessionTitle(title) {
			apiError(w, 400, "invalid_session")
			return
		}
	}
	// The role read lock is held across the child write, so a concurrent role deletion cannot
	// remove this role between the existence check and the persisted session that names it.
	if status, code := s.bots.withBotReadLock(bodyBotID(fields), func(bot Bot) {
		s.mu.Lock()
		defer s.mu.Unlock()
		if index := s.findSessionLocked(bot.ID, input.ID); index >= 0 {
			if s.sessions[index].Title != title {
				apiError(w, 409, "id_conflict")
				return
			}
			writeJSON(w, 200, s.sessions[index])
			return
		}
		if s.sessionCountLocked(bot.ID) >= maxSessionsPerBot {
			apiError(w, 409, "session_limit")
			return
		}
		now := nowStamp()
		session := Session{ID: input.ID, BotID: bot.ID, Title: title, CreatedAt: now, UpdatedAt: now}
		sessions := append(slices.Clone(s.sessions), session)
		if err := s.commit(sessions, s.messages); err != nil {
			apiError(w, 500, "storage_failed")
			return
		}
		writeJSON(w, 201, session)
	}); code != "" {
		apiError(w, status, code)
	}
}

// renameSession retitles one session. Renaming the default session stores its row for the first
// time, so the new title survives a restart.
func (s *chatStore) renameSession(w http.ResponseWriter, r *http.Request) {
	if rejectBotQuery(w, r) {
		return
	}
	id := r.PathValue("id")
	raw, err := io.ReadAll(http.MaxBytesReader(w, r.Body, taskBodyLimit))
	fields, ok := scopedBody(raw, []string{"title"}, nil)
	var input struct {
		Title string `json:"title"`
	}
	if err != nil || !ok || json.Unmarshal(raw, &input) != nil ||
		!taskIDPattern.MatchString(id) || !validSessionTitle(input.Title) {
		apiError(w, 400, "invalid_session")
		return
	}
	if status, code := s.bots.withBotReadLock(bodyBotID(fields), func(bot Bot) {
		s.mu.Lock()
		defer s.mu.Unlock()
		now := nowStamp()
		sessions := slices.Clone(s.sessions)
		var session Session
		if index := s.findSessionLocked(bot.ID, id); index >= 0 {
			sessions[index].Title = input.Title
			sessions[index].UpdatedAt = now
			session = sessions[index]
		} else if id == defaultSessionID {
			session = s.defaultSessionLocked(bot.ID)
			session.Title = input.Title
			if session.CreatedAt == "" {
				session.CreatedAt = now
			}
			session.UpdatedAt = now
			sessions = append(sessions, session)
		} else {
			apiError(w, 404, "session_not_found")
			return
		}
		if err := s.commit(sessions, s.messages); err != nil {
			apiError(w, 500, "storage_failed")
			return
		}
		writeJSON(w, 200, session)
	}); code != "" {
		apiError(w, status, code)
	}
}

// deleteSession removes one session and every message it owns in a single atomic write.
func (s *chatStore) deleteSession(w http.ResponseWriter, r *http.Request) {
	botID, _, ok := queryBotID(r, false)
	if !ok {
		apiError(w, 400, "invalid_bot_id")
		return
	}
	id := r.PathValue("id")
	if !taskIDPattern.MatchString(id) {
		apiError(w, 400, "invalid_session_id")
		return
	}
	// The default session is part of the role, not user data.
	if id == defaultSessionID {
		apiError(w, 409, "cannot_delete_default_session")
		return
	}
	if status, code := s.bots.withBotReadLock(botID, func(bot Bot) {
		s.mu.Lock()
		defer s.mu.Unlock()
		index := s.findSessionLocked(bot.ID, id)
		if index < 0 {
			apiError(w, 404, "session_not_found")
			return
		}
		if s.generating[[2]string{bot.ID, id}] {
			apiError(w, 409, "reply_in_progress")
			return
		}
		sessions := slices.Delete(slices.Clone(s.sessions), index, index+1)
		messages := make([]Message, 0, len(s.messages))
		removed := 0
		for _, m := range s.messages {
			if m.BotID == bot.ID && m.SessionID == id {
				removed++
				continue
			}
			messages = append(messages, m)
		}
		if err := s.commit(sessions, messages); err != nil {
			apiError(w, 500, "storage_failed")
			return
		}
		writeJSON(w, 200, map[string]any{
			"deleted": true, "id": id, "botId": bot.ID, "deletedMessages": removed,
		})
	}); code != "" {
		apiError(w, status, code)
	}
}
