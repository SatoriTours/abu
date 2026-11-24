package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
	"unicode/utf8"

	"abu.local/backend/internal/model"
)

// sequenceProvider returns one prepared reply per call, so a test can tell a stored answer
// from a regenerated one, and records the context each call received.
type sequenceProvider struct {
	mu       sync.Mutex
	replies  []string
	errs     []error
	calls    int
	contexts [][]model.Message
}

func (p *sequenceProvider) Complete(_ context.Context, messages []model.Message) (string, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	index := p.calls
	p.calls++
	p.contexts = append(p.contexts, messages)
	var reply string
	if len(p.replies) > 0 {
		reply = p.replies[len(p.replies)-1]
		if index < len(p.replies) {
			reply = p.replies[index]
		}
	}
	if index < len(p.errs) && p.errs[index] != nil {
		return "", p.errs[index]
	}
	return reply, nil
}

func (p *sequenceProvider) stats() (int, [][]model.Message) {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.calls, append([][]model.Message{}, p.contexts...)
}

func putTo(h http.Handler, target, body string) *httptest.ResponseRecorder {
	r := httptest.NewRequest("PUT", target, strings.NewReader(body))
	r.Header.Set("Authorization", "Bearer "+testToken)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}

func deleteBot(h http.Handler, path string) *httptest.ResponseRecorder {
	r := httptest.NewRequest("DELETE", "/api/bots"+path, nil)
	r.Header.Set("Authorization", "Bearer "+testToken)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}

func chatGet(h http.Handler, query string) *httptest.ResponseRecorder {
	r := httptest.NewRequest("GET", "/api/chat/messages"+query, nil)
	r.Header.Set("Authorization", "Bearer "+testToken)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}

func newLeafBot(t *testing.T, bots http.Handler) {
	t.Helper()
	if w := botCall(bots, "POST", "", testToken, `{"id":"leaf","name":"叶子","instructions":"","avatar":"leaf"}`); w.Code != 201 {
		t.Fatalf("create leaf status=%d body=%s", w.Code, w.Body.String())
	}
}

func TestRegenerateReplacesCompletedReply(t *testing.T) {
	provider := &sequenceProvider{replies: []string{"第一版", "第二版"}}
	h, err := NewChatHandlerWithModel(filepath.Join(t.TempDir(), "m.json"), testToken, provider)
	if err != nil {
		t.Fatal(err)
	}
	request(h, "POST", `{"id":"one","content":"hello"}`, testToken)
	if w := reply(h, `{"messageId":"one"}`); w.Code != 200 {
		t.Fatalf("first reply status=%d body=%s", w.Code, w.Body.String())
	}
	// Without regenerate a completed turn is still answered from storage.
	if replay := reply(h, `{"messageId":"one"}`); replay.Code != 200 {
		t.Fatalf("replay status=%d body=%s", replay.Code, replay.Body.String())
	}
	if calls, _ := provider.stats(); calls != 1 {
		t.Fatalf("stored reply was regenerated: calls=%d", calls)
	}
	w := reply(h, `{"messageId":"one","regenerate":true}`)
	var message Message
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &message) != nil {
		t.Fatalf("regenerate status=%d body=%s", w.Code, w.Body.String())
	}
	if message.Reply != "第二版" || message.ReplyStatus != "completed" {
		t.Fatalf("regenerated message=%+v", message)
	}
	calls, contexts := provider.stats()
	if calls != 2 {
		t.Fatalf("model calls=%d", calls)
	}
	// The reply being replaced must never be replayed as assistant context.
	if len(contexts[1]) != 1 || contexts[1][0].Role != "user" || contexts[1][0].Content != "hello" {
		t.Fatalf("regenerate context=%+v", contexts[1])
	}
	history := request(h, "GET", "", testToken).Body.String()
	if !strings.Contains(history, `"reply":"第二版"`) || strings.Contains(history, "第一版") {
		t.Fatalf("history=%s", history)
	}
}

