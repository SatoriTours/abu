package httpapi

import (
	"encoding/json"
	"net/http"
	"slices"
)

type genData struct {
	Revision    int64           `json:"revision"`
	Document    json.RawMessage `json:"document"`
	MutationID  string          `json:"mutationId,omitempty"`
	Fingerprint string          `json:"fingerprint,omitempty"`
}

type genUpdate struct {
	MutationID   string          `json:"mutationId"`
	BaseRevision int64           `json:"baseRevision"`
	Document     json.RawMessage `json:"document"`
}

func (d genData) valid() bool {
	if !validGeneratedDocument(d.Document) || d.Revision < 0 || d.Revision > todoMaxRevision {
		return false
	}
	if d.Revision == 0 {
		return string(d.Document) == "{}" && d.MutationID == "" && d.Fingerprint == ""
	}
	return taskIDPattern.MatchString(d.MutationID) && validCreationHash(d.Fingerprint)
}

func (d genData) respond(w http.ResponseWriter) {
	writeJSON(w, 200, struct {
		Revision int64           `json:"revision"`
		Document json.RawMessage `json:"document"`
	}{d.Revision, d.Document})
}

func canonicalGeneratedDocument(raw json.RawMessage) json.RawMessage {
	if !validGeneratedDocument(raw) {
		return nil
	}
	doc, err := json.Marshal(raw)
	if err != nil || len(doc) > genFileLimit {
		return nil
	}
	return doc
}

func (g *Generated) dataRoute(w http.ResponseWriter, r *http.Request, app string) {
	scope, ok := g.tokens.verifyToken(bearerAppToken(r.Header.Get("Authorization")))
	if !ok {
		apiError(w, 401, "invalid_app_token")
		return
	}
	if !genPathOK(r) {
		genMissing(w)
		return
	}
	if scope != app {
		apiError(w, 403, "app_scope_mismatch")
		return
	}

	var in genUpdate
	fingerprint := ""
	switch r.Method {
	case "GET":
	case "PUT":
		raw, ok := genBody(w, r)
		if !ok {
			return
		}
		if exactTodoObject(raw, "mutationId", "baseRevision", "document") == nil || json.Unmarshal(raw, &in) != nil ||
			!taskIDPattern.MatchString(in.MutationID) || in.BaseRevision < 0 || in.BaseRevision >= todoMaxRevision {
			genBad(w)
			return
		}
		in.Document = canonicalGeneratedDocument(in.Document)
		if in.Document == nil {
			genBad(w)
			return
		}
		fingerprint = shaHex(raw)
	default:
		genMissing(w)
		return
	}

	g.mu.Lock()
	defer g.mu.Unlock()
	if g.broken {
		genUnavailable(w)
		return
	}
	i := g.state.app(app)
	if i < 0 {
		genMissing(w)
		return
	}
	a := g.state.Apps[i]
	if r.Method == "GET" {
		a.Data.respond(w)
		return
	}
	if a.Data.MutationID == in.MutationID {
		if a.Data.Fingerprint == fingerprint {
			a.Data.respond(w)
		} else {
			apiError(w, 409, "mutation_conflict")
		}
		return
	}
	if a.Data.Revision != in.BaseRevision {
		apiError(w, 409, "revision_conflict")
		return
	}
	a.Data = genData{a.Data.Revision + 1, in.Document, in.MutationID, fingerprint}
	next := g.state
	next.Apps = slices.Clone(next.Apps)
	next.Apps[i] = a
	if code := g.save(next); code != "" {
		genSaveError(w, code)
		return
	}
	a.Data.respond(w)
}
