package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"slices"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

func genBody(w http.ResponseWriter, r *http.Request) ([]byte, bool) {
	raw, err := io.ReadAll(http.MaxBytesReader(w, r.Body, genBodyLimit))
	if err == nil && validRoleJSON(raw) {
		return raw, true
	}
	var excess *http.MaxBytesError
	if errors.As(err, &excess) {
		apiError(w, 413, "body_too_large")
	} else {
		genBad(w)
	}
	return nil, false
}

func genDecode[T any](w http.ResponseWriter, r *http.Request, dest *T, keys ...string) bool {
	raw, ok := genBody(w, r)
	if !ok {
		return false
	}
	if exactTodoObject(raw, keys...) == nil || json.Unmarshal(raw, dest) != nil {
		genBad(w)
		return false
	}
	return true
}

func validGenName(name string) bool {
	return strings.TrimSpace(name) != "" && utf8.ValidString(name) && utf8.RuneCountInString(name) <= 80
}

func (g *Generated) createJob(w http.ResponseWriter, r *http.Request) {
	var j genJob
	if app := r.PathValue("app"); app != "" {
		j.Kind, j.AppID = "proposal", app
		if !genDecode(w, r, &j, "id", "botId", "request", "baseRevision") {
			return
		}
	} else {
		j.Kind = "draft"
		if !genDecode(w, r, &j, "id", "botId", "appId", "name", "request", "baseVersion") {
			return
		}
	}
	if !taskIDPattern.MatchString(j.ID) || !generatedID.MatchString(j.AppID) || !validTaskText(j.Request) ||
		(j.Kind == "draft" && (!validGenName(j.Name) || j.BaseVersion < 0 || j.BaseVersion > genMaxVersions)) ||
		j.BaseRevision < 0 || j.BaseRevision > todoMaxRevision {
		genBad(w)
		return
	}
	bot, ok := g.bots.resolve(w, j.BotID)
	if !ok {
		return
	}
	j.Instructions, j.Status = bot.Instructions, "queued"
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.broken {
		genUnavailable(w)
		return
	}
	if i := g.state.job(j.ID); i >= 0 {
		old := g.state.Jobs[i]
		if old.Kind != j.Kind || old.BotID != j.BotID || old.AppID != j.AppID || old.Request != j.Request ||
			old.Name != j.Name || old.BaseVersion != j.BaseVersion || old.BaseRevision != j.BaseRevision {
			apiError(w, 409, "id_conflict")
			return
		}
		writeJSON(w, 200, old.view())
		return
	}
	if g.worker == nil || g.worker.workerCtx.Err() != nil || g.worker.provider == nil {
		apiError(w, 503, "generation_unavailable")
		return
	}
	if len(g.state.Jobs) >= genMaxJobs {
		apiError(w, 409, "generated_job_limit")
		return
	}
	idx := g.state.app(j.AppID)
	switch {
	case j.Kind == "proposal":
		if idx < 0 {
			genMissing(w)
			return
		}
		record := g.state.Apps[idx].Data
		if record.Revision != j.BaseRevision {
			apiError(w, 409, "revision_conflict")
			return
		}
		j.InputDocument = record.Document
	case j.BaseVersion == 0:
		if idx >= 0 {
			apiError(w, 409, "version_conflict")
			return
		}
	case idx < 0 || g.state.version(g.state.Apps[idx], j.BaseVersion) == nil:
		genMissing(w)
		return
	}
	next := g.state
	next.Jobs = append(slices.Clone(next.Jobs), j)
	if code := g.save(next); code != "" {
		genSaveError(w, code)
		return
	}
	g.worker.notify()
	writeJSON(w, 201, j.view())
}

func (g *Generated) jobs(w http.ResponseWriter, _ *http.Request) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.broken {
		genUnavailable(w)
		return
	}
	// Omit output and bodies from the list. Fetch an individual job to review it.
	type summary struct {
		ID     string `json:"id"`
		BotID  string `json:"botId"`
		AppID  string `json:"appId"`
		Kind   string `json:"kind"`
		Status string `json:"status"`
		Name   string `json:"name,omitempty"`
	}
	out := make([]summary, 0, len(g.state.Jobs))
	for _, j := range g.state.Jobs {
		out = append(out, summary{j.ID, j.BotID, j.AppID, j.Kind, j.Status, j.Name})
	}
	writeJSON(w, 200, map[string]any{"jobs": out, "modelConfigured": g.worker != nil && g.worker.provider != nil && g.worker.workerCtx.Err() == nil})
}

