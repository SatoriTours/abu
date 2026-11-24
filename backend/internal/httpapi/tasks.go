package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"abu.local/backend/internal/model"
)

const (
	taskLimit     = 200
	taskTextLimit = 4000
	taskBodyLimit = 64 * 1024
)

var taskIDPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,128}$`)

type task struct {
	ID        string    `json:"id"`
	BotID     string    `json:"botId"`
	Content   string    `json:"content"`
	Status    string    `json:"status"`
	Result    string    `json:"result,omitempty"`
	Error     string    `json:"error,omitempty"`
	CreatedAt time.Time `json:"createdAt"`
	UpdatedAt time.Time `json:"updatedAt"`
}

type storedTask struct {
	task
	Instructions string `json:"instructionsSnapshot"`
}

type taskAvailability struct {
	ModelConfigured  bool `json:"modelConfigured"`
	StorageAvailable bool `json:"storageAvailable"`
}

type taskResponse struct {
	taskAvailability
	Task task `json:"task"`
}

// TaskAPI owns the dev-only local task store and its single model worker.
type TaskAPI struct {
	mu           sync.Mutex
	path         string
	tasks        []storedTask
	bots         *Bots
	provider     model.Completer
	timeout      time.Duration
	draftTimeout time.Duration
	handler      http.Handler
	wake         chan struct{}
	done         chan struct{}
	workerCtx    context.Context
	stop         context.CancelFunc
	activeCancel context.CancelFunc
	// The running task is tracked by identity, never by slice index: a role deletion can
	// remove earlier rows while the model call is unlocked.
	activeBot     string
	activeTask    string
	storageFailed bool
	generated     *Generated
}

func NewTaskAPI(path, token string, provider model.Completer, timeout time.Duration) (*TaskAPI, error) {
	return NewTaskAPIWithBots(path, token, provider, timeout, nil)
}

func NewTaskAPIWithBots(path, token string, provider model.Completer, timeout time.Duration, bots *Bots) (*TaskAPI, error) {
	return newTaskAPI(path, token, provider, timeout, timeout, bots, nil)
}

func NewTaskAPIWithGenerated(path, token string, provider model.Completer, timeout, draftTimeout time.Duration, bots *Bots, generated *Generated) (*TaskAPI, error) {
	return newTaskAPI(path, token, provider, timeout, draftTimeout, bots, generated)
}

func newTaskAPI(path, token string, provider model.Completer, timeout, draftTimeout time.Duration, bots *Bots, generated *Generated) (*TaskAPI, error) {
	if len(token) < 32 {
		return nil, errors.New("ABU_DEV_TOKEN must contain at least 32 bytes")
	}
	if timeout <= 0 || draftTimeout <= 0 {
		return nil, errors.New("task timeout must be positive")
	}
	tasks, err := loadTaskSnapshot(path, bots)
	if err != nil {
		return nil, err
	}
	changed := false
	for i := range tasks {
		if tasks[i].Status == "running" {
			tasks[i].transition("interrupted", "", "interrupted")
			changed = true
		}
	}
	if changed && saveJSONSnapshot(path, ".tasks-*", tasks) != nil {
		return nil, errors.New("cannot persist task recovery")
	}

	ctx, stop := context.WithCancel(context.Background())
	api := &TaskAPI{
		path: path, tasks: tasks, bots: bots, provider: provider, timeout: timeout, draftTimeout: draftTimeout,
		wake: make(chan struct{}, 1), done: make(chan struct{}),
		workerCtx: ctx, stop: stop, generated: generated,
	}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/tasks", api.list)
	mux.HandleFunc("POST /api/tasks", api.create)
	mux.HandleFunc("GET /api/tasks/{id}", api.record)
	mux.HandleFunc("POST /api/tasks/{id}/cancel", api.record)
	api.handler = requireDevToken(token, mux)

	if generated != nil {
		generated.worker = api
	}
	if bots != nil {
		bots.RegisterDeleteHook(api)
	}
	if provider != nil {
		go api.run()
		api.notify()
	} else {
		close(api.done)
	}
	return api, nil
}

// NewUnavailableTaskHandler reports a startup storage fault without exposing it or starting a worker.
func NewUnavailableTaskHandler(token string) http.Handler {
	return requireDevToken(token, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		taskError(w, http.StatusServiceUnavailable, "storage_unavailable")
	}))
}

func (s *TaskAPI) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	s.handler.ServeHTTP(w, r)
}

// Shutdown stops new runs, cancels the active call, and waits for its final write.
// Queued tasks are left on disk for the next start.
func (s *TaskAPI) Shutdown(ctx context.Context) error {
	s.mu.Lock()
	s.stop()
	s.mu.Unlock()
	select {
	case <-s.done:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (s *TaskAPI) list(w http.ResponseWriter, r *http.Request) {
	id, all, ok := queryBotID(r, true)
	if !ok {
		apiError(w, 400, "invalid_bot_id")
		return
	}
	if !all {
		if _, ok := s.bots.resolve(w, id); !ok {
			return
		}
	}
	s.mu.Lock()
	tasks := []task{}
	for _, row := range s.tasks {
		if all || row.BotID == id {
			tasks = append(tasks, row.task)
		}
	}
	response := struct {
		taskAvailability
		Tasks []task `json:"tasks"`
	}{s.availability(), tasks}
	s.mu.Unlock()
	writeJSON(w, http.StatusOK, response)
}

func (s *TaskAPI) create(w http.ResponseWriter, r *http.Request) {
	if rejectBotQuery(w, r) {
		return
	}
	raw, err := io.ReadAll(http.MaxBytesReader(w, r.Body, taskBodyLimit))
	if err != nil {
		badTaskBody(w, err)
		return
	}
	fields, ok := scopedBody(raw, []string{"id", "content"}, nil)
	var input task
	if !ok || json.Unmarshal(raw, &input) != nil || !taskIDPattern.MatchString(input.ID) || !validTaskText(input.Content) {
		taskError(w, http.StatusBadRequest, "invalid_task")
		return
	}
	// The role read lock is held across the persisted task row, so a concurrent role deletion
	// cannot remove the role between the existence check and this write.
	if status, code := s.bots.withBotReadLock(bodyBotID(fields), func(bot Bot) {
		response, status, code := s.submit(bot, input.ID, input.Content)
		if code != "" {
			taskError(w, status, code)
			return
		}
		writeJSON(w, status, response)
	}); code != "" {
		taskError(w, status, code)
	}
}

func (s *TaskAPI) submit(bot Bot, id, content string) (taskResponse, int, string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if i := s.find(bot.ID, id); i >= 0 {
		if s.tasks[i].Content != content {
			return taskResponse{}, http.StatusConflict, "id_conflict"
		}
		return taskResponse{s.availability(), s.tasks[i].task}, http.StatusOK, ""
	}
	switch {
	case s.storageFailed:
		return taskResponse{}, http.StatusServiceUnavailable, "storage_unavailable"
	case s.workerCtx.Err() != nil:
		return taskResponse{}, http.StatusServiceUnavailable, "shutting_down"
	case s.provider == nil:
		return taskResponse{}, http.StatusServiceUnavailable, "model_not_configured"
	case len(s.tasks) >= taskLimit:
		return taskResponse{}, http.StatusConflict, "task_limit"
	}
	now := time.Now().UTC()
	entry := task{ID: id, BotID: bot.ID, Content: content, Status: "queued", CreatedAt: now, UpdatedAt: now}
	if !s.persist(append(slices.Clone(s.tasks), storedTask{entry, bot.Instructions})) {
		return taskResponse{}, http.StatusServiceUnavailable, "storage_unavailable"
	}
	s.notify()
	return taskResponse{s.availability(), entry}, http.StatusCreated, ""
}

func (s *TaskAPI) record(w http.ResponseWriter, r *http.Request) {
	botID, _, ok := queryBotID(r, false)
	if !ok {
		apiError(w, 400, "invalid_bot_id")
		return
	}
	if _, ok := s.bots.resolve(w, botID); !ok {
		return
	}
	id := r.PathValue("id")
	cancel := r.Method == http.MethodPost
	if cancel {
		raw, err := io.ReadAll(http.MaxBytesReader(w, r.Body, taskBodyLimit))
		if err != nil {
			badTaskBody(w, err)
			return
		}
		if len(raw) != 0 {
			taskError(w, http.StatusBadRequest, "invalid_task")
			return
		}
	}
	response, status, code := s.readOrCancel(botID, id, cancel)
	if code != "" {
		taskError(w, status, code)
		return
	}
	writeJSON(w, status, response)
}

func (s *TaskAPI) readOrCancel(botID, id string, cancel bool) (taskResponse, int, string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	i := s.find(botID, id)
	if i < 0 {
		return taskResponse{}, http.StatusNotFound, "task_not_found"
	}
	current := s.tasks[i]
	if cancel && (current.Status == "queued" || current.Status == "running") {
		if s.storageFailed || !s.change(i, "cancelled", "", "") {
			return taskResponse{}, http.StatusServiceUnavailable, "storage_unavailable"
		}
		if current.Status == "running" && s.activeCancel != nil {
			s.activeCancel()
		}
	}
	return taskResponse{s.availability(), s.tasks[i].task}, http.StatusOK, ""
}

func (s *TaskAPI) availability() taskAvailability {
	return taskAvailability{s.provider != nil, !s.storageFailed}
}

// CheckBotDeletable refuses a role whose tasks are still queued or running, and reports a
// faulted task store instead of deleting against data that cannot be rewritten.
func (s *TaskAPI) CheckBotDeletable(botID string) (int, string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.storageFailed {
		return 503, "storage_unavailable"
	}
	for _, row := range s.tasks {
		if row.BotID == botID && (row.Status == "queued" || row.Status == "running") {
			return 409, "bot_busy"
		}
	}
	return 0, ""
}

// PurgeBot removes every finished task of one role in a single atomic snapshot write and
// returns a rollback that restores those rows. It writes the snapshot directly instead of
// persist, so a purge fault does not poison the task store.
func (s *TaskAPI) PurgeBot(botID string) (func() error, int, string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.storageFailed {
		return nil, 503, "storage_unavailable"
	}
	current := s.tasks
	next := make([]storedTask, 0, len(current))
	removed := make([]storedTask, 0, 4)
	for _, row := range current {
		if row.BotID == botID {
			removed = append(removed, row)
		} else {
			next = append(next, row)
		}
	}
	if len(removed) == 0 {
		return nil, 0, ""
	}
	if err := saveJSONSnapshot(s.path, ".tasks-*", next); err != nil {
		return nil, 503, "storage_unavailable"
	}
	s.tasks = next
	return func() error {
		s.mu.Lock()
		defer s.mu.Unlock()
		// The store stays unlocked between the purge and this rollback, so the worker may have
		// finished tasks of other roles meanwhile. Merge the purged rows back into the current
		// snapshot instead of restoring the older full slice, which would drop those results.
		merged := mergeStoredTasks(s.tasks, removed)
		if err := saveJSONSnapshot(s.path, ".tasks-*", merged); err != nil {
			return err
		}
		s.tasks = merged
		return nil
	}, 0, ""
}

// mergeStoredTasks puts the purged rows back into the current snapshot. Rows of other roles keep
// their latest state untouched, and the result stays ordered by creation time as the store
// expects.
func mergeStoredTasks(current, removed []storedTask) []storedTask {
	merged := slices.Clone(current)
	for _, row := range removed {
		if slices.ContainsFunc(merged, func(other storedTask) bool {
			return other.BotID == row.BotID && other.ID == row.ID
		}) {
			continue
		}
		i := slices.IndexFunc(merged, func(other storedTask) bool { return other.CreatedAt.After(row.CreatedAt) })
		if i < 0 {
			merged = append(merged, row)
			continue
		}
		merged = slices.Insert(merged, i, row)
	}
	return merged
}

func (s *TaskAPI) find(botID, id string) int {
	return slices.IndexFunc(s.tasks, func(t storedTask) bool { return t.BotID == botID && t.ID == id })
}

func validTaskText(text string) bool {
	return utf8.ValidString(text) && strings.TrimSpace(text) != "" && utf8.RuneCountInString(text) <= taskTextLimit
}

// Reject unpaired surrogates before encoding/json can turn them into U+FFFD,
// which would make a different input appear idempotent.
func validUTF16Escapes(raw []byte) bool {
	for i := 0; i+1 < len(raw); i++ {
		if raw[i] != '\\' {
			continue
		}
		i++
		if raw[i] != 'u' {
			continue
		}
		if i+4 >= len(raw) {
			return false
		}
		code, err := strconv.ParseUint(string(raw[i+1:i+5]), 16, 16)
		if err != nil || (code >= 0xdc00 && code <= 0xdfff) {
			return false
		}
		i += 4
		if code >= 0xd800 && code <= 0xdbff {
			if i+6 >= len(raw) || raw[i+1] != '\\' || raw[i+2] != 'u' {
				return false
			}
			low, err := strconv.ParseUint(string(raw[i+3:i+7]), 16, 16)
			if err != nil || low < 0xdc00 || low > 0xdfff {
				return false
			}
			i += 6
		}
	}
	return true
}

func badTaskBody(w http.ResponseWriter, err error) {
	var excess *http.MaxBytesError
	if errors.As(err, &excess) {
		taskError(w, http.StatusRequestEntityTooLarge, "body_too_large")
		return
	}
	taskError(w, http.StatusBadRequest, "invalid_task")
}

func taskError(w http.ResponseWriter, status int, code string) {
	writeJSON(w, status, map[string]string{"error": code})
}

func (s *TaskAPI) notify() {
	select {
	case s.wake <- struct{}{}:
	default:
	}
}

func (s *TaskAPI) run() {
	defer close(s.done)
	for {
		select {
		case <-s.workerCtx.Done():
			return
		case <-s.wake:
			for {
				text := s.runNext()
				generated := s.generated.runNext(s.workerCtx, s.provider, s.timeout, s.draftTimeout)
				if !text && !generated {
					break
				}
			}
		}
	}
}

func (s *TaskAPI) runNext() bool {
	s.mu.Lock()
	if s.storageFailed || s.workerCtx.Err() != nil {
		s.mu.Unlock()
		return false
	}
	i := slices.IndexFunc(s.tasks, func(t storedTask) bool { return t.Status == "queued" })
	if i < 0 || !s.change(i, "running", "", "") {
		s.mu.Unlock()
		return false
	}
	row := s.tasks[i]
	ctx, cancel := context.WithTimeout(s.workerCtx, s.timeout)
	s.activeBot, s.activeTask, s.activeCancel = row.BotID, row.ID, cancel
	s.mu.Unlock()

	var output string
	err := ctx.Err()
	if err == nil {
		output, err = s.provider.Complete(ctx, append(rolePrompt(row.Instructions), model.Message{Role: "user", Content: row.Content}))
	}
	timedOut := errors.Is(ctx.Err(), context.DeadlineExceeded) || errors.Is(err, context.DeadlineExceeded)

	s.mu.Lock()
	defer s.mu.Unlock()
	defer cancel()
	defer func() { s.activeCancel = nil }()
	// The row may be gone or moved while the model call ran unlocked.
	i = s.find(row.BotID, row.ID)
	if i < 0 || s.tasks[i].Status != "running" {
		return true // cancellation, removal or a storage fault already owns the outcome
	}
	switch {
	case s.workerCtx.Err() != nil:
		s.change(i, "interrupted", "", "interrupted")
	case timedOut:
		s.change(i, "failed", "", "model_timeout")
	case err != nil, !utf8.ValidString(output):
		s.change(i, "failed", "", "model_failed")
	default:
		// Slice on the first rune boundary beyond the limit without widening all the output.
		count := 0
		for offset := range output {
			if count == taskTextLimit {
				output = strings.Clone(output[:offset])
				break
			}
			count++
		}
		if strings.TrimSpace(output) == "" {
			s.change(i, "failed", "", "model_failed")
		} else {
			s.change(i, "completed", output, "")
		}
	}
	return true
}
