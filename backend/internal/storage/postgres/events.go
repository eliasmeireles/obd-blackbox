package postgres

import (
	"context"
	"fmt"
	"time"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
)

// Events returns recent events globally or for one session.
func (r *Repository) Events(ctx context.Context, id string, limit int, source, device string) ([]telemetry.Event, error) {
	query := `SELECT e.session_id::text,e.t,e.label,e.automatic FROM events e JOIN sessions s ON s.id=e.session_id WHERE ($1::text='' OR s.source=$1) AND ($2::text='' OR s.device=$2)`
	args := []any{source, device}
	if id != "" {
		query += ` AND e.session_id=$3`
		args = append(args, id)
	}
	query += ` ORDER BY t DESC,label`
	if limit > 0 {
		args = append(args, limit)
		query += fmt.Sprintf(" LIMIT $%d", len(args))
	}
	rows, err := r.pool.Query(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []telemetry.Event{}
	for rows.Next() {
		var e telemetry.Event
		var t time.Time
		if err = rows.Scan(&e.SessionID, &t, &e.Label, &e.Automatic); err != nil {
			return nil, err
		}
		e.T = t.UnixMilli()
		out = append(out, e)
	}
	return out, rows.Err()
}

// InsertDTCs inserts unique diagnostic codes atomically.
func (r *Repository) InsertDTCs(ctx context.Context, id string, in telemetry.DTCInput) (int, error) {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback(ctx)
	if err = sessionExists(ctx, tx, id); err != nil {
		return 0, err
	}
	n := 0
	for _, code := range in.Codes {
		tag, err := tx.Exec(ctx, `INSERT INTO dtcs(session_id,t,kind,code) VALUES($1,$2,$3,$4) ON CONFLICT DO NOTHING`, id, time.UnixMilli(in.T), in.Kind, code)
		if err != nil {
			return 0, err
		}
		n += int(tag.RowsAffected())
	}
	return n, tx.Commit(ctx)
}

// DTCs lists diagnostic codes in descending timestamp order.
func (r *Repository) DTCs(ctx context.Context, id string) ([]telemetry.DTC, error) {
	rows, err := r.pool.Query(ctx, `SELECT t,kind,code FROM dtcs WHERE session_id=$1 ORDER BY t DESC,kind,code`, id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []telemetry.DTC{}
	for rows.Next() {
		var d telemetry.DTC
		var t time.Time
		if err = rows.Scan(&t, &d.Kind, &d.Code); err != nil {
			return nil, err
		}
		d.T = t.UnixMilli()
		out = append(out, d)
	}
	return out, rows.Err()
}
