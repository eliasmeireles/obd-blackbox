package httpapi

import (
	"encoding/json"
	"io"
	"net/http"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
)

// storeDiagnostic reads a size-bounded object and shares validation with WebSocket ingestion.
func (h *Handler) storeDiagnostic(w http.ResponseWriter, r *http.Request) {
	id, ok := sessionID(w, r)
	if !ok {
		return
	}
	data, err := io.ReadAll(http.MaxBytesReader(w, r.Body, telemetry.MaxDiagnosticsBody))
	if err != nil {
		writeError(w, 400, "diagnostics exceeds 64 KiB")
		return
	}
	n, err := h.service.StoreDiagnostic(r.Context(), id, json.RawMessage(data))
	h.respond(w, map[string]int{"stored": n}, err)
}

// diagnostics lists the newest snapshots, optionally restricted to one source.
func (h *Handler) diagnostics(w http.ResponseWriter, r *http.Request) {
	n, ok := limit(w, r, 20, 200)
	if !ok {
		return
	}
	source, ok := sourceFilter(w, r)
	if !ok {
		return
	}
	device, ok := deviceFilter(w, r)
	if !ok {
		return
	}
	entries, err := h.service.Diagnostics(r.Context(), n, source, device)
	h.respond(w, map[string]any{"diagnostics": entries}, err)
}
