package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"abu.local/backend/internal/model"
)

func TestGeneratedCompletePublishAndPropose(t *testing.T) {
	dir := t.TempDir()
	code := "/*" + strings.Repeat("x", 5000) + "*/"
	output, _ := json.Marshal(map[string]string{"index.html": "<h1>Custom</h1>", "app.css": "body{}", "app.js": code})
	provider := taskProviderFunc(func(_ context.Context, m []model.Message) (string, error) {
		if strings.Contains(m[0].Content, "DATA PROPOSAL") {
			return `{"document":{"count":2}}`, nil
		}
		return string(output), nil
	})
	g := NewGenerated(filepath.Join(dir, "generated.json"), testToken, nil)
	api, err := newTaskAPI(filepath.Join(dir, "tasks.json"), testToken, provider, time.Second, time.Second, nil, g)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { stopTaskAPI(t, api) })
	const request = `{"id":"draft","botId":"abu","appId":"gen-counter","name":"Counter","request":"Build counter","baseVersion":0}`
	newJob := func(path, body string, status int) generatedJobView {
		raw := generatedCall(t, g, "POST", path, testToken, body, status)
		var v generatedJobView
		if err := json.Unmarshal([]byte(raw), &v); err != nil {
			t.Fatal(err)
		}
		return v
	}
	newJob("/api/app-drafts", request, 201)
	newJob("/api/app-drafts", request, 200)
	generatedCall(t, g, "POST", "/api/app-drafts", testToken, strings.Replace(request, "Build counter", "Different request", 1), 409)
	generatedCall(t, g, "POST", "/api/generated-apps/gen-counter/session", testToken, "", 404)
	wait := func(id string) generatedJobView {
		t.Helper()
		deadline := time.Now().Add(time.Second)
		for time.Now().Before(deadline) {
			var v generatedJobView
			if err := json.Unmarshal([]byte(generatedCall(t, g, "GET", "/api/app-drafts/"+id, testToken, "", 200)), &v); err != nil {
				t.Fatal(err)
			}
			switch v.Status {
			case "ready":
				return v
			case "failed", "interrupted":
				t.Fatalf("job failed: %+v", v)
			}
			time.Sleep(5 * time.Millisecond)
		}
		t.Fatal("job did not finish")
		return generatedJobView{}
	}
	d := wait("draft")
	if d.Manifest == nil || d.Hash == "" {
		t.Fatalf("no draft manifest: %+v", d)
	}
	if got := generatedCall(t, g, "GET", "/api/app-drafts/draft/assets/app.js", testToken, "", 200); got != code {
		t.Fatal("code changed")
	}
	generatedCall(t, g, "PUT", "/api/generated-apps/gen-counter/data", testToken, `{"mutationId":"m","baseRevision":0,"document":{}}`, 401)
	publish := `{"hash":"` + d.Hash + `","expectedVersion":0,"expectedRevision":0}`
	first := generatedCall(t, g, "POST", "/api/app-drafts/draft/publish", testToken, publish, 200)
	if got := generatedCall(t, g, "POST", "/api/app-drafts/draft/publish", testToken, publish, 200); got != first {
		t.Fatal("unstable publication acknowledgement")
	}
	generatedCall(t, g, "POST", "/api/app-drafts/draft/publish", testToken, strings.Replace(publish, `"expectedRevision":0`, `"expectedRevision":1`, 1), 409)
	s := generatedCall(t, g, "POST", "/api/generated-apps/gen-counter/session", testToken, "", 200)
	var session struct{ AppToken string }
	_ = json.Unmarshal([]byte(s), &session)
	if session.AppToken == "" {
		t.Fatal("no scoped session")
	}
	path := "/api/generated-apps/gen-counter/data"
	const update = `{"mutationId":"m","baseRevision":0,"document":{"count":1}}`
	one := generatedCall(t, g, "PUT", path, session.AppToken, update, 200)
	if got := generatedCall(t, g, "PUT", path, session.AppToken, update, 200); one != got {
		t.Fatal("retry changed data")
	}
	generatedCall(t, g, "PUT", path, session.AppToken, strings.Replace(update, "count", "other", 1), 409)
	newJob("/api/generated-apps/gen-counter/proposals", `{"id":"suggest","botId":"abu","request":"increase count","baseRevision":1}`, 201)
	proposal := wait("suggest")
	if proposal.Proposal == nil || string(proposal.Proposal.Document) != `{"count":2}` || generatedCall(t, g, "GET", path, session.AppToken, "", 200) != one {
		t.Fatal("proposal modified live data")
	}
}

func generatedCall(t *testing.T, h http.Handler, method, path, token, body string, status int) string {
	t.Helper()
	r := httptest.NewRequest(method, path, strings.NewReader(body))
	r.Header.Set("Authorization", "Bearer "+token)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != status {
		t.Fatalf("%s %s: status %d want %d: %s", method, path, w.Code, status, w.Body.String())
	}
	return w.Body.String()
}

