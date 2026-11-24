package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"abu.local/backend/internal/model"
)

// gatedProvider answers the first free calls at once and holds every later call until release is
// closed, so a test can finish one role's work while another role's deletion is between its child
// purges and its rollback.
type gatedProvider struct {
	mu       sync.Mutex
	calls    int
	free     int
	answers  []string
	release  chan struct{}
	entered  chan struct{}
	openOnce sync.Once
}

func newGatedProvider(free int, answers ...string) *gatedProvider {
	return &gatedProvider{free: free, answers: answers, release: make(chan struct{}), entered: make(chan struct{})}
}

func (p *gatedProvider) Complete(ctx context.Context, _ []model.Message) (string, error) {
	p.mu.Lock()
	p.calls++
	call := p.calls
	answer := ""
	if len(p.answers) > 0 {
		answer = p.answers[len(p.answers)-1]
		if call <= len(p.answers) {
			answer = p.answers[call-1]
		}
	}
	p.mu.Unlock()
	if call <= p.free {
		return answer, nil
	}
	p.openOnce.Do(func() { close(p.entered) })
	select {
	case <-p.release:
		return answer, nil
	case <-ctx.Done():
		return "", ctx.Err()
	}
}

// waitForOtherRoleTask stands in for a slow child store. It runs last, so every real store has
// already purged, then releases another role's queued model call and returns only once that
// role's task result is on disk: exactly the window a rollback must not overwrite.
type waitForOtherRoleTask struct {
	api     *TaskAPI
	botID   string
	taskID  string
	release chan struct{}
}

func (h *waitForOtherRoleTask) CheckBotDeletable(string) (int, string) { return 0, "" }

func (h *waitForOtherRoleTask) PurgeBot(string) (func() error, int, string) {
	close(h.release)
	deadline := time.Now().Add(5 * time.Second)
	for {
		h.api.mu.Lock()
		done := slices.ContainsFunc(h.api.tasks, func(row storedTask) bool {
			return row.BotID == h.botID && row.ID == h.taskID && row.Status == "completed"
		})
		h.api.mu.Unlock()
		if done {
			return nil, 0, ""
		}
		if time.Now().After(deadline) {
			return nil, 503, "storage_unavailable"
		}
		time.Sleep(time.Millisecond)
	}
}

// waitForOtherRoleReply does the same for a chat generation of another role: it reads the
// snapshot from disk, because the delete holds the role lock that the history route needs.
type waitForOtherRoleReply struct {
	path      string
	messageID string
	release   chan struct{}
}

func (h *waitForOtherRoleReply) CheckBotDeletable(string) (int, string) { return 0, "" }

func (h *waitForOtherRoleReply) PurgeBot(string) (func() error, int, string) {
	close(h.release)
	deadline := time.Now().Add(5 * time.Second)
	for {
		if raw, err := os.ReadFile(h.path); err == nil && replySettled(raw, h.messageID) {
			return nil, 0, ""
		}
		if time.Now().After(deadline) {
			return nil, 503, "storage_unavailable"
		}
		time.Sleep(time.Millisecond)
	}
}

func replySettled(raw []byte, messageID string) bool {
	var rows []Message
	if json.Unmarshal(raw, &rows) != nil {
		return false
	}
	return slices.ContainsFunc(rows, func(m Message) bool {
		return m.ID == messageID && m.ReplyStatus == "completed" && m.Reply != ""
	})
}

// failingPurgeHook is the injected child store failure of a double fault test. It fails after
// every earlier store purged, and onFail can make an earlier store's rollback fail the way a real
// disk fault would.
type failingPurgeHook struct{ onFail func() }

func (h *failingPurgeHook) CheckBotDeletable(string) (int, string) { return 0, "" }

func (h *failingPurgeHook) PurgeBot(string) (func() error, int, string) {
	if h.onFail != nil {
		h.onFail()
	}
	return nil, 503, "storage_unavailable"
}

const leafRoleBody = `{"id":"leaf","name":"叶子","instructions":"","avatar":"leaf"}`

// roleFixture is one data directory with every real child store wired to one role set, so a
// test exercises the production constructors, lock order and snapshots instead of doubles.
type roleFixture struct {
	bots      *Bots
	chat      http.Handler
	tasks     *TaskAPI
	generated *Generated
	botsPath  string
	chatPath  string
	taskPath  string
	genPath   string
}

