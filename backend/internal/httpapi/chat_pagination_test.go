package httpapi

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	"abu.local/backend/internal/model"
)

type historyResponse struct {
	Messages        []Message `json:"messages"`
	ModelConfigured bool      `json:"modelConfigured"`
	SessionID       string    `json:"sessionId"`
	HasMore         bool      `json:"hasMore"`
	NextBefore      string    `json:"nextBefore"`
}

func decodeHistory(t *testing.T, w *httptest.ResponseRecorder) historyResponse {
	t.Helper()
	if w.Code != 200 {
		t.Fatalf("history status=%d body=%s", w.Code, w.Body.String())
	}
	var payload historyResponse
	if err := json.Unmarshal(w.Body.Bytes(), &payload); err != nil {
		t.Fatalf("history body: %v (%s)", err, w.Body.String())
	}
	if payload.Messages == nil {
		t.Fatalf("history messages must not be null: %s", w.Body.String())
	}
	return payload
}

func messageIDs(page historyResponse) []string {
	ids := make([]string, 0, len(page.Messages))
	for _, m := range page.Messages {
		ids = append(ids, m.ID)
	}
	return ids
}

func seededChat(t *testing.T, count int) http.Handler {
	t.Helper()
	h, _ := newChatHandler(t)
	for i := 0; i < count; i++ {
		body := fmt.Sprintf(`{"id":"m-%d","content":"第%d条"}`, i, i)
		if w := request(h, "POST", body, testToken); w.Code != 201 {
			t.Fatalf("seed %d status=%d body=%s", i, w.Code, w.Body.String())
		}
	}
	return h
}

func TestHistoryPagesByLimitAndCursor(t *testing.T) {
	h := seededChat(t, 5)
	page := decodeHistory(t, chatRequest(h, "GET", "/api/chat/messages?limit=2", ""))
	if got := messageIDs(page); len(got) != 2 || got[0] != "m-3" || got[1] != "m-4" {
		t.Fatalf("first page=%v", got)
	}
	if !page.HasMore || page.NextBefore != "m-3" || page.SessionID != defaultSessionID {
		t.Fatalf("first page cursor=%+v", page)
	}
	page = decodeHistory(t, chatRequest(h, "GET", "/api/chat/messages?limit=2&before=m-3", ""))
	if got := messageIDs(page); len(got) != 2 || got[0] != "m-1" || got[1] != "m-2" {
		t.Fatalf("second page=%v", got)
	}
	if !page.HasMore || page.NextBefore != "m-1" {
		t.Fatalf("second page cursor=%+v", page)
	}
	page = decodeHistory(t, chatRequest(h, "GET", "/api/chat/messages?limit=2&before=m-1", ""))
	if got := messageIDs(page); len(got) != 1 || got[0] != "m-0" || page.HasMore || page.NextBefore != "" {
		t.Fatalf("third page=%+v", page)
	}
	page = decodeHistory(t, chatRequest(h, "GET", "/api/chat/messages?before=m-0", ""))
	if len(page.Messages) != 0 || page.HasMore {
		t.Fatalf("empty page=%+v", page)
	}
	// The default page holds everything, so a short history has no cursor.
	page = decodeHistory(t, chatRequest(h, "GET", "/api/chat/messages", ""))
	if len(page.Messages) != 5 || page.HasMore || page.NextBefore != "" {
		t.Fatalf("default page=%+v", page)
	}
}

func TestHistoryRejectsInvalidPaginationAndScope(t *testing.T) {
	h := seededChat(t, 2)
	for _, target := range []string{
		"/api/chat/messages?limit=0",
		"/api/chat/messages?limit=201",
		"/api/chat/messages?limit=abc",
		"/api/chat/messages?limit=",
		"/api/chat/messages?limit=1&limit=2",
		"/api/chat/messages?before=",
		"/api/chat/messages?other=1",
	} {
		if w := chatRequest(h, "GET", target, ""); w.Code != 400 ||
			!strings.Contains(w.Body.String(), "invalid_message") {
			t.Fatalf("target=%s status=%d body=%s", target, w.Code, w.Body.String())
		}
	}
	for _, testCase := range []struct{ target, code string }{
		{"/api/chat/messages?sessionId=bad.id", "invalid_session_id"},
		{"/api/chat/messages?sessionId=a&sessionId=b", "invalid_session_id"},
		{"/api/chat/messages?botId=bad.id", "invalid_bot_id"},
		{"/api/chat/messages?botId=a&botId=b", "invalid_bot_id"},
	} {
		w := chatRequest(h, "GET", testCase.target, "")
		if w.Code != 400 || !strings.Contains(w.Body.String(), testCase.code) {
			t.Fatalf("target=%s status=%d body=%s", testCase.target, w.Code, w.Body.String())
		}
	}
	if w := chatRequest(h, "GET", "/api/chat/messages?before=missing", ""); w.Code != 404 ||
		!strings.Contains(w.Body.String(), "message_not_found") {
		t.Fatalf("missing cursor status=%d body=%s", w.Code, w.Body.String())
	}
	if w := chatRequest(h, "GET", "/api/chat/messages?sessionId=nope", ""); w.Code != 404 ||
		!strings.Contains(w.Body.String(), "session_not_found") {
		t.Fatalf("unknown session status=%d body=%s", w.Code, w.Body.String())
	}
	if w := chatRequest(h, "GET", "/api/chat/messages?sessionId=work&limit=3", ""); w.Code != 404 {
		t.Fatalf("unknown session with paging status=%d", w.Code)
	}
}

