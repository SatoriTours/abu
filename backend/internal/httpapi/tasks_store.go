package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"log"
	"os"
	"slices"
	"time"
	"unicode/utf8"
)

func loadTaskSnapshot(path string) ([]task, error) {
	f, err := os.Open(path)
	if errors.Is(err, os.ErrNotExist) {
		return []task{}, nil
	}
	if err != nil {
		return nil, errors.New("cannot open task storage")
	}
	defer f.Close()

	const limit = 10 * 1024 * 1024
	raw, err := io.ReadAll(io.LimitReader(f, limit+1))
	var tasks []task
	if err != nil || len(raw) > limit || !utf8.Valid(raw) || json.Unmarshal(raw, &tasks) != nil ||
		tasks == nil || len(tasks) > taskLimit {
		return nil, errors.New("invalid task snapshot")
	}
	seen := make(map[string]bool, len(tasks))
	for _, entry := range tasks {
		if seen[entry.ID] || !taskIDPattern.MatchString(entry.ID) || !validTaskText(entry.Content) ||
			entry.CreatedAt.IsZero() || entry.UpdatedAt.IsZero() || !hasValidTaskOutcome(entry) {
			return nil, errors.New("invalid task record")
		}
		seen[entry.ID] = true
	}
	return tasks, nil
}

func hasValidTaskOutcome(t task) bool {
	switch t.Status {
	case "queued", "running", "cancelled":
		return t.Result == "" && t.Error == ""
	case "completed":
		return validTaskText(t.Result) && t.Error == ""
	case "failed":
		return t.Result == "" && (t.Error == "model_failed" || t.Error == "model_timeout")
	case "interrupted":
		return t.Result == "" && t.Error == "interrupted"
	}
	return false
}

func (t *task) transition(status, result, code string) {
	t.Status, t.Result, t.Error, t.UpdatedAt = status, result, code, time.Now().UTC()
}

func (s *TaskAPI) change(i int, status, result, code string) bool {
	next := slices.Clone(s.tasks)
	next[i].transition(status, result, code)
	return s.persist(next)
}

// Call with the lock held. A write fault freezes the last committed snapshot;
// a running call's result is uncertain, and must not be exposed or rerun here.
func (s *TaskAPI) persist(next []task) bool {
	// shortcut: single-process full snapshot rewrites; move to a database before sharing the store or growing its limits.
	if err := saveJSONSnapshot(s.path, ".tasks-*", next); err != nil {
		s.storageFailed = true
		if s.activeCancel != nil {
			s.activeCancel()
			if s.tasks[s.activeIndex].Status == "running" {
				s.tasks[s.activeIndex].transition("interrupted", "", "storage_uncertain")
			}
		}
		s.stop()
		log.Print("task storage unavailable; restore storage before restarting the service")
		return false
	}
	s.tasks = next
	return true
}
