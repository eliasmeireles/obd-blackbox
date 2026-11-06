package postgres

import (
	"context"
	"testing"
	"time"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
	"github.com/stretchr/testify/require"
)

const obdID = "00000000-0000-0000-0000-000000000002"

// seedHistory creates fictional real and simulated sessions with all child records.
func seedHistory(t *testing.T, r *Repository) {
	t.Helper()
	ctx := context.Background()
	s := telemetry.NewService(r, telemetry.NewHub())
	for source, id := range map[string]string{"sim": testID, "obd": obdID} {
		_, err := s.Upsert(ctx, id, telemetry.SessionInput{Source: source, StartedAt: time.UnixMilli(1)})
		require.NoError(t, err)
		_, err = s.Ingest(ctx, id, []telemetry.SampleInput{{T: 1, Event: &telemetry.EventInput{Label: "example"}}})
		require.NoError(t, err)
		_, err = s.StoreDTCs(ctx, id, telemetry.DTCInput{T: 1, Kind: "stored", Codes: []string{"P0101"}})
		require.NoError(t, err)
	}
}

// requireNoChildren verifies foreign-key cascading for a removed session.
func requireNoChildren(t *testing.T, r *Repository, id string) {
	t.Helper()
	samples, err := r.Samples(context.Background(), id, telemetry.Query{})
	require.NoError(t, err)
	require.Empty(t, samples)
	events, err := r.Events(context.Background(), id, 100, "", "")
	require.NoError(t, err)
	require.Empty(t, events)
	dtcs, err := r.DTCs(context.Background(), id)
	require.NoError(t, err)
	require.Empty(t, dtcs)
}

// TestRepositorySourceFilters verifies session and event filters use session source.
func TestRepositorySourceFilters(t *testing.T) {
	t.Run("given mixed history then source filters isolate both lists", func(t *testing.T) {
		r := New(testDatabase(t))
		seedHistory(t, r)
		for source, id := range map[string]string{"sim": testID, "obd": obdID} {
			sessions, err := r.Sessions(context.Background(), 50, source, "")
			require.NoError(t, err)
			require.Len(t, sessions, 1)
			require.Equal(t, id, sessions[0].ID)
			events, err := r.Events(context.Background(), "", 100, source, "")
			require.NoError(t, err)
			require.Len(t, events, 1)
			require.Equal(t, id, events[0].SessionID)
		}
		sessions, err := r.Sessions(context.Background(), 50, "", "")
		require.NoError(t, err)
		require.Len(t, sessions, 2)
		events, err := r.Events(context.Background(), "", 100, "", "")
		require.NoError(t, err)
		require.Len(t, events, 2)
		events, err = r.Events(context.Background(), testID, 100, "obd", "")
		require.NoError(t, err)
		require.Empty(t, events)
	})
}

// TestRepositoryDelete verifies identifiers, idempotence and child cascades.
func TestRepositoryDelete(t *testing.T) {
	t.Run("given a session then deletion cascades only its history", func(t *testing.T) {
		r := New(testDatabase(t))
		seedHistory(t, r)
		ids, err := r.Delete(context.Background(), testID)
		require.NoError(t, err)
		require.Equal(t, []string{testID}, ids)
		requireNoChildren(t, r, testID)
		_, err = r.Get(context.Background(), testID)
		require.ErrorIs(t, err, telemetry.ErrNotFound)
		_, err = r.Get(context.Background(), obdID)
		require.NoError(t, err)
		ids, err = r.Delete(context.Background(), testID)
		require.NoError(t, err)
		require.Empty(t, ids)
	})
}

// TestRepositoryDeleteSource verifies bulk source selection and the all sentinel.
func TestRepositoryDeleteSource(t *testing.T) {
	for _, tc := range []struct {
		source            string
		removed, retained []string
	}{
		{"sim", []string{testID}, []string{obdID}},
		{"obd", []string{obdID}, []string{testID}},
		{"all", []string{testID, obdID}, []string{}},
	} {
		t.Run("given bulk source "+tc.source+" then only matching history is removed", func(t *testing.T) {
			r := New(testDatabase(t))
			seedHistory(t, r)
			ids, err := r.DeleteSource(context.Background(), tc.source, "")
			require.NoError(t, err)
			require.ElementsMatch(t, tc.removed, ids)
			for _, id := range tc.removed {
				requireNoChildren(t, r, id)
			}
			sessions, err := r.Sessions(context.Background(), 50, "", "")
			require.NoError(t, err)
			remaining := make([]string, 0, len(sessions))
			for _, session := range sessions {
				remaining = append(remaining, session.ID)
			}
			require.ElementsMatch(t, tc.retained, remaining)
			ids, err = r.DeleteSource(context.Background(), tc.source, "")
			require.NoError(t, err)
			require.Empty(t, ids)
		})
	}
}

// TestRepositoryDeviceFilters checks filters before limits and device-scoped cascading deletion.
func TestRepositoryDeviceFilters(t *testing.T) {
	t.Run("given two devices sharing a source then each history query isolates the selected device", func(t *testing.T) {
		repo := New(testDatabase(t))
		ctx := context.Background()
		service := telemetry.NewService(repo, telemetry.NewHub())
		for device, id := range map[string]string{"phone-a": testID, "phone-b": obdID} {
			_, err := service.Upsert(ctx, id, telemetry.SessionInput{Source: "obd", Device: device, StartedAt: time.UnixMilli(1)})
			require.NoError(t, err)
			_, err = service.Ingest(ctx, id, []telemetry.SampleInput{{T: 1, Event: &telemetry.EventInput{Label: "example"}}})
			require.NoError(t, err)
			_, err = service.StoreDiagnostic(ctx, id, []byte(`{"t":1,"reason":"manual"}`))
			require.NoError(t, err)
		}
		sessions, err := repo.Sessions(ctx, 1, "obd", "phone-a")
		require.NoError(t, err)
		require.Len(t, sessions, 1)
		require.Equal(t, "phone-a", sessions[0].Device)
		events, err := repo.Events(ctx, "", 1, "obd", "phone-b")
		require.NoError(t, err)
		require.Len(t, events, 1)
		require.Equal(t, obdID, events[0].SessionID)
		diagnostics, err := repo.Diagnostics(ctx, 1, "obd", "phone-a")
		require.NoError(t, err)
		require.Len(t, diagnostics, 1)
		require.Equal(t, testID, diagnostics[0].SessionID)
		empty, err := repo.Sessions(ctx, 50, "sim", "phone-a")
		require.NoError(t, err)
		require.Empty(t, empty)
		removed, err := repo.DeleteSource(ctx, "all", "phone-a")
		require.NoError(t, err)
		require.Equal(t, []string{testID}, removed)
		requireNoChildren(t, repo, testID)
		retained, err := repo.Get(ctx, obdID)
		require.NoError(t, err)
		require.Equal(t, "phone-b", retained.Device)
	})
}
