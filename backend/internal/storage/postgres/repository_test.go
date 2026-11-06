package postgres

import (
	"context"
	"os"
	"strconv"
	"testing"
	"time"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/stretchr/testify/require"
)

// testDatabase requires an explicitly configured PostgreSQL and isolates each test by schema.
func testDatabase(t *testing.T) *pgxpool.Pool {
	t.Helper()
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		t.Skip("TEST_DATABASE_URL is unset")
	}
	ctx := context.Background()
	admin, err := pgxpool.New(ctx, url)
	require.NoError(t, err)
	schema := "obd_blackbox_test_" + strconv.FormatInt(time.Now().UnixNano(), 10)
	_, err = admin.Exec(ctx, `CREATE SCHEMA `+schema)
	require.NoError(t, err)
	cfg, err := pgxpool.ParseConfig(url)
	require.NoError(t, err)
	cfg.ConnConfig.RuntimeParams["search_path"] = schema
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	require.NoError(t, err)
	t.Cleanup(func() {
		pool.Close()
		_, err := admin.Exec(ctx, `DROP SCHEMA `+schema+` CASCADE`)
		require.NoError(t, err)
		admin.Close()
	})
	require.NoError(t, Migrate(ctx, pool))
	return pool
}

const testID = "00000000-0000-0000-0000-000000000001"

// createSession installs a fictional session fixture.
func createSession(t *testing.T, r *Repository) {
	t.Helper()
	_, err := r.Upsert(context.Background(), testID, telemetry.SessionInput{Source: "sim", Vehicle: "obd-ii", StartedAt: time.UnixMilli(1)})
	require.NoError(t, err)
}

// TestRepositorySessions verifies idempotent metadata updates and null aggregates.
func TestRepositorySessions(t *testing.T) {
	t.Run("given a database then session operations preserve identity", func(t *testing.T) {
		r := New(testDatabase(t))
		require.NoError(t, r.Ping(context.Background()))
		createSession(t, r)
		createSession(t, r)
		s, err := r.Get(context.Background(), testID)
		require.NoError(t, err)
		require.Nil(t, s.LastSampleAt)
		require.Zero(t, s.SampleCount)
		list, err := r.Sessions(context.Background(), 50, "", "")
		require.NoError(t, err)
		require.Len(t, list, 1)
		_, err = r.Get(context.Background(), "00000000-0000-0000-0000-000000000002")
		require.ErrorIs(t, err, telemetry.ErrNotFound)
	})
}

// TestSessionDeviceStorage checks migration defaults and persisted authenticated identities.
func TestSessionDeviceStorage(t *testing.T) {
	t.Run("given device metadata then upsert persists it and legacy rows keep an empty device", func(t *testing.T) {
		pool := testDatabase(t)
		repo := New(pool)
		ctx := context.Background()
		_, err := pool.Exec(ctx, `INSERT INTO sessions(id,source,started_at) VALUES($1,'obd',now())`, testID)
		require.NoError(t, err)
		legacy, err := repo.Get(ctx, testID)
		require.NoError(t, err)
		require.Empty(t, legacy.Device)
		updated, err := repo.Upsert(ctx, testID, telemetry.SessionInput{Source: "obd", Device: "phone-a", StartedAt: time.UnixMilli(1)})
		require.NoError(t, err)
		require.Equal(t, "phone-a", updated.Device)
	})
}
