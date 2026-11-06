package postgres

import "context"

// Delete removes one session; foreign keys cascade its samples, events and DTCs.
func (r *Repository) Delete(ctx context.Context, id string) ([]string, error) {
	return r.deleteSessions(ctx, `DELETE FROM sessions WHERE id=$1 RETURNING id::text`, id)
}

// DeleteSource removes sessions of source, or every session when source is all.
func (r *Repository) DeleteSource(ctx context.Context, source, device string) ([]string, error) {
	return r.deleteSessions(ctx, `DELETE FROM sessions WHERE ($1::text='all' OR source=$1) AND ($2::text='' OR device=$2) RETURNING id::text`, source, device)
}

// deleteSessions returns the identities actually removed by the statement.
func (r *Repository) deleteSessions(ctx context.Context, query string, args ...any) ([]string, error) {
	rows, err := r.pool.Query(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	ids := []string{}
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}
