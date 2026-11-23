package httpapi

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"os"
	"slices"
	"strings"
	"sync"
	"unicode/utf8"
)

const defaultBotID = "abu"
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

type Bots struct {
	mu      sync.RWMutex
	path    string
	records []storedBot
	handler http.Handler
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
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/bots", s.list)
	mux.HandleFunc("POST /api/bots", s.write)
	mux.HandleFunc("PUT /api/bots/{id}", s.write)
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
	for _, record := range s.records {
		if record.ID == id {
			return record.Bot, true
		}
	}
	return Bot{}, false
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

func (s *Bots) save(b Bot, creating bool) (Bot, int, string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	i := slices.IndexFunc(s.records, func(row storedBot) bool { return row.ID == b.ID })
	next := slices.Clone(s.records)
	status := 200
	if creating {
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