func TestHistoryPagesBeyondOneDefaultPage(t *testing.T) {
	h := seededChat(t, 250)
	page := decodeHistory(t, chatRequest(h, "GET", "/api/chat/messages", ""))
	if len(page.Messages) != defaultPageLimit || !page.HasMore ||
		page.NextBefore != "m-50" || page.Messages[0].ID != "m-50" || page.Messages[199].ID != "m-249" {
		t.Fatalf("first page size=%d cursor=%q", len(page.Messages), page.NextBefore)
	}
	page = decodeHistory(t, chatRequest(h, "GET", "/api/chat/messages?before=m-50", ""))
	if len(page.Messages) != 50 || page.HasMore || page.Messages[0].ID != "m-0" ||
		page.Messages[49].ID != "m-49" {
		t.Fatalf("second page size=%d", len(page.Messages))
	}
}

func TestSessionMessageLimitIsEnforced(t *testing.T) {
	var rows []string
	for i := 0; i < maxSessionMessages; i++ {
		rows = append(rows, messageJSON(fmt.Sprintf("full-%d", i), defaultBotID, defaultSessionID, "内容"))
	}
	path := t.TempDir() + "/messages.json"
	snapshot := `{"version":2,"sessions":[],"messages":[` + strings.Join(rows, ",") + `]}`
	if err := os.WriteFile(path, []byte(snapshot), 0600); err != nil {
		t.Fatal(err)
	}
	h, err := NewChatHandler(path, testToken)
	if err != nil {
		t.Fatalf("full snapshot rejected: %v", err)
	}
	if w := request(h, "POST", `{"id":"overflow","content":"再来一条"}`, testToken); w.Code != 409 ||
		!strings.Contains(w.Body.String(), "history_limit") {
		t.Fatalf("session limit status=%d body=%s", w.Code, w.Body.String())
	}
	if w := chatRequest(h, "POST", "/api/chat/sessions", `{"id":"work"}`); w.Code != 201 {
		t.Fatalf("create session status=%d", w.Code)
	}
	if w := request(h, "POST", `{"id":"fresh","content":"新会话","sessionId":"work"}`, testToken); w.Code != 201 {
		t.Fatalf("other session status=%d body=%s", w.Code, w.Body.String())
	}
}

