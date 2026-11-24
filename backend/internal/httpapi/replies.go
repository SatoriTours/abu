package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"unicode/utf8"

	"abu.local/backend/internal/model"
)

// replyTextLimit is the maximum stored reply length, shared with the model contract.
const replyTextLimit = 4000

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
		RunID     string `json:"runId"`
	}
	raw, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 2048))
	fields, valid := scopedBody(raw, []string{"messageId"}, []string{"retry", "runId"})
	if err != nil || !valid || json.Unmarshal(raw, &input) != nil || input.MessageID == "" ||
		len(input.MessageID) > 128 || !optionalRunID(fields, input.RunID) {
		apiError(w, 400, "invalid_reply_request")
		return nil, false
	}
	bot, ok := s.bots.resolve(w, bodyBotID(fields))
	if !ok {
		return nil, false
	}
	if s.provider == nil {
		apiError(w, 503, "model_not_configured")
		return nil, false
	}
	plan := &replyPlan{bot: bot, messageID: input.MessageID}
	s.mu.Lock()
	index, latest := -1, -1
	for i, m := range s.messages {
		if m.BotID == bot.ID {
			latest = i
			if m.ID == input.MessageID {
				index = i
			}
		}
	}
	if index < 0 {
		s.mu.Unlock()
		apiError(w, 404, "message_not_found")
		return nil, false
	}
	message := s.messages[index]
	if message.ReplyStatus == "completed" {
		s.mu.Unlock()
		plan.alreadyDone = true
		plan.message = message
		return plan, true
	}
	if s.generating[bot.ID] || index != latest {
		s.mu.Unlock()
		apiError(w, 409, "reply_in_progress_or_not_latest")
		return nil, false
	}
	if message.ReplyStatus != "" && !input.Retry {
		s.mu.Unlock()
		apiError(w, 409, "explicit_retry_required")
		return nil, false
	}
	next := append([]Message{}, s.messages...)
	next[index].ReplyStatus = "running"
	if err := s.save(next); err != nil {
		s.mu.Unlock()
		apiError(w, 500, "storage_failed")
		return nil, false
	}
	s.messages = next
	s.generating[bot.ID] = true
	// Limit to the last 20 turns of this role, not the last 20 global records.
	history := make([]Message, 0, 20)
	for i := index; i >= 0 && len(history) < 20; i-- {
		if s.messages[i].BotID == bot.ID {
			history = append(history, s.messages[i])
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
	s.active[bot.ID] = active
	s.mu.Unlock()
	plan.message, plan.context = message, contextMessages
	plan.ctx, plan.cancel, plan.active = ctx, cancel, active
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
	if current := s.active[plan.bot.ID]; current == plan.active {
		delete(s.active, plan.bot.ID)
	}
	delete(s.generating, plan.bot.ID)
	index := s.locate(plan.bot.ID, plan.messageID)
	if index < 0 {
		return Message{}, "failed"
	}
	next := append([]Message{}, s.messages...)
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
	if err := s.save(next); err != nil {
		// Keep a visible uncertain state. An explicit retry may incur another model call.
		s.messages[index].ReplyStatus = "interrupted"
		return s.messages[index], "storage_failed"
	}
	s.messages = next
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
	}
	raw, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 2048))
	fields, valid := scopedBody(raw, []string{"messageId"}, []string{"runId"})
	if err != nil || !valid || json.Unmarshal(raw, &input) != nil || input.MessageID == "" ||
		len(input.MessageID) > 128 || !optionalRunID(fields, input.RunID) {
		apiError(w, 400, "invalid_reply_request")
		return
	}
	bot, ok := s.bots.resolve(w, bodyBotID(fields))
	if !ok {
		return
	}
	s.mu.Lock()
	if s.locate(bot.ID, input.MessageID) < 0 {
		s.mu.Unlock()
		apiError(w, 404, "message_not_found")
		return
	}
	cancelled := false
	// A cancel that names a round must only stop that round: a late stop from a finished
	// attempt may never interrupt the retry that replaced it.
	if active := s.active[bot.ID]; active != nil && active.messageID == input.MessageID &&
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
