// Package postgres persists telemetry using PostgreSQL transactions.
package postgres

import (
	"context"
	"embed"
	"fmt"
	"sort"

	"github.com/jackc/pgx/v5/pgxpool"
)

//go:embed migrations/*.sql
var migrations embed.FS

// Migrate serializes startup migrations on a dedicated connection.
func Migrate(ctx context.Context, pool *pgxpool.Pool) error {
	conn, err := pool.Acquire(ctx)
	if err != nil {
		return err
	}
	defer conn.Release()
	if _, err = conn.Exec(ctx, `SELECT pg_advisory_lock(827391045)`); err != nil {
		return err
	}
	defer conn.Exec(context.Background(), `SELECT pg_advisory_unlock(827391045)`)
	if _, err = conn.Exec(ctx, `CREATE TABLE IF NOT EXISTS schema_migrations(version TEXT PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())`); err != nil {
		return err
	}
	entries, err := migrations.ReadDir("migrations")
	if err != nil {
		return err
	}
	sort.Slice(entries, func(i, j int) bool { return entries[i].Name() < entries[j].Name() })
	for _, entry := range entries {
		var exists bool
		if err = conn.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM schema_migrations WHERE version=$1)`, entry.Name()).Scan(&exists); err != nil {
			return err
		}
		if exists {
			continue
		}
		sql, err := migrations.ReadFile("migrations/" + entry.Name())
		if err != nil {
			return err
		}
		if err = applyMigration(ctx, conn, string(sql), entry.Name()); err != nil {
			return fmt.Errorf("migration %s: %w", entry.Name(), err)
		}
	}
	return nil
}

// applyMigration applies and records one migration atomically.
func applyMigration(ctx context.Context, conn *pgxpool.Conn, sql, version string) error {
	tx, err := conn.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if _, err = tx.Exec(ctx, sql); err != nil {
		return err
	}
	if _, err = tx.Exec(ctx, `INSERT INTO schema_migrations(version) VALUES($1)`, version); err != nil {
		return err
	}
	return tx.Commit(ctx)
}
