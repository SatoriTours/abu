package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"slices"
	"strings"
	"unicode/utf8"

	"abu.local/backend/internal/model"
)

// replyTextLimit is the maximum stored reply length, shared with the model contract.
const replyTextLimit = model.MaxReplyRunes

func rolePrompt(instructions string) []model.Message {
	if strings.TrimSpace(instructions) == "" {
		return nil
	}
	return []model.Message{{Role: "system", Content: instructions}}
}

// validReplyText rejects blank, malformed or oversized model output before it is stored.
func validReplyText(text string) bool {
	return utf8.ValidString(text) && strings.TrimSpace(text) != "" && utf8.RuneCountInString(text) <= replyTextLimit
}

// optionalRunID accepts an omitted round identifier and otherwise requires the shared
// short ASCII task id shape. A present but empty or malformed value is a request error.
func optionalRunID(fields map[string]json.RawMessage, runID string) bool {
	if fields["runId"] == nil {
		return true
	}
	return taskIDPattern.MatchString(runID)
}

// replyPlan is the result of the shared precondition stage of both reply variants.
type replyPlan struct {
	bot         Bot
	messageID   string
	sessionID   string
	key         [2]string
	alreadyDone bool
	message     Message
	context     []model.Message
	ctx         context.Context
	cancel      context.CancelFunc
	active      *activeReply
}

// planReply validates one reply request and marks the message as running. Every failure is
// reported as the regular JSON error response before any stream header is written.
func (s *chatStore) planReply(w http.ResponseWriter, r *http.Request) (*replyPlan, bool) {
	if rejectBotQuery(w, r) {
		return nil, false
	}
	var input struct {
		MessageID string `json:"messageId"`
		Retry     bool   `json:"retry"`
		// Regenerate replaces a stored reply on the newest turn instead of returning it.
		Regenerate bool   `json:"regenerate"`
		RunID      string `json:"runId"`
		SessionID  string `json:"sessionId"`
	}
	raw, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 2048))
	fields, valid := scopedBody(raw, []string{"messageId"}, []string{"retry", "regenerate", "runId", "sessionId"})
	if err != nil || !valid || json.Unmarshal(raw, &input) != nil || input.MessageID == "" ||
		len(input.MessageID) > 128 || !optionalRunID(fields, input.RunID) {
		apiError(w, 400, "invalid_reply_request")
		return nil, false
	}
	sessionID, sessionOK := optionalSessionID(fields)
	if !sessionOK {
		apiError(w, 400, "invalid_session_id")
		return nil, false
	}
	// The role read lock covers the existence check, the message state switch and its snapshot
	// write, and the in-flight marker. Publishing that marker under the read lock is what keeps
	// a concurrent DELETE refused as bot_busy instead of racing this run. The model call itself
	// happens after the lock is released, so no network wait blocks a role deletion.
	var plan *replyPlan
	if status, code := s.bots.withBotReadLock(bodyBotID(fields), func(bot Bot) {
		if s.provider == nil {
			apiError(w, 503, "model_not_configured")
			return
		}
		p := &replyPlan{bot: bot, messageID: input.MessageID, sessionID: sessionID,
			key: [2]string{bot.ID, sessionID}}
		s.mu.Lock()
		defer s.mu.Unlock()
		if !s.hasSessionLocked(bot.ID, sessionID) {
			apiError(w, 404, "session_not_found")
			return
		}
		index, latest := -1, -1
		for i, m := range s.messages {
			if m.BotID == bot.ID && m.SessionID == sessionID {
				latest = i
				if m.ID == input.MessageID {
					index = i
				}
			}
		}
		if index < 0 {
			apiError(w, 404, "message_not_found")
			return
		}
		message := s.messages[index]
		if !input.Regenerate && message.ReplyStatus == "completed" {
			p.alreadyDone = true
			p.message = message
			plan = p
			return
		}
		if s.generating[p.key] || index != latest {
			apiError(w, 409, "reply_in_progress_or_not_latest")
			return
		}
		// Regenerate is an explicit, always allowed replacement of any stored reply on the
		// newest turn, including a completed one, so it needs no separate retry flag.
		if !input.Regenerate && message.ReplyStatus != "" && !input.Retry {
			apiError(w, 409, "explicit_retry_required")
			return
		}
		next := slices.Clone(s.messages)
		next[index].ReplyStatus = "running"
		if input.Regenerate {
			// The stored reply must not become assistant context for the run that replaces it.
			next[index].Reply = ""
		}
		sessions := touchSession(s.sessions, bot.ID, sessionID, next[index].CreatedAt)
		if err := s.commit(sessions, next); err != nil {
			apiError(w, 500, "storage_failed")
			return
		}
		s.generating[p.key] = true
		// Limit to the last 20 turns of this session, not the last 20 global records.
		history := make([]Message, 0, 20)
		for i := index; i >= 0 && len(history) < 20; i-- {
			if next[i].BotID == bot.ID && next[i].SessionID == sessionID {
				history = append(history, next[i])
			}
		}
		contextMessages := rolePrompt(bot.Instructions)
		for i := len(history) - 1; i >= 0; i-- {
			m := history[i]
			contextMessages = append(contextMessages, model.Message{Role: "user", Content: m.Content})
			if m.ReplyStatus == "completed" {
				contextMessages = append(contextMessages, model.Message{Role: "assistant", Content: m.Reply})
			}
		}
		ctx, cancel := context.WithTimeout(r.Context(), model.DefaultCompletionTimeout)
		active := &activeReply{messageID: input.MessageID, runID: input.RunID, cancel: cancel}
		s.active[p.key] = active
		p.message, p.context = message, contextMessages
		p.ctx, p.cancel, p.active = ctx, cancel, active
		plan = p
	}); code != "" {
		apiError(w, status, code)
		return nil, false
	}
	// Every failure inside the scoped callback already wrote its response and left no plan.
	if plan == nil {
		return nil, false
	}
	return plan, true
}

