package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
	"unicode/utf8"

	"abu.local/backend/internal/model"
)

const taskTestToken = "01234567890123456789012345678901"

type taskProviderFunc func(context.Context, []model.Message) (string, error)

func (f taskProviderFunc) Complete(ctx context.Context, messages []model.Message) (string, error) {
	return f(ctx, messages)
}

type testTask struct {
	ID, Content, Status, Result, Error, CreatedAt, UpdatedAt string
}
type testTaskResponse struct {
	Task             testTask
	Tasks            []testTask
	ModelConfigured  bool
	StorageAvailable bool
	Error            string
}

func openTaskAPI(t *testing.T, path string, p model.Completer, timeout time.Duration) *TaskAPI {
	t.Helper()
	api, err := NewTaskAPI(path, taskTestToken, p, timeout)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { stopTaskAPI(t, api) })
	return api
}

func stopTaskAPI(t *testing.T, api *TaskAPI) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if err := api.Shutdown(ctx); err != nil {
		t.Error(err)
	}
}

func taskRequest(t *testing.T, api http.Handler, ctx context.Context, method, path, token, body string, status int) testTaskResponse {
	t.Helper()
	r := httptest.NewRequest(method, "/api/tasks"+path, strings.NewReader(body)).WithContext(ctx)
	if token != "" {
		r.Header.Set("Authorization", "Bearer "+token)
	}
	w := httptest.NewRecorder()
	api.ServeHTTP(w, r)
	if w.Code != status {
		t.Fatalf("%s %s status = %d, want %d: %s", method, path, w.Code, status, w.Body)
	}
	var value testTaskResponse
	if err := json.Unmarshal(w.Body.Bytes(), &value); err != nil {
		t.Fatal(err)
	}
	if w.Header().Get("Cache-Control") != "no-store" {
		t.Fatal("private response is cacheable")
	}
	return value
}

func taskCall(t *testing.T, api http.Handler, method, path, body string, status int) testTaskResponse {
	t.Helper()
	return taskRequest(t, api, context.Background(), method, path, taskTestToken, body, status)
}

func waitTask(t *testing.T, api http.Handler, id, status string) testTaskResponse {
	t.Helper()
	deadline := time.After(3 * time.Second)
	for {
		got := taskCall(t, api, "GET", "/"+id, "", 200)
		if got.Task.Status == status {
			return got
		}
		select {
		case <-deadline:
			t.Fatalf("%s status = %s, want %s", id, got.Task.Status, status)
		case <-time.After(5 * time.Millisecond):
		}
	}
}

func taskSignal[T any](t *testing.T, ch <-chan T) T {
	t.Helper()
	select {
	case v := <-ch:
		return v
	case <-time.After(3 * time.Second):
		t.Fatal("timed out waiting for worker")
		var zero T
		return zero
	}
}

func TestTasksPersistBeforeCallingAndSurviveRequestCancellation(t *testing.T) {
	path := filepath.Join(t.TempDir(), "tasks.json")
	started := make(chan string, 1)
	var calls atomic.Int32
	release := make(chan struct{})
	api := openTaskAPI(t, path, taskProviderFunc(func(ctx context.Context, messages []model.Message) (string, error) {
		calls.Add(1)
		raw, _ := os.ReadFile(path)
		if len(messages) == 1 && messages[0] == (model.Message{Role: "user", Content: "hello"}) {
			select {
			case started <- string(raw):
			default:
			}
		}
		select {
		case <-release:
			return "world", ctx.Err()
		case <-ctx.Done():
			return "", ctx.Err()
		}
	}), time.Minute)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	const body = `{"id":"a","content":"hello"}`
	created := taskRequest(t, api, ctx, "POST", "", taskTestToken, body, 201)
	cancel()
	if created.Task.Status != "queued" || !created.ModelConfigured || !created.StorageAvailable || created.Task.CreatedAt == "" {
		t.Fatalf("created = %+v", created)
	}
	raw := taskSignal(t, started)
	if !strings.Contains(raw, `"status":"running"`) {
		t.Fatalf("model called before running was on disk: %s", raw)
	}
	taskCall(t, api, "POST", "", body, 200)
	taskCall(t, api, "POST", "", `{"id":"a","content":"different"}`, 409)
	close(release)
	if got := waitTask(t, api, "a", "completed"); got.Task.Result != "world" {
		t.Fatalf("result = %+v", got)
	}
	stopTaskAPI(t, api)
	reopened := openTaskAPI(t, path, nil, time.Minute)
	if got := taskCall(t, reopened, "GET", "", "", 200); got.ModelConfigured ||
		len(got.Tasks) != 1 || got.Tasks[0].Result != "world" {
		t.Fatalf("reopened = %+v", got)
	}
	taskCall(t, reopened, "POST", "", body, 200)
	if calls.Load() != 1 {
		t.Fatalf("calls = %d", calls.Load())
	}
}