func openRoleFixture(t *testing.T, dir string, provider model.Completer) *roleFixture {
	t.Helper()
	return openRoleFixtureAt(t, filepath.Join(dir, "bots.json"), filepath.Join(dir, "messages.json"),
		filepath.Join(dir, "tasks.json"), filepath.Join(dir, "generated.json"), provider)
}

// openRoleFixtureAt opens one role data set at explicit paths, so a test can give a single child
// store its own directory and make only that store unwritable.
func openRoleFixtureAt(t *testing.T, botsPath, chatPath, taskPath, genPath string, provider model.Completer) *roleFixture {
	t.Helper()
	f := &roleFixture{botsPath: botsPath, chatPath: chatPath, taskPath: taskPath, genPath: genPath}
	var err error
	if f.bots, err = NewBots(f.botsPath, testToken); err != nil {
		t.Fatal(err)
	}
	if f.chat, err = NewChatHandlerWithBots(f.chatPath, testToken, provider, f.bots); err != nil {
		t.Fatal(err)
	}
	f.generated = NewGenerated(f.genPath, testToken, f.bots)
	if f.tasks, err = NewTaskAPIWithGenerated(f.taskPath, taskTestToken, provider, 5*time.Second, 5*time.Second, f.bots, f.generated); err != nil {
		t.Fatal(err)
	}
	return f
}

// seedLeafRole stores the role with one chat turn and one finished task, which is the smallest
// child data set a delete has to purge.
func seedLeafRole(t *testing.T, f *roleFixture) {
	t.Helper()
	if w := botCall(f.bots, "POST", "", testToken, leafRoleBody); w.Code != 201 {
		t.Fatalf("create role status=%d body=%s", w.Code, w.Body.String())
	}
	if w := request(f.chat, "POST", `{"id":"leaf-turn","botId":"leaf","content":"叶子提问"}`, testToken); w.Code != 201 {
		t.Fatalf("create turn status=%d body=%s", w.Code, w.Body.String())
	}
	taskCall(t, f.tasks, "POST", "", `{"id":"leaf-task","botId":"leaf","content":"叶子任务"}`, 201)
	waitRoleTask(t, f.tasks, "leaf-task", "leaf", "completed")
}

// waitRoleTask polls one finished task. Task routes default to the primary role, so the role
// under test has to be named explicitly on every read.
func waitRoleTask(t *testing.T, api http.Handler, id, botID, status string) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for {
		got := taskCall(t, api, "GET", "/"+id+"?botId="+botID, "", 200)
		if got.Task.Status == status {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("%s status = %s, want %s", id, got.Task.Status, status)
		}
		time.Sleep(5 * time.Millisecond)
	}
}

func snapshotContains(t *testing.T, path, wanted string) bool {
	t.Helper()
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("cannot read %s: %v", path, err)
	}
	return strings.Contains(string(raw), wanted)
}

func storedLeafIDSet(t *testing.T, bots *Bots) map[string]bool {
	t.Helper()
	bots.mu.RLock()
	defer bots.mu.RUnlock()
	ids := make(map[string]bool, len(bots.records))
	for _, record := range bots.records {
		ids[record.ID] = true
	}
	return ids
}