func TestRegenerateKeepsEarlierTurnsAsContext(t *testing.T) {
	provider := &sequenceProvider{replies: []string{"答一", "答二", "答三"}}
	h, err := NewChatHandlerWithModel(filepath.Join(t.TempDir(), "m.json"), testToken, provider)
	if err != nil {
		t.Fatal(err)
	}
	request(h, "POST", `{"id":"one","content":"问一"}`, testToken)
	reply(h, `{"messageId":"one"}`)
	request(h, "POST", `{"id":"two","content":"问二"}`, testToken)
	reply(h, `{"messageId":"two"}`)
	if w := reply(h, `{"messageId":"two","regenerate":true}`); w.Code != 200 {
		t.Fatalf("regenerate status=%d body=%s", w.Code, w.Body.String())
	}
	_, contexts := provider.stats()
	got := contexts[2]
	want := []model.Message{
		{Role: "user", Content: "问一"},
		{Role: "assistant", Content: "答一"},
		{Role: "user", Content: "问二"},
	}
	if len(got) != len(want) {
		t.Fatalf("context=%+v", got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("context[%d]=%+v want %+v", i, got[i], want[i])
		}
	}
}

func TestRegenerateRequiresTheNewestTurn(t *testing.T) {
	provider := &sequenceProvider{replies: []string{"答一", "答二"}}
	h, err := NewChatHandlerWithModel(filepath.Join(t.TempDir(), "m.json"), testToken, provider)
	if err != nil {
		t.Fatal(err)
	}
	request(h, "POST", `{"id":"one","content":"问一"}`, testToken)
	reply(h, `{"messageId":"one"}`)
	request(h, "POST", `{"id":"two","content":"问二"}`, testToken)
	w := reply(h, `{"messageId":"one","regenerate":true}`)
	if w.Code != 409 || !strings.Contains(w.Body.String(), "reply_in_progress_or_not_latest") {
		t.Fatalf("status=%d body=%s", w.Code, w.Body.String())
	}
	for _, body := range []string{
		`{"messageId":"one","regenerate":"yes"}`,
		`{"messageId":"one","regenerate":null}`,
		`{"messageId":"one","regenerate":true,"extra":1}`,
	} {
		if got := reply(h, body); got.Code != 400 || !strings.Contains(got.Body.String(), "invalid_reply_request") {
			t.Fatalf("body=%s status=%d", body, got.Code)
		}
	}
	if calls, _ := provider.stats(); calls != 1 {
		t.Fatalf("model calls=%d", calls)
	}
}

func TestRegenerateFromFailedTurnNeedsNoRetryFlag(t *testing.T) {
	provider := &sequenceProvider{replies: []string{"", "救回来"}, errs: []error{errors.New("upstream down"), nil}}
	h, err := NewChatHandlerWithModel(filepath.Join(t.TempDir(), "m.json"), testToken, provider)
	if err != nil {
		t.Fatal(err)
	}
	request(h, "POST", `{"id":"one","content":"hello"}`, testToken)
	if w := reply(h, `{"messageId":"one"}`); w.Code != 502 {
		t.Fatalf("first reply status=%d", w.Code)
	}
	w := reply(h, `{"messageId":"one","regenerate":true}`)
	var message Message
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &message) != nil || message.Reply != "救回来" {
		t.Fatalf("status=%d body=%s", w.Code, w.Body.String())
	}
}

func TestRegenerateStreamsAFreshReply(t *testing.T) {
	provider := &streamProvider{deltas: []string{"新", "版"}}
	h, err := NewChatHandlerWithModel(filepath.Join(t.TempDir(), "m.json"), testToken, provider)
	if err != nil {
		t.Fatal(err)
	}
	request(h, "POST", `{"id":"one","content":"hello"}`, testToken)
	if w := postTo(h, "/api/chat/replies/stream", `{"messageId":"one"}`); w.Code != 200 {
		t.Fatalf("stream status=%d", w.Code)
	}
	w := postTo(h, "/api/chat/replies/stream", `{"messageId":"one","regenerate":true}`)
	if w.Code != 200 {
		t.Fatalf("regenerate stream status=%d body=%s", w.Code, w.Body.String())
	}
	events := parseSSE(w.Body.String())
	if len(events) != 3 || events[0].name != "delta" || events[2].name != "done" || provider.calls != 2 {
		t.Fatalf("events=%+v calls=%d", events, provider.calls)
	}
	if done := lastDoneEvent(t, w); done.Reply != "新版" || done.ReplyStatus != "completed" {
		t.Fatalf("done=%+v", done)
	}
}

