package model

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestCompleteProtocol(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" || r.URL.Path != "/v1/chat/completions" {
			t.Errorf("request %s %s", r.Method, r.URL.Path)
		}
		if r.Header.Get("Authorization") != "Bearer local-test-key" {
			t.Error("missing auth")
		}
		var body struct {
			Model    string    `json:"model"`
			Messages []Message `json:"messages"`
			Stream   bool      `json:"stream"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Error(err)
		}
		if body.Model != "test-model" || len(body.Messages) != 1 || body.Messages[0].Content != "hello" || body.Stream {
			t.Errorf("body=%+v", body)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"choices":[{"message":{"role":"assistant","content":"你好"},"finish_reason":"stop"}]}`))
	}))
	defer server.Close()
	client, err := NewOpenAI(Config{BaseURL: server.URL + "/v1/", Model: "test-model", APIKey: "local-test-key"})
	if err != nil {
		t.Fatal(err)
	}
	got, err := client.Complete(context.Background(), []Message{{Role: "user", Content: "hello"}})
	if err != nil || got != "你好" {
		t.Fatalf("result=%q err=%v", got, err)
	}
}
func TestRejectsUnsafeOrIncompleteConfig(t *testing.T) {
	for _, cfg := range []Config{
		{}, {BaseURL: "http://example.com/v1", Model: "m", APIKey: "k"},
		{BaseURL: "https://user:password@example.com/v1", Model: "m", APIKey: "k"},
		{BaseURL: "https://example.com/v1?key=secret", Model: "m", APIKey: "k"},
		{BaseURL: "https://example.com/v1", Model: "", APIKey: "k"},
	} {
		if _, err := NewOpenAI(cfg); err == nil {
			t.Error("accepted invalid configuration")
		}
	}
}
func TestRejectsInvalidRepliesWithoutLeakingBody(t *testing.T) {
	for _, tc := range []struct {
		name   string
		status int
		body   string
	}{
		{"upstream", 401, "secret-provider-error"},
		{"invalid", 200, "secret-provider-error"},
		{"empty", 200, `{"choices":[]}`},
		{"blank", 200, `{"choices":[{"message":{"role":"assistant","content":" "},"finish_reason":"stop"}]}`},
		{"truncated", 200, `{"choices":[{"message":{"role":"assistant","content":"partial"},"finish_reason":"length"}]}`},
		{"tool", 200, `{"choices":[{"message":{"role":"assistant","content":"ignored","tool_calls":[{}]},"finish_reason":"tool_calls"}]}`},
		{"large", 200, strings.Repeat("x", 1024*1024+1)},
		{"invalid-utf8", 200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\xff\xfe\"},\"finish_reason\":\"stop\"}]}"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(tc.status)
				_, _ = w.Write([]byte(tc.body))
			}))
			defer server.Close()
			client, err := NewOpenAI(Config{BaseURL: server.URL, Model: "m", APIKey: "k"})
			if err != nil {
				t.Fatal(err)
			}
			_, err = client.Complete(context.Background(), []Message{{Role: "user", Content: "hello"}})
			if err == nil || strings.Contains(err.Error(), "secret-provider-error") {
				t.Fatalf("error=%v", err)
			}
		})
	}
}
func TestDoesNotFollowRedirects(t *testing.T) {
	hits := 0
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { hits++ }))
	defer target.Close()
	source := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { http.Redirect(w, r, target.URL, 307) }))
	defer source.Close()
	client, err := NewOpenAI(Config{BaseURL: source.URL, Model: "m", APIKey: "k"})
	if err != nil {
		t.Fatal(err)
	}
	_, err = client.Complete(context.Background(), []Message{{Role: "user", Content: "hello"}})
	if err == nil || hits != 0 {
		t.Fatalf("redirect followed, hits=%d error=%v", hits, err)
	}
}
func TestCancellation(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { <-r.Context().Done() }))
	defer server.Close()
	client, err := NewOpenAI(Config{BaseURL: server.URL, Model: "m", APIKey: "k"})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	start := time.Now()
	_, err = client.Complete(ctx, []Message{{Role: "user", Content: "hello"}})
	if err == nil || time.Since(start) > time.Second {
		t.Fatalf("cancel failed: %v", err)
	}
}

