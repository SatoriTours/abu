package model

import (
	"context"
	"encoding/json"
	"errors"
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