func (g *Generated) job(w http.ResponseWriter, r *http.Request) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.broken {
		genUnavailable(w)
		return
	}
	if i := g.state.job(r.PathValue("id")); i >= 0 {
		writeJSON(w, 200, g.state.Jobs[i].view())
		return
	}
	genMissing(w)
}

func genEmpty(w http.ResponseWriter, r *http.Request) bool {
	body, ok := genBody(w, r)
	if ok && len(body) != 0 {
		genBad(w)
		return false
	}
	return ok
}

func (g *Generated) cancelJob(w http.ResponseWriter, r *http.Request) {
	if !genEmpty(w, r) {
		return
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.broken {
		genUnavailable(w)
		return
	}
	i := g.state.job(r.PathValue("id"))
	if i < 0 {
		genMissing(w)
		return
	}
	j := g.state.Jobs[i]
	if j.Status == "running" || j.Status == "queued" {
		j.Status = "cancelled"
		if code := g.setJob(i, j); code != "" {
			genSaveError(w, code)
			return
		}
		if g.activeID == j.ID && g.cancel != nil {
			g.cancel()
		}
	}
	writeJSON(w, 200, j.view())
}

type genPublicationInput struct {
	Hash             string `json:"hash"`
	ExpectedVersion  int    `json:"expectedVersion"`
	ExpectedRevision int64  `json:"expectedRevision"`
}
type genPublicationResult struct {
	AppID    string `json:"appId"`
	Version  int    `json:"version"`
	Revision int64  `json:"revision"`
}
type genPublication struct {
	genPublicationInput
	Result genPublicationResult `json:"result"`
}

func validGenExpectation(version int, revision int64) bool {
	return version >= 0 && version <= genMaxVersions && revision >= 0 && revision < todoMaxRevision
}

func (g *Generated) publish(w http.ResponseWriter, r *http.Request) {
	var in genPublicationInput
	if !genDecode(w, r, &in, "hash", "expectedVersion", "expectedRevision") {
		return
	}
	if !validCreationHash(in.Hash) || !validGenExpectation(in.ExpectedVersion, in.ExpectedRevision) {
		genBad(w)
		return
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.broken {
		genUnavailable(w)
		return
	}
	i := g.state.job(r.PathValue("id"))
	if i < 0 {
		genMissing(w)
		return
	}
	j := g.state.Jobs[i]
	if j.Status != "ready" || j.Bundle == nil {
		apiError(w, 409, "draft_not_ready")
		return
	}
	if j.Publication != nil {
		if in != j.Publication.genPublicationInput {
			apiError(w, 409, "publication_conflict")
			return
		}
		writeJSON(w, 200, j.Publication.Result)
		return
	}
	if in.Hash != j.Bundle.manifest(j.AppID, 0).Hash {
		apiError(w, 409, "draft_hash_conflict")
		return
	}
	path := g.state.app(j.AppID)
	a := genApp{ID: j.AppID, Data: genData{Document: json.RawMessage("{}")}}
	if path >= 0 {
		a = g.state.Apps[path]
	}
	if a.Active != in.ExpectedVersion || a.Revision != in.ExpectedRevision ||
		j.BaseVersion != in.ExpectedVersion {
		apiError(w, 409, "version_conflict")
		return
	}
	if len(a.Versions) >= genMaxVersions {
		apiError(w, 409, "version_limit")
		return
	}
	if path < 0 && len(g.state.Apps) >= genMaxApps {
		apiError(w, 409, "generated_app_limit")
		return
	}
	a.Versions = append(slices.Clone(a.Versions), j.ID)
	a.Active = len(a.Versions)
	a.Revision++
	receipt := genPublicationResult{a.ID, a.Active, a.Revision}
	j.Publication = &genPublication{in, receipt}
	next := g.state
	next.Apps = slices.Clone(next.Apps)
	next.Jobs = slices.Clone(next.Jobs)
	next.Jobs[i] = j
	if path < 0 {
		next.Apps = append(next.Apps, a)
	} else {
		next.Apps[path] = a
	}
	if code := g.save(next); code != "" {
		genSaveError(w, code)
		return
	}
	writeJSON(w, 200, receipt)
}

type genActivationInput struct {
	ID               string `json:"id"`
	Version          int    `json:"version"`
	ExpectedRevision int64  `json:"expectedRevision"`
}
type genActivation struct {
	genActivationInput
	Result genPublicationResult `json:"result"`
}

func (g *Generated) activate(w http.ResponseWriter, r *http.Request) {
	var in genActivationInput
	if !genDecode(w, r, &in, "id", "version", "expectedRevision") {
		return
	}
	if !taskIDPattern.MatchString(in.ID) || in.Version < 1 || !validGenExpectation(in.Version, in.ExpectedRevision) {
		genBad(w)
		return
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.broken {
		genUnavailable(w)
		return
	}
	i := g.state.app(r.PathValue("app"))
	if i < 0 {
		genMissing(w)
		return
	}
	a := g.state.Apps[i]
	if a.Activation != nil && a.Activation.ID == in.ID {
		if a.Activation.genActivationInput != in {
			apiError(w, 409, "id_conflict")
			return
		}
		writeJSON(w, 200, a.Activation.Result)
		return
	}
	if in.ExpectedRevision != a.Revision {
		apiError(w, 409, "version_conflict")
		return
	}
	if g.state.version(a, in.Version) == nil {
		genMissing(w)
		return
	}
	a.Active, a.Revision = in.Version, a.Revision+1
	result := genPublicationResult{a.ID, a.Active, a.Revision}
	a.Activation = &genActivation{in, result}
	next := g.state
	next.Apps = slices.Clone(next.Apps)
	next.Apps[i] = a
	if code := g.save(next); code != "" {
		genSaveError(w, code)
		return
	}
	writeJSON(w, 200, result)
}

func (g *Generated) catalog(w http.ResponseWriter, _ *http.Request) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.broken {
		genUnavailable(w)
		return
	}
	type catalogApp struct {
		ID             string `json:"id"`
		Name           string `json:"name"`
		CurrentVersion int    `json:"currentVersion"`
		Revision       int64  `json:"revision"`
		Versions       []int  `json:"versions"`
	}
	out := make([]catalogApp, 0, len(g.state.Apps))
	for _, a := range g.state.Apps {
		versions := make([]int, len(a.Versions))
		for i := range versions {
			versions[i] = i + 1
		}
		out = append(out, catalogApp{a.ID, g.state.version(a, a.Active).Name, a.Active, a.Revision, versions})
	}
	writeJSON(w, 200, map[string]any{"apps": out})
}

func (g *Generated) draftAsset(w http.ResponseWriter, r *http.Request) {
	g.mu.Lock()
	if g.broken {
		g.mu.Unlock()
		genUnavailable(w)
		return
	}
	i := g.state.job(r.PathValue("id"))
	var bundle *generatedBundle
	if i >= 0 {
		bundle = g.state.Jobs[i].Bundle
	}
	g.mu.Unlock()
	generatedResource(w, bundle, r.PathValue("asset"))
}

func (g *Generated) versionResource(w http.ResponseWriter, r *http.Request) {
	version, err := strconv.Atoi(r.PathValue("version"))
	if err != nil || strconv.Itoa(version) != r.PathValue("version") {
		genMissing(w)
		return
	}
	id := r.PathValue("app")
	g.mu.Lock()
	if g.broken {
		g.mu.Unlock()
		genUnavailable(w)
		return
	}
	i := g.state.app(id)
	var bundle *generatedBundle
	if i >= 0 {
		if j := g.state.version(g.state.Apps[i], version); j != nil {
			bundle = j.Bundle
		}
	}
	g.mu.Unlock()
	if asset := r.PathValue("asset"); asset != "" {
		generatedResource(w, bundle, asset)
		return
	}
	if bundle == nil {
		genMissing(w)
		return
	}
	writeJSON(w, 200, bundle.manifest(id, version))
}

func generatedResource(w http.ResponseWriter, bundle *generatedBundle, path string) {
	i := slices.Index(generatedFiles[:], path)
	if bundle == nil || i < 0 {
		genMissing(w)
		return
	}
	w.Header().Set("Content-Type", appAssetMime(path))
	w.Header().Set("Content-Security-Policy", "default-src 'none'; sandbox")
	_, _ = io.WriteString(w, bundle[i])
}

func (g *Generated) session(w http.ResponseWriter, r *http.Request) {
	if !genEmpty(w, r) {
		return
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.broken {
		genUnavailable(w)
		return
	}
	id := r.PathValue("app")
	if g.state.app(id) < 0 {
		genMissing(w)
		return
	}
	expires := time.Now().UTC().Add(appSessionTTL).Truncate(time.Second)
	writeJSON(w, 200, map[string]any{"appToken": g.tokens.signToken(id, expires), "expiresAt": expires})
}
