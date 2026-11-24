package httpapi

import (
	"bytes"
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"abu.local/backend/internal/model"
)

const (
	defaultSessionID    = "default"
	defaultSessionTitle = "默认对话"
	newSessionTitle     = "新对话"
	// maxSessionsPerBot bounds the sessions of one role, including the default session slot.
	maxSessionsPerBot = 50
	// maxSessionMessages bounds one session, maxTotalMessages bounds the whole snapshot.
	maxSessionMessages   = 500
	maxTotalMessages     = 5000
	maxChatSnapshotBytes = 32 * 1024 * 1024
	maxSessionTitleRunes = 60
	chatSnapshotVersion  = 2
	// defaultPageLimit is the history page size when the caller does not ask for one.
	defaultPageLimit = 200
	maxPageLimit     = 200
)

type Message struct {
	ID          string `json:"id"`
	BotID       string `json:"botId"`
	SessionID   string `json:"sessionId,omitempty"`
	Content     string `json:"content"`
	CreatedAt   string `json:"createdAt"`
	Reply       string `json:"reply,omitempty"`
	ReplyStatus string `json:"replyStatus,omitempty"`
}

// Session is one named conversation of one role. A role's default session is implicit until it
// is renamed, so a snapshot may hold messages of a session that has no stored row.
type Session struct {
	ID        string `json:"id"`
	BotID     string `json:"botId"`
	Title     string `json:"title"`
	CreatedAt string `json:"createdAt"`
	UpdatedAt string `json:"updatedAt"`
}

// chatSnapshot is the on-disk form. Sessions and messages share one file, so a session rename
// and a message write can never be split across two snapshots that disagree.
type chatSnapshot struct {
	Version  int       `json:"version"`
	Sessions []Session `json:"sessions"`
	Messages []Message `json:"messages"`
}

// activeReply tracks the single in-flight generation of one (role, session) so that a client can
// cancel it without holding the store lock across the model call.
type activeReply struct {
	messageID string
	// runID is the caller-supplied round identifier. A cancel request that names a different
	// round must not stop this generation.
	runID     string
	cancel    context.CancelFunc
	cancelled bool
}

type chatStore struct {
	mu       sync.Mutex
	path     string
	sessions []Session
	messages []Message
	// generating and active are keyed by (role, session), so two sessions of one role never
	// block each other's newest turn and a role delete sees every in-flight generation.
	generating map[[2]string]bool
	active     map[[2]string]*activeReply
	provider   model.Completer
	bots       *Bots
}

// NewChatHandler is a bounded, single-process development store, not a production database.
func NewChatHandler(path, token string) (http.Handler, error) {
	return NewChatHandlerWithModel(path, token, nil)
}

func NewChatHandlerWithModel(path, token string, provider model.Completer) (http.Handler, error) {
	return NewChatHandlerWithBots(path, token, provider, nil)
}

func NewChatHandlerWithBots(path, token string, provider model.Completer, bots *Bots) (http.Handler, error) {
	if len(token) < 32 {
		return nil, errors.New("ABU_DEV_TOKEN must contain at least 32 bytes")
	}
	sessions, messages, err := loadChatStore(path, bots)
	if err != nil {
		return nil, err
	}
	store := &chatStore{
		path: path, sessions: sessions, messages: messages, provider: provider, bots: bots,
		generating: map[[2]string]bool{}, active: map[[2]string]*activeReply{},
	}
	recovered := false
	for i := range store.messages {
		if store.messages[i].ReplyStatus == "running" {
			store.messages[i].ReplyStatus = "interrupted"
			recovered = true
		}
	}
	if recovered {
		if err := store.save(store.sessions, store.messages); err != nil {
			return nil, err
		}
	}
	if bots != nil {
		bots.RegisterDeleteHook(store)
	}
	mux := http.NewServeMux()
	mux.HandleFunc("POST /api/chat/replies", store.reply)
	mux.HandleFunc("POST /api/chat/replies/stream", store.replyStream)
	mux.HandleFunc("POST /api/chat/replies/cancel", store.cancelReply)
	mux.HandleFunc("GET /api/chat/sessions", store.listSessions)
	mux.HandleFunc("POST /api/chat/sessions", store.createSession)
	mux.HandleFunc("PUT /api/chat/sessions/{id}", store.renameSession)
	mux.HandleFunc("DELETE /api/chat/sessions/{id}", store.deleteSession)
	mux.HandleFunc("GET /api/chat/messages", store.history)
	mux.HandleFunc("POST /api/chat/messages", store.createMessage)
	mux.HandleFunc("PUT /api/chat/messages/{id}", store.editMessage)
	mux.HandleFunc("DELETE /api/chat/messages", store.clearMessages)
	mux.HandleFunc("DELETE /api/chat/messages/{id}", store.deleteMessage)
	return requireDevToken(token, mux), nil
}