func TestReplyAcceptsLongReplyAndRejectsOverBudget(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "m.json")
	long := strings.Repeat("好", replyTextLimit)
	provider := &sequenceProvider{replies: []string{long}}
	h, err := NewChatHandlerWithModel(path, testToken, provider)
	if err != nil {
		t.Fatal(err)
	}
	request(h, "POST", `{"id":"one","content":"hello"}`, testToken)
	w := reply(h, `{"messageId":"one"}`)
	var message Message
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &message) != nil {
		t.Fatalf("status=%d", w.Code)
	}
	if utf8.RuneCountInString(message.Reply) != replyTextLimit || message.ReplyStatus != "completed" {
		t.Fatalf("stored reply runes=%d status=%s", utf8.RuneCountInString(message.Reply), message.ReplyStatus)
	}
	// A restart must load a long stored reply instead of rejecting the snapshot.
	if _, err = NewChatHandlerWithModel(path, testToken, provider); err != nil {
		t.Fatalf("restart rejected a long reply: %v", err)
	}
	if msg := request(h, "GET", "", testToken).Body.String(); !strings.Contains(msg, `"replyStatus":"completed"`) {
		t.Fatalf("history lost the long reply")
	}

	over := &sequenceProvider{replies: []string{strings.Repeat("好", replyTextLimit+1)}}
	other, err := NewChatHandlerWithModel(filepath.Join(dir, "over.json"), testToken, over)
	if err != nil {
		t.Fatal(err)
	}
	request(other, "POST", `{"id":"one","content":"hello"}`, testToken)
	if w = reply(other, `{"messageId":"one"}`); w.Code != 502 || !strings.Contains(w.Body.String(), "model_reply_failed") {
		t.Fatalf("over budget status=%d body=%s", w.Code, w.Body.String())
	}
}

func TestEditMessageRewritesTheNewestTurn(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "m.json")
	provider := &sequenceProvider{replies: []string{"旧答案", "新答案"}}
	h, err := NewChatHandlerWithModel(path, testToken, provider)
	if err != nil {
		t.Fatal(err)
	}
	created := request(h, "POST", `{"id":"one","content":"原问题"}`, testToken)
	var before Message
	if json.Unmarshal(created.Body.Bytes(), &before) != nil {
		t.Fatal(created.Body.String())
	}
	reply(h, `{"messageId":"one"}`)
	w := putTo(h, "/api/chat/messages/one", `{"content":"改过的问题"}`)
	var edited Message
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &edited) != nil {
		t.Fatalf("status=%d body=%s", w.Code, w.Body.String())
	}
	if edited.ID != "one" || edited.CreatedAt != before.CreatedAt || edited.Content != "改过的问题" ||
		edited.Reply != "" || edited.ReplyStatus != "" || edited.BotID != "abu" {
		t.Fatalf("edited message=%+v", edited)
	}
	history := request(h, "GET", "", testToken).Body.String()
	if strings.Contains(history, "旧答案") || !strings.Contains(history, "改过的问题") {
		t.Fatalf("history=%s", history)
	}
	// The model must answer the edited text, and the restart must keep the edit.
	if w = reply(h, `{"messageId":"one"}`); w.Code != 200 || !strings.Contains(w.Body.String(), "新答案") {
		t.Fatalf("reply after edit status=%d body=%s", w.Code, w.Body.String())
	}
	_, contexts := provider.stats()
	if len(contexts) != 2 || len(contexts[1]) != 1 || contexts[1][0].Content != "改过的问题" {
		t.Fatalf("contexts=%+v", contexts)
	}
	restarted, err := NewChatHandlerWithModel(path, testToken, provider)
	if err != nil {
		t.Fatal(err)
	}
	if got := request(restarted, "GET", "", testToken).Body.String(); !strings.Contains(got, "改过的问题") {
		t.Fatalf("restart lost the edit: %s", got)
	}
}

