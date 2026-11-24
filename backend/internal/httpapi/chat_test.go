package httpapi

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
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
		messages[i] = Message{ID: fmt.Sprintf("fixture-%d", i), BotID: "abu", Content: strings.Repeat("\x01", 4000), Reply: strings.Repeat("\x01", 4000), CreatedAt: "now"}
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

func deleteRequest(h http.Handler, target, token string) *httptest.ResponseRecorder {
	r := httptest.NewRequest("DELETE", target, nil)
	if token != "" {
		r.Header.Set("Authorization", "Bearer "+token)
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}

func TestDeleteMessageIsScopedAndIdempotent(t *testing.T) {
	h, err := NewChatHandler(filepath.Join(t.TempDir(), "m.json"), testToken)
	if err != nil {
		t.Fatal(err)
	}
	request(h, "POST", `{"id":"one","content":"first"}`, testToken)
	request(h, "POST", `{"id":"two","content":"second"}`, testToken)
	w := deleteRequest(h, "/api/chat/messages/one", testToken)
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"deleted":true`) ||
		!strings.Contains(w.Body.String(), `"id":"one"`) {
		t.Fatalf("delete status=%d body=%s", w.Code, w.Body.String())
	}
	history := request(h, "GET", "", testToken).Body.String()
	if strings.Contains(history, `"first"`) || !strings.Contains(history, `"second"`) {
		t.Fatalf("history=%s", history)
	}
	if again := deleteRequest(h, "/api/chat/messages/one", testToken); again.Code != 404 ||
		!strings.Contains(again.Body.String(), "message_not_found") {
		t.Fatalf("repeat delete status=%d", again.Code)
	}
	if unauthorized := deleteRequest(h, "/api/chat/messages/two", ""); unauthorized.Code != 401 {
		t.Fatalf("unauthorized delete status=%d", unauthorized.Code)
	}
}

func TestDeleteMessageValidation(t *testing.T) {
	h, err := NewChatHandlerWithBots(filepath.Join(t.TempDir(), "m.json"), testToken, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	request(h, "POST", `{"id":"one","content":"first"}`, testToken)
	for _, tc := range []struct {
		target string
		status int
	}{
		{"/api/chat/messages/one?botId=missing", 404},
		{"/api/chat/messages/one?botId=%20bad", 400},
		{"/api/chat/messages/one?other=1", 400},
		{"/api/chat/messages/" + strings.Repeat("x", 129), 400},
	} {
		if w := deleteRequest(h, tc.target, testToken); w.Code != tc.status {
			t.Fatalf("target=%s status=%d body=%s", tc.target, w.Code, w.Body.String())
		}
	}
	if request(h, "GET", "", testToken).Body.String() == "" {
		t.Fatal("history unavailable")
	}
}

func TestDeleteAndClearRejectWhileReplying(t *testing.T) {
	provider := &blockedCompleter{started: make(chan struct{}), release: make(chan struct{})}
	h, err := NewChatHandlerWithModel(filepath.Join(t.TempDir(), "m.json"), testToken, provider)
	if err != nil {
		t.Fatal(err)
	}
	request(h, "POST", `{"id":"one","content":"hello"}`, testToken)
	finished := make(chan *httptest.ResponseRecorder, 1)
	go func() { finished <- reply(h, `{"messageId":"one"}`) }()
	<-provider.started
	deleted := deleteRequest(h, "/api/chat/messages/one", testToken)
	cleared := deleteRequest(h, "/api/chat/messages", testToken)
	close(provider.release)
	if result := <-finished; result.Code != 200 {
		t.Fatalf("reply status=%d", result.Code)
	}
	if deleted.Code != 409 || cleared.Code != 409 ||
		!strings.Contains(deleted.Body.String(), "reply_in_progress") {
		t.Fatalf("delete=%d clear=%d", deleted.Code, cleared.Code)
	}
	if w := deleteRequest(h, "/api/chat/messages/one", testToken); w.Code != 200 {
		t.Fatalf("delete after reply status=%d", w.Code)
	}
}

func TestDeleteStorageFailureKeepsMemory(t *testing.T) {
	path := filepath.Join(t.TempDir(), "m.json")
	h, err := NewChatHandler(path, testToken)
	if err != nil {
		t.Fatal(err)
	}
	request(h, "POST", `{"id":"one","content":"hello"}`, testToken)
	if err = os.Remove(path); err != nil {
		t.Fatal(err)
	}
	if err = os.Mkdir(path, 0700); err != nil {
		t.Fatal(err)
	}
	if w := deleteRequest(h, "/api/chat/messages/one", testToken); w.Code != 500 ||
		!strings.Contains(w.Body.String(), "storage_failed") {
		t.Fatalf("status=%d body=%s", w.Code, w.Body.String())
	}
	if w := deleteRequest(h, "/api/chat/messages", testToken); w.Code != 500 {
		t.Fatalf("clear status=%d", w.Code)
	}
	if history := request(h, "GET", "", testToken).Body.String(); !strings.Contains(history, `"hello"`) {
		t.Fatalf("failed delete hid the message: %s", history)
	}
}

// multiBotHandler registers a second role so scoped deletion can be verified.
func multiBotHandler(t *testing.T, path string) http.Handler {
	t.Helper()
	second := Bot{ID: "leaf", Name: "小叶子", Avatar: "leaf", Revision: 1}
	canonical, err := json.Marshal(second)
	if err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(canonical)
	raw, err := json.Marshal([]storedBot{{Bot: defaultBot()}, {Bot: second, CreationHash: hex.EncodeToString(sum[:])}})
	if err != nil {
		t.Fatal(err)
	}
	botsPath := filepath.Join(t.TempDir(), "bots.json")
	if err = os.WriteFile(botsPath, raw, 0600); err != nil {
		t.Fatal(err)
	}
	bots, err := NewBots(botsPath, testToken)
	if err != nil {
		t.Fatal(err)
	}
	h, err := NewChatHandlerWithBots(path, testToken, nil, bots)
	if err != nil {
		t.Fatal(err)
	}
	return h
}

func TestClearMessagesKeepsOtherRoles(t *testing.T) {
	h := multiBotHandler(t, filepath.Join(t.TempDir(), "m.json"))
	if w := request(h, "POST", `{"id":"one","content":"abu first"}`, testToken); w.Code != 201 {
		t.Fatalf("create abu status=%d", w.Code)
	}
	if w := request(h, "POST", `{"id":"one","botId":"leaf","content":"leaf first"}`, testToken); w.Code != 201 {
		t.Fatalf("create leaf status=%d", w.Code)
	}
	request(h, "POST", `{"id":"two","content":"abu second"}`, testToken)
	w := deleteRequest(h, "/api/chat/messages", testToken)
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"deletedCount":2`) {
		t.Fatalf("clear status=%d body=%s", w.Code, w.Body.String())
	}
	if history := request(h, "GET", "", testToken).Body.String(); strings.Contains(history, "abu first") {
		t.Fatalf("abu messages kept: %s", history)
	}
	r := httptest.NewRequest("GET", "/api/chat/messages?botId=leaf", nil)
	r.Header.Set("Authorization", "Bearer "+testToken)
	leaf := httptest.NewRecorder()
	h.ServeHTTP(leaf, r)
	if !strings.Contains(leaf.Body.String(), "leaf first") {
		t.Fatalf("leaf messages lost: %s", leaf.Body.String())
	}
	if empty := deleteRequest(h, "/api/chat/messages", testToken); !strings.Contains(empty.Body.String(), `"deletedCount":0`) {
		t.Fatalf("clear empty status=%d body=%s", empty.Code, empty.Body.String())
	}
	if invalid := deleteRequest(h, "/api/chat/messages?botId=%20bad", testToken); invalid.Code != 400 {
		t.Fatalf("invalid bot status=%d", invalid.Code)
	}
	if missing := deleteRequest(h, "/api/chat/messages?botId=missing", testToken); missing.Code != 404 {
		t.Fatalf("missing bot status=%d", missing.Code)
	}
}
