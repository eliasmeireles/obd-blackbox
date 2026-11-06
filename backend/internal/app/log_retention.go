package app

import (
	"context"
	"log/slog"
	"time"
)

const (
	appLogRetention     = 30 * 24 * time.Hour
	appLogPruneInterval = time.Hour
)

type logPruner interface {
	PruneLogs(context.Context, time.Time) error
}

// startLogRetention prunes immediately and starts a cancellable hourly worker.
// The returned function waits until the worker stops before storage is closed.
func startLogRetention(ctx context.Context, pruner logPruner, logger *slog.Logger, retention, interval time.Duration) func() {
	lifetime, cancel := context.WithCancel(ctx)
	prune := func() {
		bounded, stop := context.WithTimeout(lifetime, time.Minute)
		defer stop()
		if err := pruner.PruneLogs(bounded, time.Now().Add(-retention)); err != nil && lifetime.Err() == nil {
			logger.Error("app log retention failed", "error", err)
		}
	}
	prune()
	done := make(chan struct{})
	go func() {
		defer close(done)
		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for {
			select {
			case <-lifetime.Done():
				return
			case <-ticker.C:
				prune()
			}
		}
	}()
	return func() { cancel(); <-done }
}
