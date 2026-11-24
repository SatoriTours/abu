package main

import (
	"context"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"abu.local/backend/internal/httpapi"
	"abu.local/backend/internal/model"
)

// TestDevBotDeletionPurgesScopedRecords covers the wiring of every child store: a removed role
// must leave no chat message, task or generated record behind, so each snapshot still reloads.
func TestDevBotDeletionPurgesScopedRecords(t *testing.T) {
	dir := t.TempDir()
	provider := fixtureModel(func(context.Context, []model.Message) (string, error) { return "答案", nil })
	handler, tasks, err := newDevHandler(fixtureToken, dir, provider)
	if err != nil || tasks == nil {
		t.Fatalf("task setup: %v", err)
	}
	stop := func(t *testing.T, api interface {
		Shutdown(context.Context) error
	}) {
		t.Helper()
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		if err := api.Shutdown(ctx); err != nil {
			t.Error(err)
		}
	}
	server := httptest.NewServer(handler)
	defer server.Close()
	server.Client().Timeout = 2 * time.Second
	r := func(method, path, body string, status int) string {
		return devRequest(t, server, method, path, fixtureToken, body, status)
	}

	r("POST", "/api/bots", `{"id":"leaf","name":"叶子","instructions":"","avatar":"leaf"}`, 201)
	r("POST", "/api/chat/messages", `{"id":"leaf-turn","botId":"leaf","content":"叶子提问"}`, 201)
	r("POST", "/api/tasks", `{"id":"leaf-task","botId":"leaf","content":"叶子任务"}`, 201)
	deadline := time.Now().Add(2 * time.Second)
	for {
		if got := r("GET", "/api/tasks/leaf-task?botId=leaf", "", 200); strings.Contains(got, `"status":"completed"`) {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("leaf task did not finish")
		}
		time.Sleep(5 * time.Millisecond)
	}

	if got := r("DELETE", "/api/bots/leaf", "", 200); !strings.Contains(got, `"deleted":true`) {
		t.Fatalf("delete response: %s", got)
	}
	if got := r("GET", "/api/bots", "", 200); strings.Contains(got, `"id":"leaf"`) {
		t.Fatalf("role survived: %s", got)
	}
	r("GET", "/api/chat/messages?botId=leaf", "", 404)
	r("GET", "/api/tasks/leaf-task?botId=leaf", "", 404)
	stop(t, tasks)

	restarted, restartedTasks, err := newDevHandler(fixtureToken, dir, provider)
	if err != nil || restartedTasks == nil {
		t.Fatalf("restart rejected the purged snapshots: %v", err)
	}
	defer stop(t, restartedTasks)
	second := httptest.NewServer(restarted)
	defer second.Close()
	second.Client().Timeout = 2 * time.Second
	devRequest(t, second, "GET", "/api/chat/messages", fixtureToken, "", 200)
	devRequest(t, second, "GET", "/api/tasks?allBots=true", fixtureToken, "", 200)
}

// TestDevRestartRecoversAnInterruptedRoleDeletion covers the start-up wiring: an intent left by
// a crash is rolled forward by the next start instead of waiting for another request.
func TestDevRestartRecoversAnInterruptedRoleDeletion(t *testing.T) {
	dir := t.TempDir()
	provider := fixtureModel(func(context.Context, []model.Message) (string, error) { return "答案", nil })
	handler, tasks, err := newDevHandler(fixtureToken, dir, provider)
	if err != nil || tasks == nil {
		t.Fatalf("task setup: %v", err)
	}
	stop := func(t *testing.T, api *httpapi.TaskAPI) {
		t.Helper()
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		if err := api.Shutdown(ctx); err != nil {
			t.Error(err)
		}
	}
	first := httptest.NewServer(handler)
	first.Client().Timeout = 2 * time.Second
	devRequest(t, first, "POST", "/api/bots", fixtureToken, `{"id":"leaf","name":"叶子","instructions":"","avatar":"leaf"}`, 201)
	devRequest(t, first, "POST", "/api/chat/messages", fixtureToken, `{"id":"leaf-turn","botId":"leaf","content":"叶子提问"}`, 201)
	first.Close()
	// A crash after the intent write but before any child purge.
	pending := filepath.Join(dir, ".bot-delete.pending")
	if err := os.WriteFile(pending, []byte(`{"botId":"leaf"}`), 0600); err != nil {
		t.Fatal(err)
	}
	stop(t, tasks)

	restarted, restartedTasks, err := newDevHandler(fixtureToken, dir, provider)
	if err != nil || restartedTasks == nil {
		t.Fatalf("restart rejected the interrupted deletion: %v", err)
	}
	if _, err := os.Stat(pending); !os.IsNotExist(err) {
		t.Fatalf("restart kept the delete intent: %v", err)
	}
	server := httptest.NewServer(restarted)
	server.Client().Timeout = 2 * time.Second
	if got := devRequest(t, server, "GET", "/api/bots", fixtureToken, "", 200); strings.Contains(got, `"id":"leaf"`) {
		t.Fatalf("restart kept the interrupted role: %s", got)
	}
	devRequest(t, server, "GET", "/api/chat/messages?botId=leaf", fixtureToken, "", 404)
	server.Close()
	stop(t, restartedTasks)

	// The recovered snapshots load again without a leftover intent.
	third, thirdTasks, err := newDevHandler(fixtureToken, dir, provider)
	if err != nil || thirdTasks == nil {
		t.Fatalf("recovered snapshots no longer load: %v", err)
	}
	thirdServer := httptest.NewServer(third)
	thirdServer.Client().Timeout = 2 * time.Second
	devRequest(t, thirdServer, "GET", "/api/chat/messages", fixtureToken, "", 200)
	thirdServer.Close()
	stop(t, thirdTasks)
}

