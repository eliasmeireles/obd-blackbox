package postgres

import (
	"context"
	"time"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
)

// Devices lists identities seen in session metadata or logs, including removed API keys.
func (r *Repository) Devices(ctx context.Context) ([]telemetry.Device, error) {
	rows, err := r.pool.Query(ctx, `SELECT device,max(last_seen) FROM (
 SELECT device,GREATEST(created_at,last_sample_at) AS last_seen FROM sessions WHERE device<>''
 UNION ALL SELECT device,received_at AS last_seen FROM app_logs WHERE device<>''
 ) seen GROUP BY device ORDER BY device`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	devices := []telemetry.Device{}
	for rows.Next() {
		var device telemetry.Device
		var seen time.Time
		if err := rows.Scan(&device.Name, &seen); err != nil {
			return nil, err
		}
		stamp := seen.UnixMilli()
		device.LastSeen = &stamp
		devices = append(devices, device)
	}
	return devices, rows.Err()
}