// loadChatStore reads the snapshot, if any. A damaged file is an error that keeps its bytes
// untouched: the service refuses to start instead of overwriting an unreadable history.
func loadChatStore(path string, bots *Bots) ([]Session, []Message, error) {
	f, err := os.Open(path)
	if errors.Is(err, os.ErrNotExist) {
		return []Session{}, []Message{}, nil
	}
	if err != nil {
		return nil, nil, err
	}
	defer f.Close()
	raw, readErr := io.ReadAll(io.LimitReader(f, maxChatSnapshotBytes+1))
	if readErr != nil {
		return nil, nil, readErr
	}
	if len(raw) > maxChatSnapshotBytes {
		return nil, nil, errors.New("chat store too large")
	}
	return loadChatSnapshot(raw, bots)
}

// loadChatSnapshot accepts both on-disk forms: the legacy bare message array and the version 2
// object that also carries the stored sessions.
func loadChatSnapshot(raw []byte, bots *Bots) ([]Session, []Message, error) {
	trimmed := bytes.TrimSpace(raw)
	if !validRoleJSON(raw) || len(trimmed) == 0 {
		return nil, nil, errors.New("invalid chat snapshot")
	}
	if trimmed[0] != '{' {
		messages, err := loadLegacyMessages(trimmed, bots)
		return []Session{}, messages, err
	}
	return loadSessionSnapshot(trimmed, bots)
}

// loadLegacyMessages reads the original array form: every message belongs to its role's default
// session, which has no stored row.
func loadLegacyMessages(raw []byte, bots *Bots) ([]Message, error) {
	var rows []json.RawMessage
	if json.Unmarshal(raw, &rows) != nil || rows == nil || len(rows) > maxTotalMessages {
		return nil, errors.New("invalid chat snapshot")
	}
	messages := make([]Message, 0, len(rows))
	seen := map[[2]string]bool{}
	for _, row := range rows {
		fields, ok := scopedBody(row, []string{"id", "content", "createdAt"}, []string{"reply", "replyStatus"})
		var m Message
		if !ok || json.Unmarshal(row, &m) != nil || m.ID == "" || len(m.ID) > 128 ||
			!validTaskText(m.Content) || m.CreatedAt == "" ||
			(m.Reply != "" && !validReplyText(m.Reply)) {
			return nil, errors.New("invalid chat record")
		}
		switch m.ReplyStatus {
		case "", "running", "interrupted", "completed", "failed":
		default:
			return nil, errors.New("invalid chat reply state")
		}
		m.BotID = bodyBotID(fields)
		m.SessionID = defaultSessionID
		key := [2]string{m.BotID, m.ID}
		if _, known := bots.lookup(m.BotID); !known || seen[key] {
			return nil, errors.New("invalid chat record scope")
		}
		seen[key] = true
		messages = append(messages, m)
	}
	return messages, nil
}

