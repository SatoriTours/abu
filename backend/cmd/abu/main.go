// Command abu runs the local development server.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"abu.local/backend/internal/httpapi"
	"abu.local/backend/internal/model"
)

func main() {
	modelEnv := flag.String("model-env", "", "private model configuration file")
	listen := flag.String("listen", "", "listen address, e.g. 127.0.0.1:8080 (default: ABU_LISTEN_ADDR or 127.0.0.1:8080)")
	flag.Parse()
	// Refuse an unusable address before any store is opened, so a typo cannot look like a
	// running service on a different interface.
	addr, err := listenAddress(*listen)
	if err != nil {
		log.Fatal(err)
	}
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
		Addr:              addr,
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

// defaultListenAddr keeps the development server on the loopback interface unless the operator
// asks for another address.
const defaultListenAddr = "127.0.0.1:8080"

// listenAddress resolves the listen address from the flag, then ABU_LISTEN_ADDR, then the
// development default. Anything that is not a valid host:port is refused.
func listenAddress(flagValue string) (string, error) {
	addr := strings.TrimSpace(flagValue)
	if addr == "" {
		addr = strings.TrimSpace(os.Getenv("ABU_LISTEN_ADDR"))
	}
	if addr == "" {
		addr = defaultListenAddr
	}
	_, port, err := net.SplitHostPort(addr)
	if err != nil {
		return "", fmt.Errorf("invalid listen address %q: %w", addr, err)
	}
	number, convErr := strconv.Atoi(port)
	if convErr != nil || number < 1 || number > 65535 {
		return "", fmt.Errorf("invalid listen address %q: port must be 1-65535", addr)
	}
	return addr, nil
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
		bots.DisableDelete()
		// The unloadable store has no delete hook, so a role delete would silently skip the
		// tasks it owns and could overwrite a pending intent. Refuse deletes until a healthy
		// restart loads every store.
	}
	// Roll an interrupted role deletion forward now that every child store has loaded. With the
	// task store unavailable the intent stays on disk for the next healthy start, so no child
	// snapshot is left pointing at a removed role.
	if err == nil {
		if err := bots.RecoverPendingDelete(); err != nil {
			return nil, nil, err
		}
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