func TestEditMessageValidation(t *testing.T) {
	bots, err := NewBots(filepath.Join(t.TempDir(), "bots.json"), testToken)
	if err != nil {
		t.Fatal(err)
	}
	newLeafBot(t, bots)
	h, err := NewChatHandlerWithBots(filepath.Join(t.TempDir(), "m.json"), testToken, nil, bots)
	if err != nil {
		t.Fatal(err)
	}
	request(h, "POST", `{"id":"one","content":"第一条"}`, testToken)
	request(h, "POST", `{"id":"two","content":"第二条"}`, testToken)
	for _, tc := range []struct {
		name, target, body string
		status             int
		code               string
	}{
		{"blank", "/api/chat/messages/two", `{"content":" "}`, 400, "invalid_message"},
		{"missing", "/api/chat/messages/two", `{}`, 400, "invalid_message"},
		{"null", "/api/chat/messages/two", `{"content":null}`, 400, "invalid_message"},
		{"duplicate", "/api/chat/messages/two", `{"content":"a","content":"b"}`, 400, "invalid_message"},
		{"extra", "/api/chat/messages/two", `{"content":"a","role":"assistant"}`, 400, "invalid_message"},
		{"too-long", "/api/chat/messages/two", `{"content":"` + strings.Repeat("x", 4001) + `"}`, 400, "invalid_message"},
		{"id-too-long", "/api/chat/messages/" + strings.Repeat("x", 129), `{"content":"a"}`, 400, "invalid_message"},
		{"query", "/api/chat/messages/two?botId=abu", `{"content":"a"}`, 400, "invalid_bot_id"},
		{"unknown-bot", "/api/chat/messages/two", `{"content":"a","botId":"missing"}`, 404, "bot_not_found"},
		{"bad-bot", "/api/chat/messages/two", `{"content":"a","botId":" bad"}`, 400, "invalid_bot_id"},
		{"missing-message", "/api/chat/messages/absent", `{"content":"a"}`, 404, "message_not_found"},
		{"not-latest", "/api/chat/messages/one", `{"content":"a"}`, 409, "not_latest_message"},
		{"other-role-message", "/api/chat/messages/two", `{"content":"a","botId":"leaf"}`, 404, "message_not_found"},
	} {
		w := putTo(h, tc.target, tc.body)
		if w.Code != tc.status || !strings.Contains(w.Body.String(), tc.code) {
			t.Fatalf("%s: status=%d body=%s", tc.name, w.Code, w.Body.String())
		}
	}
	if got := chatGet(h, "").Body.String(); !strings.Contains(got, "第一条") || !strings.Contains(got, "第二条") {
		t.Fatalf("failed edits changed history: %s", got)
	}
	if w := putTo(h, "/api/chat/messages/two", `{"content":"改好了"}`); w.Code != 200 {
		t.Fatalf("valid edit status=%d body=%s", w.Code, w.Body.String())
	}
}

func TestEditMessageRejectedWhileReplying(t *testing.T) {
	provider := &blockedCompleter{started: make(chan struct{}), release: make(chan struct{})}
	h, err := NewChatHandlerWithModel(filepath.Join(t.TempDir(), "m.json"), testToken, provider)
	if err != nil {
		t.Fatal(err)
	}
	request(h, "POST", `{"id":"one","content":"hello"}`, testToken)
	finished := make(chan *httptest.ResponseRecorder, 1)
	go func() { finished <- reply(h, `{"messageId":"one"}`) }()
	<-provider.started
	edited := putTo(h, "/api/chat/messages/one", `{"content":"改过"}`)
	regenerated := reply(h, `{"messageId":"one","regenerate":true}`)
	close(provider.release)
	if w := <-finished; w.Code != 200 {
		t.Fatalf("reply status=%d", w.Code)
	}
	if edited.Code != 409 || !strings.Contains(edited.Body.String(), "reply_in_progress") {
		t.Fatalf("edit status=%d body=%s", edited.Code, edited.Body.String())
	}
	if regenerated.Code != 409 || !strings.Contains(regenerated.Body.String(), "reply_in_progress_or_not_latest") {
		t.Fatalf("regenerate status=%d body=%s", regenerated.Code, regenerated.Body.String())
	}
}

