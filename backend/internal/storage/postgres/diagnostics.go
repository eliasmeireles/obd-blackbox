package postgres

import (
	"context"
	"time"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
)

// InsertDiagnostic locks the session to serialize deletion and ignores retries by timestamp.
func (r *Repository) InsertDiagnostic(ctx context.Context, in telemetry.Diagnostic) (int, error) {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback(ctx)
	if err := sessionExists(ctx, tx, in.SessionID); err != nil {
		return 0, err
	}
	result, err := tx.Exec(ctx, `INSERT INTO diagnostics(session_id,t,data) VALUES($1,$2,$3) ON CONFLICT(session_id,t) DO NOTHING`, in.SessionID, time.UnixMilli(in.T), in.Data)
	if err != nil {
		return 0, err
	}
	if err := tx.Commit(ctx); err != nil {
		return 0, err
	}
	return int(result.RowsAffected()), nil
}

// Diagnostics returns complete snapshots in descending time order, filtered by session source.
func (r *Repository) Diagnostics(ctx context.Context, limit int, source, device string) ([]telemetry.Diagnostic, error) {
	rows, err := r.pool.Query(ctx, `SELECT d.session_id::text,d.t,d.data FROM diagnostics d JOIN sessions s ON s.id=d.session_id WHERE ($2::text='' OR s.source=$2) AND ($3::text='' OR s.device=$3) ORDER BY d.t DESC,d.session_id LIMIT $1`, limit, source, device)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []telemetry.Diagnostic{}
	for rows.Next() {
		var entry telemetry.Diagnostic
		var timestamp time.Time
		if err := rows.Scan(&entry.SessionID, &timestamp, &entry.Data); err != nil {
			return nil, err
		}
		entry.T = timestamp.UnixMilli()
		out = append(out, entry)
	}
	return out, rows.Err()
}
