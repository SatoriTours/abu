package httpapi

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
)

// TestDeleteBotRefusedWhenAChildStoreIsUnavailable covers the start-up fault path: a store that
// could not be loaded registers no hook, so the delete is refused instead of skipping the records
// that store owns and overwriting a pending transaction.
func TestDeleteBotRefusedWhenAChildStoreIsUnavailable(t *testing.T) {
	dir := t.TempDir()
	f := openRoleFixture(t, dir, &sequenceProvider{replies: []string{"答案"}})
	seedLeafRole(t, f)
	before := readOrEmpty(t, f.botsPath)
	f.bots.DisableDelete()

	if w := deleteBot(f.bots, "/leaf"); w.Code != 503 || !strings.Contains(w.Body.String(), "storage_unavailable") {
		t.Fatalf("delete status=%d body=%s", w.Code, w.Body.String())
	}
	stopTaskAPI(t, f.tasks)

	if got := readOrEmpty(t, f.botsPath); got != before {
		t.Fatal("a refused delete rewrote the bot snapshot")
	}
	if !storedLeafIDSet(t, f.bots)["leaf"] {
		t.Fatal("a refused delete removed the role from memory")
	}
	if _, err := os.Stat(botDeletePendingPath(f.botsPath)); !os.IsNotExist(err) {
		t.Fatalf("a refused delete wrote a delete intent: %v", err)
	}
	if !snapshotContains(t, f.chatPath, `"id":"leaf-turn"`) || !snapshotContains(t, f.taskPath, `"botId":"leaf"`) {
		t.Fatal("a refused delete rewrote child snapshots")
	}
}

// TestDeleteBotDoubleFaultKeepsTheIntentAndBlocksLaterDeletes covers the worst case: a child purge
// fails and the rollback of the purge that already landed cannot be written either. The intent has
// to survive, block every later delete, and be rolled forward by the next healthy start.
func TestDeleteBotDoubleFaultKeepsTheIntentAndBlocksLaterDeletes(t *testing.T) {
	dir := t.TempDir()
	chatDir := filepath.Join(dir, "chat")
	if err := os.MkdirAll(chatDir, 0700); err != nil {
		t.Fatal(err)
	}
	provider := &sequenceProvider{replies: []string{"答案"}}
	f := openRoleFixtureAt(t, filepath.Join(dir, "bots.json"), filepath.Join(chatDir, "messages.json"),
		filepath.Join(dir, "tasks.json"), filepath.Join(dir, "generated.json"), provider)
	seedLeafRole(t, f)
	// The chat snapshot stays writable for its purge and only becomes unwritable afterwards, so
	// the purge lands and its rollback is the write that fails.
	f.bots.RegisterDeleteHook(&failingPurgeHook{onFail: func() {
		if err := os.Chmod(chatDir, 0500); err != nil {
			t.Error(err)
		}
	}})
	t.Cleanup(func() { _ = os.Chmod(chatDir, 0700) })

	pending := botDeletePendingPath(f.botsPath)
	if w := deleteBot(f.bots, "/leaf"); w.Code != 503 || !strings.Contains(w.Body.String(), "storage_unavailable") {
		t.Fatalf("delete status=%d body=%s", w.Code, w.Body.String())
	}
	stopTaskAPI(t, f.tasks)

	intent, err := os.ReadFile(pending)
	if err != nil {
		t.Fatalf("a double fault dropped the delete intent: %v", err)
	}
	if strings.TrimSpace(string(intent)) != `{"botId":"leaf"}` {
		t.Fatalf("delete intent = %q", intent)
	}
	if !storedLeafIDSet(t, f.bots)["leaf"] || !snapshotContains(t, f.botsPath, `"id":"leaf"`) {
		t.Fatal("a rolled back delete removed the role")
	}
	// The child purge that could still be undone is restored, which leaves the intent as the only
	// record of the half-finished transaction.
	if !snapshotContains(t, f.taskPath, `"botId":"leaf"`) {
		t.Fatal("the task rollback that succeeded lost its rows")
	}

	// A later delete must be refused instead of overwriting the unfinished transaction.
	if w := deleteBot(f.bots, "/leaf"); w.Code != 503 || !strings.Contains(w.Body.String(), "storage_unavailable") {
		t.Fatalf("second delete status=%d body=%s", w.Code, w.Body.String())
	}
	if again, err := os.ReadFile(pending); err != nil || !bytes.Equal(again, intent) {
		t.Fatalf("a second delete rewrote the delete intent: %q", again)
	}
	if !storedLeafIDSet(t, f.bots)["leaf"] {
		t.Fatal("a second delete removed the role")
	}

	// A healthy disk lets the next start roll the transaction forward.
	if err := os.Chmod(chatDir, 0700); err != nil {
		t.Fatal(err)
	}
	restarted := openRoleFixtureAt(t, f.botsPath, f.chatPath, f.taskPath, f.genPath, provider)
	defer stopTaskAPI(t, restarted.tasks)
	if err := restarted.bots.RecoverPendingDelete(); err != nil {
		t.Fatalf("recovery failed: %v", err)
	}
	if storedLeafIDSet(t, restarted.bots)["leaf"] || snapshotContains(t, restarted.botsPath, `"id":"leaf"`) {
		t.Fatal("recovery kept the deleted role")
	}
	if snapshotContains(t, restarted.chatPath, "leaf-turn") || snapshotContains(t, restarted.taskPath, "leaf-task") {
		t.Fatal("recovery left an orphan child row")
	}
	if _, err := os.Stat(pending); !os.IsNotExist(err) {
		t.Fatalf("recovery kept the delete intent: %v", err)
	}
}

