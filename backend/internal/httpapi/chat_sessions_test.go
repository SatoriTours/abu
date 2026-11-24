package httpapi

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"abu.local/backend/internal/model"
)

// chatRequest sends one authenticated chat request with an explicit target, so the session routes
// can be exercised next to the message routes.
func chatRequest(h http.Handler, method, target, body string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, target, strings.NewReader(body))
	r.Header.Set("Authorization", "Bearer "+testToken)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}

func decodeSessions(t *testing.T, w *httptest.ResponseRecorder) []Session {
	t.Helper()
	var payload struct {
		Sessions []Session `json:"sessions"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &payload); err != nil {
		t.Fatalf("session list: %v (%s)", err, w.Body.String())
	}
	return payload.Sessions
}

func sessionJSON(id, botID, title string) string {
	return `{"id":"` + id + `","botId":"` + botID + `","title":"` + title +
		`","createdAt":"2024-01-01T00:00:00Z","updatedAt":"2024-01-01T00:00:00Z"}`
}

func messageJSON(id, botID, sessionID, content string) string {
	return `{"id":"` + id + `","botId":"` + botID + `","sessionId":"` + sessionID +
		`","content":"` + content + `","createdAt":"2024-01-01T00:00:00Z"}`
}

func newChatHandler(t *testing.T) (http.Handler, string) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "messages.json")
	h, err := NewChatHandler(path, testToken)
	if err != nil {
		t.Fatal(err)
	}
	return h, path
}

func TestSessionListAlwaysContainsTheDefaultSession(t *testing.T) {
	h, _ := newChatHandler(t)
	w := chatRequest(h, "GET", "/api/chat/sessions", "")
	if w.Code != 200 {
		t.Fatalf("list status=%d body=%s", w.Code, w.Body.String())
	}
	sessions := decodeSessions(t, w)
	if len(sessions) != 1 || sessions[0].ID != defaultSessionID ||
		sessions[0].Title != defaultSessionTitle || sessions[0].BotID != defaultBotID ||
		sessions[0].CreatedAt != "" || sessions[0].UpdatedAt != "" {
		t.Fatalf("empty default session=%+v", sessions)
	}

	request(h, "POST", `{"id":"one","content":"first"}`, testToken)
	request(h, "POST", `{"id":"two","content":"second"}`, testToken)
	page := decodeHistory(t, chatRequest(h, "GET", "/api/chat/messages", ""))
	sessions = decodeSessions(t, chatRequest(h, "GET", "/api/chat/sessions", ""))
	if len(sessions) != 1 {
		t.Fatalf("sessions=%+v", sessions)
	}
	if sessions[0].CreatedAt != page.Messages[0].CreatedAt || sessions[0].UpdatedAt != page.Messages[1].CreatedAt {
		t.Fatalf("default session times=%+v messages=%+v", sessions[0], page.Messages)
	}

	if w := chatRequest(h, "POST", "/api/chat/sessions", `{"id":"work","title":"工作"}`); w.Code != 201 {
		t.Fatalf("create status=%d body=%s", w.Code, w.Body.String())
	}
	sessions = decodeSessions(t, chatRequest(h, "GET", "/api/chat/sessions", ""))
	if len(sessions) != 2 || sessions[0].ID != defaultSessionID || sessions[1].ID != "work" ||
		sessions[1].Title != "工作" {
		t.Fatalf("sessions=%+v", sessions)
	}
	if got := chatRequest(h, "GET", "/api/chat/sessions?botId=missing", ""); got.Code != 404 {
		t.Fatalf("unknown role status=%d", got.Code)
	}
	for _, target := range []string{"/api/chat/sessions?botId=%20bad", "/api/chat/sessions?other=1"} {
		if got := chatRequest(h, "GET", target, ""); got.Code != 400 {
			t.Fatalf("target=%s status=%d", target, got.Code)
		}
	}
}

