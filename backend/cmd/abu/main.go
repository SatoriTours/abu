// Command abu runs a loopback-only development server.
package main

import (
	"context"
	"errors"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"abu.local/backend/internal/httpapi"
	"abu.local/backend/internal/model"
)

func main() {
	modelEnv := flag.String("model-env", "", "private model configuration file")
	flag.Parse()
	cfg, enabled, err := model.LoadConfig(*modelEnv)
	if err != nil {
		log.Fatal(err)
	}
	var provider model.Completer
	if enabled {
		provider, err = model.NewOpenAI(cfg)
		if err != nil {
			log.Fatal(err)
		}
		log.Print("model configured")
	}
	handler, tasks, err := newDevHandler(os.Getenv("ABU_DEV_TOKEN"), os.Getenv("ABU_DATA_DIR"), provider)
	if err != nil {
		log.Fatal(err)
	}
	server := &http.Server{
		Addr:              "127.0.0.1:8080",
		Handler:           handler,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      65 * time.Second,
		IdleTimeout:       60 * time.Second,
	}
	if err := serve(server, tasks); err != nil {
		log.Fatal(err)
	}
}

func newDevHandler(token, dir string, provider model.Completer) (http.Handler, *httpapi.TaskAPI, error) {
	mux := http.NewServeMux()
	mux.Handle("/", httpapi.NewHandler())
	if token == "" {
		log.Print("chat, tasks, apps and bots disabled: ABU_DEV_TOKEN is not configured")
		return mux, nil, nil
	}
	if dir == "" {
		dir = "data"
	}
	bots, err := httpapi.NewBots(filepath.Join(dir, "bots.json"), token)
	if err != nil {
		return nil, nil, err
	}
	mux.Handle("/api/bots", bots)
	mux.Handle("/api/bots/", bots)
	chat, err := httpapi.NewChatHandlerWithBots(filepath.Join(dir, "messages.json"), token, provider, bots)
	if err != nil {
		return nil, nil, err
	}
	mux.Handle("/api/chat/", chat)
	apps, err := httpapi.NewAppsHandler(dir, token)
	if err != nil {
		return nil, nil, err
	}
	generated := httpapi.NewGenerated(filepath.Join(dir, "generated.json"), token, bots)
	tasks, err := httpapi.NewTaskAPIWithGenerated(filepath.Join(dir, "tasks.json"), token, provider, model.DefaultCompletionTimeout, 90*time.Second, bots, generated)
	var taskHandler http.Handler = tasks
	if err != nil {
		log.Print("tasks disabled: task storage could not be initialized; restore the snapshot and restart")
		taskHandler = httpapi.NewUnavailableTaskHandler(token)
	}
	mux.Handle("/api/tasks", taskHandler)
	mux.Handle("/api/tasks/", taskHandler)
	// Keep the original path for the app asset allowlist; ServeMux can redirect dot segments.
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		for _, prefix := range []string{"/api/app-drafts", "/api/generated-apps"} {
			if r.URL.Path == prefix || strings.HasPrefix(r.URL.Path, prefix+"/") {
				generated.ServeHTTP(w, r)
				return
			}
		}
		if r.URL.Path == "/api/apps" || strings.HasPrefix(r.URL.Path, "/api/apps/") {
			apps.ServeHTTP(w, r)
			return
		}
		mux.ServeHTTP(w, r)
	}), tasks, nil
}

func serve(server *http.Server, tasks *httpapi.TaskAPI) error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	serverDone := make(chan error, 1)
	go func() {
		log.Printf("development server listening on http://%s", server.Addr)
		serverDone <- server.ListenAndServe()
	}()

	var runErr error
	select {
	case <-ctx.Done():
		stop() // A second signal can force exit.
	case runErr = <-serverDone:
		if errors.Is(runErr, http.ErrServerClosed) {
			runErr = nil
		}
	}

	if tasks != nil {
		taskCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		runErr = errors.Join(runErr, tasks.Shutdown(taskCtx))
		cancel()
	}
	httpCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	return errors.Join(runErr, server.Shutdown(httpCtx))
}
