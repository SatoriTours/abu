package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
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

type sseEvent struct{ name, data string }

func parseSSE(body string) []sseEvent {
	events := []sseEvent{}
	name := ""
	var data []string
	flush := func() {
		if name != "" || len(data) > 0 {
			events = append(events, sseEvent{name: name, data: strings.Join(data, "\n")})
		}
		name, data = "", nil
	}
	for _, line := range strings.Split(body, "\n") {
		line = strings.TrimSuffix(line, "\r")
		switch {
		case line == "":
			flush()
		case strings.HasPrefix(line, "event:"):
			name = strings.TrimSpace(strings.TrimPrefix(line, "event:"))
		case strings.HasPrefix(line, "data:"):
			data = append(data, strings.TrimPrefix(strings.TrimPrefix(line, "data:"), " "))
		}
	}
	flush()
	return events
}

func postTo(h http.Handler, target, body string) *httptest.ResponseRecorder {
	r := httptest.NewRequest("POST", target, strings.NewReader(body))
	r.Header.Set("Authorization", "Bearer "+testToken)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}

// streamProvider is an optional stream completer; Complete must not be used by the API.
type streamProvider struct {
	deltas []string
	fail   error
	before func()
	calls  int
}

func (f *streamProvider) Complete(context.Context, []model.Message) (string, error) {
	f.calls++
	return "", errors.New("stream provider must not be asked for a whole body")
}

func (f *streamProvider) CompleteStream(_ context.Context, _ []model.Message, onDelta func(string) error) (string, error) {
	f.calls++
	if f.before != nil {
		f.before()
	}
	var out strings.Builder
	for _, delta := range f.deltas {
		if err := onDelta(delta); err != nil {
			return "", err
		}
		out.WriteString(delta)
	}
	if f.fail != nil {
		return "", f.fail
	}
	return out.String(), nil
}

func TestStreamReplyEmitsDeltasThenDone(t *testing.T) {
	provider := &streamProvider{deltas: []string{"你", "好"}}
	h, err := NewChatHandlerWithModel(filepath.Join(t.TempDir(), "m.json"), testToken, provider)
	if err != nil {
		t.Fatal(err)
	}
	request(h, "POST", `{"id":"one","content":"hello"}`, testToken)
	w := postTo(h, "/api/chat/replies/stream", `{"messageId":"one"}`)
	if w.Code != 200 {
		t.Fatalf("status=%d body=%s", w.Code, w.Body.String())
	}
	if got := w.Header().Get("Content-Type"); got != "text/event-stream; charset=utf-8" {
		t.Fatalf("content-type=%q", got)
	}
	if got := w.Header().Get("X-Accel-Buffering"); got != "no" {
		t.Fatalf("buffering header=%q", got)
	}
	events := parseSSE(w.Body.String())
	if len(events) != 3 || events[0].name != "delta" || events[1].name != "delta" || events[2].name != "done" {
		t.Fatalf("events=%+v", events)
	}
	if events[0].data != `{"delta":"你"}` || events[1].data != `{"delta":"好"}` {
		t.Fatalf("delta data=%q %q", events[0].data, events[1].data)
	}
	var done Message
	if err := json.Unmarshal([]byte(events[2].data), &done); err != nil {
		t.Fatal(err)
	}
	if done.Reply != "你好" || done.ReplyStatus != "completed" {
		t.Fatalf("done=%+v", done)
	}
	history := request(h, "GET", "", testToken).Body.String()
	if !strings.Contains(history, `"reply":"你好"`) || !strings.Contains(history, `"replyStatus":"completed"`) {
		t.Fatalf("history=%s", history)
	}
	replay := parseSSE(postTo(h, "/api/chat/replies/stream", `{"messageId":"one"}`).Body.String())
	if len(replay) != 1 || replay[0].name != "done" || provider.calls != 1 {
		t.Fatalf("replay=%+v calls=%d", replay, provider.calls)
	}
}