// TestDeleteBotRollsBackTheFirstChildWhenAnotherChildFails covers the transaction: the chat
// purge lands, the task purge then fails, and both the response and the child data must show
// the delete never happened.
func TestDeleteBotRollsBackTheFirstChildWhenAnotherChildFails(t *testing.T) {
	dir := t.TempDir()
	provider := &sequenceProvider{replies: []string{"答案"}}
	f := openRoleFixture(t, dir, provider)
	seedLeafRole(t, f)
	// A regular file where a directory belongs makes the task snapshot rewrite fail without
	// touching the store that already succeeded.
	blocker := filepath.Join(dir, "blocker")
	if err := os.WriteFile(blocker, []byte("not a directory"), 0600); err != nil {
		t.Fatal(err)
	}
	f.tasks.mu.Lock()
	f.tasks.path = filepath.Join(blocker, "tasks.json")
	f.tasks.mu.Unlock()

	if w := deleteBot(f.bots, "/leaf"); w.Code != 503 || !strings.Contains(w.Body.String(), "storage_unavailable") {
		t.Fatalf("delete status=%d body=%s", w.Code, w.Body.String())
	}
	stopTaskAPI(t, f.tasks)

	if got := botCall(f.bots, "GET", "", testToken, "").Body.String(); !strings.Contains(got, `"id":"leaf"`) {
		t.Fatalf("role was removed by a failed delete: %s", got)
	}
	if !snapshotContains(t, f.botsPath, `"id":"leaf"`) {
		t.Fatal("bot snapshot lost the role after a failed delete")
	}
	// The child purge that ran before the failure must be undone in memory and on disk.
	if got := chatGet(f.chat, "?botId=leaf").Body.String(); !strings.Contains(got, `"id":"leaf-turn"`) {
		t.Fatalf("chat memory lost the rolled back turn: %s", got)
	}
	if !snapshotContains(t, f.chatPath, `"id":"leaf-turn"`) {
		t.Fatal("chat snapshot lost the rolled back turn")
	}
	f.tasks.mu.Lock()
	keptTask := slices.ContainsFunc(f.tasks.tasks, func(row storedTask) bool { return row.BotID == "leaf" })
	f.tasks.mu.Unlock()
	if !keptTask {
		t.Fatal("task memory lost the rolled back task")
	}
	if !snapshotContains(t, f.taskPath, `"botId":"leaf"`) {
		t.Fatal("task snapshot lost the rolled back task")
	}
	if _, err := os.Stat(botDeletePendingPath(f.botsPath)); !os.IsNotExist(err) {
		t.Fatalf("delete intent survived the rollback: %v", err)
	}

	// Nothing was left half-written, so every snapshot still loads after a restart.
	reopened, err := NewBots(f.botsPath, testToken)
	if err != nil {
		t.Fatalf("bot snapshot no longer loads: %v", err)
	}
	if _, err = NewChatHandlerWithBots(f.chatPath, testToken, provider, reopened); err != nil {
		t.Fatalf("chat snapshot no longer loads: %v", err)
	}
	reopenedTasks, err := NewTaskAPIWithBots(f.taskPath, taskTestToken, nil, 5*time.Second, reopened)
	if err != nil {
		t.Fatalf("task snapshot no longer loads: %v", err)
	}
	stopTaskAPI(t, reopenedTasks)
	if err := reopened.RecoverPendingDelete(); err != nil {
		t.Fatalf("a rolled back delete left work for restart: %v", err)
	}
}

// TestDeleteBotRollsBackEveryChildWhenTheBotSnapshotWriteFails covers the last step: every
// child purge lands, the role snapshot cannot be written, and all child data must come back.
func TestDeleteBotRollsBackEveryChildWhenTheBotSnapshotWriteFails(t *testing.T) {
	dir := t.TempDir()
	provider := &sequenceProvider{replies: []string{"答案"}}
	f := openRoleFixture(t, dir, provider)
	seedLeafRole(t, f)
	backup, err := os.ReadFile(f.botsPath)
	if err != nil {
		t.Fatal(err)
	}
	// A directory at the snapshot path makes the atomic rename fail while the delete intent in
	// the same directory is still writable.
	if err := os.Remove(f.botsPath); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(f.botsPath, 0700); err != nil {
		t.Fatal(err)
	}

	if w := deleteBot(f.bots, "/leaf"); w.Code != 503 || !strings.Contains(w.Body.String(), "storage_unavailable") {
		t.Fatalf("delete status=%d body=%s", w.Code, w.Body.String())
	}
	stopTaskAPI(t, f.tasks)

	if !storedLeafIDSet(t, f.bots)["leaf"] {
		t.Fatal("role was removed although its snapshot could not be written")
	}
	if got := chatGet(f.chat, "?botId=leaf").Body.String(); !strings.Contains(got, `"id":"leaf-turn"`) {
		t.Fatalf("chat memory lost the rolled back turn: %s", got)
	}
	if !snapshotContains(t, f.chatPath, `"id":"leaf-turn"`) {
		t.Fatal("chat snapshot lost the rolled back turn")
	}
	f.tasks.mu.Lock()
	keptTask := slices.ContainsFunc(f.tasks.tasks, func(row storedTask) bool { return row.BotID == "leaf" })
	f.tasks.mu.Unlock()
	if !keptTask {
		t.Fatal("task memory lost the rolled back task")
	}
	if !snapshotContains(t, f.taskPath, `"botId":"leaf"`) {
		t.Fatal("task snapshot lost the rolled back task")
	}
	if _, err := os.Stat(botDeletePendingPath(f.botsPath)); !os.IsNotExist(err) {
		t.Fatalf("delete intent survived the rollback: %v", err)
	}

	// The failed write never replaced the snapshot, so the backed up file is the restart input.
	if err := os.Remove(f.botsPath); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(f.botsPath, backup, 0600); err != nil {
		t.Fatal(err)
	}
	reopened, err := NewBots(f.botsPath, testToken)
	if err != nil {
		t.Fatalf("bot snapshot no longer loads: %v", err)
	}
	if !storedLeafIDSet(t, reopened)["leaf"] {
		t.Fatal("restart lost the role of a rolled back delete")
	}
	if _, err = NewChatHandlerWithBots(f.chatPath, testToken, provider, reopened); err != nil {
		t.Fatalf("chat snapshot no longer loads after a rollback: %v", err)
	}
	reopenedTasks, err := NewTaskAPIWithBots(f.taskPath, taskTestToken, nil, 5*time.Second, reopened)
	if err != nil {
		t.Fatalf("task snapshot no longer loads after a rollback: %v", err)
	}
	stopTaskAPI(t, reopenedTasks)
}

