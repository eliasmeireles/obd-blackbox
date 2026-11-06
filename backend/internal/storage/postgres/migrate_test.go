package postgres

import (
	"context"
	"testing"

	"github.com/stretchr/testify/require"
)

// TestMigrate checks migration tracking and idempotent reapplication.
func TestMigrate(t *testing.T) {
	t.Run("given an initialized database then migration replay is safe", func(t *testing.T) {
		pool := testDatabase(t)
		require.NoError(t, Migrate(context.Background(), pool))
		var count int
		require.NoError(t, pool.QueryRow(context.Background(), `SELECT count(*) FROM schema_migrations`).Scan(&count))
		require.Equal(t, 4, count)
	})
}

// TestMigrationRollback ensures failed migrations leave neither schema changes nor tracking rows.
func TestMigrationRollback(t *testing.T) {
	t.Run("given invalid SQL then the entire migration rolls back", func(t *testing.T) {
		pool := testDatabase(t)
		ctx := context.Background()
		conn, err := pool.Acquire(ctx)
		require.NoError(t, err)
		defer conn.Release()
		err = applyMigration(ctx, conn, `CREATE TABLE rollback_example(id int); SELECT nonexistent_column FROM rollback_example;`, "failed.sql")
		require.Error(t, err)
		var exists bool
		require.NoError(t, conn.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM schema_migrations WHERE version='failed.sql')`).Scan(&exists))
		require.False(t, exists)
		var table *string
		require.NoError(t, conn.QueryRow(ctx, `SELECT to_regclass('rollback_example')::text`).Scan(&table))
		require.Nil(t, table)
	})
}
