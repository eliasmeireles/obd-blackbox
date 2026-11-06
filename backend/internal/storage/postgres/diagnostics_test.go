package postgres

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
	"github.com/stretchr/testify/require"
)

// TestRepositoryDiagnostics verifies idempotence, complete JSON, ordering, source and cascade deletion.
func TestRepositoryDiagnostics(t *testing.T) {
	t.Run("given snapshots then retries preserve the original and source filtering isolates sessions", func(t *testing.T) {
		repo := New(testDatabase(t))
		createSession(t, repo)
		ctx := context.Background()
		obdID := "00000000-0000-0000-0000-000000000002"
		_, err := repo.Upsert(ctx, obdID, telemetry.SessionInput{Source: "obd", Vehicle: "obd-ii", StartedAt: time.UnixMilli(1)})
		require.NoError(t, err)
		raw := json.RawMessage(`{"t":100,"reason":"connect","vin":null,"values":{"new":12}}`)
		n, err := repo.InsertDiagnostic(ctx, telemetry.Diagnostic{SessionID: testID, T: 100, Data: raw})
		require.NoError(t, err)
		require.Equal(t, 1, n)
		n, err = repo.InsertDiagnostic(ctx, telemetry.Diagnostic{SessionID: testID, T: 100, Data: json.RawMessage(`{"t":100,"reason":"manual"}`)})
		require.NoError(t, err)
		require.Zero(t, n)
		_, err = repo.InsertDiagnostic(ctx, telemetry.Diagnostic{SessionID: obdID, T: 200, Data: json.RawMessage(`{"t":200,"reason":"manual"}`)})
		require.NoError(t, err)
		entries, err := repo.Diagnostics(ctx, 20, "", "")
		require.NoError(t, err)
		require.Len(t, entries, 2)
		require.Equal(t, int64(200), entries[0].T)
		require.JSONEq(t, string(raw), string(entries[1].Data))
		entries, err = repo.Diagnostics(ctx, 20, "obd", "")
		require.NoError(t, err)
		require.Len(t, entries, 1)
		require.Equal(t, obdID, entries[0].SessionID)
		entries, err = repo.Diagnostics(ctx, 20, "sim", "")
		require.NoError(t, err)
		require.Len(t, entries, 1)
		require.Equal(t, testID, entries[0].SessionID)
		entries, err = repo.Diagnostics(ctx, 1, "", "")
		require.NoError(t, err)
		require.Len(t, entries, 1)
		_, err = repo.Delete(ctx, testID)
		require.NoError(t, err)
		entries, err = repo.Diagnostics(ctx, 20, "sim", "")
		require.NoError(t, err)
		require.Empty(t, entries)
		n, err = repo.InsertDiagnostic(ctx, telemetry.Diagnostic{SessionID: testID, T: 100, Data: raw})
		require.ErrorIs(t, err, telemetry.ErrNotFound)
		require.Zero(t, n)
	})
}