// loadSessionSnapshot reads the version 2 form and rejects anything it cannot represent exactly,
// including unknown fields, duplicate keys, null members and cross-scope references.
func loadSessionSnapshot(raw []byte, bots *Bots) ([]Session, []Message, error) {
	fields := exactTodoObject(raw, "version", "sessions", "messages")
	if fields == nil {
		return nil, nil, errors.New("invalid chat snapshot")
	}
	var version int
	if json.Unmarshal(fields["version"], &version) != nil || version != chatSnapshotVersion {
		return nil, nil, errors.New("invalid chat snapshot version")
	}
	var sessionRows, messageRows []json.RawMessage
	if json.Unmarshal(fields["sessions"], &sessionRows) != nil || sessionRows == nil ||
		json.Unmarshal(fields["messages"], &messageRows) != nil || messageRows == nil {
		return nil, nil, errors.New("invalid chat snapshot")
	}

	sessions := make([]Session, 0, len(sessionRows))
	known := map[[2]string]bool{}
	perBot := map[string]int{}
	for _, row := range sessionRows {
		var session Session
		if exactTodoObject(row, "id", "botId", "title", "createdAt", "updatedAt") == nil ||
			json.Unmarshal(row, &session) != nil ||
			!taskIDPattern.MatchString(session.ID) || !taskIDPattern.MatchString(session.BotID) ||
			!validSessionTitle(session.Title) || session.CreatedAt == "" || session.UpdatedAt == "" {
			return nil, nil, errors.New("invalid chat session")
		}
		key := [2]string{session.BotID, session.ID}
		if _, ok := bots.lookup(session.BotID); !ok || known[key] || perBot[session.BotID] >= maxSessionsPerBot {
			return nil, nil, errors.New("invalid chat session scope")
		}
		known[key] = true
		perBot[session.BotID]++
		sessions = append(sessions, session)
	}

	if len(messageRows) > maxTotalMessages {
		return nil, nil, errors.New("invalid chat snapshot")
	}
	messages := make([]Message, 0, len(messageRows))
	seen := map[[2]string]bool{}
	perSession := map[[2]string]int{}
	for _, row := range messageRows {
		// Presence of botId and sessionId is enforced by the field lists, not by the decoder.
		_, ok := scopedBody(row, []string{"id", "content", "createdAt", "botId", "sessionId"},
			[]string{"reply", "replyStatus"})
		var m Message
		if !ok || json.Unmarshal(row, &m) != nil || m.ID == "" || len(m.ID) > 128 ||
			!validTaskText(m.Content) || m.CreatedAt == "" ||
			(m.Reply != "" && !validReplyText(m.Reply)) {
			return nil, nil, errors.New("invalid chat record")
		}
		switch m.ReplyStatus {
		case "", "running", "interrupted", "completed", "failed":
		default:
			return nil, nil, errors.New("invalid chat reply state")
		}
		key := [2]string{m.BotID, m.ID}
		if _, ok := bots.lookup(m.BotID); !ok || seen[key] {
			return nil, nil, errors.New("invalid chat record scope")
		}
		if !taskIDPattern.MatchString(m.SessionID) ||
			(m.SessionID != defaultSessionID && !known[[2]string{m.BotID, m.SessionID}]) {
			return nil, nil, errors.New("invalid chat record scope")
		}
		scope := [2]string{m.BotID, m.SessionID}
		if perSession[scope] >= maxSessionMessages {
			return nil, nil, errors.New("invalid chat record scope")
		}
		seen[key] = true
		perSession[scope]++
		messages = append(messages, m)
	}
	return sessions, messages, nil
}

// save writes both halves in one atomic replacement and keeps the snapshot non-null.
func (s *chatStore) save(sessions []Session, messages []Message) error {
	if sessions == nil {
		sessions = []Session{}
	}
	if messages == nil {
		messages = []Message{}
	}
	return saveJSONSnapshot(s.path, ".messages-*", chatSnapshot{
		Version: chatSnapshotVersion, Sessions: sessions, Messages: messages,
	})
}

// commit adopts a snapshot in memory only after it reached the disk.
func (s *chatStore) commit(sessions []Session, messages []Message) error {
	if err := s.save(sessions, messages); err != nil {
		return err
	}
	s.sessions, s.messages = sessions, messages
	return nil
}

// validSessionTitle rejects a blank or oversized session title before it is stored.
func validSessionTitle(title string) bool {
	return utf8.ValidString(title) && strings.TrimSpace(title) != "" &&
		utf8.RuneCountInString(title) <= maxSessionTitleRunes
}

func nowStamp() string { return time.Now().UTC().Format(time.RFC3339Nano) }

