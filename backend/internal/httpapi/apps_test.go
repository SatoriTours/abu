package httpapi

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

const devAppsToken = "fixture-not-a-real-application-key"
const todoID = "personal-todo"
const peerID = "test-second"

type appsTestReply struct {
	status int
	body   string
	mime   string
}

func callApp(h http.Handler, method, suffix, token, body string) appsTestReply {
	r := httptest.NewRequest(method, "/api/apps"+suffix, strings.NewReader(body))
	if token != "" {
		r.Header.Set("Authorization", "Bearer "+token)
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return appsTestReply{w.Code, w.Body.String(), w.Header().Get("Content-Type")}
}

func expectApp(t *testing.T, got appsTestReply, status int, code string) string {
	t.Helper()
	if got.status != status || (code != "" && got.body != fmt.Sprintf("{\"error\":%q}\n", code)) {
		t.Fatalf("want %d %s, got %d %s", status, code, got.status, got.body)
	}
	return got.body
}

func appToken(t *testing.T, h http.Handler, app string) string {
	t.Helper()
	var session struct{ AppToken, ExpiresAt string }
	raw := expectApp(t, callApp(h, "POST", "/"+app+"/session", devAppsToken, ""), 200, "")
	if err := json.Unmarshal([]byte(raw), &session); err != nil || session.AppToken == "" || session.ExpiresAt == "" {
		t.Fatal("invalid session envelope")
	}
	return session.AppToken
}

func testApps(t *testing.T, dir string, now func() time.Time, registry []appDefinition) http.Handler {
	t.Helper()
	h, err := newAppsHandler(dir, devAppsToken, registry, now)
	if err != nil {
		t.Fatal(err)
	}
	return h
}

func TestAppsCatalogAndAssetIntegrity(t *testing.T) {
	h, err := NewAppsHandler(t.TempDir(), devAppsToken)
	if err != nil {
		t.Fatal(err)
	}
	expectApp(t, callApp(h, "GET", "", "", ""), 401, "unauthorized")
	catalog := expectApp(t, callApp(h, "GET", "", devAppsToken, ""), 200, "")
	if !strings.Contains(catalog, `"currentVersion":"v3"`) || strings.Contains(catalog, peerID) {
		t.Fatal(catalog)
	}
	var entries []string
	for _, version := range []string{"v1", "v2", "v3"} {
		prefix := "/" + todoID + "/versions/" + version
		expectApp(t, callApp(h, "GET", prefix, "bad", ""), 401, "unauthorized")
		raw := expectApp(t, callApp(h, "GET", prefix, devAppsToken, ""), 200, "")
		var m struct {
			ID, Version, Entry string
			SchemaVersion      int
			Capabilities       []string
			Assets             []struct {
				Path, Mime, SHA256 string
				Size               int
			}
		}
		if err := json.Unmarshal([]byte(raw), &m); err != nil || m.ID != todoID || m.Version != version || m.SchemaVersion != 1 ||
			strings.Join(m.Capabilities, ",") != "todo.read,todo.write" || len(m.Assets) == 0 || len(m.Assets) > 16 {
			t.Fatalf("bad manifest: %s", raw)
		}
		var total int
		for _, meta := range m.Assets {
			a := callApp(h, "GET", prefix+"/assets/"+meta.Path, devAppsToken, "")
			expectApp(t, a, 200, "")
			if a.mime != meta.Mime || len(a.body) != meta.Size || meta.Size > 256*1024 {
				t.Fatal("asset metadata mismatch")
			}
			sum := sha256.Sum256([]byte(a.body))
			if hex.EncodeToString(sum[:]) != meta.SHA256 {
				t.Fatal("asset hash mismatch")
			}
			if meta.Path == m.Entry {
				entries = append(entries, a.body)
			}
			total += meta.Size
		}
		if total > 1<<20 {
			t.Fatal("asset total exceeds bound")
		}
	}
	if len(entries) != 3 || entries[0] == entries[1] || entries[0] == entries[2] || entries[1] == entries[2] {
		t.Fatal("versioned entry did not change")
	}
	for _, path := range []string{
		"/missing/versions/v1", "/personal-todo/versions/v4", "/personal-todo/versions/v1/assets/private",
		"/personal-todo/versions/v1/assets/%2e%2e/index.html", "/personal-todo/versions/v1/assets/../v1",
		"/personal-todo/versions/v1/assets/%69ndex.html", "/personal-todo/versions/v1/assets/index.html?url=elsewhere",
	} {
		expectApp(t, callApp(h, "GET", path, devAppsToken, ""), 404, "app_not_found")
	}
}

func TestAppsRejectInvalidResourceRegistrations(t *testing.T) {
	for _, tc := range []struct {
		name        string
		count, size int
	}{
		{"one-too-large", 1, (256 << 10) + 1}, {"aggregate-too-large", 5, 256 << 10}, {"too-many", 17, 1},
	} {
		t.Run(tc.name, func(t *testing.T) {
			d := builtinApps()
			d[0].Versions[0].Assets = []appAssetSource{{"index.html", "text/html; charset=utf-8", make([]byte, tc.size)}}
			for i := 1; i < tc.count; i++ {
				d[0].Versions[0].Assets = append(d[0].Versions[0].Assets,
					appAssetSource{fmt.Sprintf("chunk-%d.js", i), "text/javascript; charset=utf-8", make([]byte, tc.size)})
			}
			if _, err := newAppsHandler(t.TempDir(), devAppsToken, d, time.Now); err == nil {
				t.Fatal("invalid asset limits were accepted")
			}
		})
	}
}

func TestAppsSessionsAreScopedAndExpire(t *testing.T) {
	dir := t.TempDir()
	now := time.Unix(2_000_000_000, 0)
	defs := append(builtinApps(), fixtureApp(peerID))
	h := testApps(t, dir, func() time.Time { return now }, defs)
	a, b := appToken(t, h, todoID), appToken(t, h, peerID)
	expectApp(t, callApp(h, "POST", "/missing/session", devAppsToken, ""), 404, "app_not_found")
	expectApp(t, callApp(h, "POST", "/"+todoID+"/session", a, ""), 401, "unauthorized")
	expectApp(t, callApp(h, "GET", "", a, ""), 401, "unauthorized")
	for _, bad := range []string{"", devAppsToken, a + "A"} {
		expectApp(t, callApp(h, "GET", "/"+todoID+"/data", bad, ""), 401, "invalid_app_token")
	}
	expectApp(t, callApp(h, "GET", "/"+todoID+"/data", b, ""), 403, "app_scope_mismatch")
	expectApp(t, callApp(h, "GET", "/missing/data", a, ""), 404, "app_not_found")
	restarted := testApps(t, dir, func() time.Time { return now }, defs)
	expectApp(t, callApp(restarted, "GET", "/"+todoID+"/data", a, ""), 401, "invalid_app_token")
	now = now.Add(15 * time.Minute)
	expectApp(t, callApp(h, "GET", "/"+todoID+"/data", a, ""), 401, "invalid_app_token")
}

func fixtureApp(id string) appDefinition {
	a := builtinApps()[0]
	a.ID, a.File = id, id+".json"
	return a
}

func TestAppsSnapshotIdempotentConcurrentAndRestarted(t *testing.T) {
	dir := t.TempDir()
	defs := append(builtinApps(), fixtureApp(peerID))
	h := testApps(t, dir, time.Now, defs)
	scope := "/" + todoID + "/data"
	a := appToken(t, h, todoID)
	b := appToken(t, h, peerID)
	const empty = "{\"revision\":0,\"items\":[]}\n"
	if got := expectApp(t, callApp(h, "GET", scope, a, ""), 200, ""); got != empty {
		t.Fatal(got)
	}
	const first = `{"mutationId":"first","baseRevision":0,"items":[{"id":"m","text":"文字😀","done":false}]}`
	one := expectApp(t, callApp(h, "PUT", scope, a, first), 200, "")
	if got := expectApp(t, callApp(h, "PUT", scope, a, first), 200, ""); got != one || !strings.Contains(one, `"revision":1`) {
		t.Fatal(got)
	}
	expectApp(t, callApp(h, "PUT", scope, a, strings.Replace(first, "文字", "改动", 1)), 409, "mutation_conflict")
	var wg sync.WaitGroup
	results := make(chan appsTestReply, 2)
	for _, id := range []string{"left", "right"} {
		wg.Add(1)
		go func() {
			defer wg.Done()
			results <- callApp(h, "PUT", scope, a, fmt.Sprintf(`{"mutationId":%q,"baseRevision":1,"items":[]}`, id))
		}()
	}
	wg.Wait()
	close(results)
	var success, conflict int
	for got := range results {
		if got.status == 200 && strings.Contains(got.body, `"revision":2`) {
			success++
		} else if got.status == 409 && strings.Contains(got.body, "revision_conflict") {
			conflict++
		}
	}
	if success != 1 || conflict != 1 {
		t.Fatalf("concurrent successes=%d conflicts=%d", success, conflict)
	}
	expectApp(t, callApp(h, "PUT", scope, a, first), 409, "revision_conflict")
	if got := expectApp(t, callApp(h, "GET", "/"+peerID+"/data", b, ""), 200, ""); got != empty {
		t.Fatal(got)
	}
	restarted := testApps(t, dir, time.Now, defs)
	nextToken := appToken(t, restarted, todoID)
	if got := expectApp(t, callApp(restarted, "GET", scope, nextToken, ""), 200, ""); got != "{\"revision\":2,\"items\":[]}\n" {
		t.Fatal(got)
	}
	const same = `{"mutationId":"same","baseRevision":2,"items":[]}`
	three := expectApp(t, callApp(restarted, "PUT", scope, nextToken, same), 200, "")
	again := testApps(t, dir, time.Now, defs)
	if got := expectApp(t, callApp(again, "PUT", scope, appToken(t, again, todoID), same), 200, ""); got != three || !strings.Contains(got, `"revision":3`) {
		t.Fatal(got)
	}
}

func TestAppsRejectMalformedAndBoundedUpdates(t *testing.T) {
	h := testApps(t, t.TempDir(), time.Now, builtinApps())
	scope := "/" + todoID + "/data"
	a := appToken(t, h, todoID)
	const prefix = `{"mutationId":"m","baseRevision":0,"items":`
	bad := []string{
		`null`, `{"mutationId":"m","mutationId":"x","baseRevision":0,"items":[]}`, `{"MutationId":"m","baseRevision":0,"items":[]}`,
		`{"mutationId":"m","baseRevision":0,"items":[],"extra":1}`, prefix + `null}`, prefix + `[]} {}`,
		prefix + `[{"id":"a","text":"t"}]}`, prefix + `[{"id":"a","text":"t","done":null}]}`,
		prefix + `[{"id":"a","text":"t","done":false,"done":true}]}`,
		prefix + `[{"id":"a","text":"t","done":false},{"id":"a","text":"t","done":false}]}`,
		prefix + `[{"id":"../a","text":"t","done":false}]}`, prefix + `[{"id":"a","text":" ","done":false}]}`,
		prefix + `[{"id":"a","text":"\ud800","done":false}]}`,
		prefix + `[{"id":"a","text":"` + string([]byte{0xff}) + `","done":false}]}`,
		prefix + `[{"id":"a","text":"` + strings.Repeat("😀", 201) + `","done":false}]}`,
	}
	var tooMany []string
	for i := range 101 {
		tooMany = append(tooMany, fmt.Sprintf(`{"id":"i%d","text":"a","done":false}`, i))
	}
	bad = append(bad, prefix+"["+strings.Join(tooMany, ",")+"]}")
	for _, body := range bad {
		expectApp(t, callApp(h, "PUT", scope, a, body), 400, "invalid_data")
	}
	expectApp(t, callApp(h, "PUT", scope, a, strings.Repeat(" ", 262145)), 413, "body_too_large")
	expectApp(t, callApp(h, "PUT", scope, a, prefix+`[{"id":"a","text":"`+strings.Repeat(`\ud83d\ude00`, 200)+`","done":false}]}`), 200, "")
}

func TestAppsStorageFaultDoesNotContaminateOtherApps(t *testing.T) {
	dir := t.TempDir()
	defs := append(builtinApps(), fixtureApp(peerID))
	badPath := filepath.Join(dir, defs[0].File)
	const bad = "{private marker"
	if err := os.WriteFile(badPath, []byte(bad), 0600); err != nil {
		t.Fatal(err)
	}
	h := testApps(t, dir, time.Now, defs)
	a, b := appToken(t, h, todoID), appToken(t, h, peerID)
	expectApp(t, callApp(h, "GET", "/"+todoID+"/data", a, ""), 503, "storage_unavailable")
	expectApp(t, callApp(h, "GET", "/"+todoID+"/versions/v1", devAppsToken, ""), 200, "")
	if raw, err := os.ReadFile(badPath); err != nil || string(raw) != bad {
		t.Fatal("corrupt partition was modified")
	}
	const write = `{"mutationId":"write","baseRevision":0,"items":[]}`
	expectApp(t, callApp(h, "PUT", "/"+peerID+"/data", b, write), 200, "")

	dir = t.TempDir()
	h = testApps(t, dir, time.Now, defs)
	a, b = appToken(t, h, todoID), appToken(t, h, peerID)
	if err := os.Mkdir(filepath.Join(dir, defs[0].File), 0700); err != nil {
		t.Fatal(err)
	}
	expectApp(t, callApp(h, "PUT", "/"+todoID+"/data", a, write), 503, "storage_unavailable")
	expectApp(t, callApp(h, "GET", "/"+todoID+"/data", a, ""), 503, "storage_unavailable")
	expectApp(t, callApp(h, "PUT", "/"+peerID+"/data", b, write), 200, "")
}
