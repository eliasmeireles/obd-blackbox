package httpapi

import (
	"net/http/httptest"
	"testing"
	"time"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
	"github.com/stretchr/testify/require"
)

// TestLogQuery verifies both timestamp formats and all query validation paths.
func TestLogQuery(t *testing.T) {
	t.Run("given all filters then the service receives their parsed values", func(t *testing.T) {
		f := &fakeService{}
		w := request(testRouter(f, telemetry.NewHub()), "GET", "/api/v1/logs?since=1000&until=2026-11-06T12:00:00.123-03:00&level=warn&tag=elm&device=example&session_id="+testID+"&limit=5000&order=desc", "")
		require.Equal(t, 200, w.Code)
		until, err := time.Parse(time.RFC3339Nano, "2026-11-06T12:00:00.123-03:00")
		require.NoError(t, err)
		require.Equal(t, telemetry.LogQuery{Since: new(time.UnixMilli(1000)), Until: &until, Level: "warn", Tag: "elm", Device: "example", SessionID: testID, Limit: 5000, Order: "desc"}, f.logQuery)
	})
	t.Run("given no filters then defaults apply", func(t *testing.T) {
		q, err := logQuery(httptest.NewRequest("GET", "/", nil))
		require.NoError(t, err)
		require.Equal(t, telemetry.LogQuery{Limit: 500, Order: "asc"}, q)
	})
	for _, query := range []string{"since=bad", "until=bad", "since=2&until=1", "limit=0", "limit=-1", "limit=5001", "limit=bad", "level=fatal", "tag=ELM", "session_id=bad", "order=invalid", "since=", "until=", "limit=", "level=", "tag=", "device=", "session_id=", "order="} {
		t.Run("given invalid "+query+" then both endpoints reject it", func(t *testing.T) {
			for _, path := range []string{"/api/v1/logs?", "/api/v1/logs.txt?"} {
				w := request(testRouter(&fakeService{}, telemetry.NewHub()), "GET", path+query, "")
				require.Equal(t, 400, w.Code)
			}
		})
	}
}
