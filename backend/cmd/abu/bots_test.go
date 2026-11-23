package main

import (
	"context"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"abu.local/backend/internal/model"
)

func TestDevBotsLegacyAndIsolation(t *testing.T) {
	dir := t.TempDir()
	fixtures := map[string]string{
		"messages.json": `[{"id":"legacy","content":"only abu","createdAt":"2026-11-22T00:00:00Z","reply":"old answer","replyStatus":"completed"}]`,
		"tasks.json":    `[{"id":"legacy","content":"abu task","status":"completed","result":"old result","createdAt":"2026-11-22T00:00:00Z","updatedAt":"2026-11-22T00:00:00Z"}]`,
	}
	for name, body := range fixtures {
		if err := os.WriteFile(filepath.Join(dir, name), []byte(body), 0600); err != nil {
			t.Fatal(err)
		}
	}
	calls := make(chan []model.Message, 8)
	provider := fixtureModel(func(_ context.Context, m []model.Message) (string, error) {
		calls <- m
		return "fake answer", nil
	})
	open := func() *httptest.Server {
		t.Helper()
		h, tasks, err := newDevHandler(fixtureToken, dir, provider)
		if err != nil {
			t.Fatal(err)
		}
		s := httptest.NewServer(h)
		s.Client().Timeout = 2 * time.Second
		t.Cleanup(func() {
			s.Close()
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			defer cancel()
			if err := tasks.Shutdown(ctx); err != nil {
				t.Error(err)
			}
		})
		return s
	}
	s := open()
	req := func(method, path, body string, status int) string {
		return devRequest(t, s, method, path, fixtureToken, body, status)
	}
	if got := req("GET", "/api/bots", "", 200); !strings.Contains(got, `"id":"abu"`) {
		t.Fatal(got)
	}
	for name, body := range fixtures {
		if raw, err := os.ReadFile(filepath.Join(dir, name)); err != nil || string(raw) != body {
			t.Fatal("cold startup unexpectedly rewrote", name)
		}
	}
	cached := req("GET", "/api/apps/personal-todo/versions/v3", "", 200)
	const create = `{"id":"leaf","name":"叶子","instructions":"leaf system","avatar":"leaf"}`
	req("POST", "/api/bots", create, 201)
	req("POST", "/api/bots", create, 200)
	req("POST", "/api/bots", strings.Replace(create, "叶子", "另一角色", 1), 409)

	req("POST", "/api/chat/replies", `{"messageId":"legacy","botId":"leaf"}`, 404)
	req("POST", "/api/chat/messages", `{"id":"legacy","content":"leaf only","botId":"leaf"}`, 201)
	req("POST", "/api/chat/messages", `{"id":"legacy","content":"leaf only","botId":"leaf"}`, 200)
	req("POST", "/api/chat/messages", `{"id":"legacy","content":"conflict","botId":"leaf"}`, 409)
	for path, forbidden := range map[string]string{"/api/chat/messages": "leaf only", "/api/chat/messages?botId=leaf": "only abu"} {
		if got := req("GET", path, "", 200); strings.Contains(got, forbidden) || !strings.Contains(got, `"id":"legacy"`) {
			t.Fatal(got)
		}
	}
	req("GET", "/api/chat/messages?botId=missing", "", 404)
	req("GET", "/api/chat/messages?BotId=leaf", "", 400)
	req("POST", "/api/chat/messages?botId=leaf", `{"id":"query","content":"must not go to abu"}`, 400)
	req("POST", "/api/chat/replies?botId=leaf", `{"messageId":"legacy"}`, 400)
	req("POST", "/api/tasks?botId=leaf", `{"id":"query","content":"must not go to abu"}`, 400)
	req("POST", "/api/chat/messages", `{"id":"bad","content":"none","botId":""}`, 400)
	req("POST", "/api/chat/messages", `{"id":"bad","content":"none","botId":null}`, 400)
	req("POST", "/api/chat/messages", `{"id":"bad","content":"none","botId":"leaf","botId":"abu"}`, 400)
	// A newer message in the default scope must not block the latest leaf reply.
	req("POST", "/api/chat/messages", `{"id":"newer","content":"abu newer"}`, 201)
	req("POST", "/api/chat/replies", `{"messageId":"legacy","botId":"leaf"}`, 200)
	select {
	case m := <-calls:
		want := []model.Message{{Role: "system", Content: "leaf system"}, {Role: "user", Content: "leaf only"}}
		if len(m) != 2 || m[0] != want[0] || m[1] != want[1] {
			t.Fatalf("mixed prompt: %#v", m)
		}
	case <-time.After(time.Second):
		t.Fatal("no chat call")
	}

	req("GET", "/api/tasks/legacy?botId=leaf", "", 404)
	req("POST", "/api/tasks/legacy/cancel?botId=leaf", "", 404)
	req("POST", "/api/tasks", `{"id":"legacy","content":"leaf task","botId":"leaf"}`, 201)
	req("POST", "/api/tasks", `{"id":"legacy","content":"leaf task","botId":"leaf"}`, 200)
	req("POST", "/api/tasks", `{"id":"legacy","content":"changed","botId":"leaf"}`, 409)
	if got := req("GET", "/api/tasks", "", 200); strings.Contains(got, "leaf task") || !strings.Contains(got, "old result") {
		t.Fatal(got)
	}
	if got := req("GET", "/api/tasks?allBots=true", "", 200); !strings.Contains(got, "leaf task") ||
		!strings.Contains(got, "old result") || strings.Contains(got, "leaf system") || strings.Contains(got, "instructionsSnapshot") {
		t.Fatal(got)
	}
	req("GET", "/api/tasks?botId=missing", "", 404)
	req("GET", "/api/tasks?allBots=true&botId=leaf", "", 400)
	deadline := time.Now().Add(time.Second)
	for {
		if got := req("GET", "/api/tasks/legacy?botId=leaf", "", 200); strings.Contains(got, `"status":"completed"`) {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("task not completed")
		}
		time.Sleep(5 * time.Millisecond)
	}
	select {
	case m := <-calls:
		if len(m) != 2 || m[0] != (model.Message{Role: "system", Content: "leaf system"}) || m[1] != (model.Message{Role: "user", Content: "leaf task"}) {
			t.Fatalf("wrong leaf task prompt: %#v", m)
		}
	case <-time.After(time.Second):
		t.Fatal("no task call")
	}
	req("PUT", "/api/bots/leaf", `{"name":"叶子","instructions":"edited","avatar":"leaf","revision":1}`, 200)
	req("POST", "/api/bots", create, 200)
	s.Close()
	s = open()
	for _, path := range []string{"/api/chat/messages?botId=leaf", "/api/tasks/legacy?botId=leaf", "/api/bots"} {
		if got := req("GET", path, "", 200); !strings.Contains(got, `"leaf"`) {
			t.Fatal(got)
		}
	}
	if cached != req("GET", "/api/apps/personal-todo/versions/v3", "", 200) {
		t.Fatal("cached todo manifest changed")
	}
}

