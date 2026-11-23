package httpapi

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"os"
	"slices"
	"strings"
	"sync"
	"time"

	"abu.local/backend/internal/model"
)

type genJob struct {
	ID            string           `json:"id"`
	BotID         string           `json:"botId"`
	AppID         string           `json:"appId"`
	Kind          string           `json:"kind"`
	Name          string           `json:"name,omitempty"`
	Request       string           `json:"request"`
	BaseVersion   int              `json:"baseVersion,omitempty"`
	BaseRevision  int64            `json:"baseRevision,omitempty"`
	Instructions  string           `json:"instructions,omitempty"`
	InputDocument json.RawMessage  `json:"inputDocument,omitempty"`
	Status        string           `json:"status"`
	Error         string           `json:"error,omitempty"`
	Bundle        *generatedBundle `json:"bundle,omitempty"`
	Proposal      json.RawMessage  `json:"proposal,omitempty"`
	Publication   *genPublication  `json:"publication,omitempty"`
}

type genProposal struct {
	BaseRevision int64           `json:"baseRevision"`
	Document     json.RawMessage `json:"document"`
}

type generatedJobView struct {
	ID               string             `json:"id"`
	BotID            string             `json:"botId"`
	AppID            string             `json:"appId"`
	Kind             string             `json:"kind"`
	Name             string             `json:"name,omitempty"`
	Request          string             `json:"request"`
	BaseVersion      int                `json:"baseVersion"`
	Status           string             `json:"status"`
	Error            string             `json:"error,omitempty"`
	Hash             string             `json:"hash,omitempty"`
	Manifest         *generatedManifest `json:"manifest,omitempty"`
	Proposal         *genProposal       `json:"proposal,omitempty"`
	PublishedVersion int                `json:"publishedVersion,omitempty"`
}

func (j genJob) view() generatedJobView {
	v := generatedJobView{ID: j.ID, BotID: j.BotID, AppID: j.AppID, Kind: j.Kind, Name: j.Name, Request: j.Request, BaseVersion: j.BaseVersion, Status: j.Status, Error: j.Error}
	if j.Bundle != nil {
		m := j.Bundle.manifest(j.AppID, 0)
		v.Hash, v.Manifest = m.Hash, &m
	}
	if j.Proposal != nil {
		v.Proposal = &genProposal{j.BaseRevision, j.Proposal}
	}
	if j.Publication != nil {
		v.PublishedVersion = j.Publication.Result.Version
	}
	return v
}

type genApp struct {
	ID         string         `json:"id"`
	Active     int            `json:"active"`
	Revision   int64          `json:"revision"` // resource activation epoch (not data revision)
	Versions   []string       `json:"versions"` // links into immutable ready draft results
	Data       genData        `json:"data"`
	Activation *genActivation `json:"activation,omitempty"`
}

type genState struct {
	Apps []genApp `json:"apps"`
	Jobs []genJob `json:"jobs"`
}

func (s genState) job(id string) int {
	return slices.IndexFunc(s.Jobs, func(j genJob) bool { return j.ID == id })
}
func (s genState) app(id string) int {
	return slices.IndexFunc(s.Apps, func(a genApp) bool { return a.ID == id })
}
func (s genState) version(app genApp, v int) *genJob {
	if v < 1 || v > len(app.Versions) {
		return nil
	}
	i := s.job(app.Versions[v-1])
	if i < 0 {
		return nil
	}
	return &s.Jobs[i]
}

type Generated struct {
	mu       sync.Mutex
	path     string
	bots     *Bots
	state    genState
	broken   bool
	worker   *TaskAPI
	activeID string
	cancel   context.CancelFunc
	tokens   appsAPI // independent signing key from the legacy Todo sessions
	dev      http.Handler
}

func NewGenerated(path, token string, bots *Bots) *Generated {
	g := &Generated{path: path, bots: bots, tokens: appsAPI{now: time.Now}, state: genState{[]genApp{}, []genJob{}}}
	_, err := rand.Read(g.tokens.key[:])
	if err != nil || !g.load() {
		g.fail()
	}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/app-drafts", g.jobs)
	mux.HandleFunc("POST /api/app-drafts", g.createJob)
	mux.HandleFunc("GET /api/app-drafts/{id}", g.job)
	mux.HandleFunc("POST /api/app-drafts/{id}/cancel", g.cancelJob)
	mux.HandleFunc("GET /api/app-drafts/{id}/assets/{asset}", g.draftAsset)
	mux.HandleFunc("POST /api/app-drafts/{id}/publish", g.publish)
	mux.HandleFunc("GET /api/generated-apps", g.catalog)
	mux.HandleFunc("GET /api/generated-apps/{app}/versions/{version}", g.versionResource)
	mux.HandleFunc("GET /api/generated-apps/{app}/versions/{version}/assets/{asset}", g.versionResource)
	mux.HandleFunc("PUT /api/generated-apps/{app}/active", g.activate)
	mux.HandleFunc("POST /api/generated-apps/{app}/proposals", g.createJob)
	mux.HandleFunc("POST /api/generated-apps/{app}/session", g.session)
	g.dev = requireDevToken(token, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !genPathOK(r) {
			genMissing(w)
			return
		}
		mux.ServeHTTP(w, r)
	}))
	return g
}