func TestStreamReplyFallsBackToWholeBodyProvider(t *testing.T) {
	provider := &fakeCompleter{}
	h, err := NewChatHandlerWithModel(filepath.Join(t.TempDir(), "m.json"), testToken, provider)
	if err != nil {
		t.Fatal(err)
	}
	request(h, "POST", `{"id":"one","content":"hello"}`, testToken)
	events := parseSSE(postTo(h, "/api/chat/replies/stream", `{"messageId":"one"}`).Body.String())
	if len(events) != 2 || events[0].name != "delta" || events[1].name != "done" || provider.calls != 1 {
		t.Fatalf("events=%+v calls=%d", events, provider.calls)
	}
	var delta struct{ Delta string }
	if err := json.Unmarshal([]byte(events[0].data), &delta); err != nil || delta.Delta != "真实测试回复" {
		t.Fatalf("delta=%+v err=%v", delta, err)
	}
}

func TestStreamReplyFailureEmitsErrorEvent(t *testing.T) {
	provider := &streamProvider{deltas: []string{"部分"}, fail: errors.New("private upstream error")}
	h, err := NewChatHandlerWithModel(filepath.Join(t.TempDir(), "m.json"), testToken, provider)
	if err != nil {
		t.Fatal(err)
	}
	request(h, "POST", `{"id":"one","content":"hello"}`, testToken)
	w := postTo(h, "/api/chat/replies/stream", `{"messageId":"one"}`)
	if w.Code != 200 || strings.Contains(w.Body.String(), "private") {
		t.Fatalf("status=%d body=%s", w.Code, w.Body.String())
	}
	events := parseSSE(w.Body.String())
	if len(events) != 2 || events[1].name != "error" {
		t.Fatalf("events=%+v", events)
	}
	var failure struct {
		Error   string  `json:"error"`
		Message Message `json:"message"`
	}
	if err := json.Unmarshal([]byte(events[1].data), &failure); err != nil {
		t.Fatal(err)
	}
	if failure.Error != "model_reply_failed" || failure.Message.ReplyStatus != "failed" || failure.Message.Reply != "" {
		t.Fatalf("failure=%+v", failure)
	}
	if retry := postTo(h, "/api/chat/replies/stream", `{"messageId":"one"}`); retry.Code != 409 {
		t.Fatalf("implicit retry status=%d", retry.Code)
	}
}

func TestStreamReplyStorageFailureEmitsErrorEvent(t *testing.T) {
	path := filepath.Join(t.TempDir(), "m.json")
	provider := &streamProvider{deltas: []string{"正文"}, before: func() {
		_ = os.Remove(path)
		_ = os.Mkdir(path, 0700)
	}}
	h, err := NewChatHandlerWithModel(path, testToken, provider)
	if err != nil {
		t.Fatal(err)
	}
	request(h, "POST", `{"id":"one","content":"hello"}`, testToken)
	events := parseSSE(postTo(h, "/api/chat/replies/stream", `{"messageId":"one"}`).Body.String())
	if len(events) != 2 || events[1].name != "error" ||
		!strings.Contains(events[1].data, "reply_storage_failed") {
		t.Fatalf("events=%+v", events)
	}
	if history := request(h, "GET", "", testToken).Body.String(); !strings.Contains(history, `"replyStatus":"interrupted"`) {
		t.Fatalf("uncertain state not visible: %s", history)
	}
}