// TestFailedDeleteKeepsAnotherRolesTaskProgress covers the window between a child purge and its
// rollback: the task worker finishes another role's task there, and the rollback must merge the
// purged rows back instead of restoring an older full snapshot.
func TestFailedDeleteKeepsAnotherRolesTaskProgress(t *testing.T) {
	dir := t.TempDir()
	provider := newGatedProvider(1, "叶子答案", "角色B答案")
	f := openRoleFixture(t, dir, provider)
	seedLeafRole(t, f)
	if w := botCall(f.bots, "POST", "", testToken, `{"id":"wave","name":"波浪","instructions":"","avatar":"wave"}`); w.Code != 201 {
		t.Fatalf("create role status=%d body=%s", w.Code, w.Body.String())
	}
	taskCall(t, f.tasks, "POST", "", `{"id":"wave-task","botId":"wave","content":"角色B任务"}`, 201)
	f.bots.RegisterDeleteHook(&waitForOtherRoleTask{api: f.tasks, botID: "wave", taskID: "wave-task", release: provider.release})
	backup := readOrEmpty(t, f.botsPath)
	// A directory at the role snapshot path fails that write after every child purge landed.
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

	f.tasks.mu.Lock()
	rows := slices.Clone(f.tasks.tasks)
	f.tasks.mu.Unlock()
	leaf := slices.IndexFunc(rows, func(row storedTask) bool { return row.BotID == "leaf" && row.ID == "leaf-task" })
	if leaf < 0 || rows[leaf].Status != "completed" {
		t.Fatalf("the rollback lost the target role task: %+v", rows)
	}
	// The other role's task, finished inside that window, keeps its result.
	wave := slices.IndexFunc(rows, func(row storedTask) bool { return row.BotID == "wave" && row.ID == "wave-task" })
	if wave < 0 || rows[wave].Status != "completed" || rows[wave].Result != "角色B答案" {
		t.Fatalf("the rollback overwrote another role's finished task: %+v", rows)
	}
	if leaf > wave {
		t.Fatalf("the rollback reordered the task snapshot: %+v", rows)
	}
	tasksOnDisk := readOrEmpty(t, f.taskPath)
	if !strings.Contains(tasksOnDisk, "角色B答案") || !strings.Contains(tasksOnDisk, `"id":"leaf-task"`) {
		t.Fatalf("the task snapshot lost a row: %s", tasksOnDisk)
	}
	if !snapshotContains(t, f.chatPath, `"id":"leaf-turn"`) {
		t.Fatal("the chat rollback lost the target role turn")
	}
	if _, err := os.Stat(botDeletePendingPath(f.botsPath)); !os.IsNotExist(err) {
		t.Fatalf("a fully rolled back delete kept its intent: %v", err)
	}

	// The snapshot the failed write never replaced is the restart input.
	if err := os.Remove(f.botsPath); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(f.botsPath, []byte(backup), 0600); err != nil {
		t.Fatal(err)
	}
	reopened := openRoleFixture(t, dir, provider)
	defer stopTaskAPI(t, reopened.tasks)
	if err := reopened.bots.RecoverPendingDelete(); err != nil {
		t.Fatalf("a rolled back delete left work for restart: %v", err)
	}
	if !snapshotContains(t, reopened.taskPath, "角色B答案") {
		t.Fatal("a restart lost another role's finished task")
	}
}

