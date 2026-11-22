package httpapi

import (
	"abu.local/backend/internal/model"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

type Message struct {
	ID          string `json:"id"`
	Content     string `json:"content"`
	CreatedAt   string `json:"createdAt"`
	Reply       string `json:"reply,omitempty"`
	ReplyStatus string `json:"replyStatus,omitempty"`
}
type chatStore struct {
	mu         sync.Mutex
	path       string
	messages   []Message
	generating bool
	provider   model.Completer
}

// NewChatHandler is a bounded, single-process development store, not a production database.
func NewChatHandler(path, token string) (http.Handler, error) {
	return NewChatHandlerWithModel(path, token, nil)
}

func NewChatHandlerWithModel(path, token string, provider model.Completer) (http.Handler, error) {
	if len(token) < 32 {
		return nil, errors.New("ABU_DEV_TOKEN must contain at least 32 bytes")
	}
	store := &chatStore{path: path, messages: []Message{}, provider: provider}
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
		if err = json.Unmarshal(raw, &store.messages); err != nil {
			return nil, err
		}
		if len(store.messages) > 200 {
			return nil, errors.New("chat store exceeds record limit")
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
	mux.HandleFunc("GET /api/chat/messages", func(w http.ResponseWriter, r *http.Request) {
		store.mu.Lock()
		defer store.mu.Unlock()
		writeJSON(w, 200, map[string]any{"messages": store.messages, "modelConfigured": provider != nil})
	})
	mux.HandleFunc("POST /api/chat/messages", func(w http.ResponseWriter, r *http.Request) {
		var input struct {
			ID      string `json:"id"`
			Content string `json:"content"`
		}
		decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 32768))
		decoder.DisallowUnknownFields()
		if decoder.Decode(&input) != nil || decoder.Decode(new(any)) != io.EOF ||
			len(input.ID) == 0 || len(input.ID) > 128 || strings.TrimSpace(input.Content) == "" ||
			!utf8.ValidString(input.Content) || utf8.RuneCountInString(input.Content) > 4000 {
			writeJSON(w, 400, map[string]string{"error": "invalid_message"})
			return
		}
		store.mu.Lock()
		defer store.mu.Unlock()
		for _, m := range store.messages {
			if m.ID == input.ID {
				if m.Content != input.Content {
					writeJSON(w, 409, map[string]string{"error": "id_conflict"})
					return
				}
				writeJSON(w, 200, m)
				return
			}
		}
		if store.generating {
			writeJSON(w, 409, map[string]string{"error": "reply_in_progress"})
			return
		}
		if len(store.messages) >= 200 {
			writeJSON(w, 409, map[string]string{"error": "history_limit"})
			return
		}
		message := Message{ID: input.ID, Content: input.Content, CreatedAt: time.Now().UTC().Format(time.RFC3339Nano)}
		next := append(append([]Message{}, store.messages...), message)
		if err := store.save(next); err != nil {
			writeJSON(w, 500, map[string]string{"error": "storage_failed"})
			return
		}
		store.messages = next
		writeJSON(w, 201, message)
	})
	return requireDevToken(token, mux), nil
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
