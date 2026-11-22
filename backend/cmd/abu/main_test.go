package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"abu.local/backend/internal/model"
)

const fixtureToken = "local-fixture-not-a-real-credential"

type fixtureModel func(context.Context, []model.Message) (string, error)

func (f fixtureModel) Complete(ctx context.Context, messages []model.Message) (string, error) {
	return f(ctx, messages)
}

func devRequest(t *testing.T, server *httptest.Server, method, path, token, body string, want int) string {
	t.Helper()
	req, err := http.NewRequest(method, server.URL+path, strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := server.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var value json.RawMessage
	if err := json.NewDecoder(resp.Body).Decode(&value); err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != want {
		t.Fatalf("%s %s: got %d %s, want %d", method, path, resp.StatusCode, value, want)
	}
	return string(value)
}

func TestCorruptTaskSnapshotLeavesHealthAndChatAvailable(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "tasks.json")
	const snapshot = `{"private-fixture-marker":`
	if err := os.WriteFile(path, []byte(snapshot), 0600); err != nil {
		t.Fatal(err)
	}
	handler, tasks, err := newDevHandler(fixtureToken, dir, fixtureModel(func(context.Context, []model.Message) (string, error) {
		t.Error("task worker unexpectedly called model")
		return "", nil
	}))
	if err != nil {
		t.Fatalf("task initialization blocked healthy endpoints: %v", err)
	}
	if tasks != nil {
		t.Fatal("unavailable tasks must not own a worker")
	}
	server := httptest.NewServer(handler)
	defer server.Close()
	server.Client().Timeout = 2 * time.Second

	devRequest(t, server, "GET", "/healthz", "", "", 200)
	devRequest(t, server, "GET", "/api/chat/messages", fixtureToken, "", 200)
	devRequest(t, server, "POST", "/api/chat/messages", fixtureToken, `{"id":"chat","content":"preserved"}`, 201)
	if got := devRequest(t, server, "GET", "/api/chat/messages", fixtureToken, "", 200); !strings.Contains(got, `"content":"preserved"`) {
		t.Fatalf("chat not readable: %s", got)
	}
	if raw, err := os.ReadFile(filepath.Join(dir, "messages.json")); err != nil || !strings.Contains(string(raw), `"content":"preserved"`) {
		t.Fatalf("chat not persisted: %s (%v)", raw, err)
	}
	for _, route := range []struct{ method, path string }{
		{"GET", "/api/tasks"}, {"POST", "/api/tasks"}, {"GET", "/api/tasks/t1"}, {"POST", "/api/tasks/t1/cancel"},
	} {
		for _, token := range []string{"", "wrong"} {
			if got := devRequest(t, server, route.method, route.path, token, "", 401); got != `{"error":"unauthorized"}` {
				t.Fatalf("unexpected auth response: %s", got)
			}
		}
		if got := devRequest(t, server, route.method, route.path, fixtureToken, "", 503); got != `{"error":"storage_unavailable"}` {
			t.Fatalf("unexpected unavailable response: %s", got)
		}
	}
	if raw, err := os.ReadFile(path); err != nil || string(raw) != snapshot {
		t.Fatalf("task snapshot changed: %q (%v)", raw, err)
	}
}

func TestCorruptAppStoreLeavesDevRoutesAndAssetsAvailable(t *testing.T) {
	dir := t.TempDir()
	const corrupt = "malformed app snapshot"
	path := filepath.Join(dir, "app-personal-todo.json")
	if err := os.WriteFile(path, []byte(corrupt), 0600); err != nil {
		t.Fatal(err)
	}
	handler, tasks, err := newDevHandler(fixtureToken, dir, nil)
	if err != nil || tasks == nil {
		t.Fatalf("app fault blocked server: %v", err)
	}
	server := httptest.NewServer(handler)
	defer server.Close()
	server.Client().Timeout = 2 * time.Second

	devRequest(t, server, "GET", "/healthz", "", "", 200)
	devRequest(t, server, "POST", "/api/chat/messages", fixtureToken, `{"id":"chat","content":"preserved"}`, 201)
	devRequest(t, server, "GET", "/api/tasks", fixtureToken, "", 200)
	devRequest(t, server, "GET", "/api/apps", fixtureToken, "", 200)
	devRequest(t, server, "GET", "/api/apps/personal-todo/versions/v1", fixtureToken, "", 200)
	// A valid dev token still cannot access data without an application session.
	devRequest(t, server, "GET", "/api/apps/personal-todo/data", fixtureToken, "", 401)
	issued := devRequest(t, server, "POST", "/api/apps/personal-todo/session", fixtureToken, "", 200)
	var session struct{ AppToken string }
	if json.Unmarshal([]byte(issued), &session) != nil || session.AppToken == "" {
		t.Fatal("no application session")
	}
	if got := devRequest(t, server, "GET", "/api/apps/personal-todo/data", session.AppToken, "", 503); got != `{"error":"storage_unavailable"}` {
		t.Fatal("unexpected app data failure")
	}
	// Redirects would hide that the server normalized a disallowed asset URL.
	server.Client().CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	devRequest(t, server, "GET", "/api/apps/personal-todo/versions/v1/assets/a/../index.html", fixtureToken, "", 404)
	if raw, err := os.ReadFile(path); err != nil || string(raw) != corrupt {
		t.Fatal("corrupt app snapshot changed")
	}
}

func TestHealthyTaskHandlerRunsAndShutsDownWorker(t *testing.T) {
	started := make(chan struct{})
	handler, tasks, err := newDevHandler(fixtureToken, t.TempDir(), fixtureModel(func(ctx context.Context, _ []model.Message) (string, error) {
		close(started)
		<-ctx.Done()
		return "", ctx.Err()
	}))
	if err != nil || tasks == nil {
		t.Fatalf("task worker unavailable with healthy storage: %v", err)
	}
	shutdown := func() {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		if err := tasks.Shutdown(ctx); err != nil {
			t.Error(err)
		}
	}
	defer shutdown()
	server := httptest.NewServer(handler)
	defer server.Close()
	server.Client().Timeout = 2 * time.Second

	devRequest(t, server, "GET", "/api/tasks", fixtureToken, "", 200)
	devRequest(t, server, "POST", "/api/tasks", fixtureToken, `{"id":"running","content":"mock"}`, 201)
	select {
	case <-started:
	case <-time.After(2 * time.Second):
		t.Fatal("worker did not start")
	}
	shutdown()
	if got := devRequest(t, server, "GET", "/api/tasks/running", fixtureToken, "", 200); !strings.Contains(got, `"status":"interrupted"`) {
		t.Fatalf("worker did not record shutdown: %s", got)
	}
}