// TestFailedDeleteKeepsAnotherRolesSettledReply covers the same window for the chat store: a
// generation of another role settles between the purge and the rollback.
func TestFailedDeleteKeepsAnotherRolesSettledReply(t *testing.T) {
	dir := t.TempDir()
	provider := newGatedProvider(0, "角色B答案")
	f := openRoleFixture(t, dir, provider)
	for _, body := range []string{
		`{"id":"leaf","name":"叶子","instructions":"","avatar":"leaf"}`,
		`{"id":"wave","name":"波浪","instructions":"","avatar":"wave"}`,
	} {
		if w := botCall(f.bots, "POST", "", testToken, body); w.Code != 201 {
			t.Fatalf("create role status=%d body=%s", w.Code, w.Body.String())
		}
	}
	if w := request(f.chat, "POST", `{"id":"leaf-turn","botId":"leaf","content":"叶子提问"}`, testToken); w.Code != 201 {
		t.Fatalf("create turn status=%d body=%s", w.Code, w.Body.String())
	}
	if w := request(f.chat, "POST", `{"id":"wave-turn","botId":"wave","content":"波浪提问"}`, testToken); w.Code != 201 {
		t.Fatalf("create turn status=%d body=%s", w.Code, w.Body.String())
	}
	// The reply of the other role runs while the delete is in flight and settles inside it.
	settled := make(chan int, 1)
	go func() {
		settled <- rawCall(f.chat, "POST", "/api/chat/replies", testToken, `{"messageId":"wave-turn","botId":"wave"}`).Code
	}()
	taskSignal(t, provider.entered)

	f.bots.RegisterDeleteHook(&waitForOtherRoleReply{path: f.chatPath, messageID: "wave-turn", release: provider.release})
	backup := readOrEmpty(t, f.botsPath)
	if err := os.Remove(f.botsPath); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(f.botsPath, 0700); err != nil {
		t.Fatal(err)
	}

	if w := deleteBot(f.bots, "/leaf"); w.Code != 503 || !strings.Contains(w.Body.String(), "storage_unavailable") {
		t.Fatalf("delete status=%d body=%s", w.Code, w.Body.String())
	}
	if code := taskSignal(t, settled); code != 200 {
		t.Fatalf("reply status=%d", code)
	}
	stopTaskAPI(t, f.tasks)

	if got := chatGet(f.chat, "?botId=leaf").Body.String(); !strings.Contains(got, `"id":"leaf-turn"`) {
		t.Fatalf("the chat rollback lost the target role turn: %s", got)
	}
	waveHistory := chatGet(f.chat, "?botId=wave").Body.String()
	if !strings.Contains(waveHistory, `"reply":"角色B答案"`) || !strings.Contains(waveHistory, `"replyStatus":"completed"`) {
		t.Fatalf("the rollback overwrote another role's settled reply: %s", waveHistory)
	}
	chatOnDisk := readOrEmpty(t, f.chatPath)
	if !strings.Contains(chatOnDisk, "角色B答案") || !strings.Contains(chatOnDisk, "leaf-turn") {
		t.Fatalf("the chat snapshot lost a row: %s", chatOnDisk)
	}
	if _, err := os.Stat(botDeletePendingPath(f.botsPath)); !os.IsNotExist(err) {
		t.Fatalf("a fully rolled back delete kept its intent: %v", err)
	}

	if err := os.Remove(f.botsPath); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(f.botsPath, []byte(backup), 0600); err != nil {
		t.Fatal(err)
	}
	reopened := openRoleFixture(t, dir, provider)
	defer stopTaskAPI(t, reopened.tasks)
	if err := reopened.bots.RecoverPendingDelete(); err != nil {
		t.Fatalf("a rolled back delete left work for restart: %v", err)
	}
	if !snapshotContains(t, reopened.chatPath, "角色B答案") {
		t.Fatal("a restart lost another role's settled reply")
	}
}

func readOrEmpty(t *testing.T, path string) string {
	t.Helper()
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return string(raw)
}

// TestDamagedDeleteIntentRefusesStartAndRecovery covers a damaged write-ahead record: both the
// start and the recovery refuse it and leave the file and every snapshot exactly as found.
func TestDamagedDeleteIntentRefusesStartAndRecovery(t *testing.T) {
	cases := []struct{ name, body string }{
		{"unparsable", `{"botId":`},
		{"not an object", `["leaf"]`},
		{"missing id", `{}`},
		{"empty id", `{"botId":""}`},
		{"invalid id", `{"botId":"leaf turn"}`},
		{"default role", `{"botId":"abu"}`},
		{"extra field", `{"botId":"leaf","x":1}`},
	}
	for _, testCase := range cases {
		t.Run(testCase.name, func(t *testing.T) {
			dir := t.TempDir()
			f := openRoleFixture(t, dir, &sequenceProvider{replies: []string{"答案"}})
			seedLeafRole(t, f)
			stopTaskAPI(t, f.tasks)
			botsSnapshot := readOrEmpty(t, f.botsPath)
			if !strings.Contains(botsSnapshot, "leaf") {
				t.Fatalf("fixture missing the role: %s", botsSnapshot)
			}
			chatSnapshot := readOrEmpty(t, f.chatPath)
			taskSnapshot := readOrEmpty(t, f.taskPath)

			pending := botDeletePendingPath(f.botsPath)
			if err := os.WriteFile(pending, []byte(testCase.body), 0600); err != nil {
				t.Fatal(err)
			}
			if _, err := NewBots(f.botsPath, testToken); err == nil {
				t.Fatal("the start accepted a damaged delete intent")
			}
			if err := f.bots.RecoverPendingDelete(); err == nil {
				t.Fatal("recovery accepted a damaged delete intent")
			}
			if got := readOrEmpty(t, pending); got != testCase.body {
				t.Fatalf("the damaged intent was rewritten to %q", got)
			}
			if got := readOrEmpty(t, f.botsPath); got != botsSnapshot {
				t.Fatal("a damaged intent rewrote the bot snapshot")
			}
			if got := readOrEmpty(t, f.chatPath); got != chatSnapshot {
				t.Fatal("a damaged intent rewrote the chat snapshot")
			}
			if got := readOrEmpty(t, f.taskPath); got != taskSnapshot {
				t.Fatal("a damaged intent rewrote the task snapshot")
			}
		})
	}
}