// TestRecoverPendingDeleteRollsForwardAnInterruptedDelete covers crash point A: the intent and
// every child snapshot survived while the role snapshot still lists the role.
func TestRecoverPendingDeleteRollsForwardAnInterruptedDelete(t *testing.T) {
	dir := t.TempDir()
	provider := &sequenceProvider{replies: []string{"答案"}}
	first := openRoleFixture(t, dir, provider)
	seedLeafRole(t, first)
	stopTaskAPI(t, first.tasks)
	if err := saveJSONSnapshot(botDeletePendingPath(first.botsPath), ".bot-delete-*", botDeleteIntent{BotID: "leaf"}); err != nil {
		t.Fatal(err)
	}

	// Every child store sees its unchanged snapshot, so this start is the one that recovers.
	restarted := openRoleFixture(t, dir, provider)
	if err := restarted.bots.RecoverPendingDelete(); err != nil {
		t.Fatalf("recovery failed: %v", err)
	}
	stopTaskAPI(t, restarted.tasks)
	if storedLeafIDSet(t, restarted.bots)["leaf"] {
		t.Fatal("recovery kept the deleted role in memory")
	}
	if snapshotContains(t, restarted.botsPath, `"id":"leaf"`) {
		t.Fatal("recovery kept the deleted role on disk")
	}
	if snapshotContains(t, restarted.chatPath, "leaf-turn") {
		t.Fatal("recovery left an orphan chat message")
	}
	if snapshotContains(t, restarted.taskPath, "leaf-task") {
		t.Fatal("recovery left an orphan task")
	}
	if _, err := os.Stat(botDeletePendingPath(restarted.botsPath)); !os.IsNotExist(err) {
		t.Fatalf("recovery kept the delete intent: %v", err)
	}

	again := openRoleFixture(t, dir, provider)
	stopTaskAPI(t, again.tasks)
	if err := again.bots.RecoverPendingDelete(); err != nil {
		t.Fatalf("second restart found work left by recovery: %v", err)
	}
	if storedLeafIDSet(t, again.bots)["leaf"] {
		t.Fatal("second restart resurrected the deleted role")
	}
}

// TestRecoverPendingDeleteDropsAStaleIntentAfterTheBotSnapshot covers crash point B: the role
// row and every child row are already gone, only the intent file survived.
func TestRecoverPendingDeleteDropsAStaleIntentAfterTheBotSnapshot(t *testing.T) {
	dir := t.TempDir()
	provider := &sequenceProvider{replies: []string{"答案"}}
	first := openRoleFixture(t, dir, provider)
	seedLeafRole(t, first)
	if w := deleteBot(first.bots, "/leaf"); w.Code != 200 {
		t.Fatalf("delete status=%d body=%s", w.Code, w.Body.String())
	}
	stopTaskAPI(t, first.tasks)
	if err := saveJSONSnapshot(botDeletePendingPath(first.botsPath), ".bot-delete-*", botDeleteIntent{BotID: "leaf"}); err != nil {
		t.Fatal(err)
	}

	restarted := openRoleFixture(t, dir, provider)
	if err := restarted.bots.RecoverPendingDelete(); err != nil {
		t.Fatalf("recovery failed: %v", err)
	}
	stopTaskAPI(t, restarted.tasks)
	if _, err := os.Stat(botDeletePendingPath(restarted.botsPath)); !os.IsNotExist(err) {
		t.Fatalf("recovery kept the stale delete intent: %v", err)
	}
	if storedLeafIDSet(t, restarted.bots)["leaf"] {
		t.Fatal("recovery resurrected the deleted role")
	}
	// Recovery of an already finished delete must not touch the surviving role's data.
	again := openRoleFixture(t, dir, provider)
	stopTaskAPI(t, again.tasks)
	if err := again.bots.RecoverPendingDelete(); err != nil {
		t.Fatalf("second restart rejected the recovered snapshots: %v", err)
	}
}