func TestStreamReplyPreconditionsStayJSON(t *testing.T) {
	disabled, err := NewChatHandler(filepath.Join(t.TempDir(), "m.json"), testToken)
	if err != nil {
		t.Fatal(err)
	}
	if w := postTo(disabled, "/api/chat/replies/stream", `{"messageId":"one"}`); w.Code != 503 ||
		w.Header().Get("Content-Type") != "application/json" {
		t.Fatalf("disabled status=%d type=%q", w.Code, w.Header().Get("Content-Type"))
	}
	provider := &streamProvider{deltas: []string{"ok"}}
	h, err := NewChatHandlerWithModel(filepath.Join(t.TempDir(), "m.json"), testToken, provider)
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		body   string
		status int
	}{
		{`{"messageId":"missing"}`, 404},
		{`{"messageId":""}`, 400},
		{`{"messageId":"one","role":"assistant"}`, 400},
	} {
		if w := postTo(h, "/api/chat/replies/stream", tc.body); w.Code != tc.status ||
			w.Header().Get("Content-Type") != "application/json" {
			t.Fatalf("body=%s status=%d type=%q", tc.body, w.Code, w.Header().Get("Content-Type"))
		}
	}
	if w := postTo(h, "/api/chat/replies/stream?botId=abu", `{"messageId":"one"}`); w.Code != 400 {
		t.Fatalf("query accepted status=%d", w.Code)
	}
	if provider.calls != 0 {
		t.Fatalf("model called %d times", provider.calls)
	}
}

type gatedStreamProvider struct {
	started chan struct{}
	release chan struct{}
	calls   int
}

func (g *gatedStreamProvider) Complete(context.Context, []model.Message) (string, error) {
	g.calls++
	return "", errors.New("stream provider must not be asked for a whole body")
}

func (g *gatedStreamProvider) CompleteStream(ctx context.Context, _ []model.Message, onDelta func(string) error) (string, error) {
	g.calls++
	if err := onDelta("半截"); err != nil {
		return "", err
	}
	close(g.started)
	select {
	case <-g.release:
	case <-ctx.Done():
	}
	return "", ctx.Err()
}

func TestStreamReplyCancelKeepsPartialText(t *testing.T) {
	provider := &gatedStreamProvider{started: make(chan struct{}), release: make(chan struct{})}
	h, err := NewChatHandlerWithModel(filepath.Join(t.TempDir(), "m.json"), testToken, provider)
	if err != nil {
		t.Fatal(err)
	}
	request(h, "POST", `{"id":"one","content":"hello"}`, testToken)
	finished := make(chan *httptest.ResponseRecorder, 1)
	go func() { finished <- postTo(h, "/api/chat/replies/stream", `{"messageId":"one"}`) }()
	<-provider.started
	if blocked := deleteRequest(h, "/api/chat/messages/one", testToken); blocked.Code != 409 {
		t.Fatalf("delete during stream status=%d", blocked.Code)
	}
	cancel := postTo(h, "/api/chat/replies/cancel", `{"messageId":"one"}`)
	if cancel.Code != 200 || !strings.Contains(cancel.Body.String(), `"cancelled":true`) {
		t.Fatalf("cancel status=%d body=%s", cancel.Code, cancel.Body.String())
	}
	w := <-finished
	events := parseSSE(w.Body.String())
	if len(events) != 2 || events[0].name != "delta" || events[1].name != "done" {
		t.Fatalf("events=%+v", events)
	}
	var done Message
	if err := json.Unmarshal([]byte(events[1].data), &done); err != nil {
		t.Fatal(err)
	}
	if done.ReplyStatus != "interrupted" || done.Reply != "半截" || provider.calls != 1 {
		t.Fatalf("done=%+v calls=%d", done, provider.calls)
	}
	again := postTo(h, "/api/chat/replies/cancel", `{"messageId":"one"}`)
	if again.Code != 200 || !strings.Contains(again.Body.String(), `"cancelled":false`) {
		t.Fatalf("idempotent cancel status=%d body=%s", again.Code, again.Body.String())
	}
	history := request(h, "GET", "", testToken).Body.String()
	if !strings.Contains(history, `"reply":"半截"`) || !strings.Contains(history, `"replyStatus":"interrupted"`) {
		t.Fatalf("partial reply lost: %s", history)
	}
}