func TestGeneratedRejectsMalformedOutput(t *testing.T) {
	for _, raw := range []string{
		`{"index.html":"a","app.css":"b","app.js":`,
		`{"index.html":"a","app.css":"b","app.js":"c","app.js":"d"}`,
		`{"index.html":"\ud800","app.css":"b","app.js":"c"}`,
		`{"index.html":"a","app.css":"b","../app.js":"c"}`,
		`{"index.html":"a","app.css":"b","app.js":"c","publish":true}`,
		`{"index.html":"a","app.css":"b","app.js":"` + strings.Repeat("x", 65537) + `"}`,
	} {
		if _, ok := parseGeneratedOutput(raw); ok {
			t.Fatal("accepted malformed generated output")
		}
	}
	for _, raw := range []string{`[]`, `{"x":{"x":1,"x":2}}`, `{"x":1e400}`, `{"x":9007199254740993}`, `{"x":9007199254740991.1}`, `{"x":[[[[[[[[0]]]]]]]]}`} {
		if validGeneratedDocument([]byte(raw)) {
			t.Fatal("accepted invalid data", raw)
		}
	}
}

func TestGeneratedConcurrentPublicationRollbackAndScope(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "generated.json")
	g := NewGenerated(path, testToken, nil)
	provider := taskProviderFunc(func(_ context.Context, m []model.Message) (string, error) {
		for _, part := range m {
			if len([]rune(part.Content)) > 4000 {
				t.Error("prompt chunk exceeds adapter limit")
			}
		}
		return `{"index.html":"<h1>Revision</h1>","app.css":"","app.js":""}`, nil
	})
	api, err := newTaskAPI(filepath.Join(dir, "tasks.json"), testToken, provider, 2*time.Second, 2*time.Second, nil, g)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { stopTaskAPI(t, api) })
	makeDraft := func(id, app string, version int) {
		t.Helper()
		raw, _ := json.Marshal(map[string]any{"id": id, "appId": app, "botId": "abu", "name": id, "request": "Build a page", "baseVersion": version})
		generatedCall(t, g, "POST", "/api/app-drafts", testToken, string(raw), 201)
	}
	ready := func(id string) generatedJobView {
		t.Helper()
		deadline := time.Now().Add(2 * time.Second)
		for time.Now().Before(deadline) {
			var v generatedJobView
			err := json.Unmarshal([]byte(generatedCall(t, g, "GET", "/api/app-drafts/"+id, testToken, "", 200)), &v)
			if err != nil {
				t.Fatal(err)
			}
			if v.Status == "ready" {
				return v
			}
			time.Sleep(5 * time.Millisecond)
		}
		t.Fatal("draft never ready")
		return generatedJobView{}
	}
	body := func(v generatedJobView, rev int) string {
		raw, _ := json.Marshal(genPublicationInput{v.Hash, v.BaseVersion, int64(rev)})
		return string(raw)
	}
	for _, p := range []struct{ id, app string }{{"initial", "gen-one"}, {"peer", "gen-two"}} {
		makeDraft(p.id, p.app, 0)
		v := ready(p.id)
		generatedCall(t, g, "POST", "/api/app-drafts/"+p.id+"/publish", testToken, body(v, 0), 200)
	}
	open := func(h http.Handler, app string) string {
		t.Helper()
		var s struct{ AppToken string }
		raw := generatedCall(t, h, "POST", "/api/generated-apps/"+app+"/session", testToken, "", 200)
		if json.Unmarshal([]byte(raw), &s) != nil || s.AppToken == "" {
			t.Fatal("no session")
		}
		return s.AppToken
	}
	first, peer := open(g, "gen-one"), open(g, "gen-two")
	const data = "/api/generated-apps/gen-one/data"
	const update = `{"mutationId":"one","baseRevision":0,"document":{"count":1.2}}`
	saved := generatedCall(t, g, "PUT", data, first, update, 200)
	generatedCall(t, g, "PUT", data, peer, update, 403)
	generatedCall(t, g, "GET", data, first+"x", "", 401)
	generatedCall(t, g, "GET", "/api/generated-apps/gen-one/versions/1/assets/%61pp.js", testToken, "", 404)
	generatedCall(t, g, "GET", "/api/generated-apps/gen-one/versions/1/assets/../app.js", testToken, "", 404)

	var candidates []generatedJobView
	for _, id := range []string{"update-a", "update-b"} {
		makeDraft(id, "gen-one", 1)
		candidates = append(candidates, ready(id))
	}
	var wg sync.WaitGroup
	status := make(chan int, 2)
	for _, candidate := range candidates {
		wg.Go(func() {
			req := httptest.NewRequest("POST", "/api/app-drafts/"+candidate.ID+"/publish", strings.NewReader(body(candidate, 1)))
			req.Header.Set("Authorization", "Bearer "+testToken)
			w := httptest.NewRecorder()
			g.ServeHTTP(w, req)
			status <- w.Code
		})
	}
	wg.Wait()
	x, y := <-status, <-status
	if !(x == 200 && y == 409 || x == 409 && y == 200) {
		t.Fatalf("publication results %d %d", x, y)
	}
	const activate = `{"id":"rollback","version":1,"expectedRevision":2}`
	history := generatedCall(t, g, "PUT", "/api/generated-apps/gen-one/active", testToken, activate, 200)
	if got := generatedCall(t, g, "PUT", "/api/generated-apps/gen-one/active", testToken, activate, 200); got != history {
		t.Fatal("unstable rollback")
	}
	if got := generatedCall(t, g, "GET", data, first, "", 200); got != saved {
		t.Fatal("rollback changed data")
	}
	stopTaskAPI(t, api)
	restarted := NewGenerated(path, testToken, nil)
	generatedCall(t, restarted, "GET", data, first, "", 401)
	restarted.tokens.now = func() time.Time { return time.Now().Add(appSessionTTL) }
	generatedCall(t, restarted, "GET", data, open(restarted, "gen-one"), "", 401)
	restarted.tokens.now = time.Now
	if got := generatedCall(t, restarted, "PUT", data, open(restarted, "gen-one"), update, 200); got != saved {
		t.Fatal("data retry lost at restart")
	}
	generatedCall(t, restarted, "GET", "/api/generated-apps/gen-one/versions/2", testToken, "", 200)
	const subsequent = `{"mutationId":"two","baseRevision":1,"document":{}}`
	reopened := open(restarted, "gen-one")
	generatedCall(t, restarted, "PUT", data, reopened, subsequent, 200)
	generatedCall(t, restarted, "PUT", data, reopened, update, 409)
}

