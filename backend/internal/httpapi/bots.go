package httpapi

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"unicode/utf8"
)

const defaultBotID = "abu"
const botDeletePendingName = ".bot-delete.pending"
const botCapacity = 32
const botBodyLimit = 32 * 1024
const maxBotRevision = 1<<53 - 1

type Bot struct {
	ID           string `json:"id"`
	Name         string `json:"name"`
	Instructions string `json:"instructions"`
	Avatar       string `json:"avatar"`
	Revision     int64  `json:"revision"`
}

type storedBot struct {
	Bot
	CreationHash string `json:"creationHash,omitempty"`
}

// BotDeleteHook is a store that owns records scoped to one role. Every hook is asked to
// check first, so a refused delete never leaves half-removed child data behind; only then is
// each hook asked to purge. Both calls run with Bots.mu held and must never call back into
// Bots, which keeps the single lock order Bots.mu -> store.mu.
type BotDeleteHook interface {
	CheckBotDeletable(botID string) (status int, code string)
	// PurgeBot removes every record of one role in a single atomic snapshot write. It returns a
	// rollback that restores the previous in-memory and on-disk snapshot, or nil when nothing
	// changed, so a delete that fails later never loses child data.
	PurgeBot(botID string) (rollback func() error, status int, code string)
}

// botDeleteIntent is the persisted write-ahead record of one role deletion. It lets the next
// start roll an interrupted delete forward instead of leaving child records behind.
type botDeleteIntent struct {
	BotID string `json:"botId"`
}

func botDeletePendingPath(path string) string {
	return filepath.Join(filepath.Dir(path), botDeletePendingName)
}

// deleteIntentExists reports a write-ahead record this process has not accounted for, so a new
// delete never overwrites a transaction that is still waiting to be rolled forward. An
// unreadable path counts as present: the record cannot be proven absent.
func deleteIntentExists(path string) bool {
	_, err := os.Stat(botDeletePendingPath(path))
	return !errors.Is(err, os.ErrNotExist)
}

// loadDeleteIntent reads and validates the persisted write-ahead record. It returns an empty id
// when no intent exists. A damaged or out-of-shape file is an error that keeps its bytes
// untouched, so a transaction record is never silently rewritten into something else.
func loadDeleteIntent(path string) (string, error) {
	raw, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return "", nil
	}
	if err != nil {
		return "", errors.New("cannot read the pending bot deletion")
	}
	var intent botDeleteIntent
	if !validRoleJSON(raw) || exactTodoObject(raw, "botId") == nil || json.Unmarshal(raw, &intent) != nil ||
		!taskIDPattern.MatchString(intent.BotID) || intent.BotID == defaultBotID {
		return "", errors.New("invalid pending bot deletion")
	}
	return intent.BotID, nil
}

type Bots struct {
	mu          sync.RWMutex
	path        string
	records     []storedBot
	deleteHooks []BotDeleteHook
	handler     http.Handler
	// deleteBlocked is set when one child store could not be loaded, so a delete would skip the
	// records that store owns; it has no way to purge them and must not run at all.
	deleteBlocked bool
	// pendingDeleteID is a persisted delete intent that has not been rolled forward yet. It
	// guards the write-ahead record: a new delete must never overwrite a transaction that the
	// next healthy start has to finish, and the same ID cannot be recreated until cleared.
	pendingDeleteID string
	// removeIntent defaults to os.Remove and can be overridden in tests to inject cleanup faults.
	removeIntent func(string) error
}

func (s *Bots) removeIntentFile(path string) error {
	if s.removeIntent != nil {
		return s.removeIntent(path)
	}
	return os.Remove(path)
}

func defaultBot() Bot {
	return Bot{ID: defaultBotID, Name: "阿布", Avatar: "abu", Revision: 1}
}