// optionalSessionID accepts an omitted session and otherwise requires the shared short id shape.
func optionalSessionID(fields map[string]json.RawMessage) (string, bool) {
	if fields["sessionId"] == nil {
		return defaultSessionID, true
	}
	var id string
	if json.Unmarshal(fields["sessionId"], &id) != nil || !taskIDPattern.MatchString(id) {
		return "", false
	}
	return id, true
}

// queryScope parses the role and session query of one message route. Only these two keys are
// accepted, at most once each, and the session is validated like a body field.
func queryScope(r *http.Request) (string, string, int, string) {
	q, err := url.ParseQuery(r.URL.RawQuery)
	if err != nil {
		return "", "", 400, "invalid_bot_id"
	}
	for key, values := range q {
		switch key {
		case "botId":
			if len(values) != 1 {
				return "", "", 400, "invalid_bot_id"
			}
		case "sessionId":
			if len(values) != 1 {
				return "", "", 400, "invalid_session_id"
			}
		default:
			return "", "", 400, "invalid_message"
		}
	}
	botID, sessionID := defaultBotID, defaultSessionID
	if values := q["botId"]; len(values) == 1 {
		botID = values[0]
	}
	if !taskIDPattern.MatchString(botID) {
		return "", "", 400, "invalid_bot_id"
	}
	if values := q["sessionId"]; len(values) == 1 {
		sessionID = values[0]
	}
	if !taskIDPattern.MatchString(sessionID) {
		return "", "", 400, "invalid_session_id"
	}
	return botID, sessionID, 0, ""
}

// historyQuery is the parsed pagination request of one history read.
type historyQuery struct {
	botID     string
	sessionID string
	before    string
	limit     int
}

func parseHistoryQuery(r *http.Request) (historyQuery, int, string) {
	q, err := url.ParseQuery(r.URL.RawQuery)
	if err != nil {
		return historyQuery{}, 400, "invalid_message"
	}
	for key, values := range q {
		switch key {
		case "botId", "sessionId", "before", "limit":
			if len(values) != 1 {
				switch key {
				case "botId":
					return historyQuery{}, 400, "invalid_bot_id"
				case "sessionId":
					return historyQuery{}, 400, "invalid_session_id"
				default:
					return historyQuery{}, 400, "invalid_message"
				}
			}
		default:
			return historyQuery{}, 400, "invalid_message"
		}
	}
	out := historyQuery{botID: defaultBotID, sessionID: defaultSessionID, limit: defaultPageLimit}
	if values := q["botId"]; len(values) == 1 {
		out.botID = values[0]
	}
	if !taskIDPattern.MatchString(out.botID) {
		return historyQuery{}, 400, "invalid_bot_id"
	}
	if values := q["sessionId"]; len(values) == 1 {
		out.sessionID = values[0]
	}
	if !taskIDPattern.MatchString(out.sessionID) {
		return historyQuery{}, 400, "invalid_session_id"
	}
	if values := q["before"]; len(values) == 1 {
		if values[0] == "" || len(values[0]) > 128 {
			return historyQuery{}, 400, "invalid_message"
		}
		out.before = values[0]
	}
	if values := q["limit"]; len(values) == 1 {
		parsed, convErr := strconv.Atoi(values[0])
		if convErr != nil || parsed < 1 || parsed > maxPageLimit {
			return historyQuery{}, 400, "invalid_message"
		}
		out.limit = parsed
	}
	return out, 0, ""
}

