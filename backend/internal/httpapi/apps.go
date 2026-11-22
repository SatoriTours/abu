package httpapi

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"io"
	"net/http"
	"path/filepath"
	"strings"
	"time"
	"unicode/utf8"
)

const appSessionTTL = 15 * time.Minute

type registeredApp struct {
	versions map[string]appBundle
	data     *todoStore
}

type appsAPI struct {
	apps    map[string]registeredApp
	catalog []appCatalogEntry
	key     [32]byte
	now     func() time.Time
	dev     http.Handler
}

func NewAppsHandler(dir, devToken string) (http.Handler, error) {
	return newAppsHandler(dir, devToken, builtinApps(), time.Now)
}

func newAppsHandler(dir, devToken string, registry []appDefinition, now func() time.Time) (http.Handler, error) {
	if len(devToken) < 32 {
		return nil, errors.New("ABU_DEV_TOKEN must contain at least 32 bytes")
	}
	if len(registry) > 16 {
		return nil, errors.New("too many application registrations")
	}
	s := &appsAPI{apps: make(map[string]registeredApp), catalog: make([]appCatalogEntry, 0, len(registry)), now: now}
	if _, err := rand.Read(s.key[:]); err != nil {
		return nil, errors.New("cannot initialize application session key")
	}
	files := make(map[string]bool)
	for _, d := range registry {
		if !appNamePattern.MatchString(d.ID) || strings.TrimSpace(d.Name) == "" ||
			!utf8.ValidString(d.Name) || utf8.RuneCountInString(d.Name) > 80 ||
			s.apps[d.ID].versions != nil || len(d.Versions) > 16 ||
			filepath.Base(d.File) != d.File || !strings.HasSuffix(d.File, ".json") ||
			d.File == "messages.json" || d.File == "tasks.json" || files[d.File] {
			return nil, errors.New("invalid application registration")
		}
		files[d.File] = true
		registered := registeredApp{versions: make(map[string]appBundle)}
		entry := appCatalogEntry{ID: d.ID, Name: d.Name, CurrentVersion: d.Current}
		for _, v := range d.Versions {
			bundle, err := compileAppVersion(d.ID, v)
			if err != nil || registered.versions[v.ID].Assets != nil {
				return nil, errors.New("invalid application resource registration")
			}
			registered.versions[v.ID] = bundle
			entry.Versions = append(entry.Versions, v.ID)
		}
		if registered.versions[d.Current].Assets == nil {
			return nil, errors.New("missing current application version")
		}
		registered.data = openTodoStore(filepath.Join(dir, d.File))
		s.apps[d.ID] = registered
		s.catalog = append(s.catalog, entry)
	}
	s.dev = requireDevToken(devToken, http.HandlerFunc(s.developerRoute))
	return s, nil
}

func (s *appsAPI) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	// Handle paths ourselves: ServeMux would redirect and normalize dot segments.
	route := strings.Split(strings.TrimPrefix(r.URL.Path, "/api/apps/"), "/")
	if len(route) == 2 && route[1] == "data" {
		s.dataRoute(w, r, route[0])
		return
	}
	s.dev.ServeHTTP(w, r)
}

func canonicalAppRequest(r *http.Request) bool {
	return !strings.Contains(r.URL.EscapedPath(), "%") && r.URL.RawQuery == "" && !r.URL.ForceQuery
}

func appError(w http.ResponseWriter, status int, code string) {
	writeJSON(w, status, map[string]string{"error": code})
}

func appNotFound(w http.ResponseWriter) {
	appError(w, http.StatusNotFound, "app_not_found")
}