// settleReply stores the final state of one generation and releases its in-flight
// registration inside a single lock critical section. The cancel verdict therefore shares the
// critical section that cancelReply writes the flag in, and once a generation is settled a
// later cancelReply can only see "no active generation". It returns the stored snapshot and
// one of "completed", "interrupted", "failed" or "storage_failed".
func (s *chatStore) settleReply(plan *replyPlan, result, partial string, callErr error) (Message, string) {
	defer plan.cancel()
	s.mu.Lock()
	defer s.mu.Unlock()
	// A cancel always wins over a result that arrived at the same moment.
	wasCancelled := plan.active.cancelled || errors.Is(plan.ctx.Err(), context.Canceled)
	// Unregister before unlocking so a later cancelReply sees no active generation at all.
	if current := s.active[plan.key]; current == plan.active {
		delete(s.active, plan.key)
	}
	delete(s.generating, plan.key)
	index := s.locate(plan.bot.ID, plan.sessionID, plan.messageID)
	if index < 0 {
		return Message{}, "failed"
	}
	next := slices.Clone(s.messages)
	outcome := "completed"
	switch {
	case wasCancelled:
		// Keep whatever already reached the user instead of discarding a partial answer.
		next[index].ReplyStatus = "interrupted"
		if strings.TrimSpace(partial) != "" {
			next[index].Reply = partial
		} else {
			next[index].Reply = ""
		}
		outcome = "interrupted"
	case callErr == nil && validReplyText(result):
		next[index].Reply = result
		next[index].ReplyStatus = "completed"
	default:
		next[index].Reply = ""
		next[index].ReplyStatus = "failed"
		outcome = "failed"
	}
	if err := s.commit(s.sessions, next); err != nil {
		// Keep a visible uncertain state. An explicit retry may incur another model call.
		s.messages[index].ReplyStatus = "interrupted"
		return s.messages[index], "storage_failed"
	}
	return s.messages[index], outcome
}

func (s *chatStore) reply(w http.ResponseWriter, r *http.Request) {
	plan, ok := s.planReply(w, r)
	if !ok {
		return
	}
	if plan.alreadyDone {
		writeJSON(w, 200, plan.message)
		return
	}
	result, callErr := s.provider.Complete(plan.ctx, plan.context)
	message, outcome := s.settleReply(plan, result, "", callErr)
	switch outcome {
	case "storage_failed":
		apiError(w, 500, "reply_storage_failed")
	case "failed":
		apiError(w, http.StatusBadGateway, "model_reply_failed")
	default:
		writeJSON(w, 200, message)
	}
}