// finishingCompleter reports a complete result only after the test released it, so the test
// controls whether the cancel or the completion reaches the store lock first.
type finishingCompleter struct {
	started chan struct{}
	release chan struct{}
}

func (f *finishingCompleter) Complete(context.Context, []model.Message) (string, error) {
	close(f.started)
	<-f.release
	return "完整结果", nil
}

// TestCancelThatTakesTheLockFirstWins covers the cancel-first ordering: the model has already
// produced a full reply when the cancel request wins the race.
func TestCancelThatTakesTheLockFirstWins(t *testing.T) {
	provider := &finishingCompleter{started: make(chan struct{}), release: make(chan struct{})}
	path := filepath.Join(t.TempDir(), "m.json")
	h, err := NewChatHandlerWithModel(path, testToken, provider)
	if err != nil {
		t.Fatal(err)
	}
	request(h, "POST", `{"id":"one","content":"hello"}`, testToken)
	finished := make(chan *httptest.ResponseRecorder, 1)
	go func() { finished <- reply(h, `{"messageId":"one"}`) }()
	<-provider.started
	// The cancel request completes while the model call is still in flight.
	cancel := postTo(h, "/api/chat/replies/cancel", `{"messageId":"one"}`)
	if cancel.Code != 200 || !strings.Contains(cancel.Body.String(), `"cancelled":true`) {
		t.Fatalf("cancel status=%d body=%s", cancel.Code, cancel.Body.String())
	}
	close(provider.release)
	w := <-finished
	var message Message
	if err := json.Unmarshal(w.Body.Bytes(), &message); err != nil || w.Code != 200 {
		t.Fatalf("status=%d body=%s err=%v", w.Code, w.Body.String(), err)
	}
	if message.ReplyStatus != "interrupted" || message.Reply != "" {
		t.Fatalf("stored cancel outcome overwritten: %+v", message)
	}
	// The generation is unregistered with its final state: no late cancel may claim it.
	late := postTo(h, "/api/chat/replies/cancel", `{"messageId":"one"}`)
	if late.Code != 200 || !strings.Contains(late.Body.String(), `"cancelled":false`) {
		t.Fatalf("late cancel status=%d body=%s", late.Code, late.Body.String())
	}
	history := request(h, "GET", "", testToken).Body.String()
	if !strings.Contains(history, `"replyStatus":"interrupted"`) {
		t.Fatalf("history=%s", history)
	}
}

// TestCompletionThatTakesTheLockFirstWins covers the completion-first ordering: the reply is
// stored as completed and the later cancel must report that nothing was cancelled.
func TestCompletionThatTakesTheLockFirstWins(t *testing.T) {
	provider := &fakeCompleter{}
	h, err := NewChatHandlerWithModel(filepath.Join(t.TempDir(), "m.json"), testToken, provider)
	if err != nil {
		t.Fatal(err)
	}
	request(h, "POST", `{"id":"one","content":"hello"}`, testToken)
	w := reply(h, `{"messageId":"one"}`)
	var message Message
	if err := json.Unmarshal(w.Body.Bytes(), &message); err != nil || message.ReplyStatus != "completed" {
		t.Fatalf("status=%d body=%s err=%v", w.Code, w.Body.String(), err)
	}
	for i := 0; i < 5; i++ {
		late := postTo(h, "/api/chat/replies/cancel", `{"messageId":"one"}`)
		if late.Code != 200 || !strings.Contains(late.Body.String(), `"cancelled":false`) {
			t.Fatalf("cancel of a settled reply status=%d body=%s", late.Code, late.Body.String())
		}
	}
	if history := request(h, "GET", "", testToken).Body.String(); !strings.Contains(history, `"replyStatus":"completed"`) {
		t.Fatalf("completed reply was overwritten: %s", history)
	}
}

// slowCompleter answers like an upstream that is already returning when the cancel arrives.
type slowCompleter struct {
	started chan struct{}
	delay   time.Duration
}

