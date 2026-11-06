package httpapi

import (
	"encoding/csv"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
)

// export streams bounded pages as a CSV download using the configured timezone.
func (h *Handler) export(w http.ResponseWriter, r *http.Request) {
	id, ok := sessionID(w, r)
	if !ok {
		return
	}
	if _, err := h.service.Get(r.Context(), id); err != nil {
		h.respond(w, nil, err)
		return
	}
	q := telemetry.Query{Limit: 5000}
	samples, err := h.service.Samples(r.Context(), id, q)
	if err != nil {
		h.respond(w, nil, err)
		return
	}
	w.Header().Set("Content-Type", "text/csv; charset=utf-8")
	w.Header().Set("Content-Disposition", `attachment; filename="obd-ii-`+id+`.csv"`)
	writer := csv.NewWriter(w)
	header := append([]string{"timestamp", "epoch_ms"}, telemetry.Signals...)
	header = append(header, "alerts", "event")
	if writer.Write(header) != nil {
		return
	}
	for {
		for _, sample := range samples {
			if writer.Write(csvRow(sample, h.timezone)) != nil {
				return
			}
		}
		writer.Flush()
		if writer.Error() != nil || len(samples) < q.Limit {
			return
		}
		q.From = samples[len(samples)-1].T + 1
		samples, err = h.service.Samples(r.Context(), id, q)
		if err != nil {
			h.logger.Error("CSV stream interrupted", "error", err)
			return
		}
	}
}

// csvRow formats a sample with empty nulls and dot decimal signals.
func csvRow(s telemetry.Sample, zone *time.Location) []string {
	row := []string{time.UnixMilli(s.T).In(zone).Format(time.RFC3339Nano), strconv.FormatInt(s.T, 10)}
	for _, key := range telemetry.Signals {
		cell := ""
		if v, ok := s.V[key]; ok {
			cell = strconv.FormatFloat(v, 'f', -1, 64)
		}
		row = append(row, cell)
	}
	event := ""
	if s.Event != nil {
		event = *s.Event
	}
	return append(row, strings.Join(s.Alerts, "|"), event)
}