func NewBots(path, token string) (*Bots, error) {
	if len(token) < 32 {
		return nil, errors.New("ABU_DEV_TOKEN must contain at least 32 bytes")
	}
	records, err := loadBots(path)
	if err != nil {
		return nil, err
	}
	s := &Bots{path: path, records: records}
	// Fail the start on a damaged intent before any child store is wired, and remember a valid
	// one so every delete is refused until RecoverPendingDelete rolls it forward.
	pendingID, err := loadDeleteIntent(botDeletePendingPath(path))
	if err != nil {
		return nil, err
	}
	s.pendingDeleteID = pendingID
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/bots", s.list)
	mux.HandleFunc("POST /api/bots", s.write)
	mux.HandleFunc("PUT /api/bots/{id}", s.write)
	mux.HandleFunc("DELETE /api/bots/{id}", s.delete)
	s.handler = requireDevToken(token, mux)
	return s, nil
}

func loadBots(path string) ([]storedBot, error) {
	f, err := os.Open(path)
	if os.IsNotExist(err) {
		return []storedBot{{Bot: defaultBot()}}, nil
	}
	if err != nil {
		return nil, errors.New("cannot open bot storage")
	}
	defer f.Close()
	const limit = 1024 * 1024
	raw, err := io.ReadAll(io.LimitReader(f, limit+1))
	var entries []json.RawMessage
	if err != nil || len(raw) > limit || !validRoleJSON(raw) || json.Unmarshal(raw, &entries) != nil || len(entries) == 0 || len(entries) > botCapacity {
		return nil, errors.New("invalid bot snapshot")
	}
	var records []storedBot
	ids := map[string]bool{}
	for i, entry := range entries {
		fields := []string{"id", "name", "instructions", "avatar", "revision"}
		if i != 0 {
			fields = append(fields, "creationHash")
		}
		var b storedBot
		if exactTodoObject(entry, fields...) == nil || json.Unmarshal(entry, &b) != nil || !validBot(b.Bot) ||
			b.Revision < 1 || b.Revision > maxBotRevision || ids[b.ID] || (i == 0) != (b.ID == defaultBotID) ||
			(i != 0 && !validCreationHash(b.CreationHash)) {
			return nil, errors.New("invalid bot record")
		}
		ids[b.ID] = true
		records = append(records, b)
	}
	return records, nil
}

func validCreationHash(s string) bool {
	b, err := hex.DecodeString(s)
	return err == nil && len(b) == sha256.Size && s == hex.EncodeToString(b)
}

func validBot(b Bot) bool {
	if !taskIDPattern.MatchString(b.ID) || strings.TrimSpace(b.Name) == "" ||
		!utf8.ValidString(b.Name) || utf8.RuneCountInString(b.Name) > 40 || !validBotInstructions(b.Instructions) {
		return false
	}
	return slices.Contains([]string{"abu", "leaf", "flame", "wave", "fluff"}, b.Avatar)
}

func validBotInstructions(s string) bool {
	return utf8.ValidString(s) && utf8.RuneCountInString(s) <= 4000
}

func validRoleJSON(raw []byte) bool { return utf8.Valid(raw) && validUTF16Escapes(raw) }