func TestCreateSessionIsIdempotentAndValidatesInput(t *testing.T) {
	h, _ := newChatHandler(t)
	if w := chatRequest(h, "POST", "/api/chat/sessions", `{"id":"work"}`); w.Code != 201 ||
		!strings.Contains(w.Body.String(), `"title":"新对话"`) {
		t.Fatalf("default title status=%d body=%s", w.Code, w.Body.String())
	}
	if w := chatRequest(h, "POST", "/api/chat/sessions", `{"id":"work"}`); w.Code != 200 {
		t.Fatalf("repeat status=%d", w.Code)
	}
	if w := chatRequest(h, "POST", "/api/chat/sessions", `{"id":"work","title":"别的"}`); w.Code != 409 ||
		!strings.Contains(w.Body.String(), "id_conflict") {
		t.Fatalf("conflict status=%d body=%s", w.Code, w.Body.String())
	}
	if w := chatRequest(h, "POST", "/api/chat/sessions", `{"id":"other","title":""}`); w.Code != 201 ||
		!strings.Contains(w.Body.String(), `"title":"新对话"`) {
		t.Fatalf("empty title status=%d body=%s", w.Code, w.Body.String())
	}
	longTitle := strings.Repeat("标", 61)
	for _, body := range []string{
		`{}`,
		`{"id":""}`,
		`{"id":"default"}`,
		`{"id":"bad id"}`,
		`{"id":"work2","title":"   "}`,
		`{"id":"work2","title":"` + longTitle + `"}`,
		`{"id":"work2","title":"x","extra":1}`,
		`{"id":"work2","title":null}`,
	} {
		if w := chatRequest(h, "POST", "/api/chat/sessions", body); w.Code != 400 ||
			!strings.Contains(w.Body.String(), "invalid_session") {
			t.Fatalf("body=%s status=%d response=%s", body, w.Code, w.Body.String())
		}
	}
	if w := chatRequest(h, "POST", "/api/chat/sessions?botId=abu", `{"id":"work4"}`); w.Code != 400 ||
		!strings.Contains(w.Body.String(), "invalid_bot_id") {
		t.Fatalf("query status=%d body=%s", w.Code, w.Body.String())
	}
}

func TestCreateSessionRespectsThePerRoleLimit(t *testing.T) {
	h, _ := newChatHandler(t)
	// The default session occupies one of the fifty slots.
	for i := 0; i < maxSessionsPerBot-1; i++ {
		body := fmt.Sprintf(`{"id":"session-%d"}`, i)
		if w := chatRequest(h, "POST", "/api/chat/sessions", body); w.Code != 201 {
			t.Fatalf("session %d status=%d body=%s", i, w.Code, w.Body.String())
		}
	}
	if w := chatRequest(h, "POST", "/api/chat/sessions", `{"id":"one-too-many"}`); w.Code != 409 ||
		!strings.Contains(w.Body.String(), "session_limit") {
		t.Fatalf("limit status=%d body=%s", w.Code, w.Body.String())
	}
	if sessions := decodeSessions(t, chatRequest(h, "GET", "/api/chat/sessions", "")); len(sessions) != maxSessionsPerBot {
		t.Fatalf("session count=%d", len(sessions))
	}
	// Deleting one frees exactly one slot again.
	if w := chatRequest(h, "DELETE", "/api/chat/sessions/session-0", ""); w.Code != 200 {
		t.Fatalf("delete status=%d body=%s", w.Code, w.Body.String())
	}
	if w := chatRequest(h, "POST", "/api/chat/sessions", `{"id":"one-too-many"}`); w.Code != 201 {
		t.Fatalf("recreate status=%d body=%s", w.Code, w.Body.String())
	}
}