func (s *slowCompleter) Complete(context.Context, []model.Message) (string, error) {
	close(s.started)
	time.Sleep(s.delay)
	return "完整结果", nil
}

// TestCancelAndCompletionRaceStaysConsistent hammers both orderings: whichever side takes the
// store lock first decides the outcome, and the two answers may never disagree.
func TestCancelAndCompletionRaceStaysConsistent(t *testing.T) {
	cancelFirst, completionFirst := 0, 0
	for i := 0; i < 25; i++ {
		provider := &slowCompleter{started: make(chan struct{}), delay: time.Duration(i%5) * 100 * time.Microsecond}
		h, err := NewChatHandlerWithModel(filepath.Join(t.TempDir(), "m.json"), testToken, provider)
		if err != nil {
			t.Fatal(err)
		}
		request(h, "POST", `{"id":"one","content":"hello"}`, testToken)
		finished := make(chan *httptest.ResponseRecorder, 1)
		go func() { finished <- reply(h, `{"messageId":"one"}`) }()
		<-provider.started
		cancel := postTo(h, "/api/chat/replies/cancel", `{"messageId":"one"}`)
		w := <-finished
		var message Message
		if err := json.Unmarshal(w.Body.Bytes(), &message); err != nil || w.Code != 200 {
			t.Fatalf("iteration=%d status=%d body=%s err=%v", i, w.Code, w.Body.String(), err)
		}
		cancelled := strings.Contains(cancel.Body.String(), `"cancelled":true`)
		switch {
		case cancelled:
			cancelFirst++
			if message.ReplyStatus != "interrupted" {
				t.Fatalf("iteration=%d cancel claimed a completed reply: %+v", i, message)
			}
		default:
			completionFirst++
			if message.ReplyStatus != "completed" {
				t.Fatalf("iteration=%d lost cancel reported as done: %+v", i, message)
			}
		}
	}
	t.Logf("cancel-first=%d completion-first=%d", cancelFirst, completionFirst)
}

func TestCancelReplyValidation(t *testing.T) {
	h, err := NewChatHandlerWithModel(filepath.Join(t.TempDir(), "m.json"), testToken, &fakeCompleter{})
	if err != nil {
		t.Fatal(err)
	}
	request(h, "POST", `{"id":"one","content":"hello"}`, testToken)
	idle := postTo(h, "/api/chat/replies/cancel", `{"messageId":"one"}`)
	if idle.Code != 200 || !strings.Contains(idle.Body.String(), `"cancelled":false`) {
		t.Fatalf("idle cancel status=%d body=%s", idle.Code, idle.Body.String())
	}
	for _, tc := range []struct {
		target string
		body   string
		status int
	}{
		{"/api/chat/replies/cancel", `{"messageId":"missing"}`, 404},
		{"/api/chat/replies/cancel", `{"messageId":""}`, 400},
		{"/api/chat/replies/cancel", `{"messageId":"one","retry":true}`, 400},
		{"/api/chat/replies/cancel?botId=abu", `{"messageId":"one"}`, 400},
		{"/api/chat/replies/cancel?botId=missing", `{"messageId":"one"}`, 400},
	} {
		if w := postTo(h, tc.target, tc.body); w.Code != tc.status {
			t.Fatalf("target=%s body=%s status=%d", tc.target, tc.body, w.Code)
		}
	}
}