func TestDevBotQueuedRoleUsesSubmissionSnapshot(t *testing.T) {
	dir := t.TempDir()
	started := make(chan []model.Message, 3)
	provider := fixtureModel(func(ctx context.Context, m []model.Message) (string, error) {
		started <- m
		if m[len(m)-1].Content == "hold" {
			<-ctx.Done()
			return "", ctx.Err()
		}
		return "fake", nil
	})
	h, tasks, err := newDevHandler(fixtureToken, dir, provider)
	if err != nil || tasks == nil {
		t.Fatal("task setup", err)
	}
	stop := func() {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		if err := tasks.Shutdown(ctx); err != nil {
			t.Error(err)
		}
	}
	t.Cleanup(stop)
	s := httptest.NewServer(h)
	defer s.Close()
	s.Client().Timeout = 2 * time.Second
	r := func(method, path, body string, status int) {
		devRequest(t, s, method, path, fixtureToken, body, status)
	}
	r("POST", "/api/bots", `{"id":"leaf","name":"Leaf","instructions":"old job","avatar":"leaf"}`, 201)
	r("POST", "/api/tasks", `{"id":"same","content":"hold"}`, 201)
	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("worker not started")
	}
	r("POST", "/api/tasks", `{"id":"same","content":"queued","botId":"leaf"}`, 201)
	r("POST", "/api/tasks/same/cancel?botId=leaf", "", 200)
	r("POST", "/api/tasks", `{"id":"next","content":"next","botId":"leaf"}`, 201)
	r("PUT", "/api/bots/leaf", `{"name":"Leaf","instructions":"new job","avatar":"leaf","revision":1}`, 200)
	if got := devRequest(t, s, "GET", "/api/tasks/same", fixtureToken, "", 200); !strings.Contains(got, `"status":"running"`) {
		t.Fatal("cancel stopped same id on another bot", got)
	}
	stop()
	_, restored, err := newDevHandler(fixtureToken, dir, provider)
	if err != nil || restored == nil {
		t.Fatal("restart", err)
	}
	tasks = restored
	select {
	case m := <-started:
		if len(m) != 2 || m[0] != (model.Message{Role: "system", Content: "old job"}) || m[1].Content != "next" {
			t.Fatalf("bad restored prompt %#v", m)
		}
	case <-time.After(time.Second):
		t.Fatal("queued task not restored")
	}
}