func TestDeleteBotRejectsDefaultInvalidAndMissing(t *testing.T) {
	bots, err := NewBots(filepath.Join(t.TempDir(), "bots.json"), testToken)
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		path   string
		status int
		code   string
	}{
		{"/abu", 409, "cannot_delete_default_bot"},
		{"/%20bad", 400, "invalid_bot_id"},
		{"/" + strings.Repeat("x", 129), 400, "invalid_bot_id"},
		{"/leaf?botId=leaf", 400, "invalid_bot_id"},
		{"/missing", 404, "bot_not_found"},
	} {
		w := deleteBot(bots, tc.path)
		if w.Code != tc.status || !strings.Contains(w.Body.String(), tc.code) {
			t.Fatalf("path=%s status=%d body=%s", tc.path, w.Code, w.Body.String())
		}
	}
	// Without child stores a role is removable straight away.
	newLeafBot(t, bots)
	w := deleteBot(bots, "/leaf")
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"deleted":true`) || !strings.Contains(w.Body.String(), `"id":"leaf"`) {
		t.Fatalf("delete status=%d body=%s", w.Code, w.Body.String())
	}
	if got := botCall(bots, "GET", "", testToken, "").Body.String(); strings.Contains(got, `"id":"leaf"`) {
		t.Fatalf("role survived: %s", got)
	}
}

func TestDeleteBotPurgesChildRecordsAndKeepsSnapshotsValid(t *testing.T) {
	dir := t.TempDir()
	botsPath := filepath.Join(dir, "bots.json")
	chatPath := filepath.Join(dir, "messages.json")
	taskPath := filepath.Join(dir, "tasks.json")
	genPath := filepath.Join(dir, "generated.json")
	provider := &sequenceProvider{replies: []string{"叶子的答案"}}
	bots, err := NewBots(botsPath, testToken)
	if err != nil {
		t.Fatal(err)
	}
	chat, err := NewChatHandlerWithBots(chatPath, testToken, provider, bots)
	if err != nil {
		t.Fatal(err)
	}
	generated := NewGenerated(genPath, testToken, bots)
	tasks, err := NewTaskAPIWithGenerated(taskPath, taskTestToken, provider, 5*time.Second, 5*time.Second, bots, generated)
	if err != nil {
		t.Fatal(err)
	}
	newLeafBot(t, bots)
	request(chat, "POST", `{"id":"abu-turn","content":"abu 提问"}`, testToken)
	request(chat, "POST", `{"id":"leaf-turn","botId":"leaf","content":"叶子提问"}`, testToken)
	taskCall(t, tasks, "POST", "", `{"id":"leaf-task","botId":"leaf","content":"叶子任务"}`, 201)
	for deadline := time.Now().Add(3 * time.Second); ; {
		got := taskCall(t, tasks, "GET", "/leaf-task?botId=leaf", "", 200)
		if got.Task.Status == "completed" {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("leaf task status=%s", got.Task.Status)
		}
		time.Sleep(5 * time.Millisecond)
	}

	w := deleteBot(bots, "/leaf")
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"deleted":true`) {
		t.Fatalf("delete status=%d body=%s", w.Code, w.Body.String())
	}
	if got := chatGet(chat, "?botId=leaf"); got.Code != 404 {
		t.Fatalf("leaf history status=%d", got.Code)
	}
	if got := taskCall(t, tasks, "GET", "/leaf-task?botId=leaf", "", 404); got.Error != "bot_not_found" {
		t.Fatalf("leaf task survived: %+v", got)
	}
	if got := chatGet(chat, "").Body.String(); !strings.Contains(got, "abu 提问") {
		t.Fatalf("abu history lost: %s", got)
	}
	if got := taskCall(t, tasks, "GET", "", "", 200); len(got.Tasks) != 0 {
		t.Fatalf("tasks survived purge: %+v", got.Tasks)
	}

	// Every snapshot must reload without an orphaned role reference.
	stopTaskAPI(t, tasks)
	reopened, err := NewBots(botsPath, testToken)
	if err != nil {
		t.Fatalf("bot snapshot no longer loads: %v", err)
	}
	if _, ok := reopened.lookup("leaf"); ok {
		t.Fatal("deleted role came back")
	}
	if _, err = NewChatHandlerWithBots(chatPath, testToken, provider, reopened); err != nil {
		t.Fatalf("chat snapshot no longer loads: %v", err)
	}
	reopenedGenerated := NewGenerated(genPath, testToken, reopened)
	if reopenedGenerated.broken {
		t.Fatal("generated snapshot no longer loads")
	}
	reopenedTasks, err := NewTaskAPIWithGenerated(taskPath, taskTestToken, provider, 5*time.Second, 5*time.Second, reopened, reopenedGenerated)
	if err != nil {
		t.Fatalf("task snapshot no longer loads: %v", err)
	}
	stopTaskAPI(t, reopenedTasks)
}

