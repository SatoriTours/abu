package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"abu.local/backend/internal/model"
)

type fakeCompleter struct {
	calls    int
	fail     bool
	received []model.Message
}

func (f *fakeCompleter) Complete(_ context.Context, messages []model.Message) (string, error) {
	f.calls++
	f.received = messages
	if f.fail {
		return "", errors.New("private upstream error")
	}
	return "真实测试回复", nil
}
func reply(h http.Handler, body string) *httptest.ResponseRecorder {
	r := httptest.NewRequest("POST", "/api/chat/replies", strings.NewReader(body))
	r.Header.Set("Authorization", "Bearer "+testToken)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}
func TestReplyKeepsShortBudgetAndEarlierRequestDeadline(t *testing.T) {
	for _, caller := range []time.Duration{0, 2 * time.Minute, 2 * time.Second} {
		provider := taskProviderFunc(func(ctx context.Context, _ []model.Message) (string, error) {
			deadline, ok := ctx.Deadline()
			want := 45 * time.Second
			if caller > 0 && caller < want {
				want = caller
			}
			if remaining := time.Until(deadline); !ok || remaining > want || remaining < want-time.Second {
				t.Errorf("caller=%s model deadline remaining=%s, want <= %s", caller, remaining, want)
			}
			return "reply", nil
		})
		h, err := NewChatHandlerWithModel(filepath.Join(t.TempDir(), "m.json"), testToken, provider)
		if err != nil {
			t.Fatal(err)
		}
		request(h, "POST", `{"id":"one","content":"hello"}`, testToken)
		r := httptest.NewRequest("POST", "/api/chat/replies", strings.NewReader(`{"messageId":"one"}`))
		r.Header.Set("Authorization", "Bearer "+testToken)
		if caller > 0 {
			ctx, cancel := context.WithTimeout(r.Context(), caller)
			defer cancel()
			r = r.WithContext(ctx)
		}
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code != 200 {
			t.Fatal(w.Code, w.Body.String())
		}
	}
}

func TestReplyPersistsAndDoesNotRegenerate(t *testing.T) {
	path := filepath.Join(t.TempDir(), "m.json")
	provider := &fakeCompleter{}
	h, err := NewChatHandlerWithModel(path, testToken, provider)
	if err != nil {
		t.Fatal(err)
	}
	request(h, "POST", `{"id":"one","content":"hello"}`, testToken)
	w := reply(h, `{"messageId":"one"}`)
	if w.Code != 200 {
		t.Fatalf("reply status=%d %s", w.Code, w.Body.String())
	}
	var m Message
	if err = json.Unmarshal(w.Body.Bytes(), &m); err != nil {
		t.Fatal(err)
	}
	if m.Reply != "真实测试回复" || m.ReplyStatus != "completed" {
		t.Fatal("reply missing")
	}
	if w = reply(h, `{"messageId":"one"}`); w.Code != 200 || provider.calls != 1 {
		t.Fatal("duplicate generation")
	}
	h, err = NewChatHandlerWithModel(path, testToken, provider)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(request(h, "GET", "", testToken).Body.String(), `"modelConfigured":true`) {
		t.Fatal("configuration not exposed")
	}
	if reply(h, `{"messageId":"one"}`).Code != 200 || provider.calls != 1 {
		t.Fatal("restart lost reply")
	}
	if len(provider.received) != 1 || provider.received[0].Role != "user" {
		t.Fatal("invalid context")
	}
}
func TestReplyFailureRequiresExplicitRetry(t *testing.T) {
	provider := &fakeCompleter{fail: true}
	h, err := NewChatHandlerWithModel(filepath.Join(t.TempDir(), "m.json"), testToken, provider)
	if err != nil {
		t.Fatal(err)
	}
	request(h, "POST", `{"id":"one","content":"hello"}`, testToken)
	w := reply(h, `{"messageId":"one"}`)
	if w.Code != 502 || strings.Contains(w.Body.String(), "private") {
		t.Fatal("unsafe failure")
	}
	if reply(h, `{"messageId":"one"}`).Code != 409 || provider.calls != 1 {
		t.Fatal("implicit retry")
	}
	provider.fail = false
	if reply(h, `{"messageId":"one","retry":true}`).Code != 200 || provider.calls != 2 {
		t.Fatal("explicit retry failed")
	}
}
func TestReplyDisabledAndMissingMessage(t *testing.T) {
	h, err := NewChatHandler(filepath.Join(t.TempDir(), "m.json"), testToken)
	if err != nil {
		t.Fatal(err)
	}
	if reply(h, `{"messageId":"one"}`).Code != 503 {
		t.Fatal("disabled model accepted")
	}
	provider := &fakeCompleter{}
	h, err = NewChatHandlerWithModel(filepath.Join(t.TempDir(), "m.json"), testToken, provider)
	if err != nil {
		t.Fatal(err)
	}
	if reply(h, `{"messageId":"missing"}`).Code != 404 || provider.calls != 0 {
		t.Fatal("missing message accepted")
	}
}

func TestInterruptedReplyRequiresExplicitRetry(t *testing.T) {
	path := filepath.Join(t.TempDir(), "m.json")
	raw := []byte(`[{"id":"one","content":"hello","createdAt":"now","replyStatus":"running"}]`)
	if err := os.WriteFile(path, raw, 0600); err != nil {
		t.Fatal(err)
	}
	provider := &fakeCompleter{}
	h, err := NewChatHandlerWithModel(path, testToken, provider)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(request(h, "GET", "", testToken).Body.String(), `"replyStatus":"interrupted"`) {
		t.Fatal("running state not recovered")
	}
	if reply(h, `{"messageId":"one"}`).Code != 409 || provider.calls != 0 {
		t.Fatal("automatic restart invocation")
	}
	if reply(h, `{"messageId":"one","retry":true}`).Code != 200 {
		t.Fatal("explicit recovery failed")
	}
}

type blockedCompleter struct {
	started chan struct{}
	release chan struct{}
}

func (f *blockedCompleter) Complete(ctx context.Context, m []model.Message) (string, error) {
	close(f.started)
	select {
	case <-f.release:
		return "done", nil
	case <-ctx.Done():
		return "", ctx.Err()
	}
}
func TestConcurrentReplyAndNewMessageAreRejected(t *testing.T) {
	provider := &blockedCompleter{started: make(chan struct{}), release: make(chan struct{})}
	h, err := NewChatHandlerWithModel(filepath.Join(t.TempDir(), "m.json"), testToken, provider)
	if err != nil {
		t.Fatal(err)
	}
	request(h, "POST", `{"id":"one","content":"hello"}`, testToken)
	finished := make(chan *httptest.ResponseRecorder, 1)
	go func() { finished <- reply(h, `{"messageId":"one"}`) }()
	<-provider.started
	duplicate := reply(h, `{"messageId":"one"}`)
	newMessage := request(h, "POST", `{"id":"two","content":"later"}`, testToken)
	history := request(h, "GET", "", testToken)
	close(provider.release)
	result := <-finished
	if duplicate.Code != 409 || newMessage.Code != 409 || history.Code != 200 || result.Code != 200 {
		t.Fatal("concurrency guard failed")
	}
}
