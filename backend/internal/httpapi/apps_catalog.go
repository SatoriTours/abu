package httpapi

import (
	"crypto/sha256"
	"embed"
	"encoding/hex"
	"errors"
	"path/filepath"
	"regexp"
	"strings"
)

const (
	appAssetLimit  = 256 * 1024
	appBundleLimit = 1024 * 1024
)

var (
	appNamePattern    = regexp.MustCompile(`^[a-z0-9][a-z0-9-]{0,63}$`)
	appVersionPattern = regexp.MustCompile(`^v[1-9][0-9]{0,8}$`)
	appAssetSegment   = regexp.MustCompile(`^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}$`)
)

//go:embed builtinapps
var appAssets embed.FS

type appDefinition struct {
	ID, Name, File, Current string
	Versions                []appVersion
}

type appVersion struct {
	ID, Entry string
	Assets    []appAssetSource
}

type appAssetSource struct {
	Path, Mime string
	Bytes      []byte
}

type appCatalogEntry struct {
	ID             string   `json:"id"`
	Name           string   `json:"name"`
	CurrentVersion string   `json:"currentVersion"`
	Versions       []string `json:"versions"`
}

type appManifest struct {
	SchemaVersion int                `json:"schemaVersion"`
	ID            string             `json:"id"`
	Version       string             `json:"version"`
	Entry         string             `json:"entry"`
	Capabilities  []string           `json:"capabilities"`
	Assets        []appAssetMetadata `json:"assets"`
}

type appAssetMetadata struct {
	Path   string `json:"path"`
	Mime   string `json:"mime"`
	Size   int    `json:"size"`
	SHA256 string `json:"sha256"`
}

type appBundle struct {
	Manifest appManifest
	Assets   map[string]appAssetSource
}

func builtinApps() []appDefinition {
	d := appDefinition{ID: "personal-todo", Name: "个人待办", File: "app-personal-todo.json", Current: "v3"}
	for _, version := range []string{"v1", "v2", "v3"} {
		shared := "v1"
		if version == "v3" {
			shared = version
		}
		var assets []appAssetSource
		for _, entry := range []struct{ path, mime, source string }{
			{"index.html", "text/html; charset=utf-8", version + "/index.html"},
			{"todo.js", "text/javascript; charset=utf-8", shared + "/todo.js"},
			{"todo.css", "text/css; charset=utf-8", shared + "/todo.css"},
		} {
			body, err := appAssets.ReadFile("builtinapps/personal-todo/" + entry.source)
			if err != nil {
				panic("missing embedded todo resource")
			}
			assets = append(assets, appAssetSource{entry.path, entry.mime, body})
		}
		d.Versions = append(d.Versions, appVersion{version, "index.html", assets})
	}
	return []appDefinition{d}
}

func validAppAssetPath(path string) bool {
	if len(path) > 192 {
		return false
	}
	for _, segment := range strings.Split(path, "/") {
		if !appAssetSegment.MatchString(segment) {
			return false
		}
	}
	return true
}

func appAssetMime(path string) string {
	switch filepath.Ext(path) {
	case ".html":
		return "text/html; charset=utf-8"
	case ".css":
		return "text/css; charset=utf-8"
	case ".js":
		return "text/javascript; charset=utf-8"
	case ".png":
		return "image/png"
	case ".webp":
		return "image/webp"
	}
	return ""
}

func compileAppVersion(id string, v appVersion) (appBundle, error) {
	b := appBundle{
		Manifest: appManifest{1, id, v.ID, v.Entry, []string{"todo.read", "todo.write"}, nil},
		Assets:   make(map[string]appAssetSource),
	}
	if !appVersionPattern.MatchString(v.ID) || len(v.Assets) == 0 || len(v.Assets) > 16 {
		return appBundle{}, errors.New("invalid application version")
	}
	total := 0
	for _, a := range v.Assets {
		expected := appAssetMime(a.Path)
		if !validAppAssetPath(a.Path) || expected == "" || expected != a.Mime ||
			len(a.Bytes) == 0 || len(a.Bytes) > appAssetLimit || b.Assets[a.Path].Path != "" {
			return appBundle{}, errors.New("invalid application asset")
		}
		total += len(a.Bytes)
		if total > appBundleLimit {
			return appBundle{}, errors.New("application bundle too large")
		}
		sum := sha256.Sum256(a.Bytes)
		b.Manifest.Assets = append(b.Manifest.Assets, appAssetMetadata{a.Path, a.Mime, len(a.Bytes), hex.EncodeToString(sum[:])})
		b.Assets[a.Path] = a
	}
	if b.Assets[v.Entry].Mime != "text/html; charset=utf-8" {
		return appBundle{}, errors.New("invalid application entry")
	}
	return b, nil
}