func TestDeleteBotRefusesBusyRole(t *testing.T) {
	t.Run("chat", func(t *testing.T) {
		provider := &blockedCompleter{started: make(chan struct{}), release: make(chan struct{})}
		bots, err := NewBots(filepath.Join(t.TempDir(), "bots.json"), testToken)
		if err != nil {
			t.Fatal(err)
		}
		chat, err := NewChatHandlerWithBots(filepath.Join(t.TempDir(), "m.json"), testToken, provider, bots)
		if err != nil {
			t.Fatal(err)
		}
		newLeafBot(t, bots)
		request(chat, "POST", `{"id":"leaf-turn","botId":"leaf","content":"叶子提问"}`, testToken)
		finished := make(chan *httptest.ResponseRecorder, 1)
		go func() { finished <- reply(chat, `{"messageId":"leaf-turn","botId":"leaf"}`) }()
		<-provider.started
		w := deleteBot(bots, "/leaf")
		close(provider.release)
		<-finished
		if w.Code != 409 || !strings.Contains(w.Body.String(), "bot_busy") {
			t.Fatalf("status=%d body=%s", w.Code, w.Body.String())
		}
		if w = deleteBot(bots, "/leaf"); w.Code != 200 {
			t.Fatalf("delete after reply status=%d body=%s", w.Code, w.Body.String())
		}
	})

	t.Run("tasks", func(t *testing.T) {
		provider := &blockedCompleter{started: make(chan struct{}), release: make(chan struct{})}
		bots, err := NewBots(filepath.Join(t.TempDir(), "bots.json"), testToken)
		if err != nil {
			t.Fatal(err)
		}
		tasks, err := NewTaskAPI(filepath.Join(t.TempDir(), "tasks.json"), taskTestToken, provider, 5*time.Second)
		if err != nil {
			t.Fatal(err)
		}
		// Rebind the store to the role set under test.
		tasks.bots = bots
		bots.RegisterDeleteHook(tasks)
		newLeafBot(t, bots)
		taskCall(t, tasks, "POST", "", `{"id":"leaf-task","botId":"leaf","content":"叶子任务"}`, 201)
		<-provider.started
		w := deleteBot(bots, "/leaf")
		close(provider.release)
		if w.Code != 409 || !strings.Contains(w.Body.String(), "bot_busy") {
			t.Fatalf("status=%d body=%s", w.Code, w.Body.String())
		}
		stopTaskAPI(t, tasks)
	})
}

func TestDeleteBotRefusesRoleWithGeneratedRecords(t *testing.T) {
	bots, err := NewBots(filepath.Join(t.TempDir(), "bots.json"), testToken)
	if err != nil {
		t.Fatal(err)
	}
	newLeafBot(t, bots)
	g := NewGenerated(filepath.Join(t.TempDir(), "generated.json"), testToken, bots)
	g.mu.Lock()
	g.state.Jobs = append(g.state.Jobs, genJob{ID: "job-1", BotID: "leaf", AppID: "app-1", Kind: "draft", Name: "小工具", Request: "画一个", Status: "queued"})
	g.mu.Unlock()
	w := deleteBot(bots, "/leaf")
	if w.Code != 409 || !strings.Contains(w.Body.String(), "bot_in_use") {
		t.Fatalf("status=%d body=%s", w.Code, w.Body.String())
	}
}