func TestCompleteDeadlineUsesCallerOrFiniteDefault(t *testing.T) {
	for _, tc := range []struct {
		name               string
		budget, release    time.Duration
		cancel, noDeadline bool
		want               error
	}{
		{name: "explicit-long-budget", budget: 2 * time.Second, release: 160 * time.Millisecond},
		{name: "short-caller-deadline", budget: 35 * time.Millisecond, want: context.DeadlineExceeded},
		{name: "no-deadline-fallback", noDeadline: true, want: context.DeadlineExceeded},
		{name: "explicit-cancel", budget: 2 * time.Second, cancel: true, want: context.Canceled},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var hits atomic.Int32
			started := make(chan struct{}, 1)
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				hits.Add(1)
				// Flush the header and withhold the response body to test the full-call deadline.
				w.WriteHeader(http.StatusOK)
				w.(http.Flusher).Flush()
				select {
				case started <- struct{}{}:
				default:
				}
				if tc.release == 0 {
					select {
					case <-r.Context().Done():
					case <-time.After(3 * time.Second):
					}
					return
				}
				select {
				case <-r.Context().Done():
				case <-time.After(tc.release):
					_, _ = w.Write([]byte(`{"choices":[{"message":{"role":"assistant","content":"complete"},"finish_reason":"stop"}]}`))
				}
			}))
			defer server.Close()
			client, err := NewOpenAI(Config{BaseURL: server.URL, Model: "fixture", APIKey: "fixture"})
			if err != nil {
				t.Fatal(err)
			}
			client.client.Timeout = 100 * time.Millisecond // Scaled equivalent of the old fixed 45s HTTP limit.
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			if !tc.noDeadline {
				var stop context.CancelFunc
				ctx, stop = context.WithTimeout(ctx, tc.budget)
				defer stop()
			}
			if tc.cancel {
				go func() {
					select {
					case <-started:
						cancel()
					case <-ctx.Done():
					}
				}()
			}
			got, err := client.Complete(ctx, []Message{{Role: "user", Content: "fixture"}})
			if !errors.Is(err, tc.want) || tc.want == nil && got != "complete" {
				t.Fatalf("result=%q error=%v; expected %v", got, err, tc.want)
			}
			if hits.Load() != 1 {
				t.Fatalf("model HTTP requests=%d, want 1", hits.Load())
			}
		})
	}
}

func TestInvalidMessagesNeverCallProvider(t *testing.T) {
	hits := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { hits++ }))
	defer server.Close()
	client, err := NewOpenAI(Config{BaseURL: server.URL, Model: "m", APIKey: "k"})
	if err != nil {
		t.Fatal(err)
	}
	for _, messages := range [][]Message{nil, {{Role: "tool", Content: "x"}}, {{Role: "user", Content: " "}}, {{Role: "user", Content: strings.Repeat("x", 4001)}}} {
		if _, err = client.Complete(context.Background(), messages); err == nil {
			t.Error("invalid input accepted")
		}
	}
	if hits != 0 {
		t.Fatalf("provider contacted %d times", hits)
	}
}

func newStreamClient(t *testing.T, handler http.HandlerFunc) *OpenAI {
	t.Helper()
	server := httptest.NewServer(handler)
	t.Cleanup(server.Close)
	client, err := NewOpenAI(Config{BaseURL: server.URL, Model: "test-model", APIKey: "local-test-key"})
	if err != nil {
		t.Fatal(err)
	}
	return client
}

func TestCompleteStreamReportsDeltasAndFinish(t *testing.T) {
	var requestBody struct {
		Model  string    `json:"model"`
		Stream bool      `json:"stream"`
		Msgs   []Message `json:"messages"`
	}
	var accept string
	client := newStreamClient(t, func(w http.ResponseWriter, r *http.Request) {
		accept = r.Header.Get("Accept")
		raw, _ := io.ReadAll(r.Body)
		if err := json.Unmarshal(raw, &requestBody); err != nil {
			t.Error(err)
		}
		w.Header().Set("Content-Type", "text/event-stream")
		flusher := w.(http.Flusher)
		for _, line := range []string{
			": keep-alive",
			`data: {"choices":[{"delta":{"role":"assistant","content":"你"},"finish_reason":null}]}`,
			"",
			`data: {"choices":[{"delta":{"content":"好"},"finish_reason":null}]}`,
			"",
			`data: {"choices":[]}`,
			"",
			`data: {"choices":[{"delta":{},"finish_reason":"stop"}]}`,
			"",
			`data: [DONE]`,
			"",
		} {
			fmt.Fprintf(w, "%s\r\n", line)
			flusher.Flush()
		}
	})
	var deltas []string
	got, err := client.CompleteStream(context.Background(), []Message{{Role: "user", Content: "hello"}}, func(delta string) error {
		deltas = append(deltas, delta)
		return nil
	})
	if err != nil || got != "你好" {
		t.Fatalf("result=%q err=%v", got, err)
	}
	if len(deltas) != 2 || deltas[0] != "你" || deltas[1] != "好" {
		t.Fatalf("deltas=%v", deltas)
	}
	if accept != "text/event-stream" || !requestBody.Stream || requestBody.Model != "test-model" ||
		len(requestBody.Msgs) != 1 || requestBody.Msgs[0].Content != "hello" {
		t.Fatalf("request accept=%q body=%+v", accept, requestBody)
	}
}

