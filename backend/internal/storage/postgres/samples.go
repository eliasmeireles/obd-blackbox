package postgres

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
	"github.com/jackc/pgx/v5"
)

// InsertSamples commits samples, events and aggregate counters as one transaction.
func (r *Repository) InsertSamples(ctx context.Context, id string, batch []telemetry.SampleInput, events []telemetry.Event) (telemetry.BatchResult, error) {
	result := telemetry.BatchResult{}
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return result, err
	}
	defer tx.Rollback(ctx)
	if err = sessionExists(ctx, tx, id); err != nil {
		return result, err
	}
	accepted := map[int64]string{}
	var latest int64
	for _, sample := range batch {
		inserted, err := insertSample(ctx, tx, id, sample)
		if err != nil {
			return result, err
		}
		if inserted {
			result.Accepted++
			if sample.Event != nil {
				accepted[sample.T] = sample.Event.Label
			}
			if sample.T > latest {
				latest = sample.T
			}
		} else {
			result.Duplicates++
		}
	}
	for _, event := range events {
		if label, ok := accepted[event.T]; !ok || label != event.Label {
			continue
		}
		if _, err = tx.Exec(ctx, `INSERT INTO events(session_id,t,label,automatic) VALUES($1,$2,$3,$4) ON CONFLICT DO NOTHING`, id, time.UnixMilli(event.T), event.Label, event.Automatic); err != nil {
			return result, err
		}
	}
	if result.Accepted > 0 {
		_, err = tx.Exec(ctx, `UPDATE sessions SET sample_count=sample_count+$2,last_sample_at=GREATEST(last_sample_at,$3) WHERE id=$1`, id, result.Accepted, time.UnixMilli(latest))
		if err != nil {
			return result, err
		}
	}
	return result, tx.Commit(ctx)
}

// insertSample separates typed signals from future JSONB signals.
func insertSample(ctx context.Context, tx pgx.Tx, id string, s telemetry.SampleInput) (bool, error) {
	args := []any{id, time.UnixMilli(s.T)}
	extra := map[string]float64{}
	for key, v := range s.V {
		extra[key] = v
	}
	for _, key := range telemetry.Signals {
		if v, ok := s.V[key]; ok {
			args = append(args, v)
		} else {
			args = append(args, nil)
		}
		delete(extra, key)
	}
	data, err := json.Marshal(extra)
	if err != nil {
		return false, err
	}
	var event *string
	if s.Event != nil {
		event = &s.Event.Label
	}
	args = append(args, data, s.Alerts, event)
	placeholders := make([]string, len(args))
	for i := range args {
		placeholders[i] = fmt.Sprintf("$%d", i+1)
	}
	query := `INSERT INTO samples(session_id,t,` + strings.Join(telemetry.Signals, ",") + `,extra,alerts,event) VALUES(` + strings.Join(placeholders, ",") + `) ON CONFLICT(session_id,t) DO NOTHING`
	tag, err := tx.Exec(ctx, query, args...)
	return tag.RowsAffected() == 1, err
}

// Samples returns chronologically ordered samples, retaining the last value per bucket.
func (r *Repository) Samples(ctx context.Context, id string, q telemetry.Query) ([]telemetry.Sample, error) {
	query, args := sampleQuery(id, q)
	rows, err := r.pool.Query(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []telemetry.Sample{}
	for rows.Next() {
		var s telemetry.Sample
		var stamp time.Time
		var data []byte
		values := make([]*float32, len(telemetry.Signals))
		dest := []any{&stamp}
		for i := range values {
			dest = append(dest, &values[i])
		}
		dest = append(dest, &data, &s.Alerts, &s.Event)
		if err = rows.Scan(dest...); err != nil {
			return nil, err
		}
		s.T = stamp.UnixMilli()
		s.V = map[string]float64{}
		if len(data) > 0 {
			if err = json.Unmarshal(data, &s.V); err != nil {
				return nil, err
			}
		}
		for i, v := range values {
			if v != nil {
				s.V[telemetry.Signals[i]] = float64(*v)
			}
		}
		if s.Alerts == nil {
			s.Alerts = []string{}
		}
		out = append(out, s)
	}
	return out, rows.Err()
}

// sampleQuery builds a parameterized query with filtering before downsampling.
func sampleQuery(id string, q telemetry.Query) (string, []any) {
	columns := `t,` + strings.Join(telemetry.Signals, ",") + `,extra,alerts,event`
	args := []any{id}
	where := `session_id=$1`
	if q.From > 0 {
		args = append(args, time.UnixMilli(q.From))
		where += fmt.Sprintf(" AND t >= $%d", len(args))
	}
	if q.To > 0 {
		args = append(args, time.UnixMilli(q.To))
		where += fmt.Sprintf(" AND t <= $%d", len(args))
	}
	query := `SELECT ` + columns + ` FROM samples WHERE ` + where
	if q.Step > 0 {
		args = append(args, q.Step)
		bucket := fmt.Sprintf(`floor(extract(epoch FROM t)*1000/$%d::bigint)`, len(args))
		query = `SELECT ` + columns + ` FROM (SELECT DISTINCT ON (` + bucket + `) ` + columns + ` FROM samples WHERE ` + where + ` ORDER BY ` + bucket + `,t DESC) buckets`
	}
	query += ` ORDER BY t`
	if q.Limit > 0 {
		args = append(args, q.Limit)
		query += fmt.Sprintf(" LIMIT $%d", len(args))
	}
	return query, args
}