// history returns one page of one session, oldest message first. Older pages are requested with
// the nextBefore cursor of the previous response.
func (s *chatStore) history(w http.ResponseWriter, r *http.Request) {
	query, status, code := parseHistoryQuery(r)
	if code != "" {
		apiError(w, status, code)
		return
	}
	if _, ok := s.bots.resolve(w, query.botID); !ok {
		return
	}
	s.mu.Lock()
	known := s.hasSessionLocked(query.botID, query.sessionID)
	scoped := make([]Message, 0, 16)
	if known {
		for _, m := range s.messages {
			if m.BotID == query.botID && m.SessionID == query.sessionID {
				scoped = append(scoped, m)
			}
		}
	}
	s.mu.Unlock()
	if !known {
		apiError(w, 404, "session_not_found")
		return
	}
	if query.before != "" {
		index := slices.IndexFunc(scoped, func(m Message) bool { return m.ID == query.before })
		if index < 0 {
			apiError(w, 404, "message_not_found")
			return
		}
		scoped = scoped[:index]
	}
	hasMore, nextBefore := false, ""
	if len(scoped) > query.limit {
		scoped = scoped[len(scoped)-query.limit:]
		hasMore, nextBefore = true, scoped[0].ID
	}
	writeJSON(w, 200, struct {
		Messages        []Message `json:"messages"`
		ModelConfigured bool      `json:"modelConfigured"`
		SessionID       string    `json:"sessionId"`
		HasMore         bool      `json:"hasMore"`
		NextBefore      string    `json:"nextBefore,omitempty"`
	}{scoped, s.provider != nil, query.sessionID, hasMore, nextBefore})
}

func (s *chatStore) createMessage(w http.ResponseWriter, r *http.Request) {
	if rejectBotQuery(w, r) {
		return
	}
	raw, err := io.ReadAll(http.MaxBytesReader(w, r.Body, taskBodyLimit))
	fields, ok := scopedBody(raw, []string{"id", "content"}, []string{"sessionId"})
	var input Message
	if err != nil || !ok || json.Unmarshal(raw, &input) != nil ||
		len(input.ID) == 0 || len(input.ID) > 128 || !validTaskText(input.Content) {
		apiError(w, 400, "invalid_message")
		return
	}
	sessionID, sessionOK := optionalSessionID(fields)
	if !sessionOK {
		apiError(w, 400, "invalid_session_id")
		return
	}
	// The role read lock is held across the child write, so a concurrent role deletion cannot
	// remove this role between the existence check and the persisted message that names it.
	if status, code := s.bots.withBotReadLock(bodyBotID(fields), func(bot Bot) {
		s.mu.Lock()
		defer s.mu.Unlock()
		if !s.hasSessionLocked(bot.ID, sessionID) {
			apiError(w, 404, "session_not_found")
			return
		}
		for _, m := range s.messages {
			if m.BotID == bot.ID && m.ID == input.ID {
				if m.Content != input.Content || m.SessionID != sessionID {
					apiError(w, 409, "id_conflict")
					return
				}
				writeJSON(w, 200, m)
				return
			}
		}
		if s.generating[[2]string{bot.ID, sessionID}] {
			apiError(w, 409, "reply_in_progress")
			return
		}
		if s.sessionMessageCountLocked(bot.ID, sessionID) >= maxSessionMessages ||
			len(s.messages) >= maxTotalMessages {
			apiError(w, 409, "history_limit")
			return
		}
		now := nowStamp()
		message := Message{ID: input.ID, BotID: bot.ID, SessionID: sessionID, Content: input.Content, CreatedAt: now}
		messages := append(slices.Clone(s.messages), message)
		sessions := touchSession(s.sessions, bot.ID, sessionID, now)
		if err := s.commit(sessions, messages); err != nil {
			apiError(w, 500, "storage_failed")
			return
		}
		writeJSON(w, 201, message)
	}); code != "" {
		apiError(w, status, code)
	}
}

// locate returns the index of one message inside the (role, session) scope. Callers hold s.mu.
func (s *chatStore) locate(botID, sessionID, messageID string) int {
	for i, m := range s.messages {
		if m.BotID == botID && m.SessionID == sessionID && m.ID == messageID {
			return i
		}
	}
	return -1
}

// sessionMessageCountLocked counts the stored messages of one session. Callers hold s.mu.
func (s *chatStore) sessionMessageCountLocked(botID, sessionID string) int {
	count := 0
	for _, m := range s.messages {
		if m.BotID == botID && m.SessionID == sessionID {
			count++
		}
	}
	return count
}

// CheckBotDeletable refuses to delete a role that is still producing a reply in any session.
func (s *chatStore) CheckBotDeletable(botID string) (int, string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for key := range s.generating {
		if key[0] == botID {
			return 409, "bot_busy"
		}
	}
	return 0, ""
}