func TestCompleteStreamJoinsMultiLineDataFrames(t *testing.T) {
	client := newStreamClient(t, func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w,
			"data: {\"choices\":[{\"delta\":\r\n"+
				"data: {\"content\":\"分帧\"},\"finish_reason\":\"stop\"}]}\r\n\r\n"+
				"data: [DONE]\r\n\r\n")
	})
	var deltas []string
	got, err := client.CompleteStream(context.Background(), []Message{{Role: "user", Content: "hello"}}, func(delta string) error {
		deltas = append(deltas, delta)
		return nil
	})
	if err != nil || got != "分帧" || len(deltas) != 1 || deltas[0] != "分帧" {
		t.Fatalf("result=%q deltas=%v err=%v", got, deltas, err)
	}
}

func TestCompleteStreamAcceptsStopFrameBeforeEOF(t *testing.T) {
	client := newStreamClient(t, func(w http.ResponseWriter, r *http.Request) {
		// A complete stop frame followed directly by EOF, without a [DONE] sentinel.
		_, _ = io.WriteString(w, "data: {\"choices\":[{\"delta\":{\"content\":\"完整\"},\"finish_reason\":\"stop\"}]}\n\n")
	})
	got, err := client.CompleteStream(context.Background(), []Message{{Role: "user", Content: "hello"}}, nil)
	if err != nil || got != "完整" {
		t.Fatalf("result=%q err=%v", got, err)
	}
}

func TestCompleteStreamAcceptsRealBlankLineFraming(t *testing.T) {
	for _, tc := range []struct{ name, body string }{
		{"lf", "data: {\"choices\":[{\"delta\":{\"content\":\"完整\"},\"finish_reason\":\"stop\"}]}\n\n"},
		{"crlf", "data: {\"choices\":[{\"delta\":{\"content\":\"完整\"},\"finish_reason\":\"stop\"}]}\r\n\r\n"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			client := newStreamClient(t, func(w http.ResponseWriter, r *http.Request) {
				_, _ = io.WriteString(w, tc.body)
			})
			got, err := client.CompleteStream(context.Background(), []Message{{Role: "user", Content: "hello"}}, nil)
			if err != nil || got != "完整" {
				t.Fatalf("result=%q err=%v", got, err)
			}
		})
	}
}

// TestCompleteStreamRejectsWhitespaceOnlyLineAsFrameEnd pins the framing rule: only a really
// empty line ends an event, so a line that merely holds blanks and tabs leaves the frame open
// and the following EOF is an incomplete stream instead of a valid stop.
func TestCompleteStreamRejectsWhitespaceOnlyLineAsFrameEnd(t *testing.T) {
	stop := "data: {\"choices\":[{\"delta\":{\"content\":\"完整\"},\"finish_reason\":\"stop\"}]}"
	for _, tc := range []struct{ name, body string }{
		{"lf-spaces", stop + "\n   \n"},
		{"crlf-blanks", stop + "\r\n \t\r\n"},
		{"lf-tabs", stop + "\n\t\t\n"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			client := newStreamClient(t, func(w http.ResponseWriter, r *http.Request) {
				_, _ = io.WriteString(w, tc.body)
			})
			got, err := client.CompleteStream(context.Background(), []Message{{Role: "user", Content: "hello"}}, nil)
			if err == nil || got != "" {
				t.Fatalf("result=%q err=%v", got, err)
			}
			if err.Error() != "incomplete model stream" {
				t.Fatalf("err=%v", err)
			}
		})
	}
}