func (s *appsAPI) developerRoute(w http.ResponseWriter, r *http.Request) {
	if !canonicalAppRequest(r) {
		appNotFound(w)
		return
	}
	if r.URL.Path == "/api/apps" && r.Method == http.MethodGet {
		writeJSON(w, 200, struct {
			Apps []appCatalogEntry `json:"apps"`
		}{s.catalog})
		return
	}
	suffix, ok := strings.CutPrefix(r.URL.Path, "/api/apps/")
	if !ok {
		appNotFound(w)
		return
	}
	p := strings.SplitN(suffix, "/", 5)
	app, known := s.apps[p[0]]
	if !known {
		appNotFound(w)
		return
	}
	switch {
	case r.Method == http.MethodPost && len(p) == 2 && p[1] == "session":
		body, ok := readAppBody(w, r)
		if !ok {
			return
		}
		if len(body) != 0 {
			appError(w, 400, "invalid_data")
			return
		}
		expires := s.now().UTC().Add(appSessionTTL).Truncate(time.Second)
		writeJSON(w, 200, struct {
			AppToken  string    `json:"appToken"`
			ExpiresAt time.Time `json:"expiresAt"`
		}{s.signToken(p[0], expires), expires})
	case r.Method == http.MethodGet && len(p) >= 3 && p[1] == "versions":
		b, found := app.versions[p[2]]
		if found && len(p) == 3 {
			writeJSON(w, 200, b.Manifest)
			return
		}
		if found && len(p) == 5 && p[3] == "assets" {
			if asset, ok := b.Assets[p[4]]; ok {
				w.Header().Set("Content-Type", asset.Mime)
				_, _ = w.Write(asset.Bytes)
				return
			}
		}
		appNotFound(w)
	default:
		appNotFound(w)
	}
}

func bearerAppToken(header string) string {
	token, _ := strings.CutPrefix(header, "Bearer ")
	return token
}

func (s *appsAPI) signToken(id string, expiry time.Time) string {
	payload := binary.BigEndian.AppendUint64(nil, uint64(expiry.Unix()))
	payload = append(payload, id...)
	return base64.RawURLEncoding.EncodeToString(append(payload, s.signature(payload)...))
}

func (s *appsAPI) signature(payload []byte) []byte {
	mac := hmac.New(sha256.New, s.key[:])
	_, _ = mac.Write(payload)
	return mac.Sum(nil)
}

func (s *appsAPI) verifyToken(token string) (string, bool) {
	if len(token) > 140 {
		return "", false
	}
	raw, err := base64.RawURLEncoding.Strict().DecodeString(token)
	if err != nil || len(raw) <= 8+sha256.Size || base64.RawURLEncoding.EncodeToString(raw) != token {
		return "", false
	}
	payload, signature := raw[:len(raw)-sha256.Size], raw[len(raw)-sha256.Size:]
	if !hmac.Equal(signature, s.signature(payload)) || s.now().Unix() >= int64(binary.BigEndian.Uint64(payload[:8])) {
		return "", false
	}
	return string(payload[8:]), true
}

func readAppBody(w http.ResponseWriter, r *http.Request) ([]byte, bool) {
	raw, err := io.ReadAll(http.MaxBytesReader(w, r.Body, todoBodyLimit))
	if err == nil {
		return raw, true
	}
	var large *http.MaxBytesError
	if errors.As(err, &large) {
		appError(w, 413, "body_too_large")
	} else {
		appError(w, 400, "invalid_data")
	}
	return nil, false
}

func (s *appsAPI) dataRoute(w http.ResponseWriter, r *http.Request, id string) {
	scope, ok := s.verifyToken(bearerAppToken(r.Header.Get("Authorization")))
	if !ok {
		appError(w, 401, "invalid_app_token")
		return
	}
	app, known := s.apps[id]
	if !known || !canonicalAppRequest(r) {
		appNotFound(w)
		return
	}
	if scope != id {
		appError(w, 403, "app_scope_mismatch")
		return
	}
	switch r.Method {
	case http.MethodGet:
		app.data.respond(w, nil)
	case http.MethodPut:
		raw, ok := readAppBody(w, r)
		if !ok {
			return
		}
		update, ok := parseTodoUpdate(raw)
		if !ok {
			appError(w, 400, "invalid_data")
			return
		}
		app.data.respond(w, &update)
	default:
		appNotFound(w)
	}
}