func rawCall(h http.Handler, method, target, token, body string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, target, bytes.NewBufferString(body))
	r.Header.Set("Authorization", "Bearer "+token)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}

func oneOfCode(got int, allowed ...int) bool { return slices.Contains(allowed, got) }

// assertChildRowsNameStoredRoles verifies every stored child row names a role that is still in
// the bot snapshot. Scoped rows omit botId for the primary role, so the default applies.
func assertChildRowsNameStoredRoles(t *testing.T, path string, ids map[string]bool) {
	t.Helper()
	raw, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return
	}
	if err != nil {
		t.Fatal(err)
	}
	rows := childSnapshotRows(t, path, raw)
	for _, row := range rows {
		botID := defaultBotID
		if value := row["botId"]; value != nil && json.Unmarshal(value, &botID) != nil {
			t.Fatalf("invalid role in %s: %s", path, raw)
		}
		if !ids[botID] {
			t.Fatalf("orphan row in %s: role %q is not stored (roles %v)", path, botID, ids)
		}
	}
}

// childSnapshotRows reads the stored children of one snapshot. A chat snapshot keeps its rows in
// a versioned object next to the session table; every other store keeps a bare array.
func childSnapshotRows(t *testing.T, path string, raw []byte) []map[string]json.RawMessage {
	t.Helper()
	if value := bytes.TrimSpace(raw); len(value) > 0 && value[0] == '{' {
		var snapshot struct {
			Sessions []map[string]json.RawMessage `json:"sessions"`
			Messages []map[string]json.RawMessage `json:"messages"`
		}
		if err := json.Unmarshal(raw, &snapshot); err != nil {
			t.Fatalf("%s is not a chat snapshot object: %s", path, raw)
		}
		return append(snapshot.Sessions, snapshot.Messages...)
	}
	var rows []map[string]json.RawMessage
	if json.Unmarshal(raw, &rows) != nil {
		t.Fatalf("%s is not a JSON array: %s", path, raw)
	}
	return rows
}

// TestSameRoleDeletionRacesChatWrites runs the delete of one role against that role's own chat
// writes. A stored message does not make a role busy, so the delete wins often: the turn written
// just before is purged and a turn that arrives afterwards is refused. Every interleaving has to
// reload from disk without an orphan row.
func TestSameRoleDeletionRacesChatWrites(t *testing.T) {
	dir := t.TempDir()
	provider := &sequenceProvider{replies: []string{"答案"}}
	f := openRoleFixture(t, dir, provider)
	// A real deadlock blocks this test goroutine, so the watchdog reports it with every stack.
	watchdog := time.AfterFunc(120*time.Second, func() { panic("same role delete and chat write deadlocked") })
	defer watchdog.Stop()

	const rounds = 20
	var deleted, refusedWrites atomic.Int32
	for i := 0; i < rounds; i++ {
		// A winning delete removes the role, so every round starts from the same state.
		if w := botCall(f.bots, "POST", "", testToken, leafRoleBody); !oneOfCode(w.Code, 200, 201, 409) {
			t.Fatalf("round %d: recreate role status=%d body=%s", i, w.Code, w.Body.String())
		}
		body := `{"id":"chat-` + strconv.Itoa(i) + `","botId":"leaf","content":"并发提问"}`
		var group sync.WaitGroup
		group.Add(2)
		go func() {
			defer group.Done()
			switch w := deleteBot(f.bots, "/leaf"); w.Code {
			case 200:
				deleted.Add(1)
			case 404, 409:
			default:
				t.Errorf("delete status=%d body=%s", w.Code, w.Body.String())
			}
		}()
		go func() {
			defer group.Done()
			switch w := rawCall(f.chat, "POST", "/api/chat/messages", testToken, body); {
			case oneOfCode(w.Code, 200, 201):
			case w.Code == 404:
				refusedWrites.Add(1)
			default:
				t.Errorf("chat write status=%d body=%s", w.Code, w.Body.String())
			}
		}()
		group.Wait()

		// Reloading after each interleaving is the orphan check: the loaders reject a child row
		// whose role is no longer stored.
		reopened, err := NewBots(f.botsPath, testToken)
		if err != nil {
			t.Fatalf("round %d: bot snapshot no longer loads: %v", i, err)
		}
		if _, err = NewChatHandlerWithBots(f.chatPath, testToken, provider, reopened); err != nil {
			t.Fatalf("round %d: orphan chat message after a concurrent delete: %v", i, err)
		}
	}
	if deleted.Load() == 0 {
		t.Fatal("no interleaving let the delete win")
	}
	if refusedWrites.Load() == 0 {
		t.Fatal("no chat write was refused after the role was deleted")
	}
	assertChildRowsNameStoredRoles(t, f.chatPath, storedLeafIDSet(t, f.bots))
	if _, err := os.Stat(botDeletePendingPath(f.botsPath)); !os.IsNotExist(err) {
		t.Fatalf("concurrent deletes left a delete intent: %v", err)
	}
}