func TestTasksShutdownAndRestartResumeOnlyQueuedTasks(t *testing.T) {
	path := filepath.Join(t.TempDir(), "tasks.json")
	started := make(chan struct{})
	api := openTaskAPI(t, path, taskProviderFunc(func(ctx context.Context, _ []model.Message) (string, error) {
		close(started)
		<-ctx.Done()
		return "late", nil
	}), time.Minute)
	taskCall(t, api, "POST", "", `{"id":"old","content":"running"}`, 201)
	taskSignal(t, started)
	taskCall(t, api, "POST", "", `{"id":"next","content":"queued"}`, 201)
	// Snapshot the disk while a call is active to simulate abrupt restart separately.
	crashPath := filepath.Join(t.TempDir(), "tasks.json")
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(crashPath, raw, 0600); err != nil {
		t.Fatal(err)
	}
	stopTaskAPI(t, api)
	waitTask(t, api, "old", "interrupted")
	waitTask(t, api, "next", "queued")
	for _, candidate := range []string{path, crashPath} {
		resumed := openTaskAPI(t, candidate, taskProviderFunc(func(_ context.Context, msgs []model.Message) (string, error) {
			return msgs[0].Content, nil
		}), time.Minute)
		if got := waitTask(t, resumed, "next", "completed"); got.Task.Result != "queued" {
			t.Fatalf("resumed = %+v", got)
		}
		if got := waitTask(t, resumed, "old", "interrupted"); got.Task.Result != "" {
			t.Fatalf("previous model call was retried: %+v", got)
		}
	}
}

func TestTasksCancelQueuedAndRunningBeforeLateCompletion(t *testing.T) {
	path := filepath.Join(t.TempDir(), "tasks.json")
	started := make(chan string, 3)
	cancelled := make(chan struct{})
	release := make(chan struct{})
	var released atomic.Bool
	api := openTaskAPI(t, path, taskProviderFunc(func(ctx context.Context, msgs []model.Message) (string, error) {
		started <- msgs[0].Content
		if msgs[0].Content == "running" {
			<-ctx.Done()
			close(cancelled)
			<-release
		}
		return "answer", nil
	}), time.Minute)
	defer func() {
		if !released.Swap(true) {
			close(release)
		}
	}()
	taskCall(t, api, "POST", "", `{"id":"a","content":"running"}`, 201)
	if got := taskSignal(t, started); got != "running" {
		t.Fatal(got)
	}
	taskCall(t, api, "POST", "", `{"id":"b","content":"skip"}`, 201)
	for _, id := range []string{"b", "a", "a"} {
		if got := taskCall(t, api, "POST", "/"+id+"/cancel", "", 200); got.Task.Status != "cancelled" {
			t.Fatalf("cancel = %+v", got)
		}
	}
	taskSignal(t, cancelled)
	taskCall(t, api, "POST", "", `{"id":"c","content":"run"}`, 201)
	released.Store(true)
	close(release)
	if got := taskSignal(t, started); got != "run" {
		t.Fatalf("ran cancelled task: %s", got)
	}
	waitTask(t, api, "c", "completed")
	if got := taskCall(t, api, "POST", "/c/cancel", "", 200); got.Task.Status != "completed" {
		t.Fatalf("terminal status changed: %+v", got)
	}
	stopTaskAPI(t, api)
	reopened := openTaskAPI(t, path, nil, time.Minute)
	for _, id := range []string{"a", "b"} {
		if got := waitTask(t, reopened, id, "cancelled"); got.Task.Result != "" {
			t.Fatalf("late result saved: %+v", got)
		}
	}
}

