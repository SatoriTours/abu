package httpapi

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

func botCall(h http.Handler, method, path, token, body string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, "/api/bots"+path, strings.NewReader(body))
	r.Header.Set("Authorization", "Bearer "+token)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}

func TestBotsConcurrentCapacityAndRevision(t *testing.T) {
	path := filepath.Join(t.TempDir(), "bots.json")
	h, err := NewBots(path, testToken)
	if err != nil {
		t.Fatal(err)
	}
	if got := botCall(h, "GET", "", "wrong", ""); got.Code != 401 {
		t.Fatal(got.Code)
	}
	var wg sync.WaitGroup
	results := make(chan int, 40)
	for i := range 40 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			body := fmt.Sprintf(`{"id":"b%d","name":"角色😀","instructions":"","avatar":"wave"}`, i)
			results <- botCall(h, "POST", "", testToken, body).Code
		}()
	}
	wg.Wait()
	close(results)
	ok, full := 0, 0
	for code := range results {
		switch code {
		case 201:
			ok++
		case 409:
			full++
		default:
			t.Fatal(code)
		}
	}
	if ok != 31 || full != 9 {
		t.Fatalf("created=%d full=%d", ok, full)
	}
	body := `{"name":"主助手","instructions":"","avatar":"abu","revision":1}`
	updates := make(chan int, 2)
	for range 2 {
		wg.Add(1)
		go func() { defer wg.Done(); updates <- botCall(h, "PUT", "/abu", testToken, body).Code }()
	}
	wg.Wait()
	c1, c2 := <-updates, <-updates
	if !((c1 == 200 && c2 == 409) || (c1 == 409 && c2 == 200)) {
		t.Fatalf("update results %d/%d", c1, c2)
	}
	h, err = NewBots(path, testToken)
	if err != nil {
		t.Fatal(err)
	}
	var data struct {
		Bots []struct {
			ID, Name string
			Revision int64
		}
	}
	w := botCall(h, "GET", "", testToken, "")
	if json.Unmarshal(w.Body.Bytes(), &data) != nil || len(data.Bots) != 32 || data.Bots[0].ID != "abu" || data.Bots[0].Revision != 2 || data.Bots[0].Name != "主助手" {
		t.Fatal(w.Body.String())
	}
}

func TestBotsRejectInvalidAndKeepSnapshotOnFault(t *testing.T) {
	path := filepath.Join(t.TempDir(), "bots.json")
	h, err := NewBots(path, testToken)
	if err != nil {
		t.Fatal(err)
	}
	for _, b := range []string{
		`{"id":"x","name":" ","instructions":"","avatar":"abu"}`,
		`{"id":"x","name":"\ud800","instructions":"","avatar":"abu"}`,
		`{"id":"x","name":"x","name":"y","instructions":"","avatar":"abu"}`,
		`{"id":"x","name":"x","instructions":"","avatar":"custom"}`,
		`{"id":"x","name":"x","instructions":"","avatar":"leaf","tools":[]}`,
		`{"id":"x","name":"` + strings.Repeat("😀", 41) + `","instructions":"","avatar":"leaf"}`,
		`{"id":"x","name":"x","instructions":"` + strings.Repeat("😀", 4001) + `","avatar":"leaf"}`,
	} {
		if got := botCall(h, "POST", "", testToken, b); got.Code != 400 {
			t.Fatal(got.Code, got.Body.String())
		}
	}
	body := `{"id":"x","name":"x","instructions":"","avatar":"fluff"}`
	if err := os.Mkdir(path, 0700); err != nil {
		t.Fatal(err)
	}
	if got := botCall(h, "POST", "", testToken, body); got.Code != 503 {
		t.Fatal(got.Code, got.Body.String())
	}
	if got := botCall(h, "GET", "", testToken, ""); strings.Contains(got.Body.String(), `"id":"x"`) {
		t.Fatal(got.Body.String())
	}
	if err := os.Remove(path); err != nil {
		t.Fatal(err)
	}
	const corrupt = `[{"id":"abu","private":"broken"}]`
	if err := os.WriteFile(path, []byte(corrupt), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := NewBots(path, testToken); err == nil {
		t.Fatal("loaded invalid bot snapshot")
	}
	if raw, err := os.ReadFile(path); err != nil || string(raw) != corrupt {
		t.Fatal("corrupt snapshot changed")
	}
}
