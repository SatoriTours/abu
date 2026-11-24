package httpapi

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"time"

	"abu.local/backend/internal/model"
)

type Message struct {
	ID          string `json:"id"`
	BotID       string `json:"botId"`
	Content     string `json:"content"`
	CreatedAt   string `json:"createdAt"`
	Reply       string `json:"reply,omitempty"`
	ReplyStatus string `json:"replyStatus,omitempty"`
}

// activeReply tracks the single in-flight generation of one bot so that a client can
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
	mu         sync.Mutex
	path       string
	messages   []Message
	generating map[string]bool
	active     map[string]*activeReply
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
	store := &chatStore{
		path: path, messages: []Message{}, provider: provider, bots: bots,
		generating: map[string]bool{}, active: map[string]*activeReply{},
	}
	f, err := os.Open(path)
	if err == nil {
		defer f.Close()
		raw, readErr := io.ReadAll(io.LimitReader(f, 16*1024*1024+1))
		if readErr != nil {
			return nil, readErr
		}
		if len(raw) > 16*1024*1024 {
			return nil, errors.New("chat store too large")
		}
		store.messages, err = loadChatMessages(raw, bots)
		if err != nil {
			return nil, err
		}
	} else if !os.IsNotExist(err) {
		return nil, err
	}
	if store.messages == nil {
		store.messages = []Message{}
	}
	recovered := false
	for i := range store.messages {
		if store.messages[i].ReplyStatus == "running" {
			store.messages[i].ReplyStatus = "interrupted"
			recovered = true
		}
	}
	if recovered {
		if err := store.save(store.messages); err != nil {
			return nil, err
		}
	}
	mux := http.NewServeMux()
	mux.HandleFunc("POST /api/chat/replies", store.reply)
	mux.HandleFunc("POST /api/chat/replies/stream", store.replyStream)
	mux.HandleFunc("POST /api/chat/replies/cancel", store.cancelReply)
	mux.HandleFunc("GET /api/chat/messages", store.history)
	mux.HandleFunc("POST /api/chat/messages", store.createMessage)
	mux.HandleFunc("DELETE /api/chat/messages", store.clearMessages)
	mux.HandleFunc("DELETE /api/chat/messages/{id}", store.deleteMessage)
	return requireDevToken(token, mux), nil
}

func loadChatMessages(raw []byte, bots *Bots) ([]Message, error) {
	var rows []json.RawMessage
	if !validRoleJSON(raw) || json.Unmarshal(raw, &rows) != nil || rows == nil || len(rows) > 200 {
		return nil, errors.New("invalid chat snapshot")
	}
	messages := make([]Message, 0, len(rows))
	seen := map[[2]string]bool{}
	for _, row := range rows {
		fields, ok := scopedBody(row, []string{"id", "content", "createdAt"}, []string{"reply", "replyStatus"})
		var m Message
		if !ok || json.Unmarshal(row, &m) != nil || m.ID == "" || len(m.ID) > 128 ||
			!validTaskText(m.Content) || m.CreatedAt == "" ||
			(m.Reply != "" && !validTaskText(m.Reply)) {
			return nil, errors.New("invalid chat record")
		}
		switch m.ReplyStatus {
		case "", "running", "interrupted", "completed", "failed":
		default:
			return nil, errors.New("invalid chat reply state")
		}
		m.BotID = bodyBotID(fields)
		key := [2]string{m.BotID, m.ID}
		if _, known := bots.lookup(m.BotID); !known || seen[key] {
			return nil, errors.New("invalid chat record scope")
		}
		seen[key] = true
		messages = append(messages, m)
	}
	return messages, nil
}

func (s *chatStore) history(w http.ResponseWriter, r *http.Request) {
	id, _, ok := queryBotID(r, false)
	if !ok {
		apiError(w, 400, "invalid_bot_id")
		return
	}
	if _, ok := s.bots.resolve(w, id); !ok {
		return
	}
	s.mu.Lock()
	messages := []Message{}
	for _, m := range s.messages {
		if m.BotID == id {
			messages = append(messages, m)
		}
	}
	s.mu.Unlock()
	writeJSON(w, 200, map[string]any{"messages": messages, "modelConfigured": s.provider != nil})
}

