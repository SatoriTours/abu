package httpapi

import (
	"bytes"
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
	Content   string    `json:"content"`
	Status    string    `json:"status"`
	Result    string    `json:"result,omitempty"`
	Error     string    `json:"error,omitempty"`
	CreatedAt time.Time `json:"createdAt"`
	UpdatedAt time.Time `json:"updatedAt"`
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
	mu            sync.Mutex
	path          string
	tasks         []task
	provider      model.Completer
	timeout       time.Duration
	handler       http.Handler
	wake          chan struct{}
	done          chan struct{}
	workerCtx     context.Context
	stop          context.CancelFunc
	activeCancel  context.CancelFunc
	activeIndex   int
	storageFailed bool
}

func NewTaskAPI(path, token string, provider model.Completer, timeout time.Duration) (*TaskAPI, error) {
	if len(token) < 32 {
		return nil, errors.New("ABU_DEV_TOKEN must contain at least 32 bytes")
	}
	if timeout <= 0 {
		return nil, errors.New("task timeout must be positive")
	}
	tasks, err := loadTaskSnapshot(path)
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
		path: path, tasks: tasks, provider: provider, timeout: timeout,
		wake: make(chan struct{}, 1), done: make(chan struct{}),
		workerCtx: ctx, stop: stop,
	}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/tasks", api.list)
	mux.HandleFunc("POST /api/tasks", api.create)
	mux.HandleFunc("GET /api/tasks/{id}", api.record)
	mux.HandleFunc("POST /api/tasks/{id}/cancel", api.record)
	api.handler = requireDevToken(token, mux)

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

func (s *TaskAPI) list(w http.ResponseWriter, _ *http.Request) {
	s.mu.Lock()
	response := struct {
		taskAvailability
		Tasks []task `json:"tasks"`
	}{s.availability(), slices.Clone(s.tasks)}
	s.mu.Unlock()
	writeJSON(w, http.StatusOK, response)
}

func (s *TaskAPI) create(w http.ResponseWriter, r *http.Request) {
	raw, err := io.ReadAll(http.MaxBytesReader(w, r.Body, taskBodyLimit))
	if err != nil {
		badTaskBody(w, err)
		return
	}
	id, content, ok := decodeTaskInput(raw)
	if !ok || !taskIDPattern.MatchString(id) || !validTaskText(content) {
		taskError(w, http.StatusBadRequest, "invalid_task")
		return
	}
	response, status, code := s.submit(id, content)
	if code != "" {
		taskError(w, status, code)
		return
	}
	writeJSON(w, status, response)
}

func (s *TaskAPI) submit(id, content string) (taskResponse, int, string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if i := s.find(id); i >= 0 {
		if s.tasks[i].Content != content {
			return taskResponse{}, http.StatusConflict, "id_conflict"
		}
		return taskResponse{s.availability(), s.tasks[i]}, http.StatusOK, ""
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
	entry := task{ID: id, Content: content, Status: "queued", CreatedAt: now, UpdatedAt: now}
	if !s.persist(append(slices.Clone(s.tasks), entry)) {
		return taskResponse{}, http.StatusServiceUnavailable, "storage_unavailable"
	}
	s.notify()
	return taskResponse{s.availability(), entry}, http.StatusCreated, ""
}

func (s *TaskAPI) record(w http.ResponseWriter, r *http.Request) {
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
	response, status, code := s.readOrCancel(id, cancel)
	if code != "" {
		taskError(w, status, code)
		return
	}
	writeJSON(w, status, response)
}

func (s *TaskAPI) readOrCancel(id string, cancel bool) (taskResponse, int, string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	i := s.find(id)
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
	return taskResponse{s.availability(), s.tasks[i]}, http.StatusOK, ""
}

func (s *TaskAPI) availability() taskAvailability {
	return taskAvailability{s.provider != nil, !s.storageFailed}
}

func (s *TaskAPI) find(id string) int {
	return slices.IndexFunc(s.tasks, func(t task) bool { return t.ID == id })
}

func validTaskText(text string) bool {
	return utf8.ValidString(text) && strings.TrimSpace(text) != "" && utf8.RuneCountInString(text) <= taskTextLimit
}

// Read exactly one object with the two exact keys. encoding/json's struct
// decoder would otherwise accept duplicate and case-insensitive field names.
func decodeTaskInput(raw []byte) (id, content string, ok bool) {
	if !utf8.Valid(raw) || !validUTF16Escapes(raw) {
		return "", "", false
	}
	d := json.NewDecoder(bytes.NewReader(raw))
	if token, err := d.Token(); err != nil || token != json.Delim('{') {
		return "", "", false
	}
	values := map[string]string{}
	for d.More() {
		key, err := d.Token()
		if err != nil || (key != "id" && key != "content") {
			return "", "", false
		}
		name := key.(string)
		var value *string
		if _, seen := values[name]; seen || d.Decode(&value) != nil || value == nil {
			return "", "", false
		}
		values[name] = *value
	}
	if token, err := d.Token(); err != nil || token != json.Delim('}') || d.Decode(new(any)) != io.EOF {
		return "", "", false
	}
	return values["id"], values["content"], len(values) == 2
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
			for s.runNext() {
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
	i := slices.IndexFunc(s.tasks, func(t task) bool { return t.Status == "queued" })
	if i < 0 || !s.change(i, "running", "", "") {
		s.mu.Unlock()
		return false
	}
	content := s.tasks[i].Content
	ctx, cancel := context.WithTimeout(s.workerCtx, s.timeout)
	s.activeIndex, s.activeCancel = i, cancel
	s.mu.Unlock()

	var output string
	err := ctx.Err()
	if err == nil {
		output, err = s.provider.Complete(ctx, []model.Message{{Role: "user", Content: content}})
	}
	timedOut := errors.Is(ctx.Err(), context.DeadlineExceeded) || errors.Is(err, context.DeadlineExceeded)

	s.mu.Lock()
	defer s.mu.Unlock()
	defer cancel()
	defer func() { s.activeCancel = nil }()
	if s.tasks[i].Status != "running" {
		return true // cancellation or a storage fault already owns the outcome
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