func TestGeneratedCancelledAndInterruptedJobsNeverReplayModel(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "generated.json")
	g := NewGenerated(path, testToken, nil)
	started, allowLate := make(chan struct{}), make(chan struct{})
	var calls atomic.Int32
	provider := taskProviderFunc(func(ctx context.Context, _ []model.Message) (string, error) {
		if calls.Add(1) == 1 {
			close(started)
			<-ctx.Done()
			<-allowLate
		}
		return `{"index.html":"<p>Late</p>","app.css":"","app.js":""}`, nil
	})
	api, err := newTaskAPI(filepath.Join(dir, "tasks.json"), testToken, provider, 2*time.Second, 2*time.Second, nil, g)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { stopTaskAPI(t, api) })
	defer func() {
		select {
		case <-allowLate:
		default:
			close(allowLate)
		}
	}()
	body := `{"id":"cancel","botId":"abu","appId":"gen-one","name":"One","request":"Build once","baseVersion":0}`
	generatedCall(t, g, "POST", "/api/app-drafts", testToken, body, 201)
	taskSignal(t, started)
	copyOfRunning, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	generatedCall(t, g, "POST", "/api/app-drafts/cancel/cancel", testToken, "", 200)
	close(allowLate)
	stopTaskAPI(t, api)
	if got := generatedCall(t, g, "GET", "/api/app-drafts/cancel", testToken, "", 200); !strings.Contains(got, `"status":"cancelled"`) {
		t.Fatal(got)
	}
	generatedCall(t, g, "GET", "/api/app-drafts/cancel/assets/index.html", testToken, "", 404)
	crash := filepath.Join(dir, "crash.json")
	if err := os.WriteFile(crash, copyOfRunning, 0600); err != nil {
		t.Fatal(err)
	}
	reopened := NewGenerated(crash, testToken, nil)
	api2, err := newTaskAPI(filepath.Join(dir, "other-tasks.json"), testToken, provider, time.Second, time.Second, nil, reopened)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { stopTaskAPI(t, api2) })
	if got := generatedCall(t, reopened, "POST", "/api/app-drafts", testToken, body, 200); !strings.Contains(got, `"status":"interrupted"`) {
		t.Fatal(got)
	}
	stopTaskAPI(t, api2)
	if calls.Load() != 1 {
		t.Fatal("interrupted work called model again")
	}
}

func TestGeneratedBadSnapshotRetainsFileAndLeavesOldWorkerUsable(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "generated.json")
	const bad = `{"apps":[],"jobs":[],"jobs":[]}`
	if err := os.WriteFile(path, []byte(bad), 0600); err != nil {
		t.Fatal(err)
	}
	g := NewGenerated(path, testToken, nil)
	provider := taskProviderFunc(func(context.Context, []model.Message) (string, error) { return "old text works", nil })
	api, err := newTaskAPI(filepath.Join(dir, "tasks.json"), taskTestToken, provider, time.Second, time.Second, nil, g)
	if err != nil {
		t.Fatal(err)
	}
	defer stopTaskAPI(t, api)
	generatedCall(t, g, "GET", "/api/generated-apps", testToken, "", 503)
	generatedCall(t, g, "GET", "/api/generated-apps", "wrong", "", 401)
	taskCall(t, api, "POST", "", `{"id":"old","content":"ordinary"}`, 201)
	waitTask(t, api, "old", "completed")
	if got, err := os.ReadFile(path); err != nil || string(got) != bad {
		t.Fatal("corrupt snapshot overwritten")
	}
}
