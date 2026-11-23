package main

import (
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestGeneratedStoreFaultIsolatedFromOtherDevelopmentEndpoints(t *testing.T) {
	dir := t.TempDir()
	const corrupt = "invalid fixture"
	path := filepath.Join(dir, "generated.json")
	if err := os.WriteFile(path, []byte(corrupt), 0600); err != nil {
		t.Fatal(err)
	}
	h, tasks, err := newDevHandler(fixtureToken, dir, nil)
	if err != nil || tasks == nil {
		t.Fatalf("generated fault blocked development server: %v", err)
	}
	s := httptest.NewServer(h)
	defer s.Close()
	s.Client().Timeout = 2 * time.Second

	devRequest(t, s, "GET", "/healthz", "", "", 200)
	devRequest(t, s, "GET", "/api/chat/messages", fixtureToken, "", 200)
	devRequest(t, s, "GET", "/api/tasks", fixtureToken, "", 200)
	devRequest(t, s, "GET", "/api/apps/personal-todo/versions/v3", fixtureToken, "", 200)
	devRequest(t, s, "GET", "/api/generated-apps", "", "", 401)
	if got := devRequest(t, s, "GET", "/api/app-drafts", fixtureToken, "", 503); got != `{"error":"storage_unavailable"}` {
		t.Fatal(got)
	}
	if raw, err := os.ReadFile(path); err != nil || string(raw) != corrupt {
		t.Fatal("damaged generated file changed")
	}
}