// PurgeBot removes every session and message of one role in a single atomic snapshot write and
// returns a rollback that restores those rows.
func (s *chatStore) PurgeBot(botID string) (func() error, int, string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	sessions := make([]Session, 0, len(s.sessions))
	removedSessions := make([]Session, 0, 4)
	for _, session := range s.sessions {
		if session.BotID == botID {
			removedSessions = append(removedSessions, session)
		} else {
			sessions = append(sessions, session)
		}
	}
	messages := make([]Message, 0, len(s.messages))
	removed := make([]Message, 0, 4)
	for _, m := range s.messages {
		if m.BotID == botID {
			removed = append(removed, m)
		} else {
			messages = append(messages, m)
		}
	}
	if len(removedSessions) == 0 && len(removed) == 0 {
		return nil, 0, ""
	}
	if err := s.save(sessions, messages); err != nil {
		return nil, 503, "storage_unavailable"
	}
	s.sessions, s.messages = sessions, messages
	return func() error {
		s.mu.Lock()
		defer s.mu.Unlock()
		// The store stays unlocked between the purge and this rollback, so a generation of
		// another role may have settled meanwhile and written its session or reply. Merge the
		// purged rows back into the current snapshot instead of the older full slice.
		mergedSessions := mergeSessions(s.sessions, removedSessions)
		merged := mergeMessages(s.messages, removed)
		if err := s.save(mergedSessions, merged); err != nil {
			return err
		}
		s.sessions, s.messages = mergedSessions, merged
		return nil
	}, 0, ""
}

// mergeSessions puts the purged sessions back into the current snapshot. Sessions of other roles
// keep their latest state untouched.
func mergeSessions(current, removed []Session) []Session {
	merged := slices.Clone(current)
	for _, session := range removed {
		known := slices.ContainsFunc(merged, func(other Session) bool {
			return other.BotID == session.BotID && other.ID == session.ID
		})
		if !known {
			merged = append(merged, session)
		}
	}
	return merged
}

// mergeMessages puts the purged messages back into the current snapshot. Messages of other roles
// keep their latest state untouched, and the result stays ordered by creation time.
func mergeMessages(current, removed []Message) []Message {
	at := func(m Message) (time.Time, bool) {
		parsed, err := time.Parse(time.RFC3339Nano, m.CreatedAt)
		return parsed, err == nil
	}
	merged := slices.Clone(current)
	for _, m := range removed {
		if slices.ContainsFunc(merged, func(other Message) bool { return other.BotID == m.BotID && other.ID == m.ID }) {
			continue
		}
		created, known := at(m)
		i := -1
		if known {
			i = slices.IndexFunc(merged, func(other Message) bool {
				otherCreated, otherKnown := at(other)
				return otherKnown && otherCreated.After(created)
			})
		}
		if i < 0 {
			merged = append(merged, m)
			continue
		}
		merged = slices.Insert(merged, i, m)
	}
	return merged
}

// latest returns the index of the newest message of one session, or -1. Callers hold s.mu.
func (s *chatStore) latest(botID, sessionID string) int {
	latest := -1
	for i, m := range s.messages {
		if m.BotID == botID && m.SessionID == sessionID {
			latest = i
		}
	}
	return latest
}

// editMessage rewrites the text of the newest turn of one session and drops its stored reply, so
// the caller can ask for a fresh answer to the edited question.
func (s *chatStore) editMessage(w http.ResponseWriter, r *http.Request) {
	if rejectBotQuery(w, r) {
		return
	}
	raw, err := io.ReadAll(http.MaxBytesReader(w, r.Body, taskBodyLimit))
	fields, ok := scopedBody(raw, []string{"content"}, []string{"sessionId"})
	var input struct {
		Content string `json:"content"`
	}
	id := r.PathValue("id")
	if err != nil || !ok || json.Unmarshal(raw, &input) != nil ||
		len(id) == 0 || len(id) > 128 || !validTaskText(input.Content) {
		apiError(w, 400, "invalid_message")
		return
	}
	sessionID, sessionOK := optionalSessionID(fields)
	if !sessionOK {
		apiError(w, 400, "invalid_session_id")
		return
	}
	if status, code := s.bots.withBotReadLock(bodyBotID(fields), func(bot Bot) {
		s.mu.Lock()
		defer s.mu.Unlock()
		if s.generating[[2]string{bot.ID, sessionID}] {
			apiError(w, 409, "reply_in_progress")
			return
		}
		index := s.locate(bot.ID, sessionID, id)
		if index < 0 {
			apiError(w, 404, "message_not_found")
			return
		}
		if index != s.latest(bot.ID, sessionID) {
			apiError(w, 409, "not_latest_message")
			return
		}
		messages := slices.Clone(s.messages)
		messages[index].Content = input.Content
		messages[index].Reply = ""
		messages[index].ReplyStatus = ""
		sessions := touchSession(s.sessions, bot.ID, sessionID, nowStamp())
		if err := s.commit(sessions, messages); err != nil {
			apiError(w, 500, "storage_failed")
			return
		}
		writeJSON(w, 200, messages[index])
	}); code != "" {
		apiError(w, status, code)
	}
}

