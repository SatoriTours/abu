package httpapi

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"os"
	"regexp"
	"strings"
	"sync"
	"unicode/utf8"
)

const (
	todoBodyLimit   = 256 * 1024
	todoItemLimit   = 100
	todoMaxRevision = 1<<53 - 1 // Exact integer in a browser.
)

var todoIDPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,128}$`)

type todoItem struct {
	ID   string `json:"id"`
	Text string `json:"text"`
	Done bool   `json:"done"`
}

type todoSnapshot struct {
	Revision int64      `json:"revision"`
	Items    []todoItem `json:"items"`
}

type todoStored struct {
	todoSnapshot
	MutationID   string `json:"mutationId"`
	BaseRevision int64  `json:"baseRevision"`
	Fingerprint  string `json:"fingerprint"`
}

type todoStore struct {
	mu          sync.Mutex
	path        string
	unavailable bool
	record      todoStored
}

func openTodoStore(path string) *todoStore {
	s := &todoStore{path: path, record: todoStored{todoSnapshot: todoSnapshot{Items: []todoItem{}}}}
	info, err := os.Lstat(path)
	if os.IsNotExist(err) {
		return s
	}
	if err != nil || !info.Mode().IsRegular() {
		s.disable()
		return s
	}
	f, err := os.Open(path)
	if err != nil {
		s.disable()
		return s
	}
	defer f.Close()
	raw, err := io.ReadAll(io.LimitReader(f, todoBodyLimit+1))
	if err != nil || len(raw) > todoBodyLimit {
		s.disable()
		return s
	}
	record, ok := parseTodoStored(raw)
	if !ok {
		s.disable()
		return s
	}
	s.record = record
	return s
}

func (s *todoStore) disable() {
	s.unavailable = true
	log.Print("application data storage unavailable; restore the affected snapshot and restart")
}

func (s *todoStore) respond(w http.ResponseWriter, update *todoStored) {
	snapshot, status, code := s.apply(update)
	if code != "" {
		appError(w, status, code)
		return
	}
	writeJSON(w, status, snapshot)
}

func (s *todoStore) apply(update *todoStored) (todoSnapshot, int, string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.unavailable {
		return todoSnapshot{}, 503, "storage_unavailable"
	}
	current := s.record
	if update == nil {
		return current.todoSnapshot, 200, ""
	}
	if update.MutationID == current.MutationID {
		if update.BaseRevision == current.BaseRevision && update.Fingerprint == current.Fingerprint {
			return current.todoSnapshot, 200, ""
		}
		return todoSnapshot{}, 409, "mutation_conflict"
	}
	if update.BaseRevision != current.Revision {
		return todoSnapshot{}, 409, "revision_conflict"
	}
	if current.Revision == todoMaxRevision {
		return todoSnapshot{}, 409, "revision_limit"
	}
	update.Revision = current.Revision + 1
	if err := saveJSONSnapshot(s.path, ".app-data-*", update); err != nil {
		s.disable()
		return todoSnapshot{}, 503, "storage_unavailable"
	}
	s.record = *update
	return s.record.todoSnapshot, 200, ""
}

// Parse exact object members so case variants, duplicates and null do not silently
// become a different valid request through encoding/json's struct decoder.
func exactTodoObject(raw []byte, fields ...string) map[string]json.RawMessage {
	d := json.NewDecoder(bytes.NewReader(raw))
	if t, err := d.Token(); err != nil || t != json.Delim('{') {
		return nil
	}
	values := make(map[string]json.RawMessage, len(fields))
	for d.More() {
		key, err := d.Token()
		if err != nil {
			return nil
		}
		name, ok := key.(string)
		if !ok || values[name] != nil {
			return nil
		}
		var rawValue json.RawMessage
		if d.Decode(&rawValue) != nil || bytes.Equal(rawValue, []byte("null")) {
			return nil
		}
		values[name] = rawValue
	}
	if t, err := d.Token(); err != nil || t != json.Delim('}') || d.Decode(new(any)) != io.EOF || len(values) != len(fields) {
		return nil
	}
	for _, field := range fields {
		if values[field] == nil {
			return nil
		}
	}
	return values
}

func parseTodoItems(raw json.RawMessage) ([]todoItem, bool) {
	var members []json.RawMessage
	if json.Unmarshal(raw, &members) != nil || members == nil || len(members) > todoItemLimit {
		return nil, false
	}
	items := make([]todoItem, 0, len(members))
	seen := make(map[string]bool)
	for _, member := range members {
		fields := exactTodoObject(member, "id", "text", "done")
		var item todoItem
		if fields == nil || json.Unmarshal(fields["id"], &item.ID) != nil ||
			json.Unmarshal(fields["text"], &item.Text) != nil || json.Unmarshal(fields["done"], &item.Done) != nil ||
			!todoIDPattern.MatchString(item.ID) || seen[item.ID] ||
			strings.TrimSpace(item.Text) == "" || utf8.RuneCountInString(item.Text) > 200 {
			return nil, false
		}
		seen[item.ID] = true
		items = append(items, item)
	}
	return items, true
}

func parseTodoFields(fields map[string]json.RawMessage) (todoStored, bool) {
	var data todoStored
	if fields == nil || json.Unmarshal(fields["mutationId"], &data.MutationID) != nil ||
		json.Unmarshal(fields["baseRevision"], &data.BaseRevision) != nil ||
		!todoIDPattern.MatchString(data.MutationID) || data.BaseRevision < 0 || data.BaseRevision > todoMaxRevision {
		return todoStored{}, false
	}
	var ok bool
	data.Items, ok = parseTodoItems(fields["items"])
	return data, ok
}

func parseTodoUpdate(raw []byte) (todoStored, bool) {
	if !utf8.Valid(raw) || !validUTF16Escapes(raw) {
		return todoStored{}, false
	}
	update, ok := parseTodoFields(exactTodoObject(raw, "mutationId", "baseRevision", "items"))
	sum := sha256.Sum256(raw)
	update.Fingerprint = hex.EncodeToString(sum[:])
	return update, ok
}

func parseTodoStored(raw []byte) (todoStored, bool) {
	if !utf8.Valid(raw) || !validUTF16Escapes(raw) {
		return todoStored{}, false
	}
	fields := exactTodoObject(raw, "mutationId", "baseRevision", "items", "revision", "fingerprint")
	data, ok := parseTodoFields(fields)
	if !ok || json.Unmarshal(fields["revision"], &data.Revision) != nil ||
		json.Unmarshal(fields["fingerprint"], &data.Fingerprint) != nil || data.Revision != data.BaseRevision+1 ||
		data.Revision > todoMaxRevision {
		return todoStored{}, false
	}
	fingerprint, err := hex.DecodeString(data.Fingerprint)
	return data, err == nil && len(fingerprint) == sha256.Size && data.Fingerprint == hex.EncodeToString(fingerprint)
}
