package httpapi

import (
	"context"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
)

const logTimeFormat = "2006-01-02T15:04:05.000Z07:00"

type logOutput struct {
	ID        string  `json:"id"`
	Device    string  `json:"device"`
	T         string  `json:"t"`
	Level     string  `json:"level"`
	Tag       string  `json:"tag"`
	Message   string  `json:"message"`
	SessionID *string `json:"session_id"`
}

// ingestLogs shares the authenticated identity and service validation across transports.
func (h *Handler) ingestLogs(ctx context.Context, logs []telemetry.LogInput) (*telemetry.BatchResult, error) {
	device, _ := ctx.Value(identityKey{}).(string)
	result, err := h.service.IngestLogs(ctx, device, logs)
	return &result, err
}

// storeLogs accepts a JSON batch using the same service operation as WebSocket logs.
func (h *Handler) storeLogs(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Logs []telemetry.LogInput `json:"logs"`
	}
	if !decode(w, r, &in) {
		return
	}
	result, err := h.ingestLogs(r.Context(), in.Logs)
	h.respond(w, result, err)
}

// logs renders matching entries as JSON or single-line plain text.
func (h *Handler) logs(w http.ResponseWriter, r *http.Request) {
	q, err := logQuery(r)
	if err != nil {
		writeError(w, 400, err.Error())
		return
	}
	entries, err := h.service.Logs(r.Context(), q)
	if err != nil {
		h.respond(w, nil, err)
		return
	}
	out := make([]logOutput, 0, len(entries))
	for _, entry := range entries {
		out = append(out, logOutput{ID: entry.ID, Device: entry.Device, T: time.UnixMilli(entry.T).In(h.timezone).Format(logTimeFormat), Level: entry.Level, Tag: entry.Tag, Message: entry.Message, SessionID: entry.SessionID})
	}
	if strings.HasSuffix(r.URL.Path, ".txt") {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		for _, entry := range out {
			message := strings.NewReplacer("\r\n", "↵", "\r", "↵", "\n", "↵").Replace(entry.Message)
			fmt.Fprintf(w, "%s %s %s %s %s\n", entry.T, entry.Device, strings.ToUpper(entry.Level), entry.Tag, message)
		}
		return
	}
	writeJSON(w, 200, map[string]any{"logs": out})
}
