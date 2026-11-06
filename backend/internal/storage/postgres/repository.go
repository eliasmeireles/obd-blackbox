package postgres

import (
	"context"
	"errors"
	"time"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type Repository struct{ pool *pgxpool.Pool }

const sessionColumns = `s.id::text,s.device,s.source,s.vehicle,s.app_version,s.started_at,s.last_sample_at,s.sample_count,(SELECT count(*) FROM events e WHERE e.session_id=s.id)`

// New creates the PostgreSQL repository.
func New(pool *pgxpool.Pool) *Repository { return &Repository{pool: pool} }

// Ping verifies the database connection.
func (r *Repository) Ping(ctx context.Context) error { return r.pool.Ping(ctx) }

// scanSession translates database timestamps to epoch milliseconds.
func scanSession(row pgx.Row) (telemetry.Session, error) {
	var s telemetry.Session
	var start time.Time
	var last *time.Time
	err := row.Scan(&s.ID, &s.Device, &s.Source, &s.Vehicle, &s.AppVersion, &start, &last, &s.SampleCount, &s.EventCount)
	s.StartedAt = start.UnixMilli()
	if last != nil {
		v := last.UnixMilli()
		s.LastSampleAt = &v
	}
	if errors.Is(err, pgx.ErrNoRows) {
		err = telemetry.ErrNotFound
	}
	return s, err
}

// Upsert updates session metadata while preserving received samples.
func (r *Repository) Upsert(ctx context.Context, id string, in telemetry.SessionInput) (telemetry.Session, error) {
	_, err := r.pool.Exec(ctx, `INSERT INTO sessions(id,source,vehicle,app_version,started_at,device) VALUES($1,$2,$3,$4,$5,$6) ON CONFLICT(id) DO UPDATE SET device=excluded.device,source=excluded.source,vehicle=excluded.vehicle,app_version=excluded.app_version,started_at=excluded.started_at`, id, in.Source, in.Vehicle, in.AppVersion, in.StartedAt, in.Device)
	if err != nil {
		return telemetry.Session{}, err
	}
	return r.Get(ctx, id)
}

// Get retrieves a session and its aggregate counts.
func (r *Repository) Get(ctx context.Context, id string) (telemetry.Session, error) {
	return scanSession(r.pool.QueryRow(ctx, `SELECT `+sessionColumns+` FROM sessions s WHERE s.id=$1`, id))
}

// Sessions returns the newest sessions first.
func (r *Repository) Sessions(ctx context.Context, limit int, source, device string) ([]telemetry.Session, error) {
	rows, err := r.pool.Query(ctx, `SELECT `+sessionColumns+` FROM sessions s WHERE ($2::text='' OR s.source=$2) AND ($3::text='' OR s.device=$3) ORDER BY s.started_at DESC,s.id LIMIT $1`, limit, source, device)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []telemetry.Session{}
	for rows.Next() {
		s, err := scanSession(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, s)
	}
	return out, rows.Err()
}

// sessionExists checks a session within the current transaction and serializes batch counters.
func sessionExists(ctx context.Context, tx pgx.Tx, id string) error {
	var found string
	err := tx.QueryRow(ctx, `SELECT id::text FROM sessions WHERE id=$1 FOR UPDATE`, id).Scan(&found)
	if errors.Is(err, pgx.ErrNoRows) {
		return telemetry.ErrNotFound
	}
	return err
}