func (s *chatStore) replyStream(w http.ResponseWriter, r *http.Request) {
	plan, ok := s.planReply(w, r)
	if !ok {
		return
	}
	if plan.alreadyDone {
		startEventStream(w)
		_ = writeEvent(w, "done", eventJSON(plan.message))
		return
	}
	startEventStream(w)
	var partial strings.Builder
	var result string
	var callErr error
	if streamer, canStream := s.provider.(model.StreamCompleter); canStream {
		result, callErr = streamer.CompleteStream(plan.ctx, plan.context, func(delta string) error {
			if delta == "" {
				return nil
			}
			if !utf8.ValidString(delta) ||
				utf8.RuneCountInString(partial.String())+utf8.RuneCountInString(delta) > replyTextLimit {
				return errors.New("invalid streamed delta")
			}
			partial.WriteString(delta)
			return writeEvent(w, "delta", eventJSON(struct {
				Delta string `json:"delta"`
			}{delta}))
		})
	} else {
		result, callErr = s.provider.Complete(plan.ctx, plan.context)
		if callErr == nil && validReplyText(result) {
			partial.WriteString(result)
			// A lost client is handled by the stored state, not by failing the reply.
			_ = writeEvent(w, "delta", eventJSON(struct {
				Delta string `json:"delta"`
			}{result}))
		}
	}
	message, outcome := s.settleReply(plan, result, partial.String(), callErr)
	switch outcome {
	case "storage_failed":
		_ = writeEvent(w, "error", eventJSON(map[string]any{"error": "reply_storage_failed"}))
	case "failed":
		_ = writeEvent(w, "error", eventJSON(map[string]any{"error": "model_reply_failed", "message": message}))
	default:
		_ = writeEvent(w, "done", eventJSON(message))
	}
}

// cancelReply stops the in-flight generation of one message. It is idempotent.
func (s *chatStore) cancelReply(w http.ResponseWriter, r *http.Request) {
	if rejectBotQuery(w, r) {
		return
	}
	var input struct {
		MessageID string `json:"messageId"`
		RunID     string `json:"runId"`
		SessionID string `json:"sessionId"`
	}
	raw, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 2048))
	fields, valid := scopedBody(raw, []string{"messageId"}, []string{"runId", "sessionId"})
	if err != nil || !valid || json.Unmarshal(raw, &input) != nil || input.MessageID == "" ||
		len(input.MessageID) > 128 || !optionalRunID(fields, input.RunID) {
		apiError(w, 400, "invalid_reply_request")
		return
	}
	sessionID, sessionOK := optionalSessionID(fields)
	if !sessionOK {
		apiError(w, 400, "invalid_session_id")
		return
	}
	bot, ok := s.bots.resolve(w, bodyBotID(fields))
	if !ok {
		return
	}
	s.mu.Lock()
	key := [2]string{bot.ID, sessionID}
	if s.locate(bot.ID, sessionID, input.MessageID) < 0 {
		s.mu.Unlock()
		apiError(w, 404, "message_not_found")
		return
	}
	cancelled := false
	// A cancel that names a round must only stop that round: a late stop from a finished
	// attempt may never interrupt the retry that replaced it.
	if active := s.active[key]; active != nil && active.messageID == input.MessageID &&
		(input.RunID == "" || active.runID == input.RunID) {
		active.cancelled = true
		active.cancel()
		cancelled = true
	}
	s.mu.Unlock()
	writeJSON(w, 200, map[string]any{"cancelled": cancelled, "messageId": input.MessageID, "botId": bot.ID})
}

func startEventStream(w http.ResponseWriter) {
	h := w.Header()
	h.Set("Content-Type", "text/event-stream; charset=utf-8")
	h.Set("Cache-Control", "no-store")
	h.Set("X-Accel-Buffering", "no")
	w.WriteHeader(http.StatusOK)
	_ = http.NewResponseController(w).Flush()
}

func writeEvent(w http.ResponseWriter, event string, data []byte) error {
	if _, err := fmt.Fprintf(w, "event: %s\ndata: %s\n\n", event, data); err != nil {
		return err
	}
	return http.NewResponseController(w).Flush()
}

func eventJSON(value any) []byte {
	data, err := json.Marshal(value)
	if err != nil {
		return []byte("{}")
	}
	return data
}