func (g *Generated) load() bool {
	info, err := os.Lstat(g.path)
	if errors.Is(err, os.ErrNotExist) {
		return true
	}
	if err != nil || !info.Mode().IsRegular() || info.Size() > genStoreLimit {
		return false
	}
	f, err := os.Open(g.path)
	if err != nil {
		return false
	}
	defer f.Close()
	raw, err := io.ReadAll(io.LimitReader(f, genStoreLimit+1))
	var s genState
	if err != nil || len(raw) > genStoreLimit || !validRoleJSON(raw) || json.Unmarshal(raw, &s) != nil {
		return false
	}
	canonical, err := json.Marshal(s)
	// The snapshot is written by this process only. Reject unknown/duplicate or noncanonical fields.
	if err != nil || !bytes.Equal(raw, append(canonical, '\n')) || !g.validState(s) {
		return false
	}
	g.state = s
	changed := false
	for i, j := range s.Jobs {
		if j.Status == "running" {
			s.Jobs[i].Status, s.Jobs[i].Error = "interrupted", "interrupted"
			changed = true
		}
	}
	return !changed || g.save(s) == ""
}

func (g *Generated) validState(s genState) bool {
	if s.Apps == nil || len(s.Apps) > genMaxApps || s.Jobs == nil || len(s.Jobs) > genMaxJobs {
		return false
	}
	for i, j := range s.Jobs {
		if s.job(j.ID) != i || !taskIDPattern.MatchString(j.ID) || !generatedID.MatchString(j.AppID) ||
			!validTaskText(j.Request) || !validBotInstructions(j.Instructions) {
			return false
		}
		if _, ok := g.bots.lookup(j.BotID); !ok {
			return false
		}
		switch j.Kind {
		case "draft":
			if !validGenName(j.Name) || j.BaseVersion < 0 || j.BaseVersion > genMaxVersions ||
				(j.BaseVersion > 0 && (s.app(j.AppID) < 0 || s.version(s.Apps[s.app(j.AppID)], j.BaseVersion) == nil)) ||
				j.InputDocument != nil || j.Proposal != nil {
				return false
			}
		case "proposal":
			if s.app(j.AppID) < 0 || !validGeneratedDocument(j.InputDocument) || j.BaseRevision < 0 ||
				j.BaseRevision > todoMaxRevision || j.Bundle != nil || j.Publication != nil {
				return false
			}
		default:
			return false
		}
		switch j.Status {
		case "queued", "running", "cancelled":
			if j.Error != "" || j.Bundle != nil || j.Proposal != nil || j.Publication != nil {
				return false
			}
		case "failed", "interrupted":
			if j.Error == "" || j.Bundle != nil || j.Proposal != nil || j.Publication != nil {
				return false
			}
		case "ready":
			if j.Error != "" || (j.Kind == "draft" && (j.Bundle == nil || !j.Bundle.valid())) ||
				(j.Kind == "proposal" && !validGeneratedDocument(j.Proposal)) {
				return false
			}
		default:
			return false
		}
		if j.Publication != nil {
			idx := s.app(j.AppID)
			if idx < 0 || j.Bundle == nil || j.Publication.Hash != j.Bundle.manifest(j.AppID, 0).Hash {
				return false
			}
			v := j.Publication.Result.Version
			if v < 1 || s.version(s.Apps[idx], v) == nil || s.Apps[idx].Versions[v-1] != j.ID {
				return false
			}
		}
	}
	for i, a := range s.Apps {
		if s.app(a.ID) != i || !generatedID.MatchString(a.ID) || a.Revision < 1 || a.Revision > todoMaxRevision ||
			len(a.Versions) > genMaxVersions || a.Active < 1 || a.Active > len(a.Versions) || !a.Data.valid() {
			return false
		}
		if a.Activation != nil {
			r := a.Activation.Result
			if !taskIDPattern.MatchString(a.Activation.ID) || r.AppID != a.ID || r.Version != a.Activation.Version ||
				s.version(a, r.Version) == nil || r.Revision != a.Activation.ExpectedRevision+1 || r.Revision > a.Revision {
				return false
			}
		}
		for v := range a.Versions {
			j := s.version(a, v+1)
			if j == nil || j.AppID != a.ID || j.Status != "ready" || j.Bundle == nil || j.Publication == nil ||
				j.Publication.Result.Version != v+1 || j.Publication.Result.AppID != a.ID ||
				j.Publication.ExpectedVersion != j.BaseVersion ||
				j.Publication.Result.Revision != j.Publication.ExpectedRevision+1 || j.Publication.Result.Revision > a.Revision {
				return false
			}
		}
	}
	return true
}