// TestDevStartWithBrokenTaskStorageRefusesRoleDeletes covers the start-up wiring: a task snapshot
// that cannot be loaded disables the task API, and because the task store then registers no delete
// hook, role deletion is refused instead of skipping it and overwriting the pending intent.
func TestDevStartWithBrokenTaskStorageRefusesRoleDeletes(t *testing.T) {
	dir := t.TempDir()
	provider := fixtureModel(func(context.Context, []model.Message) (string, error) { return "答案", nil })
	handler, tasks, err := newDevHandler(fixtureToken, dir, provider)
	if err != nil || tasks == nil {
		t.Fatalf("task setup: %v", err)
	}
	stop := func(t *testing.T, api *httpapi.TaskAPI) {
		t.Helper()
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		if err := api.Shutdown(ctx); err != nil {
			t.Error(err)
		}
	}
	first := httptest.NewServer(handler)
	first.Client().Timeout = 2 * time.Second
	devRequest(t, first, "POST", "/api/bots", fixtureToken, `{"id":"leaf","name":"叶子","instructions":"","avatar":"leaf"}`, 201)
	devRequest(t, first, "POST", "/api/bots", fixtureToken, `{"id":"wave","name":"波浪","instructions":"","avatar":"wave"}`, 201)
	devRequest(t, first, "POST", "/api/chat/messages", fixtureToken, `{"id":"leaf-turn","botId":"leaf","content":"叶子提问"}`, 201)
	devRequest(t, first, "POST", "/api/tasks", fixtureToken, `{"id":"leaf-task","botId":"leaf","content":"叶子任务"}`, 201)
	deadline := time.Now().Add(2 * time.Second)
	for {
		if got := devRequest(t, first, "GET", "/api/tasks/leaf-task?botId=leaf", fixtureToken, "", 200); strings.Contains(got, `"status":"completed"`) {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("leaf task did not finish")
		}
		time.Sleep(5 * time.Millisecond)
	}
	first.Close()
	stop(t, tasks)

	// The next start cannot load the task snapshot. Even with no intent on disk the delete is
	// refused, because the unloadable store has no hook to purge the task this role owns.
	taskSnapshot := filepath.Join(dir, "tasks.json")
	if err := os.WriteFile(taskSnapshot, []byte("{not json"), 0600); err != nil {
		t.Fatal(err)
	}
	botsPath := filepath.Join(dir, "bots.json")
	chatPath := filepath.Join(dir, "messages.json")
	botsBefore, err := os.ReadFile(botsPath)
	if err != nil {
		t.Fatal(err)
	}
	chatBefore, err := os.ReadFile(chatPath)
	if err != nil {
		t.Fatal(err)
	}
	start := func(t *testing.T) *httptest.Server {
		t.Helper()
		handler, taskAPI, err := newDevHandler(fixtureToken, dir, provider)
		if err != nil {
			t.Fatalf("a broken task snapshot must only disable tasks: %v", err)
		}
		if taskAPI != nil {
			t.Fatal("a broken task snapshot started a task store")
		}
		server := httptest.NewServer(handler)
		t.Cleanup(server.Close)
		server.Client().Timeout = 2 * time.Second
		return server
	}
	assertRefused := func(t *testing.T, server *httptest.Server) {
		t.Helper()
		if got := devRequest(t, server, "DELETE", "/api/bots/leaf", fixtureToken, "", 503); !strings.Contains(got, "storage_unavailable") {
			t.Fatalf("delete response: %s", got)
		}
		if got := devRequest(t, server, "GET", "/api/bots", fixtureToken, "", 200); !strings.Contains(got, `"id":"leaf"`) {
			t.Fatalf("role was removed while the task store was unavailable: %s", got)
		}
		if got, err := os.ReadFile(botsPath); err != nil || string(got) != string(botsBefore) {
			t.Fatalf("a refused delete rewrote the bot snapshot: %v", err)
		}
		if got, err := os.ReadFile(chatPath); err != nil || string(got) != string(chatBefore) {
			t.Fatalf("a refused delete rewrote the chat snapshot: %v", err)
		}
	}

	assertRefused(t, start(t))

	// An intent left by an interrupted delete of another role is never overwritten either.
	pending := filepath.Join(dir, ".bot-delete.pending")
	intent := `{"botId":"wave"}`
	if err := os.WriteFile(pending, []byte(intent), 0600); err != nil {
		t.Fatal(err)
	}
	withIntent := start(t)
	assertRefused(t, withIntent)
	if got, err := os.ReadFile(pending); err != nil || string(got) != intent {
		t.Fatalf("a refused delete rewrote the delete intent: %q %v", got, err)
	}
}
