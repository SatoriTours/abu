// Package httpapi defines the initial local-development HTTP surface.
package httpapi

import "net/http"

// NewHandler exposes liveness only; no product APIs are implemented yet.
func NewHandler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte("{\"status\":\"ok\"}\n"))
	})
	return mux
}
