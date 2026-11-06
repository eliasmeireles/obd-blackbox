package postgres

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
)

// InsertLogs inserts one atomic batch, ignoring UUIDs already received.
func (r *Repository) InsertLogs(ctx context.Context, device string, batch []telemetry.LogInput) (telemetry.BatchResult, error) {
	values := make([]string, len(batch))
	args := make([]any, 0, len(batch)*7)
	for i, entry := range batch {
		n := i * 7
		values[i] = fmt.Sprintf("($%d,$%d,$%d,$%d,$%d,$%d,$%d)", n+1, n+2, n+3, n+4, n+5, n+6, n+7)
		args = append(args, entry.ID, device, time.UnixMilli(entry.T), entry.Level, entry.Tag, entry.Message, entry.SessionID)
	}
	result, err := r.pool.Exec(ctx, `INSERT INTO app_logs(id,device,t,level,tag,message,session_id) VALUES `+strings.Join(values, ",")+` ON CONFLICT(id) DO NOTHING`, args...)
	if err != nil {
		return telemetry.BatchResult{}, err
	}
	accepted := int(result.RowsAffected())
	return telemetry.BatchResult{Accepted: accepted, Duplicates: len(batch) - accepted}, nil
}

// Logs returns filtered entries ordered by logging time and receipt time.
func (r *Repository) Logs(ctx context.Context, q telemetry.LogQuery) ([]telemetry.AppLog, error) {
	direction := "ASC"
	if q.Order == "desc" {
		direction = "DESC"
	}
	rows, err := r.pool.Query(ctx, `SELECT id::text,device,t,level,tag,message,session_id::text FROM app_logs
 WHERE ($1::timestamptz IS NULL OR t >= $1) AND ($2::timestamptz IS NULL OR t <= $2)
 AND (CASE level WHEN 'debug' THEN 1 WHEN 'info' THEN 2 WHEN 'warn' THEN 3 WHEN 'error' THEN 4 END >= $3)
 AND ($4::text='' OR tag=$4) AND ($5::text='' OR device=$5)
 AND ($6::text='' OR session_id::text=$6)
 ORDER BY t `+direction+`,received_at `+direction+` LIMIT $7`, q.Since, q.Until, telemetry.LogLevelRank(q.Level), q.Tag, q.Device, strings.ToLower(q.SessionID), q.Limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []telemetry.AppLog{}
	for rows.Next() {
		var entry telemetry.AppLog
		var timestamp time.Time
		if err := rows.Scan(&entry.ID, &entry.Device, &timestamp, &entry.Level, &entry.Tag, &entry.Message, &entry.SessionID); err != nil {
			return nil, err
		}
		entry.T = timestamp.UnixMilli()
		out = append(out, entry)
	}
	return out, rows.Err()
}

// PruneLogs removes entries older than the supplied logging-time cutoff.
func (r *Repository) PruneLogs(ctx context.Context, cutoff time.Time) error {
	_, err := r.pool.Exec(ctx, `DELETE FROM app_logs WHERE t < $1`, cutoff)
	return err
}