func TestDeleteBotReportsBrokenChildStores(t *testing.T) {
	t.Run("generated", func(t *testing.T) {
		dir := t.TempDir()
		bots, err := NewBots(filepath.Join(dir, "bots.json"), testToken)
		if err != nil {
			t.Fatal(err)
		}
		// A directory where the snapshot file belongs makes the store unrecoverable.
		genPath := filepath.Join(dir, "generated.json")
		if err := os.MkdirAll(genPath, 0700); err != nil {
			t.Fatal(err)
		}
		g := NewGenerated(genPath, testToken, bots)
		newLeafBot(t, bots)
		if !g.broken {
			t.Fatal("broken generated store was not detected")
		}
		w := deleteBot(bots, "/leaf")
		if w.Code != 503 || !strings.Contains(w.Body.String(), "storage_unavailable") {
			t.Fatalf("status=%d body=%s", w.Code, w.Body.String())
		}
	})

	t.Run("tasks", func(t *testing.T) {
		dir := t.TempDir()
		bots, err := NewBots(filepath.Join(dir, "bots.json"), testToken)
		if err != nil {
			t.Fatal(err)
		}
		tasks, err := NewTaskAPI(filepath.Join(dir, "tasks.json"), taskTestToken, &sequenceProvider{replies: []string{"答"}}, 5*time.Second)
		if err != nil {
			t.Fatal(err)
		}
		tasks.bots = bots
		bots.RegisterDeleteHook(tasks)
		newLeafBot(t, bots)
		tasks.mu.Lock()
		tasks.storageFailed = true
		tasks.mu.Unlock()
		w := deleteBot(bots, "/leaf")
		if w.Code != 503 || !strings.Contains(w.Body.String(), "storage_unavailable") {
			t.Fatalf("status=%d body=%s", w.Code, w.Body.String())
		}
		stopTaskAPI(t, tasks)
	})
}

func TestConcurrentRoleDeletionAndChatWritesStayConsistent(t *testing.T) {
	dir := t.TempDir()
	botsPath := filepath.Join(dir, "bots.json")
	chatPath := filepath.Join(dir, "messages.json")
	bots, err := NewBots(botsPath, testToken)
	if err != nil {
		t.Fatal(err)
	}
	provider := &sequenceProvider{replies: []string{"答案"}}
	chat, err := NewChatHandlerWithBots(chatPath, testToken, provider, bots)
	if err != nil {
		t.Fatal(err)
	}
	const roles = 4
	for i := 0; i < roles; i++ {
		body := `{"id":"role` + strconv.Itoa(i) + `","name":"角色` + strconv.Itoa(i) + `","instructions":"","avatar":"wave"}`
		if w := botCall(bots, "POST", "", testToken, body); w.Code != 201 {
			t.Fatalf("create role status=%d body=%s", w.Code, w.Body.String())
		}
		if w := request(chat, "POST", `{"id":"turn","botId":"role`+strconv.Itoa(i)+`","content":"提问"}`, testToken); w.Code != 201 {
			t.Fatalf("create turn status=%d body=%s", w.Code, w.Body.String())
		}
	}
	var group sync.WaitGroup
	for i := 0; i < roles; i++ {
		group.Add(2)
		id := "role" + strconv.Itoa(i)
		go func() {
			defer group.Done()
			if w := deleteBot(bots, "/"+id); w.Code != 200 {
				t.Errorf("delete %s status=%d body=%s", id, w.Code, w.Body.String())
			}
		}()
		go func(n int) {
			defer group.Done()
			body := `{"id":"abu-` + strconv.Itoa(n) + `","content":"并发写入"}`
			if w := request(chat, "POST", body, testToken); w.Code != 201 {
				t.Errorf("chat write status=%d body=%s", w.Code, w.Body.String())
			}
		}(i)
	}
	group.Wait()

	history := chatGet(chat, "").Body.String()
	if strings.Contains(history, `"id":"turn"`) || strings.Contains(history, "提问") {
		t.Fatalf("leftover role turn: %s", history)
	}
	count := strings.Count(history, "并发写入")
	if count != roles {
		t.Fatalf("abu writes kept=%d want %d: %s", count, roles, history)
	}
	reopened, err := NewBots(botsPath, testToken)
	if err != nil {
		t.Fatalf("bot snapshot no longer loads: %v", err)
	}
	if _, err = NewChatHandlerWithBots(chatPath, testToken, provider, reopened); err != nil {
		t.Fatalf("chat snapshot no longer loads: %v", err)
	}
}
