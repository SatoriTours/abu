package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"log"
	"os"
	"slices"
	"time"
)

func loadTaskSnapshot(path string, bots *Bots) ([]storedTask, error) {
	f, err := os.Open(path)
	if errors.Is(err, os.ErrNotExist) {
		return []storedTask{}, nil
	}
	if err != nil {
		return nil, errors.New("cannot open task storage")
	}
	defer f.Close()

	// At capacity, content, result and the role snapshot can each use six bytes per rune.
	const limit = 16 * 1024 * 1024
	raw, err := io.ReadAll(io.LimitReader(f, limit+1))
	var rows []json.RawMessage
	if err != nil || len(raw) > limit || !validRoleJSON(raw) || json.Unmarshal(raw, &rows) != nil ||
		rows == nil || len(rows) > taskLimit {
		return nil, errors.New("invalid task snapshot")
	}
	tasks := make([]storedTask, 0, len(rows))
	seen := map[[2]string]bool{}
	for _, row := range rows {
		fields, ok := scopedBody(row, []string{"id", "content", "status", "createdAt", "updatedAt"},
			[]string{"result", "error", "instructionsSnapshot"})
		var entry storedTask
		if !ok || json.Unmarshal(row, &entry) != nil || !taskIDPattern.MatchString(entry.ID) ||
			!validTaskText(entry.Content) || entry.CreatedAt.IsZero() || entry.UpdatedAt.IsZero() ||
			!hasValidTaskOutcome(entry.task) || !validBotInstructions(entry.Instructions) ||
			(fields["botId"] != nil) != (fields["instructionsSnapshot"] != nil) {
			return nil, errors.New("invalid task record")
		}
		entry.BotID = bodyBotID(fields)
		key := [2]string{entry.BotID, entry.ID}
		if _, known := bots.lookup(entry.BotID); !known || seen[key] {
			return nil, errors.New("invalid task record scope")
		}
		seen[key] = true
		tasks = append(tasks, entry)
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
func (s *TaskAPI) persist(next []storedTask) bool {
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