func (s *chatStore) createMessage(w http.ResponseWriter, r *http.Request) {
	if rejectBotQuery(w, r) {
		return
	}
	raw, err := io.ReadAll(http.MaxBytesReader(w, r.Body, taskBodyLimit))
	fields, ok := scopedBody(raw, []string{"id", "content"}, nil)
	var input Message
	if err != nil || !ok || json.Unmarshal(raw, &input) != nil ||
		len(input.ID) == 0 || len(input.ID) > 128 || !validTaskText(input.Content) {
		apiError(w, 400, "invalid_message")
		return
	}
	bot, ok := s.bots.resolve(w, bodyBotID(fields))
	if !ok {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, m := range s.messages {
		if m.BotID == bot.ID && m.ID == input.ID {
			if m.Content != input.Content {
				apiError(w, 409, "id_conflict")
				return
			}
			writeJSON(w, 200, m)
			return
		}
	}
	if s.generating[bot.ID] {
		apiError(w, 409, "reply_in_progress")
		return
	}
	if len(s.messages) >= 200 {
		apiError(w, 409, "history_limit")
		return
	}
	message := Message{ID: input.ID, BotID: bot.ID, Content: input.Content, CreatedAt: time.Now().UTC().Format(time.RFC3339Nano)}
	next := append(append([]Message{}, s.messages...), message)
	if err := s.save(next); err != nil {
		apiError(w, 500, "storage_failed")
		return
	}
	s.messages = next
	writeJSON(w, 201, message)
}

// locate returns the index of one message inside the role scope. Callers hold s.mu.
func (s *chatStore) locate(botID, messageID string) int {
	for i, m := range s.messages {
		if m.BotID == botID && m.ID == messageID {
			return i
		}
	}
	return -1
}

func (s *chatStore) deleteMessage(w http.ResponseWriter, r *http.Request) {
	botID, _, ok := queryBotID(r, false)
	if !ok {
		apiError(w, 400, "invalid_bot_id")
		return
	}
	bot, ok := s.bots.resolve(w, botID)
	if !ok {
		return
	}
	id := r.PathValue("id")
	if id == "" || len(id) > 128 {
		apiError(w, 400, "invalid_message")
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.generating[bot.ID] {
		apiError(w, 409, "reply_in_progress")
		return
	}
	index := s.locate(bot.ID, id)
	if index < 0 {
		apiError(w, 404, "message_not_found")
		return
	}
	next := append(append([]Message{}, s.messages[:index]...), s.messages[index+1:]...)
	if err := s.save(next); err != nil {
		apiError(w, 500, "storage_failed")
		return
	}
	s.messages = next
	writeJSON(w, 200, map[string]any{"deleted": true, "id": id, "botId": bot.ID})
}

func (s *chatStore) clearMessages(w http.ResponseWriter, r *http.Request) {
	botID, _, ok := queryBotID(r, false)
	if !ok {
		apiError(w, 400, "invalid_bot_id")
		return
	}
	bot, ok := s.bots.resolve(w, botID)
	if !ok {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.generating[bot.ID] {
		apiError(w, 409, "reply_in_progress")
		return
	}
	next := make([]Message, 0, len(s.messages))
	for _, m := range s.messages {
		if m.BotID != bot.ID {
			next = append(next, m)
		}
	}
	count := len(s.messages) - len(next)
	if count > 0 {
		if err := s.save(next); err != nil {
			apiError(w, 500, "storage_failed")
			return
		}
		s.messages = next
	}
	writeJSON(w, 200, map[string]any{"deletedCount": count, "botId": bot.ID})
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
func (s *chatStore) save(messages []Message) error {
	return saveJSONSnapshot(s.path, ".messages-*", messages)
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