func TestCancelInterruptsNonStreamingReply(t *testing.T) {
	provider := &blockedCompleter{started: make(chan struct{}), release: make(chan struct{})}
	h, err := NewChatHandlerWithModel(filepath.Join(t.TempDir(), "m.json"), testToken, provider)
	if err != nil {
		t.Fatal(err)
	}
	request(h, "POST", `{"id":"one","content":"hello"}`, testToken)
	finished := make(chan *httptest.ResponseRecorder, 1)
	go func() { finished <- reply(h, `{"messageId":"one"}`) }()
	<-provider.started
	if w := postTo(h, "/api/chat/replies/cancel", `{"messageId":"one"}`); w.Code != 200 ||
		!strings.Contains(w.Body.String(), `"cancelled":true`) {
		t.Fatalf("cancel status=%d body=%s", w.Code, w.Body.String())
	}
	w := <-finished
	var message Message
	if err := json.Unmarshal(w.Body.Bytes(), &message); err != nil || w.Code != 200 || message.ReplyStatus != "interrupted" {
		t.Fatalf("status=%d body=%s err=%v", w.Code, w.Body.String(), err)
	}
}

// roundProvider blocks each stream until the test releases it or the context is cancelled, so
// one message can be stopped once and then retried under a new run id.
type roundProvider struct {
	mu      sync.Mutex
	calls   int
	started chan int
	release chan struct{}
}

func (p *roundProvider) Complete(context.Context, []model.Message) (string, error) {
	return "", errors.New("stream provider must not be asked for a whole body")
}

func (p *roundProvider) CompleteStream(ctx context.Context, _ []model.Message, onDelta func(string) error) (string, error) {
	p.mu.Lock()
	p.calls++
	round := p.calls
	p.mu.Unlock()
	text := fmt.Sprintf("第%d轮", round)
	if err := onDelta(text); err != nil {
		return "", err
	}
	p.started <- round
	select {
	case <-p.release:
		return text, nil
	case <-ctx.Done():
		return "", ctx.Err()
	}
}

func lastDoneEvent(t *testing.T, w *httptest.ResponseRecorder) Message {
	t.Helper()
	events := parseSSE(w.Body.String())
	if len(events) == 0 || events[len(events)-1].name != "done" {
		t.Fatalf("events=%+v", events)
	}
	var message Message
	if err := json.Unmarshal([]byte(events[len(events)-1].data), &message); err != nil {
		t.Fatal(err)
	}
	return message
}

// TestCancelReplyIgnoresALateStopFromAnEarlierRunID covers the retry case: a stop request that
// still names the first round must never interrupt the second round of the same message.
func TestCancelReplyIgnoresALateStopFromAnEarlierRunID(t *testing.T) {
	provider := &roundProvider{started: make(chan int, 2), release: make(chan struct{})}
	h, err := NewChatHandlerWithModel(filepath.Join(t.TempDir(), "m.json"), testToken, provider)
	if err != nil {
		t.Fatal(err)
	}
	request(h, "POST", `{"id":"one","content":"hello"}`, testToken)

	// Round 1 is stopped by its own run id and stored as interrupted.
	first := make(chan *httptest.ResponseRecorder, 1)
	go func() { first <- postTo(h, "/api/chat/replies/stream", `{"messageId":"one","runId":"run-1"}`) }()
	if round := <-provider.started; round != 1 {
		t.Fatalf("first round=%d", round)
	}
	cancel := postTo(h, "/api/chat/replies/cancel", `{"messageId":"one","runId":"run-1"}`)
	if cancel.Code != 200 || !strings.Contains(cancel.Body.String(), `"cancelled":true`) {
		t.Fatalf("cancel status=%d body=%s", cancel.Code, cancel.Body.String())
	}
	if done := lastDoneEvent(t, <-first); done.ReplyStatus != "interrupted" || done.Reply != "第1轮" {
		t.Fatalf("first done=%+v", done)
	}

	// Round 2 retries the same message under a new run id and is still in flight.
	second := make(chan *httptest.ResponseRecorder, 1)
	go func() {
		second <- postTo(h, "/api/chat/replies/stream", `{"messageId":"one","retry":true,"runId":"run-2"}`)
	}()
	if round := <-provider.started; round != 2 {
		t.Fatalf("second round=%d", round)
	}
	late := postTo(h, "/api/chat/replies/cancel", `{"messageId":"one","runId":"run-1"}`)
	if late.Code != 200 || !strings.Contains(late.Body.String(), `"cancelled":false`) {
		t.Fatalf("late cancel status=%d body=%s", late.Code, late.Body.String())
	}
	close(provider.release)
	if done := lastDoneEvent(t, <-second); done.ReplyStatus != "completed" || done.Reply != "第2轮" {
		t.Fatalf("second done=%+v", done)
	}
	history := request(h, "GET", "", testToken).Body.String()
	if !strings.Contains(history, `"reply":"第2轮"`) || !strings.Contains(history, `"replyStatus":"completed"`) {
		t.Fatalf("history=%s", history)
	}
}