// TestLeftoverDeleteIntentBlocksRecreatingTheSameRoleIDUntilRestartRecovery covers the case
// where deleting the role snapshot succeeds but removing the intent file fails: recreating the
// same role ID must be refused until restart recovery clears the intent, so a restart can never
// wipe a newly recreated role.
func TestLeftoverDeleteIntentBlocksRecreatingTheSameRoleIDUntilRestartRecovery(t *testing.T) {
	dir := t.TempDir()
	f := openRoleFixture(t, dir, &sequenceProvider{replies: []string{"答案"}})
	seedLeafRole(t, f)

	// Inject a cleanup failure after bots.json has already been updated.
	f.bots.removeIntent = func(string) error { return os.ErrPermission }

	send := func(h http.Handler, method, target, body string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(method, target, strings.NewReader(body))
		req.Header.Set("Authorization", "Bearer "+testToken)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, req)
		return w
	}

	del := send(f.bots, http.MethodDelete, "/api/bots/leaf", "")
	if del.Code != http.StatusOK {
		t.Fatalf("delete status=%d body=%s", del.Code, del.Body.String())
	}
	pending := botDeletePendingPath(f.botsPath)
	if _, err := os.Stat(pending); err != nil {
		t.Fatalf("expected leftover delete intent on disk: %v", err)
	}

	// Recreating the same ID before the stale intent is cleared must be refused.
	recreate := send(f.bots, http.MethodPost, "/api/bots",
		`{"id":"leaf","name":"新小叶","instructions":"新职责","avatar":"leaf"}`)
	if recreate.Code != http.StatusServiceUnavailable || !strings.Contains(recreate.Body.String(), "storage_unavailable") {
		t.Fatalf("expected 503 storage_unavailable recreating role with leftover intent, got %d %s",
			recreate.Code, recreate.Body.String())
	}
	stopTaskAPI(t, f.tasks)

	// After restart recovery clears the stale intent, recreating the same ID succeeds and survives
	// a subsequent restart intact.
	recovered := openRoleFixture(t, dir, &sequenceProvider{replies: []string{"新回复"}})
	if err := recovered.bots.RecoverPendingDelete(); err != nil {
		t.Fatalf("recovery failed: %v", err)
	}
	if _, err := os.Stat(pending); !os.IsNotExist(err) {
		t.Fatalf("recovery should remove stale intent, stat err=%v", err)
	}
	created := send(recovered.bots, http.MethodPost, "/api/bots",
		`{"id":"leaf","name":"新小叶","instructions":"新职责","avatar":"leaf"}`)
	if created.Code != http.StatusCreated {
		t.Fatalf("recreate after recovery status=%d body=%s", created.Code, created.Body.String())
	}
	msg := send(recovered.chat, http.MethodPost, "/api/chat/messages",
		`{"id":"m-new","content":"重启后保留","botId":"leaf"}`)
	if msg.Code != http.StatusCreated {
		t.Fatalf("message on recreated role status=%d body=%s", msg.Code, msg.Body.String())
	}
	stopTaskAPI(t, recovered.tasks)

	reopened := openRoleFixture(t, dir, &sequenceProvider{})
	if err := reopened.bots.RecoverPendingDelete(); err != nil {
		t.Fatalf("second restart recovery failed: %v", err)
	}
	if _, ok := reopened.bots.lookup("leaf"); !ok {
		t.Fatal("recreated role was lost after restart")
	}
	hist := send(reopened.chat, http.MethodGet, "/api/chat/messages?botId=leaf", "")
	if hist.Code != http.StatusOK || !strings.Contains(hist.Body.String(), "重启后保留") {
		t.Fatalf("recreated role history lost after restart: %d %s", hist.Code, hist.Body.String())
	}
	stopTaskAPI(t, reopened.tasks)
}
