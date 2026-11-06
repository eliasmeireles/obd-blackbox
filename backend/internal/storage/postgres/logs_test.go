package postgres

import (
	"context"
	"testing"
	"time"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
	"github.com/stretchr/testify/require"
)

// TestRepositoryLogs verifies atomic idempotence, independent sessions, filters and retention.
func TestRepositoryLogs(t *testing.T) {
	t.Run("given batches then duplicates filters ordering and pruning preserve matching logs", func(t *testing.T) {
		repo := New(testDatabase(t))
		ctx := context.Background()
		entries := []telemetry.LogInput{
			{ID: testID, T: 1000, Level: "warn", Tag: "elm", Message: "first", SessionID: new(testID)},
			{ID: "00000000-0000-0000-0000-000000000002", T: 2000, Level: "error", Tag: "bluetooth", Message: "second"},
			{ID: "00000000-0000-0000-0000-000000000003", T: 3000, Level: "info", Tag: "app", Message: "third"},
		}
		result, err := repo.InsertLogs(ctx, "example", entries)
		require.NoError(t, err)
		require.Equal(t, telemetry.BatchResult{Accepted: 3}, result)
		result, err = repo.InsertLogs(ctx, "other", entries)
		require.NoError(t, err)
		require.Equal(t, telemetry.BatchResult{Duplicates: 3}, result)
		for _, tc := range []struct {
			q   telemetry.LogQuery
			ids []string
		}{
			{telemetry.LogQuery{Limit: 500, Order: "asc"}, []string{entries[0].ID, entries[1].ID, entries[2].ID}},
			{telemetry.LogQuery{Limit: 1, Order: "desc"}, []string{entries[2].ID}},
			{telemetry.LogQuery{Limit: 500, Order: "asc", Level: "warn"}, []string{entries[0].ID, entries[1].ID}},
			{telemetry.LogQuery{Limit: 500, Level: "error"}, []string{entries[1].ID}},
			{telemetry.LogQuery{Limit: 500, Tag: "elm"}, []string{entries[0].ID}},
			{telemetry.LogQuery{Limit: 500, Device: "other"}, []string{}},
			{telemetry.LogQuery{Limit: 500, SessionID: testID}, []string{entries[0].ID}},
			{telemetry.LogQuery{Limit: 500, Since: new(time.UnixMilli(2000)), Until: new(time.UnixMilli(2000))}, []string{entries[1].ID}},
		} {
			logs, err := repo.Logs(ctx, tc.q)
			require.NoError(t, err)
			ids := []string{}
			for _, entry := range logs {
				ids = append(ids, entry.ID)
				require.Equal(t, "example", entry.Device)
			}
			require.Equal(t, tc.ids, ids)
		}
		require.NoError(t, repo.PruneLogs(ctx, time.UnixMilli(2000)))
		logs, err := repo.Logs(ctx, telemetry.LogQuery{Limit: 500, Order: "asc"})
		require.NoError(t, err)
		require.Len(t, logs, 2)
		require.Equal(t, int64(2000), logs[0].T)
	})
	t.Run("given an invalid entry then the whole statement rolls back", func(t *testing.T) {
		repo := New(testDatabase(t))
		_, err := repo.InsertLogs(context.Background(), "example", []telemetry.LogInput{{ID: testID, T: 1, Level: "info", Tag: "app", Message: "m"}, {ID: "bad", T: 1}})
		require.Error(t, err)
		logs, err := repo.Logs(context.Background(), telemetry.LogQuery{Limit: 500})
		require.NoError(t, err)
		require.Empty(t, logs)
	})
}

// TestLogReceiptOrdering verifies receipt time breaks logging-time ties in both directions.
func TestLogReceiptOrdering(t *testing.T) {
	t.Run("given equal logging times then receipt time determines ordering", func(t *testing.T) {
		pool := testDatabase(t)
		repo := New(pool)
		ctx := context.Background()
		secondID := "00000000-0000-0000-0000-000000000002"
		entry := telemetry.LogInput{ID: testID, T: 1000, Level: "debug", Tag: "app", Message: "first"}
		result, err := repo.InsertLogs(ctx, "example", []telemetry.LogInput{entry, entry})
		require.NoError(t, err)
		require.Equal(t, telemetry.BatchResult{Accepted: 1, Duplicates: 1}, result)
		entry.ID = secondID
		_, err = repo.InsertLogs(ctx, "example", []telemetry.LogInput{entry})
		require.NoError(t, err)
		_, err = pool.Exec(ctx, `UPDATE app_logs SET received_at=$1 WHERE id=$2`, time.UnixMilli(100), testID)
		require.NoError(t, err)
		_, err = pool.Exec(ctx, `UPDATE app_logs SET received_at=$1 WHERE id=$2`, time.UnixMilli(200), secondID)
		require.NoError(t, err)
		ascending, err := repo.Logs(ctx, telemetry.LogQuery{Limit: 500, Order: "asc"})
		require.NoError(t, err)
		require.Len(t, ascending, 2)
		require.Equal(t, testID, ascending[0].ID)
		descending, err := repo.Logs(ctx, telemetry.LogQuery{Limit: 500, Order: "desc"})
		require.NoError(t, err)
		require.Len(t, descending, 2)
		require.Equal(t, secondID, descending[0].ID)
	})
}