func (g *Generated) save(s genState) string {
	raw, err := json.Marshal(s)
	if err != nil {
		g.fail()
		return "storage_unavailable"
	}
	// Reserve the few bytes needed for every queued/running job to reach a terminal state,
	// even when a generated result does not fit on disk.
	pending := 0
	for _, j := range s.Jobs {
		if j.Status == "queued" || j.Status == "running" {
			pending++
		}
	}
	if len(raw)+1+pending*128 > genStoreLimit {
		return "generated_storage_limit"
	}
	// Persist the result and ready status together, before exposing either.
	if saveJSONSnapshot(g.path, ".generated-*", json.RawMessage(raw)) != nil {
		g.fail()
		return "storage_unavailable"
	}
	g.state = s
	return ""
}

func (g *Generated) fail() {
	g.broken = true
	if g.cancel != nil {
		g.cancel()
	}
	log.Print("generated application storage unavailable; existing chat, tasks and built-in apps are unaffected")
}

func (g *Generated) setJob(index int, j genJob) string {
	next := g.state
	next.Jobs = slices.Clone(next.Jobs)
	next.Jobs[index] = j
	return g.save(next)
}

// runNext is called by the existing worker, never by a second worker or an HTTP request.
func (g *Generated) runNext(parent context.Context, provider model.Completer, timeout, draftTimeout time.Duration) bool {
	if g == nil || parent.Err() != nil {
		return false
	}
	g.mu.Lock()
	if g.broken {
		g.mu.Unlock()
		return false
	}
	i := slices.IndexFunc(g.state.Jobs, func(j genJob) bool { return j.Status == "queued" })
	if i < 0 {
		g.mu.Unlock()
		return false
	}
	j := g.state.Jobs[i]
	j.Status = "running"
	if g.setJob(i, j) != "" {
		g.mu.Unlock()
		return false
	}
	var base *generatedBundle
	if j.BaseVersion > 0 {
		base = g.state.version(g.state.Apps[g.state.app(j.AppID)], j.BaseVersion).Bundle
	}
	if j.Kind == "draft" {
		timeout = draftTimeout
	}
	ctx, cancel := context.WithTimeout(parent, timeout)
	g.activeID, g.cancel = j.ID, cancel
	g.mu.Unlock()
	defer cancel()

	var output string
	err := ctx.Err()
	if err == nil {
		output, err = provider.Complete(ctx, genPrompt(j, base))
	}
	timeoutHit := errors.Is(ctx.Err(), context.DeadlineExceeded) || errors.Is(err, context.DeadlineExceeded)
	j.Status = "failed"
	switch {
	case timeoutHit:
		j.Error = "model_timeout"
	case err != nil:
		j.Error = "model_failed"
	case j.Kind == "draft":
		if result, ok := parseGeneratedOutput(output); ok {
			j.Status, j.Bundle = "ready", &result
		} else {
			j.Error = "invalid_generated_output"
		}
	default:
		fields := map[string]json.RawMessage(nil)
		if len(output) <= genModelOutputLimit && validRoleJSON([]byte(output)) {
			fields = exactTodoObject([]byte(output), "document")
		}
		if doc := canonicalGeneratedDocument(fields["document"]); doc != nil {
			j.Status, j.Proposal = "ready", doc
		} else {
			j.Error = "invalid_generated_output"
		}
	}

	g.mu.Lock()
	defer g.mu.Unlock()
	g.activeID, g.cancel = "", nil
	if g.broken || g.state.Jobs[i].Status != "running" {
		return true
	}
	if parent.Err() != nil {
		j.Status, j.Error, j.Bundle, j.Proposal = "interrupted", "interrupted", nil, nil
	}
	if g.setJob(i, j) == "generated_storage_limit" {
		j.Status, j.Error, j.Bundle, j.Proposal = "failed", "generated_storage_limit", nil, nil
		if g.setJob(i, j) != "" {
			g.fail()
		}
	}
	return true
}

func (g *Generated) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	const prefix = "/api/generated-apps/"
	if tail, ok := strings.CutPrefix(r.URL.Path, prefix); ok {
		p := strings.Split(tail, "/")
		if len(p) == 2 && p[1] == "data" {
			g.dataRoute(w, r, p[0])
			return
		}
	}
	g.dev.ServeHTTP(w, r)
}

func genBad(w http.ResponseWriter)         { apiError(w, 400, "invalid_generated_request") }
func genUnavailable(w http.ResponseWriter) { apiError(w, 503, "storage_unavailable") }
func genMissing(w http.ResponseWriter)     { apiError(w, 404, "generated_not_found") }
func genSaveError(w http.ResponseWriter, code string) {
	if code == "generated_storage_limit" {
		apiError(w, 409, code)
	} else {
		genUnavailable(w)
	}
}

func genPathOK(r *http.Request) bool {
	return canonicalAppRequest(r) && !strings.Contains(r.URL.Path, "//") &&
		!slices.Contains(strings.Split(r.URL.Path, "/"), ".") && !slices.Contains(strings.Split(r.URL.Path, "/"), "..")
}