func (s *Bots) lookup(id string) (Bot, bool) {
	if s == nil {
		return defaultBot(), id == defaultBotID
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.findLocked(id)
}

// findLocked returns one role. Callers hold s.mu.
func (s *Bots) findLocked(id string) (Bot, bool) {
	for _, record := range s.records {
		if record.ID == id {
			return record.Bot, true
		}
	}
	return Bot{}, false
}

// withBotReadLock resolves one role and runs fn while s.mu stays read-locked, so a concurrent
// DELETE /api/bots/{id} cannot remove the role between the existence check and a write that
// names it. fn takes the child store lock and writes its own response; a non-empty code
// reports a role resolution failure. The single lock order stays Bots.mu -> store.mu, and a
// role that only starts a background run must release the read lock before any network call.
func (s *Bots) withBotReadLock(id string, fn func(Bot)) (status int, code string) {
	if !taskIDPattern.MatchString(id) {
		return 400, "invalid_bot_id"
	}
	if s == nil {
		if id != defaultBotID {
			return 404, "bot_not_found"
		}
		fn(defaultBot())
		return 0, ""
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	bot, ok := s.findLocked(id)
	if !ok {
		return 404, "bot_not_found"
	}
	fn(bot)
	return 0, ""
}

func (s *Bots) ServeHTTP(w http.ResponseWriter, r *http.Request) { s.handler.ServeHTTP(w, r) }

func (s *Bots) list(w http.ResponseWriter, _ *http.Request) {
	s.mu.RLock()
	result := make([]Bot, 0, len(s.records))
	for _, b := range s.records {
		result = append(result, b.Bot)
	}
	s.mu.RUnlock()
	writeJSON(w, 200, map[string]any{"bots": result})
}

func (s *Bots) write(w http.ResponseWriter, r *http.Request) {
	raw, err := io.ReadAll(http.MaxBytesReader(w, r.Body, botBodyLimit))
	var excess *http.MaxBytesError
	if errors.As(err, &excess) {
		apiError(w, 413, "body_too_large")
		return
	}
	creating := r.Method == "POST"
	fields := []string{"name", "instructions", "avatar", "revision"}
	if creating {
		fields[3] = "id"
	}
	var input Bot
	if err != nil || !validRoleJSON(raw) || exactTodoObject(raw, fields...) == nil || json.Unmarshal(raw, &input) != nil {
		apiError(w, 400, "invalid_bot")
		return
	}
	if creating {
		input.Revision = 1
	} else {
		input.ID = r.PathValue("id")
	}
	if !validBot(input) || input.Revision < 1 || input.Revision > maxBotRevision {
		apiError(w, 400, "invalid_bot")
		return
	}
	b, status, code := s.save(input, creating)
	if code != "" {
		apiError(w, status, code)
		return
	}
	writeJSON(w, status, b)
}

// RegisterDeleteHook connects one child store. Constructors call it so a store created with
// these roles is always part of deletion; registering the same store twice is a no-op.
func (s *Bots) RegisterDeleteHook(hook BotDeleteHook) {
	if s == nil || hook == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, existing := range s.deleteHooks {
		if existing == hook {
			return
		}
	}
	s.deleteHooks = append(s.deleteHooks, hook)
}

// DisableDelete refuses every role deletion for this process. A child store that could not be
// loaded has no hook to purge its records, so deleting a role would leave them behind and could
// overwrite a pending transaction; only a healthy restart re-enables deletion.
func (s *Bots) DisableDelete() {
	if s == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.deleteBlocked = true
}

func (s *Bots) delete(w http.ResponseWriter, r *http.Request) {
	if rejectBotQuery(w, r) {
		return
	}
	id := r.PathValue("id")
	if !taskIDPattern.MatchString(id) {
		apiError(w, 400, "invalid_bot_id")
		return
	}
	// The primary assistant is part of the application, not user data.
	if id == defaultBotID {
		apiError(w, 409, "cannot_delete_default_bot")
		return
	}
	if status, code := s.remove(id); code != "" {
		apiError(w, status, code)
		return
	}
	writeJSON(w, 200, map[string]any{"deleted": true, "id": id})
}

// remove deletes one role and every child record that references it as a recoverable
// transaction. A write-ahead intent is persisted first and the role row is written last, so an
// interrupted delete is rolled forward on the next start; a child purge that fails part way is
// rolled back, which keeps both the in-memory records and the snapshots on disk unchanged.
func (s *Bots) remove(id string) (int, string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	// A store that could not be loaded, an intent this process has not rolled forward, or a record
	// left by another writer owns child rows that a delete here cannot account for. Refuse instead
	// of overwriting the transaction or deleting a role whose data would survive it.
	if s.deleteBlocked || s.pendingDeleteID != "" || deleteIntentExists(s.path) {
		return 503, "storage_unavailable"
	}
	i := slices.IndexFunc(s.records, func(row storedBot) bool { return row.ID == id })
	if i < 0 {
		return 404, "bot_not_found"
	}
	for _, hook := range s.deleteHooks {
		if status, code := hook.CheckBotDeletable(id); code != "" {
			return status, code
		}
	}
	pending := botDeletePendingPath(s.path)
	if err := saveJSONSnapshot(pending, ".bot-delete-*", botDeleteIntent{BotID: id}); err != nil {
		// Nothing was changed yet, so the ordinary error response is already accurate.
		return 503, "storage_unavailable"
	}
	rollbacks := make([]func() error, 0, len(s.deleteHooks))
	for _, hook := range s.deleteHooks {
		rollback, status, code := hook.PurgeBot(id)
		if code != "" {
			if s.rollbackPurge(rollbacks, pending, id) {
				return status, code
			}
			return 503, "storage_unavailable"
		}
		if rollback != nil {
			rollbacks = append(rollbacks, rollback)
		}
	}
	next := slices.Delete(slices.Clone(s.records), i, i+1)
	if err := saveJSONSnapshot(s.path, ".bots-*", next); err != nil {
		s.rollbackPurge(rollbacks, pending, id)
		return 503, "storage_unavailable"
	}
	s.records = next
	if err := s.removeIntentFile(pending); err != nil && !errors.Is(err, os.ErrNotExist) {
		// The role is gone and every child row is purged, but keep the pending guard active until
		// recovery clears the file so the same role ID cannot be recreated and later wiped on restart.
		s.pendingDeleteID = id
		log.Print("cannot remove the finished bot delete intent; it is rolled forward on restart")
	}
	return 200, ""
}

// rollbackPurge restores the child snapshots in reverse purge order and then drops the
// intent. It reports whether the failed delete left no trace. A restore that fails, or an intent
// file that cannot be removed, keeps the write-ahead record for the next healthy start and
// blocks every later delete, so a half-purged role is never hidden behind a new transaction.
func (s *Bots) rollbackPurge(rollbacks []func() error, pending, id string) bool {
	restored := true
	for i := len(rollbacks) - 1; i >= 0; i-- {
		if err := rollbacks[i](); err != nil {
			restored = false
			log.Print("cannot restore bot child records after a failed delete; the delete intent is rolled forward on restart")
		}
	}
	if !restored {
		s.pendingDeleteID = id
		return false
	}
	if err := s.removeIntentFile(pending); err != nil && !errors.Is(err, os.ErrNotExist) {
		log.Print("cannot remove the intent of a rolled back bot delete; it is rolled forward on restart")
		s.pendingDeleteID = id
		return false
	}
	return true
}

// RecoverPendingDelete rolls an interrupted role deletion forward. Call it after every child
// store has loaded its snapshot. The intent is written before any child purge and removed
// after the bot snapshot, so both crash points leave readable stores: with the role row still
// present, every child snapshot validates and this pass purges and removes it; with the row
// already gone, the pass only drops the stale intent. Every step is idempotent, so a crash
// during recovery itself is also recovered on the next start.
func (s *Bots) RecoverPendingDelete() error {
	if s == nil {
		return nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	pending := botDeletePendingPath(s.path)
	id, err := loadDeleteIntent(pending)
	if err != nil {
		return err
	}
	if id == "" {
		s.pendingDeleteID = ""
		return nil
	}
	// Keep the transaction guarded for the whole pass, so nothing overwrites the intent this
	// recovery is finishing.
	s.pendingDeleteID = id
	for _, hook := range s.deleteHooks {
		if _, _, code := hook.PurgeBot(id); code != "" {
			return errors.New("cannot finish the pending bot deletion")
		}
	}
	if i := slices.IndexFunc(s.records, func(row storedBot) bool { return row.ID == id }); i >= 0 {
		next := slices.Delete(slices.Clone(s.records), i, i+1)
		if err := saveJSONSnapshot(s.path, ".bots-*", next); err != nil {
			return errors.New("cannot finish the pending bot deletion")
		}
		s.records = next
	}
	if err := s.removeIntentFile(pending); err != nil && !errors.Is(err, os.ErrNotExist) {
		return errors.New("cannot remove the pending bot deletion")
	}
	s.pendingDeleteID = ""
	return nil
}

func (s *Bots) save(b Bot, creating bool) (Bot, int, string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	i := slices.IndexFunc(s.records, func(row storedBot) bool { return row.ID == b.ID })
	next := slices.Clone(s.records)
	status := 200
	if creating {
		// Never allow recreating a role ID while a delete intent for that ID is still present,
		// otherwise the next restart's RecoverPendingDelete would wipe the recreated role.
		if s.pendingDeleteID == b.ID {
			return Bot{}, 503, "storage_unavailable"
		}
		if deleteIntentExists(s.path) {
			pendingID, err := loadDeleteIntent(botDeletePendingPath(s.path))
			if err != nil || pendingID == b.ID {
				return Bot{}, 503, "storage_unavailable"
			}
		}
		canonical, _ := json.Marshal(b)
		sum := sha256.Sum256(canonical)
		fingerprint := hex.EncodeToString(sum[:])
		if i >= 0 {
			if next[i].CreationHash == fingerprint {
				return next[i].Bot, 200, ""
			}
			return Bot{}, 409, "id_conflict"
		}
		if len(next) >= botCapacity {
			return Bot{}, 409, "bot_limit"
		}
		next = append(next, storedBot{b, fingerprint})
		status = 201
	} else {
		if i < 0 {
			return Bot{}, 404, "bot_not_found"
		}
		if b.Revision != next[i].Revision || b.Revision == maxBotRevision {
			return Bot{}, 409, "revision_conflict"
		}
		b.Revision++
		next[i].Bot = b
	}
	if err := saveJSONSnapshot(s.path, ".bots-*", next); err != nil {
		return Bot{}, 503, "storage_unavailable"
	}
	s.records = next
	return b, status, ""
}

func apiError(w http.ResponseWriter, status int, code string) {
	writeJSON(w, status, map[string]string{"error": code})
}

// scopedBody preserves the distinction between omitted botId and explicit invalid input.
func scopedBody(raw []byte, required, optional []string) (map[string]json.RawMessage, bool) {
	if !validRoleJSON(raw) {
		return nil, false
	}
	d := json.NewDecoder(bytes.NewReader(raw))
	if token, err := d.Token(); err != nil || token != json.Delim('{') {
		return nil, false
	}
	fields := make(map[string]json.RawMessage)
	for d.More() {
		token, err := d.Token()
		key, ok := token.(string)
		if err != nil || !ok || fields[key] != nil ||
			(key != "botId" && !slices.Contains(required, key) && !slices.Contains(optional, key)) {
			return nil, false
		}
		var value json.RawMessage
		if d.Decode(&value) != nil || bytes.Equal(value, []byte("null")) {
			return nil, false
		}
		fields[key] = value
	}
	if token, err := d.Token(); err != nil || token != json.Delim('}') || d.Decode(new(any)) != io.EOF {
		return nil, false
	}
	for _, key := range required {
		if fields[key] == nil {
			return nil, false
		}
	}
	return fields, true
}

func bodyBotID(fields map[string]json.RawMessage) string {
	value := fields["botId"]
	if value == nil {
		return defaultBotID
	}
	var id string
	if json.Unmarshal(value, &id) != nil {
		return ""
	}
	return id
}

func queryBotID(r *http.Request, allowAll bool) (string, bool, bool) {
	q, err := url.ParseQuery(r.URL.RawQuery)
	if err != nil {
		return "", false, false
	}
	for key := range q {
		if key != "botId" && key != "allBots" {
			return "", false, false
		}
	}
	all, filtered := q["allBots"], q["botId"]
	if len(all) != 0 {
		return "", true, allowAll && len(all) == 1 && all[0] == "true" && len(filtered) == 0
	}
	if len(filtered) == 0 {
		return defaultBotID, false, true
	}
	return filtered[0], false, len(filtered) == 1
}

// Body-scoped writes accept the role in one place only.
func rejectBotQuery(w http.ResponseWriter, r *http.Request) bool {
	if r.URL.RawQuery == "" {
		return false
	}
	apiError(w, 400, "invalid_bot_id")
	return true
}

func (s *Bots) resolve(w http.ResponseWriter, id string) (Bot, bool) {
	if !taskIDPattern.MatchString(id) {
		apiError(w, 400, "invalid_bot_id")
		return Bot{}, false
	}
	b, ok := s.lookup(id)
	if !ok {
		apiError(w, 404, "bot_not_found")
	}
	return b, ok
}
