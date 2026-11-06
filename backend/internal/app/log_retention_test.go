package app

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

type fakeLogPruner struct {
	cutoffs []time.Time
	err     error
}

// PruneLogs records startup cleanup.
func (p *fakeLogPruner) PruneLogs(_ context.Context, cutoff time.Time) error {
	p.cutoffs = append(p.cutoffs, cutoff)
	return p.err
}

// TestLogRetention checks immediate pruning and prompt worker cancellation.
func TestLogRetention(t *testing.T) {
	for _, err := range []error{nil, errors.New("offline")} {
		t.Run("given startup then retention prunes immediately and stops with context", func(t *testing.T) {
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			p := &fakeLogPruner{err: err}
			before := time.Now().Add(-appLogRetention)
			stop := startLogRetention(ctx, p, slog.New(slog.NewTextHandler(io.Discard, nil)), appLogRetention, appLogPruneInterval)
			cancel()
			stop()
			require.Len(t, p.cutoffs, 1)
			require.False(t, p.cutoffs[0].Before(before))
			require.False(t, p.cutoffs[0].After(time.Now().Add(-appLogRetention)))
			require.Equal(t, 30*24*time.Hour, appLogRetention)
			require.Equal(t, time.Hour, appLogPruneInterval)
		})
	}
}