func TestTasksValidateRequestsAndRequireConfiguredModel(t *testing.T) {
	api := openTaskAPI(t, filepath.Join(t.TempDir(), "tasks.json"), nil, time.Minute)
	for _, route := range []struct{ method, path string }{{"GET", ""}, {"GET", "/a"}, {"POST", ""}, {"POST", "/a/cancel"}} {
		taskRequest(t, api, context.Background(), route.method, route.path, "bad", "", 401)
	}
	for _, body := range []string{
		`null`, `[]`, `{"id":"x","content":" "}`, `{"id":"x","content":"ok","tool":"x"}`,
		`{"ID":"x","content":"ok"}`, `{"id":"x","id":"y","content":"ok"}`,
		`{"id":"x","content":"ok"} {}`, `{"id":"../x","content":"ok"}`,
		`{"id":"x","content":"\ud800"}`, `{"id":"x","content":"\udc00\ud800"}`,
		`{"id":"x","content":"` + strings.Repeat("😀", 4001) + `"}`,
		`{"id":"x","content":"` + string([]byte{0xff}) + `"}`,
	} {
		taskCall(t, api, "POST", "", body, 400)
	}
	taskCall(t, api, "POST", "", strings.Repeat(" ", 65537), 413)
	taskCall(t, api, "POST", "/x/cancel", "{}", 400)
	taskCall(t, api, "GET", "/x", "", 404)
	taskCall(t, api, "POST", "/x/cancel", "", 404)
	if got := taskCall(t, api, "POST", "", `{"id":"x","content":"`+strings.Repeat(`\ud83d\ude00`, 4000)+`"}`, 503); got.Error != "model_not_configured" {
		t.Fatalf("model error = %+v", got)
	}
	if got := taskCall(t, api, "GET", "", "", 200); got.ModelConfigured || got.Tasks == nil || len(got.Tasks) != 0 {
		t.Fatalf("list = %+v", got)
	}
}

func TestTasksPersistSafeModelFailuresAndBoundResult(t *testing.T) {
	path := filepath.Join(t.TempDir(), "tasks.json")
	api := openTaskAPI(t, path, taskProviderFunc(func(ctx context.Context, msgs []model.Message) (string, error) {
		switch msgs[0].Content {
		case "timeout":
			<-ctx.Done()
			return "late success", nil
		case "error":
			return "not for logs", errors.New("private provider failure details")
		}
		return strings.Repeat("😀", 4001), nil
	}), 30*time.Millisecond)
	for _, tc := range []struct{ id, want string }{{"timeout", "model_timeout"}, {"error", "model_failed"}} {
		taskCall(t, api, "POST", "", `{"id":"`+tc.id+`","content":"`+tc.id+`"}`, 201)
		got := waitTask(t, api, tc.id, "failed").Task
		if got.Error != tc.want || got.Result != "" {
			t.Fatalf("failure = %+v", got)
		}
	}
	taskCall(t, api, "POST", "", `{"id":"large","content":"large"}`, 201)
	if got := waitTask(t, api, "large", "completed").Task; utf8.RuneCountInString(got.Result) != 4000 || !utf8.ValidString(got.Result) {
		t.Fatalf("invalid output size: %d runes", utf8.RuneCountInString(got.Result))
	}
	stopTaskAPI(t, api)
	reopened := openTaskAPI(t, path, nil, time.Minute)
	if got := waitTask(t, reopened, "timeout", "failed").Task; got.Error != "model_timeout" {
		t.Fatalf("failure wasn't persisted: %+v", got)
	}
}

func TestTasksFailClosedWhenCompletionCannotBeSaved(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "store", "tasks.json")
	started, release := make(chan struct{}), make(chan struct{})
	var calls atomic.Int32
	api := openTaskAPI(t, path, taskProviderFunc(func(ctx context.Context, _ []model.Message) (string, error) {
		if calls.Add(1) == 1 {
			close(started)
			select {
			case <-release:
			case <-ctx.Done():
			}
		}
		return "not durably saved", nil
	}), time.Minute)
	taskCall(t, api, "POST", "", `{"id":"running","content":"run"}`, 201)
	taskSignal(t, started)
	taskCall(t, api, "POST", "", `{"id":"queued","content":"wait"}`, 201)
	backup := filepath.Join(dir, "backup")
	if err := os.Rename(filepath.Dir(path), backup); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Dir(path), []byte("blocks writes"), 0600); err != nil {
		t.Fatal(err)
	}
	close(release)
	got := waitTask(t, api, "running", "interrupted")
	if got.StorageAvailable || got.Task.Error != "storage_uncertain" || got.Task.Result != "" {
		t.Fatalf("unconfirmed output was exposed: %+v", got)
	}
	waitTask(t, api, "queued", "queued")
	if got := taskCall(t, api, "POST", "", `{"id":"new","content":"stop"}`, 503); got.Error != "storage_unavailable" {
		t.Fatalf("storage error = %+v", got)
	}
	stopTaskAPI(t, api)
	if calls.Load() != 1 {
		t.Fatalf("calls after storage failure = %d", calls.Load())
	}
	reopened := openTaskAPI(t, filepath.Join(backup, "tasks.json"), nil, time.Minute)
	waitTask(t, reopened, "running", "interrupted")
	waitTask(t, reopened, "queued", "queued")
}