func TestRenameSessionIncludingTheDefaultOne(t *testing.T) {
	h, path := newChatHandler(t)
	if w := chatRequest(h, "POST", "/api/chat/sessions", `{"id":"work","title":"工作"}`); w.Code != 201 {
		t.Fatalf("create status=%d", w.Code)
	}
	w := chatRequest(h, "PUT", "/api/chat/sessions/work", `{"title":"工作台"}`)
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"title":"工作台"`) {
		t.Fatalf("rename status=%d body=%s", w.Code, w.Body.String())
	}
	// Renaming the default session stores its row, so the title survives a restart.
	w = chatRequest(h, "PUT", "/api/chat/sessions/default", `{"title":"聊天"}`)
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"title":"聊天"`) {
		t.Fatalf("default rename status=%d body=%s", w.Code, w.Body.String())
	}
	restarted, err := NewChatHandler(path, testToken)
	if err != nil {
		t.Fatalf("snapshot no longer loads: %v", err)
	}
	sessions := decodeSessions(t, chatRequest(restarted, "GET", "/api/chat/sessions", ""))
	if len(sessions) != 2 || sessions[0].ID != defaultSessionID || sessions[0].Title != "聊天" ||
		sessions[1].Title != "工作台" {
		t.Fatalf("sessions after restart=%+v", sessions)
	}
	if w := chatRequest(restarted, "PUT", "/api/chat/sessions/missing", `{"title":"x"}`); w.Code != 404 ||
		!strings.Contains(w.Body.String(), "session_not_found") {
		t.Fatalf("missing rename status=%d body=%s", w.Code, w.Body.String())
	}
	for _, testCase := range []struct{ target, body string }{
		{"/api/chat/sessions/work", `{"title":"   "}`},
		{"/api/chat/sessions/work", `{}`},
		{"/api/chat/sessions/work", `{"title":"x","extra":1}`},
		{"/api/chat/sessions/bad.id", `{"title":"x"}`},
	} {
		if w := chatRequest(restarted, "PUT", testCase.target, testCase.body); w.Code != 400 ||
			!strings.Contains(w.Body.String(), "invalid_session") {
			t.Fatalf("target=%s body=%s status=%d", testCase.target, testCase.body, w.Code)
		}
	}
	if w := chatRequest(restarted, "PUT", "/api/chat/sessions/work?botId=abu", `{"title":"x"}`); w.Code != 400 {
		t.Fatalf("query rename status=%d", w.Code)
	}
}