func (s *chatStore) deleteMessage(w http.ResponseWriter, r *http.Request) {
	botID, sessionID, status, code := queryScope(r)
	if code != "" {
		apiError(w, status, code)
		return
	}
	id := r.PathValue("id")
	if id == "" || len(id) > 128 {
		apiError(w, 400, "invalid_message")
		return
	}
	if status, code := s.bots.withBotReadLock(botID, func(bot Bot) {
		s.mu.Lock()
		defer s.mu.Unlock()
		if s.generating[[2]string{bot.ID, sessionID}] {
			apiError(w, 409, "reply_in_progress")
			return
		}
		index := s.locate(bot.ID, sessionID, id)
		if index < 0 {
			apiError(w, 404, "message_not_found")
			return
		}
		messages := slices.Delete(slices.Clone(s.messages), index, index+1)
		if err := s.commit(touchSession(s.sessions, bot.ID, sessionID, nowStamp()), messages); err != nil {
			apiError(w, 500, "storage_failed")
			return
		}
		writeJSON(w, 200, map[string]any{"deleted": true, "id": id, "botId": bot.ID, "sessionId": sessionID})
	}); code != "" {
		apiError(w, status, code)
	}
}

// clearMessages removes the messages of one session only, never those of the role's other
// sessions.
func (s *chatStore) clearMessages(w http.ResponseWriter, r *http.Request) {
	botID, sessionID, status, code := queryScope(r)
	if code != "" {
		apiError(w, status, code)
		return
	}
	if status, code := s.bots.withBotReadLock(botID, func(bot Bot) {
		s.mu.Lock()
		defer s.mu.Unlock()
		if s.generating[[2]string{bot.ID, sessionID}] {
			apiError(w, 409, "reply_in_progress")
			return
		}
		messages := make([]Message, 0, len(s.messages))
		for _, m := range s.messages {
			if m.BotID == bot.ID && m.SessionID == sessionID {
				continue
			}
			messages = append(messages, m)
		}
		count := len(s.messages) - len(messages)
		if count > 0 {
			if err := s.commit(touchSession(s.sessions, bot.ID, sessionID, nowStamp()), messages); err != nil {
				apiError(w, 500, "storage_failed")
				return
			}
		}
		writeJSON(w, 200, map[string]any{"deletedCount": count, "botId": bot.ID, "sessionId": sessionID})
	}); code != "" {
		apiError(w, status, code)
	}
}

func requireDevToken(token string, next http.Handler) http.Handler {
	expected := []byte("Bearer " + token)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		if subtle.ConstantTimeCompare([]byte(r.Header.Get("Authorization")), expected) != 1 {
			writeJSON(w, 401, map[string]string{"error": "unauthorized"})
			return
		}
		next.ServeHTTP(w, r)
	})
}
func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func saveJSONSnapshot(path, tempPattern string, value any) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	f, err := os.CreateTemp(dir, tempPattern)
	if err != nil {
		return err
	}
	defer os.Remove(f.Name())
	if err = json.NewEncoder(f).Encode(value); err != nil {
		f.Close()
		return err
	}
	if err = f.Sync(); err != nil {
		f.Close()
		return err
	}
	if err = f.Close(); err != nil {
		return err
	}
	return os.Rename(f.Name(), path)
}
