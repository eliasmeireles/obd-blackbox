// Package app wires startup and coordinates graceful server shutdown.
package app

import (
	"context"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"os"
	"time"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/config"
	"github.com/eliasmeireles/obd-blackbox/backend/internal/httpapi"
	"github.com/eliasmeireles/obd-blackbox/backend/internal/storage/postgres"
	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
	"github.com/eliasmeireles/obd-blackbox/backend/pkg/auth"
	"github.com/eliasmeireles/obd-blackbox/backend/web"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Run configures dependencies and serves until SIGINT or SIGTERM.
func Run(ctx context.Context, login auth.LoginProvider) error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: cfg.LogLevel}))
	slog.SetDefault(logger)
	startup, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	pool, err := pgxpool.New(startup, cfg.DatabaseURL)
	if err != nil {
		return errors.New("database pool configuration failed")
	}
	defer pool.Close()
	if err = pool.Ping(startup); err != nil {
		return errors.New("database connection failed")
	}
	if err = postgres.Migrate(startup, pool); err != nil {
		return errors.New("database migration failed")
	}
	repo := postgres.New(pool)
	hub := telemetry.NewHub()
	service := telemetry.NewService(repo, hub)
	stopRetention := startLogRetention(ctx, service, logger, cfg.LogRetention, cfg.LogPruneInterval)
	defer stopRetention()
	sessions := httpapi.NewAuth(cfg.Keys, cfg.CookieSecret, cfg.SessionTTL, cfg.CookieSecure, cfg.AppURL)
	router := httpapi.NewRouter(service, hub, sessions, login, web.Static(), cfg.Timezone, logger)
	server := &http.Server{Addr: cfg.HTTPAddr, Handler: router, ReadHeaderTimeout: 10 * time.Second, IdleTimeout: 60 * time.Second}
	return Serve(ctx, server, logger)
}

// Serve drains ordinary requests and cancels long-lived streams within ten seconds.
func Serve(ctx context.Context, server *http.Server, logger *slog.Logger) error {
	if ctx.Err() != nil {
		return nil
	}
	requests, cancelRequests := context.WithCancel(ctx)
	defer cancelRequests()
	server.BaseContext = baseContext(requests)
	return serve(ctx, server, logger)
}

// httpServer is the lifecycle contract used to verify shutdown without opening ports.
type httpServer interface {
	ListenAndServe() error
	Shutdown(context.Context) error
	Close() error
}

// serve shuts down an active server when its lifecycle context is canceled.
func serve(ctx context.Context, server httpServer, logger *slog.Logger) error {
	done := make(chan error, 1)
	go func() { done <- server.ListenAndServe() }()
	select {
	case err := <-done:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	case <-ctx.Done():
	}
	shutdown, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	logger.Info("shutting down")
	if err := server.Shutdown(shutdown); err != nil {
		server.Close()
		return err
	}
	err := <-done
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}

// baseContext associates every accepted connection with the shutdown context.
func baseContext(ctx context.Context) func(net.Listener) context.Context {
	return func(net.Listener) context.Context { return ctx }
}
