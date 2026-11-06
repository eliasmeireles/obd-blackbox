package postgres

import (
	"context"
	"testing"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
	"github.com/stretchr/testify/require"
)

// TestSampleQuery checks parameterized bounds and last-in-bucket downsampling SQL.
func TestSampleQuery(t *testing.T) {
	t.Run("given a step then the last sample per bucket is retained", func(t *testing.T) {
		sql, args := sampleQuery(testID, telemetry.Query{From: 1, To: 999, Step: 200, Limit: 100})
		require.Contains(t, sql, "DISTINCT ON (floor(extract(epoch FROM t)*1000/$4::bigint))")
		require.Contains(t, sql, "t DESC")
		require.Contains(t, sql, "ORDER BY t LIMIT $5")
		require.Len(t, args, 5)
	})
	t.Run("given unlimited export then no limit is added", func(t *testing.T) {
		sql, args := sampleQuery(testID, telemetry.Query{})
		require.NotContains(t, sql, "LIMIT")
		require.Len(t, args, 1)
	})
}

// TestRepositorySamples checks transactional duplicates, event extraction and chart buckets.
func TestRepositorySamples(t *testing.T) {
	t.Run("given retry batches then counts remain correct and chart buckets keep newest", func(t *testing.T) {
		r := New(testDatabase(t))
		createSession(t, r)
		hub := telemetry.NewHub()
		s := telemetry.NewService(r, hub)
		ctx := context.Background()
		batch := []telemetry.SampleInput{{T: 1001, V: map[string]float64{"rpm": 800, "future": 1}}, {T: 1100, V: map[string]float64{"rpm": 900}, Event: &telemetry.EventInput{Label: "mark", Automatic: true}}, {T: 1300, V: map[string]float64{"rpm": 1000}}}
		result, err := s.Ingest(ctx, testID, batch)
		require.NoError(t, err)
		require.Equal(t, 3, result.Accepted)
		result, err = s.Ingest(ctx, testID, batch)
		require.NoError(t, err)
		require.Equal(t, 3, result.Duplicates)
		session, err := r.Get(ctx, testID)
		require.NoError(t, err)
		require.Equal(t, int64(3), session.SampleCount)
		require.Equal(t, int64(1300), *session.LastSampleAt)
		require.Equal(t, int64(1), session.EventCount)
		samples, err := r.Samples(ctx, testID, telemetry.Query{Step: 200, Limit: 5000})
		require.NoError(t, err)
		require.Len(t, samples, 2)
		require.Equal(t, int64(1100), samples[0].T)
		require.Equal(t, int64(1300), samples[1].T)
		samples, err = r.Samples(ctx, testID, telemetry.Query{From: 1001, To: 1001, Limit: 10})
		require.NoError(t, err)
		require.Equal(t, 1.0, samples[0].V["future"])
		require.Equal(t, 800.0, samples[0].V["rpm"])
		_, err = s.Ingest(ctx, "00000000-0000-0000-0000-000000000002", batch)
		require.ErrorIs(t, err, telemetry.ErrNotFound)
	})
}

// TestDuplicateBatchEvents prevents duplicate timestamps from creating new event labels.
func TestDuplicateBatchEvents(t *testing.T) {
	t.Run("given a duplicate timestamp with changed event then only the inserted sample owns an event", func(t *testing.T) {
		r := New(testDatabase(t))
		createSession(t, r)
		s := telemetry.NewService(r, telemetry.NewHub())
		ctx := context.Background()
		_, err := s.Ingest(ctx, testID, []telemetry.SampleInput{{T: 1}, {T: 1, Event: &telemetry.EventInput{Label: "duplicate"}}, {T: 2, Event: &telemetry.EventInput{Label: "original"}}, {T: 2, Event: &telemetry.EventInput{Label: "changed"}}})
		require.NoError(t, err)
		events, err := r.Events(ctx, testID, 100, "", "")
		require.NoError(t, err)
		require.Len(t, events, 1)
		require.Equal(t, "original", events[0].Label)
	})
}