func TestCompleteStreamRejectsInvalidUTF8BeforeDecoding(t *testing.T) {
	client := newStreamClient(t, func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("data: {\"choices\":[{\"delta\":{\"content\":\"\xff\xfe\"}}]}\n\n"))
	})
	var deltas []string
	_, err := client.CompleteStream(context.Background(), []Message{{Role: "user", Content: "hello"}}, func(delta string) error {
		deltas = append(deltas, delta)
		return nil
	})
	if err == nil || len(deltas) != 0 || strings.Contains(err.Error(), "�") {
		t.Fatalf("deltas=%v err=%v", deltas, err)
	}
}

func TestCompleteStreamRejectsIncompleteStreams(t *testing.T) {
	stop := "data: [DONE]\n\n"
	for _, tc := range []struct{ name, body string }{
		{"no-finish", "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n" + stop},
		{"length", "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"},\"finish_reason\":\"length\"}]}\n\n" + stop},
		{"blank", "data: {\"choices\":[{\"delta\":{\"content\":\" \"},\"finish_reason\":\"stop\"}]}\n\n" + stop},
		{"empty", stop},
		{"two-choices", "data: {\"choices\":[{\"delta\":{\"content\":\"a\"}},{\"delta\":{\"content\":\"b\"}}]}\n\n" + stop},
		{"tool", "data: {\"choices\":[{\"delta\":{\"content\":\"a\",\"tool_calls\":[{}]},\"finish_reason\":\"stop\"}]}\n\n" + stop},
		{"role", "data: {\"choices\":[{\"delta\":{\"role\":\"tool\",\"content\":\"a\"},\"finish_reason\":\"stop\"}]}\n\n" + stop},
		{"invalid-json", "data: not-json-secret\n\n" + stop},
		{"oversized-line", "data: " + strings.Repeat("x", 4*1024*1024+8) + "\n"},
		{"invalid-utf8", "data: {\"choices\":[{\"delta\":{\"content\":\"\xff\xfe\"}}]}\n\n" + stop},
		{"undispatched-event", "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"},\"finish_reason\":\"stop\"}]}\n"},
		{"truncated-line", "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"},\"finish_reason\":\"stop\"}]}"},
		{"half-frame-before-done", "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"},\"finish_reason\":\"stop\"}]}\n" + stop},
	} {
		t.Run(tc.name, func(t *testing.T) {
			client := newStreamClient(t, func(w http.ResponseWriter, r *http.Request) {
				_, _ = io.WriteString(w, tc.body)
			})
			got, err := client.CompleteStream(context.Background(), []Message{{Role: "user", Content: "hello"}}, nil)
			if err == nil || strings.Contains(err.Error(), "secret") {
				t.Fatalf("result=%q err=%v", got, err)
			}
		})
	}
}

func TestCompleteStreamRejectsOverlongAndInvalidOutput(t *testing.T) {
	long := strings.Repeat("好", 2000)
	client := newStreamClient(t, func(w http.ResponseWriter, r *http.Request) {
		for i := 0; i < 3; i++ {
			fmt.Fprintf(w, "data: {\"choices\":[{\"delta\":{\"content\":%q}}]}\n\n", long)
		}
		_, _ = io.WriteString(w, "data: [DONE]\n\n")
	})
	if _, err := client.CompleteStream(context.Background(), []Message{{Role: "user", Content: "hi"}}, nil); err == nil {
		t.Fatal("overlong stream accepted")
	}
	for _, messages := range [][]Message{nil, {{Role: "tool", Content: "x"}}, {{Role: "user", Content: " "}}, {{Role: "user", Content: strings.Repeat("x", 4001)}}} {
		if _, err := client.CompleteStream(context.Background(), messages, nil); err == nil {
			t.Fatal("invalid input accepted")
		}
	}
}

func TestCompleteStreamStopsOnCallbackError(t *testing.T) {
	client := newStreamClient(t, func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, "data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\n\n")
	})
	abort := errors.New("client gone")
	_, err := client.CompleteStream(context.Background(), []Message{{Role: "user", Content: "hi"}}, func(string) error { return abort })
	if !errors.Is(err, abort) {
		t.Fatalf("err=%v", err)
	}
}

func TestCompleteStreamRespectsCancellation(t *testing.T) {
	started := make(chan struct{})
	client := newStreamClient(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		_, _ = io.WriteString(w, "data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\n\n")
		w.(http.Flusher).Flush()
		close(started)
		<-r.Context().Done()
	})
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() {
		_, err := client.CompleteStream(ctx, []Message{{Role: "user", Content: "hi"}}, func(string) error {
			cancel()
			return nil
		})
		done <- err
	}()
	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("err=%v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("cancelled stream did not return")
	}
}
