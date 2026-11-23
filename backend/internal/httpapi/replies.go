package httpapi

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"unicode/utf8"

	"abu.local/backend/internal/model"
)

func rolePrompt(instructions string) []model.Message {
	if strings.TrimSpace(instructions) == "" {
		return nil
	}
	return []model.Message{{Role: "system", Content: instructions}}
}

func (s *chatStore) reply(w http.ResponseWriter, r *http.Request) {
	if rejectBotQuery(w, r) {
		return
	}
	var input struct {
		MessageID string `json:"messageId"`
		Retry     bool   `json:"retry"`
	}
	raw, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 2048))
	fields, valid := scopedBody(raw, []string{"messageId"}, []string{"retry"})
	if err != nil || !valid || json.Unmarshal(raw, &input) != nil || input.MessageID == "" || len(input.MessageID) > 128 {
		apiError(w, 400, "invalid_reply_request")
		return
	}
	bot, ok := s.bots.resolve(w, bodyBotID(fields))
	if !ok {
		return
	}
	if s.provider == nil {
		apiError(w, 503, "model_not_configured")
		return
	}
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
		writeJSON(w, 404, map[string]string{"error": "message_not_found"})
		return
	}
	message := s.messages[index]
	if message.ReplyStatus == "completed" {
		s.mu.Unlock()
		writeJSON(w, 200, message)
		return
	}
	if s.generating[bot.ID] || index != latest {
		s.mu.Unlock()
		writeJSON(w, 409, map[string]string{"error": "reply_in_progress_or_not_latest"})
		return
	}
	if message.ReplyStatus != "" && !input.Retry {
		s.mu.Unlock()
		writeJSON(w, 409, map[string]string{"error": "explicit_retry_required"})
		return
	}
	next := append([]Message{}, s.messages...)
	next[index].ReplyStatus = "running"
	if err := s.save(next); err != nil {
		s.mu.Unlock()
		writeJSON(w, 500, map[string]string{"error": "storage_failed"})
		return
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
	s.mu.Unlock()

	ctx, cancel := context.WithTimeout(r.Context(), model.DefaultCompletionTimeout)
	defer cancel()
	result, callErr := s.provider.Complete(ctx, contextMessages)
	s.mu.Lock()
	defer s.mu.Unlock()
	defer delete(s.generating, bot.ID)
	next = append([]Message{}, s.messages...)
	status := http.StatusOK
	if callErr != nil || strings.TrimSpace(result) == "" || !utf8.ValidString(result) || utf8.RuneCountInString(result) > 4000 {
		next[index].ReplyStatus = "failed"
		status = http.StatusBadGateway
	} else {
		next[index].Reply = result
		next[index].ReplyStatus = "completed"
	}
	if err := s.save(next); err != nil {
		// Keep a visible uncertain state. An explicit retry may incur another model call.
		s.messages[index].ReplyStatus = "interrupted"
		writeJSON(w, 500, map[string]string{"error": "reply_storage_failed"})
		return
	}
	s.messages = next
	if status != http.StatusOK {
		writeJSON(w, status, map[string]string{"error": "model_reply_failed"})
		return
	}
	writeJSON(w, 200, s.messages[index])
}
