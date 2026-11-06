package app

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/http"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

// TestRunConfiguration checks startup fails before accessing a database on invalid settings.
func TestRunConfiguration(t *testing.T) {
	t.Run("given missing database settings then startup fails clearly", func(t *testing.T) {
		t.Setenv("DATABASE_URL", "")
		require.ErrorContains(t, Run(context.Background(), nil), "DATABASE_URL")
	})
}

// TestServe checks listener errors and signal context shutdown without external services.
func TestServe(t *testing.T) {
	t.Run("given an invalid listener then serve returns the error", func(t *testing.T) {
		err := Serve(context.Background(), &http.Server{Addr: "bad address"}, slog.New(slog.NewTextHandler(io.Discard, nil)))
		require.Error(t, err)
	})
	t.Run("given a canceled context then server shuts down", func(t *testing.T) {
		ctx, cancel := context.WithCancel(context.Background())
		cancel()
		err := Serve(ctx, &http.Server{Addr: "127.0.0.1:0", ReadHeaderTimeout: time.Second}, slog.New(slog.NewTextHandler(io.Discard, nil)))
		require.NoError(t, err)
	})
	t.Run("given base context then connections inherit cancellation", func(t *testing.T) {
		ctx, cancel := context.WithCancel(context.Background())
		cancel()
		var listener net.Listener
		require.ErrorIs(t, baseContext(ctx)(listener).Err(), context.Canceled)
	})
}

type fakeServer struct {
	started, stopped            chan struct{}
	once                        sync.Once
	shutdownErr                 error
	shutdownCalled, closeCalled bool
	deadline                    bool
}

// ListenAndServe blocks until shutdown closes the fake listener.
func (f *fakeServer) ListenAndServe() error {
	close(f.started)
	<-f.stopped
	return http.ErrServerClosed
}

// Shutdown records the timeout and releases the fake listener on success.
func (f *fakeServer) Shutdown(ctx context.Context) error {
	f.shutdownCalled = true
	_, f.deadline = ctx.Deadline()
	if f.shutdownErr == nil {
		f.once.Do(func() { close(f.stopped) })
	}
	return f.shutdownErr
}

// Close forces the listener to terminate after a failed graceful shutdown.
func (f *fakeServer) Close() error {
	f.closeCalled = true
	f.once.Do(func() { close(f.stopped) })
	return nil
}

// TestGracefulShutdown checks active serving, the shutdown deadline and forced close.
func TestGracefulShutdown(t *testing.T) {
	t.Run("given a running server then cancellation performs bounded shutdown", func(t *testing.T) {
		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()
		server := &fakeServer{started: make(chan struct{}), stopped: make(chan struct{})}
		go func() { <-server.started; cancel() }()
		err := serve(ctx, server, slog.New(slog.NewTextHandler(io.Discard, nil)))
		require.NoError(t, err)
		require.True(t, server.shutdownCalled)
		require.True(t, server.deadline)
		require.False(t, server.closeCalled)
	})
	t.Run("given shutdown failure then the server is force closed", func(t *testing.T) {
		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()
		failure := errors.New("shutdown timeout")
		server := &fakeServer{started: make(chan struct{}), stopped: make(chan struct{}), shutdownErr: failure}
		go func() { <-server.started; cancel() }()
		err := serve(ctx, server, slog.New(slog.NewTextHandler(io.Discard, nil)))
		require.ErrorIs(t, err, failure)
		require.True(t, server.closeCalled)
	})
}
