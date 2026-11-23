package httpapi

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"abu.local/backend/internal/model"
)

func TestGeneratedDraftBudgetDoesNotExtendProposalOrText(t *testing.T) {
	const ordinary = 120 * time.Millisecond
	const draft = 900 * time.Millisecond
	const delay = 230 * time.Millisecond
	code := "/*" + strings.Repeat("x", 5000) + "*/"
	output, _ := json.Marshal(map[string]string{"index.html": "<h1>Local</h1>", "app.css": "body{}", "app.js": code})
	var hits atomic.Int32
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		var body struct{ Messages []model.Message }
		if json.NewDecoder(r.Body).Decode(&body) != nil || len(body.Messages) == 0 {
			w.WriteHeader(400)
			return
		}
		prompt := body.Messages[0].Content
		last := body.Messages[len(body.Messages)-1].Content
		answer := string(output)
		if strings.Contains(prompt, "DATA PROPOSAL") {
			answer = `{"document":{"test":true}}`
		}
		if last == "ordinary text" {
			answer = "text reply"
		}
		if strings.Contains(last, "hold body") {
			w.WriteHeader(200)
			_, _ = w.Write([]byte(`{"choices":[`))
			w.(http.Flusher).Flush()
			select {
			case <-r.Context().Done():
			case <-time.After(3 * time.Second):
			}
			return
		}
		select {
		case <-r.Context().Done():
		case <-time.After(delay):
			_ = json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{
				"message": map[string]string{"role": "assistant", "content": answer}, "finish_reason": "stop",
			}}})
		}
	}))
	defer upstream.Close()
	provider, err := model.NewOpenAI(model.Config{BaseURL: upstream.URL, APIKey: "fixture", Model: "fixture"})
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	g := NewGenerated(filepath.Join(dir, "generated.json"), testToken, nil)
	api, err := NewTaskAPIWithGenerated(filepath.Join(dir, "tasks.json"), testToken, provider, ordinary, draft, nil, g)
	if err != nil {
		t.Fatal(err)
	}
	defer stopTaskAPI(t, api)

	const request = `{"id":"draft","botId":"abu","appId":"gen-local","name":"Fixture","request":"complete three files","baseVersion":0}`
	generatedCall(t, g, "POST", "/api/app-drafts", testToken, request, 201)
	ready := waitGeneratedTerminal(t, g, "draft")
	if ready.Status != "ready" {
		t.Fatalf("draft with longer budget: %+v", ready)
	}
	if got := generatedCall(t, g, "GET", "/api/app-drafts/draft/assets/app.js", testToken, "", 200); got != code {
		t.Fatal("truncated code")
	}
	generatedCall(t, g, "POST", "/api/app-drafts/draft/publish", testToken,
		`{"hash":"`+ready.Hash+`","expectedVersion":0,"expectedRevision":0}`, 200)

	generatedCall(t, g, "POST", "/api/generated-apps/gen-local/proposals", testToken,
		`{"id":"proposal","botId":"abu","request":"change fixture","baseRevision":0}`, 201)
	if j := waitGeneratedTerminal(t, g, "proposal"); j.Status != "failed" || j.Error != "model_timeout" || j.Proposal != nil {
		t.Fatalf("proposal exceeded ordinary budget: %+v", j)
	}

	generatedCall(t, api, "POST", "/api/tasks", testToken, `{"id":"ordinary","content":"ordinary text"}`, 201)
	deadline := time.Now().Add(3 * time.Second)
	for {
		result := generatedCall(t, api, "GET", "/api/tasks/ordinary", testToken, "", 200)
		if strings.Contains(result, `"error":"model_timeout"`) {
			break
		}
		if strings.Contains(result, `"status":"completed"`) || time.Now().After(deadline) {
			t.Fatal("text task did not time out:", result)
		}
		time.Sleep(5 * time.Millisecond)
	}

	const partial = `{"id":"partial","botId":"abu","appId":"gen-other","name":"Fixture","request":"hold body","baseVersion":0}`
	generatedCall(t, g, "POST", "/api/app-drafts", testToken, partial, 201)
	if j := waitGeneratedTerminal(t, g, "partial"); j.Status != "failed" || j.Error != "model_timeout" || j.Manifest != nil {
		t.Fatalf("partial draft received a terminal resource: %+v", j)
	}
	generatedCall(t, g, "GET", "/api/app-drafts/partial/assets/index.html", testToken, "", 404)
	if got := generatedCall(t, g, "POST", "/api/app-drafts", testToken, partial, 200); !strings.Contains(got, `"error":"model_timeout"`) {
		t.Fatal(got)
	}
	stopTaskAPI(t, api)
	if hits.Load() != 4 {
		t.Fatalf("expected exactly four HTTP calls, got %d", hits.Load())
	}
}

func waitGeneratedTerminal(t *testing.T, g *Generated, id string) generatedJobView {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		var j generatedJobView
		if err := json.Unmarshal([]byte(generatedCall(t, g, "GET", "/api/app-drafts/"+id, testToken, "", 200)), &j); err != nil {
			t.Fatal(err)
		}
		if j.Status != "running" && j.Status != "queued" {
			return j
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("job %s did not complete", id)
	return generatedJobView{}
}