func TestDeleteSessionRemovesOnlyItsOwnMessages(t *testing.T) {
	h, _ := newChatHandler(t)
	chatRequest(h, "POST", "/api/chat/sessions", `{"id":"work","title":"工作"}`)
	request(h, "POST", `{"id":"keep","content":"默认问题"}`, testToken)
	request(h, "POST", `{"id":"gone","content":"工作问题","sessionId":"work"}`, testToken)
	request(h, "POST", `{"id":"gone2","content":"工作问题二","sessionId":"work"}`, testToken)

	if w := chatRequest(h, "DELETE", "/api/chat/sessions/default", ""); w.Code != 409 ||
		!strings.Contains(w.Body.String(), "cannot_delete_default_session") {
		t.Fatalf("default delete status=%d body=%s", w.Code, w.Body.String())
	}
	if w := chatRequest(h, "DELETE", "/api/chat/sessions/bad.id", ""); w.Code != 400 ||
		!strings.Contains(w.Body.String(), "invalid_session_id") {
		t.Fatalf("invalid id status=%d body=%s", w.Code, w.Body.String())
	}
	if w := chatRequest(h, "DELETE", "/api/chat/sessions/missing", ""); w.Code != 404 {
		t.Fatalf("missing status=%d", w.Code)
	}
	w := chatRequest(h, "DELETE", "/api/chat/sessions/work", "")
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"deleted":true`) ||
		!strings.Contains(w.Body.String(), `"deletedMessages":2`) {
		t.Fatalf("delete status=%d body=%s", w.Code, w.Body.String())
	}
	if got := chatRequest(h, "GET", "/api/chat/messages?sessionId=work", ""); got.Code != 404 ||
		!strings.Contains(got.Body.String(), "session_not_found") {
		t.Fatalf("deleted session history=%d %s", got.Code, got.Body.String())
	}
	page := decodeHistory(t, chatRequest(h, "GET", "/api/chat/messages", ""))
	if len(page.Messages) != 1 || page.Messages[0].ID != "keep" {
		t.Fatalf("default history=%+v", page.Messages)
	}
	if sessions := decodeSessions(t, chatRequest(h, "GET", "/api/chat/sessions", "")); len(sessions) != 1 {
		t.Fatalf("sessions after delete=%+v", sessions)
	}
}

// twoRoleChat builds a chat store next to a second role, so role scoped purge and rollback can be
// verified from the HTTP surface.
func twoRoleChat(t *testing.T, provider model.Completer) (http.Handler, *Bots) {
	t.Helper()
	dir := t.TempDir()
	bots, err := NewBots(filepath.Join(dir, "bots.json"), testToken)
	if err != nil {
		t.Fatal(err)
	}
	if w := botCall(bots, "POST", "", testToken, `{"id":"leaf","name":"叶子","instructions":"","avatar":"leaf"}`); w.Code != 201 {
		t.Fatalf("create leaf status=%d body=%s", w.Code, w.Body.String())
	}
	h, err := NewChatHandlerWithBots(filepath.Join(dir, "messages.json"), testToken, provider, bots)
	if err != nil {
		t.Fatal(err)
	}
	return h, bots
}

func TestDeleteBotPurgesSessionsOfThatRole(t *testing.T) {
	h, bots := twoRoleChat(t, nil)
	if w := chatRequest(h, "POST", "/api/chat/sessions", `{"id":"leaf-work","botId":"leaf"}`); w.Code != 201 {
		t.Fatalf("leaf session status=%d body=%s", w.Code, w.Body.String())
	}
	if w := chatRequest(h, "POST", "/api/chat/sessions", `{"id":"abu-work"}`); w.Code != 201 {
		t.Fatalf("abu session status=%d body=%s", w.Code, w.Body.String())
	}
	request(h, "POST", `{"id":"leaf-turn","botId":"leaf","content":"叶子提问","sessionId":"leaf-work"}`, testToken)
	request(h, "POST", `{"id":"abu-turn","content":"阿布提问","sessionId":"abu-work"}`, testToken)

	if w := deleteBot(bots, "/leaf"); w.Code != 200 {
		t.Fatalf("delete role status=%d body=%s", w.Code, w.Body.String())
	}
	if got := chatRequest(h, "GET", "/api/chat/sessions?botId=leaf", ""); got.Code != 404 {
		t.Fatalf("leaf sessions status=%d", got.Code)
	}
	sessions := decodeSessions(t, chatRequest(h, "GET", "/api/chat/sessions", ""))
	if len(sessions) != 2 || sessions[0].ID != defaultSessionID || sessions[1].ID != "abu-work" {
		t.Fatalf("abu sessions lost: %+v", sessions)
	}
}

func TestFailedDeleteBotRestoresSessions(t *testing.T) {
	h, bots := twoRoleChat(t, nil)
	if w := chatRequest(h, "POST", "/api/chat/sessions", `{"id":"leaf-work","title":"叶子工作","botId":"leaf"}`); w.Code != 201 {
		t.Fatalf("leaf session status=%d body=%s", w.Code, w.Body.String())
	}
	request(h, "POST", `{"id":"leaf-turn","botId":"leaf","content":"叶子提问","sessionId":"leaf-work"}`, testToken)
	// The injected hook fails the delete after the chat store purged its rows, so the rollback
	// has to restore them.
	bots.RegisterDeleteHook(&failingPurgeHook{})
	if w := deleteBot(bots, "/leaf"); w.Code != 503 {
		t.Fatalf("delete role status=%d body=%s", w.Code, w.Body.String())
	}
	sessions := decodeSessions(t, chatRequest(h, "GET", "/api/chat/sessions?botId=leaf", ""))
	if len(sessions) != 2 || sessions[0].ID != defaultSessionID || sessions[1].ID != "leaf-work" ||
		sessions[1].Title != "叶子工作" {
		t.Fatalf("sessions after a rollback=%+v", sessions)
	}
	page := decodeHistory(t, chatRequest(h, "GET", "/api/chat/messages?botId=leaf&sessionId=leaf-work", ""))
	if len(page.Messages) != 1 || page.Messages[0].ID != "leaf-turn" {
		t.Fatalf("messages after a rollback=%+v", page.Messages)
	}
}

func TestChatSnapshotAcceptsBothFormats(t *testing.T) {
	path := filepath.Join(t.TempDir(), "messages.json")
	legacy := `[{"id":"old","content":"旧消息","createdAt":"2024-01-01T00:00:00Z"}]`
	if err := os.WriteFile(path, []byte(legacy), 0600); err != nil {
		t.Fatal(err)
	}
	h, err := NewChatHandler(path, testToken)
	if err != nil {
		t.Fatalf("legacy snapshot rejected: %v", err)
	}
	page := decodeHistory(t, chatRequest(h, "GET", "/api/chat/messages", ""))
	if page.SessionID != defaultSessionID || len(page.Messages) != 1 ||
		page.Messages[0].SessionID != defaultSessionID {
		t.Fatalf("legacy page=%+v", page)
	}
	// The next write migrates the file to the version 2 object form.
	if w := request(h, "POST", `{"id":"new","content":"新消息"}`, testToken); w.Code != 201 {
		t.Fatalf("create status=%d", w.Code)
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var snapshot chatSnapshot
	if err := json.Unmarshal(raw, &snapshot); err != nil || snapshot.Version != chatSnapshotVersion ||
		len(snapshot.Messages) != 2 || snapshot.Sessions == nil {
		t.Fatalf("snapshot=%s (%v)", raw, err)
	}
	restarted, err := NewChatHandler(path, testToken)
	if err != nil {
		t.Fatalf("migrated snapshot rejected: %v", err)
	}
	if page := decodeHistory(t, chatRequest(restarted, "GET", "/api/chat/messages", "")); len(page.Messages) != 2 {
		t.Fatalf("messages after restart=%+v", page.Messages)
	}
}

func TestChatSnapshotRejectsBrokenFilesWithoutRewritingThem(t *testing.T) {
	session := sessionJSON("work", "abu", "工作")
	message := messageJSON("one", "abu", "default", "hi")
	cases := map[string]string{
		"wrong version":           `{"version":1,"sessions":[],"messages":[]}`,
		"string version":          `{"version":"2","sessions":[],"messages":[]}`,
		"missing sessions":        `{"version":2,"messages":[]}`,
		"extra top field":         `{"version":2,"sessions":[],"messages":[],"extra":1}`,
		"null messages":           `{"version":2,"sessions":[],"messages":null}`,
		"null session":            `{"version":2,"sessions":[null],"messages":[]}`,
		"duplicate session":       `{"version":2,"sessions":[` + session + `,` + session + `],"messages":[]}`,
		"unknown session bot":     `{"version":2,"sessions":[` + sessionJSON("work", "gone", "工作") + `],"messages":[]}`,
		"blank title":             `{"version":2,"sessions":[` + sessionJSON("work", "abu", "   ") + `],"messages":[]}`,
		"long title":              `{"version":2,"sessions":[` + sessionJSON("work", "abu", strings.Repeat("标", 61)) + `],"messages":[]}`,
		"missing session stamp":   `{"version":2,"sessions":[{"id":"work","botId":"abu","title":"x","createdAt":"","updatedAt":""}],"messages":[]}`,
		"unknown message session": `{"version":2,"sessions":[],"messages":[` + messageJSON("one", "abu", "gone", "hi") + `]}`,
		"missing message session": `{"version":2,"sessions":[],"messages":[{"id":"one","botId":"abu","content":"hi","createdAt":"2024-01-01T00:00:00Z"}]}`,
		"unknown message bot":     `{"version":2,"sessions":[],"messages":[` + messageJSON("one", "gone", "default", "hi") + `]}`,
		"duplicate message":       `{"version":2,"sessions":[],"messages":[` + message + `,` + message + `]}`,
		"not an array":            `{"version":2,"sessions":{},"messages":[]}`,
		"truncated":               `{"version":2,"sessions":[]`,
	}
	for name, body := range cases {
		t.Run(name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "messages.json")
			if err := os.WriteFile(path, []byte(body), 0600); err != nil {
				t.Fatal(err)
			}
			if _, err := NewChatHandler(path, testToken); err == nil {
				t.Fatalf("accepted a damaged snapshot: %s", body)
			}
			raw, err := os.ReadFile(path)
			if err != nil {
				t.Fatal(err)
			}
			if string(raw) != body {
				t.Fatalf("the damaged snapshot was rewritten to %s", raw)
			}
		})
	}
	// A renamed default session stores its row explicitly, which stays valid.
	path := filepath.Join(t.TempDir(), "messages.json")
	valid := `{"version":2,"sessions":[` + sessionJSON("default", "abu", "聊天") + `],"messages":[]}`
	if err := os.WriteFile(path, []byte(valid), 0600); err != nil {
		t.Fatal(err)
	}
	h, err := NewChatHandler(path, testToken)
	if err != nil {
		t.Fatalf("explicit default session rejected: %v", err)
	}
	sessions := decodeSessions(t, chatRequest(h, "GET", "/api/chat/sessions", ""))
	if len(sessions) != 1 || sessions[0].Title != "聊天" {
		t.Fatalf("sessions=%+v", sessions)
	}
}