// TestSameRoleDeletionRacesQueuedWrites runs the delete against the task and draft writes that
// make a role busy. A write either lands and refuses the delete or is refused after it, and no
// child row ever survives its role.
func TestSameRoleDeletionRacesQueuedWrites(t *testing.T) {
	dir := t.TempDir()
	provider := &sequenceProvider{replies: []string{"答案"}}
	f := openRoleFixture(t, dir, provider)
	if w := botCall(f.bots, "POST", "", testToken, leafRoleBody); w.Code != 201 {
		t.Fatalf("create role status=%d body=%s", w.Code, w.Body.String())
	}
	watchdog := time.AfterFunc(120*time.Second, func() { panic("same role delete and queued writes deadlocked") })
	defer watchdog.Stop()

	const rounds = 12
	for i := 0; i < rounds; i++ {
		if w := botCall(f.bots, "POST", "", testToken, leafRoleBody); !oneOfCode(w.Code, 200, 201, 409) {
			t.Fatalf("round %d: recreate role status=%d body=%s", i, w.Code, w.Body.String())
		}
		suffix := strconv.Itoa(i)
		var group sync.WaitGroup
		group.Add(3)
		go func() {
			defer group.Done()
			if w := deleteBot(f.bots, "/leaf"); !oneOfCode(w.Code, 200, 404, 409) {
				t.Errorf("delete status=%d body=%s", w.Code, w.Body.String())
			}
		}()
		go func() {
			defer group.Done()
			body := `{"id":"task-` + suffix + `","botId":"leaf","content":"并发任务"}`
			if w := rawCall(f.tasks, "POST", "/api/tasks", taskTestToken, body); !oneOfCode(w.Code, 200, 201, 404, 409, 503) {
				t.Errorf("task write status=%d body=%s", w.Code, w.Body.String())
			}
		}()
		go func() {
			defer group.Done()
			body := `{"id":"draft-` + suffix + `","botId":"leaf","appId":"gen-app-` + suffix + `","name":"工具","request":"画一个","baseVersion":0}`
			if w := rawCall(f.generated, "POST", "/api/app-drafts", testToken, body); !oneOfCode(w.Code, 200, 201, 404, 409, 503) {
				t.Errorf("draft write status=%d body=%s", w.Code, w.Body.String())
			}
		}()
		group.Wait()
	}
	f.generated.mu.Lock()
	brokeStore := f.generated.broken
	f.generated.mu.Unlock()
	if brokeStore {
		t.Fatal("concurrent role writes faulted the generated store")
	}
	stopTaskAPI(t, f.tasks)

	// Reloading every snapshot is the orphan check: each loader rejects a child row whose role
	// is no longer stored.
	reopened, err := NewBots(f.botsPath, testToken)
	if err != nil {
		t.Fatalf("bot snapshot no longer loads: %v", err)
	}
	if _, err = NewChatHandlerWithBots(f.chatPath, testToken, provider, reopened); err != nil {
		t.Fatalf("orphan chat message after concurrent deletes: %v", err)
	}
	reopenedGenerated := NewGenerated(f.genPath, testToken, reopened)
	if reopenedGenerated.broken {
		t.Fatal("orphan generated record after concurrent deletes")
	}
	reopenedTasks, err := NewTaskAPIWithGenerated(f.taskPath, taskTestToken, nil, 5*time.Second, 5*time.Second, reopened, reopenedGenerated)
	if err != nil {
		t.Fatalf("orphan task after concurrent deletes: %v", err)
	}
	stopTaskAPI(t, reopenedTasks)
	if err := reopened.RecoverPendingDelete(); err != nil {
		t.Fatalf("concurrent deletes left a delete intent: %v", err)
	}
	ids := storedLeafIDSet(t, reopened)
	assertChildRowsNameStoredRoles(t, f.chatPath, ids)
	assertChildRowsNameStoredRoles(t, f.taskPath, ids)
}
