package postgres

import (
	"context"
	"testing"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
	"github.com/stretchr/testify/require"
)

// TestRepositoryEvents checks event projections and deduplicated diagnostic codes.
func TestRepositoryEvents(t *testing.T) {
	t.Run("given events and DTC retries then queries return unique rows", func(t *testing.T) {
		r := New(testDatabase(t))
		createSession(t, r)
		s := telemetry.NewService(r, telemetry.NewHub())
		ctx := context.Background()
		_, err := s.Ingest(ctx, testID, []telemetry.SampleInput{{T: 1, Event: &telemetry.EventInput{Label: "mark"}}})
		require.NoError(t, err)
		events, err := r.Events(ctx, "", 100, "", "")
		require.NoError(t, err)
		require.Len(t, events, 1)
		events, err = r.Events(ctx, testID, 100, "", "")
		require.NoError(t, err)
		require.Equal(t, "mark", events[0].Label)
		in := telemetry.DTCInput{T: 1, Kind: "stored", Codes: []string{"P0101", "P0101"}}
		n, err := s.StoreDTCs(ctx, testID, in)
		require.NoError(t, err)
		require.Equal(t, 1, n)
		n, err = s.StoreDTCs(ctx, testID, in)
		require.NoError(t, err)
		require.Zero(t, n)
		codes, err := r.DTCs(ctx, testID)
		require.NoError(t, err)
		require.Len(t, codes, 1)
		require.Equal(t, "P0101", codes[0].Code)
		_, err = s.StoreDTCs(ctx, "00000000-0000-0000-0000-000000000002", in)
		require.ErrorIs(t, err, telemetry.ErrNotFound)
	})
}
