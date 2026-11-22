package httpapi

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

const testToken = "0123456789abcdef0123456789abcdef"

func request(h http.Handler, method, body, token string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, "/api/chat/messages", bytes.NewBufferString(body))
	r.Header.Set("Authorization", "Bearer "+token)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}
func TestChatAuthAndPersistence(t *testing.T) {
	file := filepath.Join(t.TempDir(), "messages.json")
	h, err := NewChatHandler(file, testToken)
	if err != nil {
		t.Fatal(err)
	}
	if got := request(h, "GET", "", "").Code; got != 401 {
		t.Fatalf("unauthorized=%d", got)
	}
	if got := request(h, "POST", `{"id":"one","content":"hello"}`, testToken).Code; got != 201 {
		t.Fatalf("create=%d", got)
	}
	if got := request(h, "POST", `{"id":"one","content":"hello"}`, testToken).Code; got != 200 {
		t.Fatalf("retry=%d", got)
	}
	if got := request(h, "POST", `{"id":"one","content":"other"}`, testToken).Code; got != 409 {
		t.Fatalf("conflict=%d", got)
	}
	h, err = NewChatHandler(file, testToken)
	if err != nil {
		t.Fatal(err)
	}
	w := request(h, "GET", "", testToken)
	if w.Code != 200 || strings.Count(w.Body.String(), `"content":"hello"`) != 1 {
		t.Fatal(w.Body.String())
	}
}
func TestChatRejectsInvalidInput(t *testing.T) {
	h, err := NewChatHandler(filepath.Join(t.TempDir(), "m.json"), testToken)
	if err != nil {
		t.Fatal(err)
	}
	for _, body := range []string{`{}`, `{"id":"x","content":" "}`, `{"id":"x","content":"ok","role":"assistant"}`, `{"id":"x","content":"ok"}{}`, `{"id":"x","content":"` + strings.Repeat("x", 4001) + `"}`} {
		if got := request(h, "POST", body, testToken).Code; got != 400 {
			t.Fatalf("invalid input status=%d", got)
		}
	}
}
func TestChatRequiresStrongDevToken(t *testing.T) {
	if _, err := NewChatHandler(filepath.Join(t.TempDir(), "m.json"), "short"); err == nil {
		t.Fatal("weak token accepted")
	}
}

func TestChatLoadsMaximumEscapedHistory(t *testing.T) {
	path := filepath.Join(t.TempDir(), "m.json")
	messages := make([]Message, 200)
	for i := range messages {
		messages[i] = Message{ID: "fixture", Content: strings.Repeat("\x01", 4000), Reply: strings.Repeat("\x01", 4000), CreatedAt: "now"}
	}
	raw, err := json.Marshal(messages)
	if err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(path, raw, 0600); err != nil {
		t.Fatal(err)
	}
	if _, err = NewChatHandler(path, testToken); err != nil {
		t.Fatal(err)
	}
}
func TestConcurrentRetriesDoNotDuplicate(t *testing.T) {
	h, err := NewChatHandler(filepath.Join(t.TempDir(), "m.json"), testToken)
	if err != nil {
		t.Fatal(err)
	}
	var group sync.WaitGroup
	for i := 0; i < 10; i++ {
		group.Add(1)
		go func() {
			defer group.Done()
			w := request(h, "POST", `{"id":"one","content":"hello"}`, testToken)
			if w.Code != 200 && w.Code != 201 {
				t.Errorf("status=%d", w.Code)
			}
		}()
	}
	group.Wait()
	w := request(h, "GET", "", testToken)
	if strings.Count(w.Body.String(), `"id":"one"`) != 1 {
		t.Fatal(w.Body.String())
	}
}
func TestStorageFailureDoesNotAppearSaved(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "m.json")
	h, err := NewChatHandler(path, testToken)
	if err != nil {
		t.Fatal(err)
	}
	if err = os.Mkdir(path, 0700); err != nil {
		t.Fatal(err)
	}
	if w := request(h, "POST", `{"id":"one","content":"hello"}`, testToken); w.Code != 500 {
		t.Fatalf("status=%d", w.Code)
	}
	if w := request(h, "GET", "", testToken); strings.Contains(w.Body.String(), "hello") {
		t.Fatal("failed write exposed")
	}
}