func TestTasksNeverCallModelIfRunningWriteFails(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "store", "tasks.json")
	started, release := make(chan struct{}), make(chan struct{}, 1)
	defer close(release)
	var calls atomic.Int32
	api := openTaskAPI(t, path, taskProviderFunc(func(ctx context.Context, _ []model.Message) (string, error) {
		if calls.Add(1) == 1 {
			close(started)
			<-ctx.Done()
			<-release
		}
		return "output", nil
	}), time.Minute)
	taskCall(t, api, "POST", "", `{"id":"first","content":"active"}`, 201)
	taskSignal(t, started)
	taskCall(t, api, "POST", "", `{"id":"next","content":"waiting"}`, 201)
	taskCall(t, api, "POST", "/first/cancel", "", 200)
	if err := os.Rename(filepath.Dir(path), filepath.Join(dir, "backup")); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Dir(path), nil, 0600); err != nil {
		t.Fatal(err)
	}
	// No result write is attempted for the cancelled task; the next running transition fails.
	release <- struct{}{}
	deadline := time.After(3 * time.Second)
	for {
		if got := waitTask(t, api, "next", "queued"); !got.StorageAvailable {
			break
		}
		select {
		case <-deadline:
			t.Fatal("storage failure not surfaced")
		case <-time.After(5 * time.Millisecond):
		}
	}
	stopTaskAPI(t, api)
	if calls.Load() != 1 {
		t.Fatalf("calls = %d", calls.Load())
	}
}

func TestTasksRejectUnsavedCreation(t *testing.T) {
	dir := t.TempDir()
	parent := filepath.Join(dir, "store")
	api := openTaskAPI(t, filepath.Join(parent, "tasks.json"), taskProviderFunc(func(_ context.Context, _ []model.Message) (string, error) {
		t.Error("model called for an unsaved task")
		return "", nil
	}), time.Minute)
	if err := os.WriteFile(parent, nil, 0600); err != nil {
		t.Fatal(err)
	}
	taskCall(t, api, "POST", "", `{"id":"not_saved","content":"wait"}`, 503)
	taskCall(t, api, "GET", "/not_saved", "", 404)
	if got := taskCall(t, api, "GET", "", "", 200); got.StorageAvailable || len(got.Tasks) != 0 {
		t.Fatalf("failed submission was reported as present: %+v", got)
	}
}

func TestTasksCapacityKeepsExistingRecordsAndIdempotency(t *testing.T) {
	path := filepath.Join(t.TempDir(), "tasks.json")
	records := make([]map[string]string, 0, 199)
	for i := range 199 {
		records = append(records, map[string]string{
			"id": fmt.Sprintf("old_%d", i), "content": "kept", "status": "cancelled",
			"createdAt": "2026-11-21T00:00:00Z", "updatedAt": "2026-11-21T00:00:00Z",
		})
	}
	raw, err := json.Marshal(records)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, raw, 0600); err != nil {
		t.Fatal(err)
	}
	api := openTaskAPI(t, path, taskProviderFunc(func(_ context.Context, _ []model.Message) (string, error) {
		return "done", nil
	}), time.Minute)
	taskCall(t, api, "POST", "", `{"id":"last","content":"kept"}`, 201)
	if got := taskCall(t, api, "POST", "", `{"id":"overflow","content":"cannot fit"}`, 409); got.Error != "task_limit" {
		t.Fatalf("limit response = %+v", got)
	}
	taskCall(t, api, "POST", "", `{"id":"old_0","content":"kept"}`, 200)
	if got := taskCall(t, api, "GET", "", "", 200); len(got.Tasks) != 200 || got.Tasks[0].ID != "old_0" {
		t.Fatalf("capacity lost old records: count=%d", len(got.Tasks))
	}
}

func TestTasksDoNotOpenOrReplaceCorruptSnapshot(t *testing.T) {
	path := filepath.Join(t.TempDir(), "tasks.json")
	raw := []byte(`[{"id":"only-previous-copy","status":"misspelled"}]`)
	if err := os.WriteFile(path, raw, 0600); err != nil {
		t.Fatal(err)
	}
	api, err := NewTaskAPI(path, taskTestToken, nil, time.Minute)
	if err == nil {
		stopTaskAPI(t, api)
		t.Fatal("opened invalid snapshot")
	}
	after, err := os.ReadFile(path)
	if err != nil || string(after) != string(raw) {
		t.Fatal("invalid snapshot was modified")
	}
}
