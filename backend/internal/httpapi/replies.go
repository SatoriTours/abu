package httpapi

import (
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"unicode/utf8"

	"abu.local/backend/internal/model"
)

func (s *chatStore) reply(w http.ResponseWriter, r *http.Request) {
	if s.provider == nil {
		writeJSON(w, 503, map[string]string{"error": "model_not_configured"})
		return
	}
	var input struct {
		MessageID string `json:"messageId"`
		Retry     bool   `json:"retry"`
	}
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 2048))
	decoder.DisallowUnknownFields()
	if decoder.Decode(&input) != nil || decoder.Decode(new(any)) != io.EOF || input.MessageID == "" {
		writeJSON(w, 400, map[string]string{"error": "invalid_reply_request"})
		return
	}
	s.mu.Lock()
	index := -1
	for i, m := range s.messages {
		if m.ID == input.MessageID {
			index = i
			break
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
	if s.generating || index != len(s.messages)-1 {
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
	s.generating = true
	start := len(s.messages) - 20
	if start < 0 {
		start = 0
	}
	contextMessages := make([]model.Message, 0, 40)
	for _, m := range s.messages[start:] {
		contextMessages = append(contextMessages, model.Message{Role: "user", Content: m.Content})
		if m.ReplyStatus == "completed" {
			contextMessages = append(contextMessages, model.Message{Role: "assistant", Content: m.Reply})
		}
	}
	s.mu.Unlock()

	result, callErr := s.provider.Complete(r.Context(), contextMessages)
	s.mu.Lock()
	defer s.mu.Unlock()
	defer func() { s.generating = false }()
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