func TestMessagesAndRepliesAreScopedToTheirSession(t *testing.T) {
	h, _ := newChatHandler(t)
	if w := chatRequest(h, "POST", "/api/chat/sessions", `{"id":"work","title":"工作"}`); w.Code != 201 {
		t.Fatalf("create session status=%d", w.Code)
	}
	if w := request(h, "POST", `{"id":"same","content":"默认"}`, testToken); w.Code != 201 {
		t.Fatalf("default message status=%d", w.Code)
	}
	if w := request(h, "POST", `{"id":"same","content":"默认","sessionId":"work"}`, testToken); w.Code != 409 ||
		!strings.Contains(w.Body.String(), "id_conflict") {
		t.Fatalf("cross session id status=%d body=%s", w.Code, w.Body.String())
	}
	if w := request(h, "POST", `{"id":"work-1","content":"工作","sessionId":"nope"}`, testToken); w.Code != 404 ||
		!strings.Contains(w.Body.String(), "session_not_found") {
		t.Fatalf("unknown session status=%d body=%s", w.Code, w.Body.String())
	}
	if w := request(h, "POST", `{"id":"work-1","content":"工作","sessionId":"bad.id"}`, testToken); w.Code != 400 ||
		!strings.Contains(w.Body.String(), "invalid_session_id") {
		t.Fatalf("invalid session status=%d body=%s", w.Code, w.Body.String())
	}
	if w := request(h, "POST", `{"id":"work-1","content":"工作","sessionId":"work"}`, testToken); w.Code != 201 {
		t.Fatalf("work message status=%d body=%s", w.Code, w.Body.String())
	}
	page := decodeHistory(t, chatRequest(h, "GET", "/api/chat/messages?sessionId=work", ""))
	if len(page.Messages) != 1 || page.Messages[0].SessionID != "work" || page.SessionID != "work" {
		t.Fatalf("work history=%+v", page)
	}
	// An edit only reaches the session that owns the turn.
	if w := putTo(h, "/api/chat/messages/work-1", `{"content":"改过"}`); w.Code != 404 ||
		!strings.Contains(w.Body.String(), "message_not_found") {
		t.Fatalf("edit in default status=%d body=%s", w.Code, w.Body.String())
	}
	if w := putTo(h, "/api/chat/messages/work-1", `{"content":"改过","sessionId":"work"}`); w.Code != 200 ||
		!strings.Contains(w.Body.String(), `"content":"改过"`) {
		t.Fatalf("edit status=%d body=%s", w.Code, w.Body.String())
	}
	if w := putTo(h, "/api/chat/messages/work-1", `{"content":"改过","sessionId":"bad.id"}`); w.Code != 400 {
		t.Fatalf("edit session format status=%d", w.Code)
	}
	// Deletion is scoped the same way.
	if w := deleteRequest(h, "/api/chat/messages/work-1", testToken); w.Code != 404 {
		t.Fatalf("delete in default status=%d", w.Code)
	}
	if w := deleteRequest(h, "/api/chat/messages/work-1?sessionId=bad.id", testToken); w.Code != 400 {
		t.Fatalf("delete session format status=%d", w.Code)
	}
	// Clearing one session keeps the other one intact.
	if w := request(h, "POST", `{"id":"work-2","content":"工作二","sessionId":"work"}`, testToken); w.Code != 201 {
		t.Fatalf("second work message status=%d", w.Code)
	}
	w := deleteRequest(h, "/api/chat/messages?sessionId=work", testToken)
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"deletedCount":2`) {
		t.Fatalf("clear status=%d body=%s", w.Code, w.Body.String())
	}
	if page := decodeHistory(t, chatRequest(h, "GET", "/api/chat/messages", "")); len(page.Messages) != 1 {
		t.Fatalf("default history after clear=%+v", page.Messages)
	}
	if page := decodeHistory(t, chatRequest(h, "GET", "/api/chat/messages?sessionId=work", "")); len(page.Messages) != 0 {
		t.Fatalf("work history after clear=%+v", page.Messages)
	}
	if w := deleteRequest(h, "/api/chat/messages/work-2?sessionId=work", testToken); w.Code != 404 {
		t.Fatalf("delete after clear status=%d", w.Code)
	}
}

// gateCompleter blocks every generation until the test releases it, so two sessions can be shown
// to run at the same time.
type gateCompleter struct {
	entered chan struct{}
	release chan struct{}
}

func (g *gateCompleter) Complete(ctx context.Context, _ []model.Message) (string, error) {
	select {
	case g.entered <- struct{}{}:
	case <-ctx.Done():
		return "", ctx.Err()
	}
	select {
	case <-g.release:
		return "答案", nil
	case <-ctx.Done():
		return "", ctx.Err()
	}
}

func TestSessionsOfOneRoleReplyIndependently(t *testing.T) {
	provider := &gateCompleter{entered: make(chan struct{}, 2), release: make(chan struct{})}
	h, err := NewChatHandlerWithModel(t.TempDir()+"/messages.json", testToken, provider)
	if err != nil {
		t.Fatal(err)
	}
	chatRequest(h, "POST", "/api/chat/sessions", `{"id":"work"}`)
	request(h, "POST", `{"id":"default-turn","content":"默认问题"}`, testToken)
	request(h, "POST", `{"id":"work-turn","content":"工作问题","sessionId":"work"}`, testToken)

	finished := make(chan *httptest.ResponseRecorder, 2)
	go func() { finished <- reply(h, `{"messageId":"work-turn","sessionId":"work"}`) }()
	go func() { finished <- reply(h, `{"messageId":"default-turn"}`) }()
	// Both generations reach the model, so one session never waits for the other.
	for i := 0; i < 2; i++ {
		<-provider.entered
	}
	if w := reply(h, `{"messageId":"default-turn","retry":true}`); w.Code != 409 {
		t.Fatalf("second reply in the same session status=%d", w.Code)
	}
	if w := request(h, "POST", `{"id":"blocked","content":"新问题"}`, testToken); w.Code != 409 {
		t.Fatalf("write into a generating session status=%d", w.Code)
	}
	if w := request(h, "POST", `{"id":"blocked","content":"新问题","sessionId":"work"}`, testToken); w.Code != 409 {
		t.Fatalf("write into the other generating session status=%d", w.Code)
	}
	// A cancel names the session too, so the other session stays untouched.
	if w := postTo(h, "/api/chat/replies/cancel", `{"messageId":"work-turn"}`); w.Code != 404 ||
		!strings.Contains(w.Body.String(), "message_not_found") {
		t.Fatalf("cancel in default status=%d body=%s", w.Code, w.Body.String())
	}
	close(provider.release)
	for i := 0; i < 2; i++ {
		if w := <-finished; w.Code != 200 {
			t.Fatalf("reply status=%d body=%s", w.Code, w.Body.String())
		}
	}
	// Both sessions are idle again.
	if w := request(h, "POST", `{"id":"after","content":"结束之后","sessionId":"work"}`, testToken); w.Code != 201 {
		t.Fatalf("write after reply status=%d body=%s", w.Code, w.Body.String())
	}
}

func TestReplyContextStaysInsideItsSession(t *testing.T) {
	provider := &sequenceProvider{replies: []string{"工作回答"}}
	h, err := NewChatHandlerWithModel(t.TempDir()+"/messages.json", testToken, provider)
	if err != nil {
		t.Fatal(err)
	}
	chatRequest(h, "POST", "/api/chat/sessions", `{"id":"work"}`)
	request(h, "POST", `{"id":"default-turn","content":"默认问题"}`, testToken)
	request(h, "POST", `{"id":"work-turn","content":"工作问题","sessionId":"work"}`, testToken)
	if w := reply(h, `{"messageId":"work-turn","sessionId":"work"}`); w.Code != 200 {
		t.Fatalf("reply status=%d body=%s", w.Code, w.Body.String())
	}
	calls, contexts := provider.stats()
	if calls != 1 {
		t.Fatalf("model calls=%d", calls)
	}
	joined := ""
	for _, message := range contexts[0] {
		joined += message.Content + "|"
	}
	if !strings.Contains(joined, "工作问题") || strings.Contains(joined, "默认问题") {
		t.Fatalf("context leaked across sessions: %s", joined)
	}
	page := decodeHistory(t, chatRequest(h, "GET", "/api/chat/messages?sessionId=work", ""))
	if len(page.Messages) != 1 || page.Messages[0].ReplyStatus != "completed" || page.Messages[0].Reply != "工作回答" {
		t.Fatalf("work history=%+v", page.Messages)
	}
	defaultPage := decodeHistory(t, chatRequest(h, "GET", "/api/chat/messages", ""))
	if len(defaultPage.Messages) != 1 || defaultPage.Messages[0].ReplyStatus != "" {
		t.Fatalf("default history=%+v", defaultPage.Messages)
	}
}

func TestRoleDeleteRefusesBusySession(t *testing.T) {
	provider := &gateCompleter{entered: make(chan struct{}, 1), release: make(chan struct{})}
	h, bots := twoRoleChat(t, provider)
	if w := chatRequest(h, "POST", "/api/chat/sessions", `{"id":"leaf-work","botId":"leaf"}`); w.Code != 201 {
		t.Fatalf("leaf session status=%d body=%s", w.Code, w.Body.String())
	}
	request(h, "POST", `{"id":"leaf-turn","botId":"leaf","content":"叶子提问","sessionId":"leaf-work"}`, testToken)
	finished := make(chan *httptest.ResponseRecorder, 1)
	go func() {
		finished <- reply(h, `{"messageId":"leaf-turn","botId":"leaf","sessionId":"leaf-work"}`)
	}()
	<-provider.entered
	if w := deleteBot(bots, "/leaf"); w.Code != 409 || !strings.Contains(w.Body.String(), "bot_busy") {
		t.Fatalf("busy delete status=%d body=%s", w.Code, w.Body.String())
	}
	close(provider.release)
	if w := <-finished; w.Code != 200 {
		t.Fatalf("reply status=%d body=%s", w.Code, w.Body.String())
	}
	if w := deleteBot(bots, "/leaf"); w.Code != 200 {
		t.Fatalf("idle delete status=%d body=%s", w.Code, w.Body.String())
	}
}
