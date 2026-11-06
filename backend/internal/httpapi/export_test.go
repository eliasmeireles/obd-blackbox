package httpapi

import (
	"context"
	"encoding/csv"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
	"github.com/stretchr/testify/require"
)

// TestExport checks the complete CSV header, timezone, nulls and escaped event labels.
func TestExport(t *testing.T) {
	t.Run("given a sample then CSV matches the application columns", func(t *testing.T) {
		event := "mark,example"
		s := &fakeService{sample: telemetry.Sample{T: 1793925150857, V: map[string]float64{"rpm": 800.1}, Alerts: []string{"IDLE_DROP", "LOW_VOLTAGE"}, Event: &event}}
		w := request(testRouter(s, telemetry.NewHub()), "GET", "/api/v1/sessions/"+testID+"/export.csv", "")
		require.Equal(t, 200, w.Code)
		require.Equal(t, `attachment; filename="obd-ii-`+testID+`.csv"`, w.Header().Get("Content-Disposition"))
		records, err := csv.NewReader(strings.NewReader(w.Body.String())).ReadAll()
		require.NoError(t, err)
		require.Equal(t, []string{"timestamp", "epoch_ms", "speed_kmh", "rpm", "coolant_c", "load_pct", "timing_deg", "pedal_pct", "throttle_pct", "cmd_throttle_pct", "map_kpa", "intake_c", "stft_pct", "ltft_pct", "o2_b1s1_v", "voltage_v", "fuel_level_pct", "fuel_rate_lph_est", "alerts", "event"}, records[0])
		require.Equal(t, "800.1", records[1][3])
		require.Empty(t, records[1][2])
		require.Equal(t, "IDLE_DROP|LOW_VOLTAGE", records[1][18])
		require.Equal(t, event, records[1][19])
	})
	t.Run("given configured timezone then timestamp includes offset", func(t *testing.T) {
		zone, err := time.LoadLocation("America/Sao_Paulo")
		require.NoError(t, err)
		row := csvRow(telemetry.Sample{T: 1000}, zone)
		require.Equal(t, "1969-12-31T21:00:01-03:00", row[0])
		require.Empty(t, row[19])
	})
	t.Run("given unknown session then CSV returns JSON 404", func(t *testing.T) {
		w := request(testRouter(&fakeService{err: telemetry.ErrNotFound}, telemetry.NewHub()), "GET", "/api/v1/sessions/"+testID+"/export.csv", "")
		require.Equal(t, 404, w.Code)
	})
}

type pagedService struct {
	*fakeService
	queries []telemetry.Query
}

// Samples yields a full first page followed by a final smaller page.
func (f *pagedService) Samples(_ context.Context, _ string, q telemetry.Query) ([]telemetry.Sample, error) {
	f.queries = append(f.queries, q)
	if q.From > 0 {
		return []telemetry.Sample{{T: 5001}}, nil
	}
	out := make([]telemetry.Sample, 5000)
	for i := range out {
		out[i] = telemetry.Sample{T: int64(i + 1)}
	}
	return out, nil
}

// TestExportPagination checks bounded streaming covers every sample without overlap.
func TestExportPagination(t *testing.T) {
	t.Run("given more than one page then CSV advances beyond the last timestamp", func(t *testing.T) {
		s := &pagedService{fakeService: &fakeService{}}
		h := &Handler{service: s, timezone: time.UTC}
		r := httptest.NewRequest("GET", "/", nil)
		r.SetPathValue("id", testID)
		w := httptest.NewRecorder()
		h.export(w, r)
		rows, err := csv.NewReader(strings.NewReader(w.Body.String())).ReadAll()
		require.NoError(t, err)
		require.Len(t, rows, 5002)
		require.Len(t, s.queries, 2)
		require.Equal(t, int64(5001), s.queries[1].From)
		require.Equal(t, "5001", rows[5001][1])
	})
}