// TestCancelReplyStopsTheMatchingRunID keeps the positive direction: the cancel that names the
// running round still interrupts the reply and stores the partial text.
func TestCancelReplyStopsTheMatchingRunID(t *testing.T) {
	provider := &roundProvider{started: make(chan int, 1), release: make(chan struct{})}
	h, err := NewChatHandlerWithModel(filepath.Join(t.TempDir(), "m.json"), testToken, provider)
	if err != nil {
		t.Fatal(err)
	}
	request(h, "POST", `{"id":"one","content":"hello"}`, testToken)
	finished := make(chan *httptest.ResponseRecorder, 1)
	go func() { finished <- postTo(h, "/api/chat/replies/stream", `{"messageId":"one","runId":"run-2"}`) }()
	<-provider.started
	cancel := postTo(h, "/api/chat/replies/cancel", `{"messageId":"one","runId":"run-2"}`)
	if cancel.Code != 200 || !strings.Contains(cancel.Body.String(), `"cancelled":true`) {
		t.Fatalf("cancel status=%d body=%s", cancel.Code, cancel.Body.String())
	}
	if done := lastDoneEvent(t, <-finished); done.ReplyStatus != "interrupted" || done.Reply != "第1轮" {
		t.Fatalf("done=%+v", done)
	}
	if history := request(h, "GET", "", testToken).Body.String(); !strings.Contains(history, `"replyStatus":"interrupted"`) {
		t.Fatalf("history=%s", history)
	}
}

func TestReplyRunIDValidation(t *testing.T) {
	provider := &fakeCompleter{}
	h, err := NewChatHandlerWithModel(filepath.Join(t.TempDir(), "m.json"), testToken, provider)
	if err != nil {
		t.Fatal(err)
	}
	request(h, "POST", `{"id":"one","content":"hello"}`, testToken)
	for _, target := range []string{"/api/chat/replies", "/api/chat/replies/stream", "/api/chat/replies/cancel"} {
		for _, body := range []string{
			`{"messageId":"one","runId":""}`,
			`{"messageId":"one","runId":"has space"}`,
			`{"messageId":"one","runId":"a/b"}`,
			`{"messageId":"one","runId":null}`,
			`{"messageId":"one","runId":"` + strings.Repeat("x", 129) + `"}`,
		} {
			w := postTo(h, target, body)
			if w.Code != 400 || !strings.Contains(w.Body.String(), "invalid_reply_request") {
				t.Fatalf("target=%s body=%s status=%d response=%s", target, body, w.Code, w.Body.String())
			}
		}
	}
	if provider.calls != 0 {
		t.Fatalf("model called %d times for invalid run ids", provider.calls)
	}
	if w := reply(h, `{"messageId":"one","runId":"run-1"}`); w.Code != 200 {
		t.Fatalf("valid run id rejected status=%d body=%s", w.Code, w.Body.String())
	}
	late := postTo(h, "/api/chat/replies/cancel", `{"messageId":"one","runId":"run-1"}`)
	if late.Code != 200 || !strings.Contains(late.Body.String(), `"cancelled":false`) {
		t.Fatalf("settled cancel status=%d body=%s", late.Code, late.Body.String())
	}
}
