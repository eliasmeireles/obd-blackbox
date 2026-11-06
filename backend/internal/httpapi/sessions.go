package httpapi

import (
	"encoding/json"
	"net/http"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
)

// upsert creates or updates session metadata.
func (h *Handler) upsert(w http.ResponseWriter, r *http.Request) {
	id, ok := sessionID(w, r)
	if !ok {
		return
	}
	var in telemetry.SessionInput
	if !decode(w, r, &in) {
		return
	}
	if err := validateSessionInput(in); err != nil {
		writeError(w, 400, err.Error())
		return
	}
	in.Device, _ = r.Context().Value(identityKey{}).(string)
	v, err := h.service.Upsert(r.Context(), id, in)
	h.respond(w, v, err)
}

// sessions lists sessions newest first.
func (h *Handler) sessions(w http.ResponseWriter, r *http.Request) {
	n, ok := limit(w, r, 50, 500)
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
	v, err := h.service.Sessions(r.Context(), n, source, device)
	h.mutation(w, map[string]any{"sessions": v}, err)
}

// session retrieves a single session.
func (h *Handler) session(w http.ResponseWriter, r *http.Request) {
	id, ok := sessionID(w, r)
	if !ok {
		return
	}
	v, err := h.service.Get(r.Context(), id)
	h.respond(w, v, err)
}

// ingest accepts retryable batches of telemetry samples.
func (h *Handler) ingest(w http.ResponseWriter, r *http.Request) {
	id, ok := sessionID(w, r)
	if !ok {
		return
	}
	var in struct {
		Samples []json.RawMessage `json:"samples"`
	}
	if !decode(w, r, &in) {
		return
	}
	batch, err := parseSamples(in.Samples)
	if err != nil {
		writeError(w, 400, err.Error())
		return
	}
	v, err := h.service.Ingest(r.Context(), id, batch)
	h.mutation(w, v, err)
}

// samples retrieves filtered chart samples.
func (h *Handler) samples(w http.ResponseWriter, r *http.Request) {
	id, ok := sessionID(w, r)
	if !ok {
		return
	}
	q, err := query(r)
	if err != nil {
		writeError(w, 400, err.Error())
		return
	}
	v, err := h.service.Samples(r.Context(), id, q)
	h.respond(w, map[string]any{"samples": v}, err)
}

// events retrieves recent automatic and manual events.
func (h *Handler) events(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if id != "" {
		var ok bool
		id, ok = sessionID(w, r)
		if !ok {
			return
		}
	}
	n := 0
	if id == "" || r.URL.Query().Has("limit") {
		var ok bool
		n, ok = limit(w, r, 100, 50000)
		if !ok {
			return
		}
	}
	source, ok := sourceFilter(w, r)
	if !ok {
		return
	}
	device, ok := deviceFilter(w, r)
	if !ok {
		return
	}
	v, err := h.service.Events(r.Context(), id, n, source, device)
	h.mutation(w, map[string]any{"events": v}, err)
}

// storeDTCs records diagnostic trouble codes.
func (h *Handler) storeDTCs(w http.ResponseWriter, r *http.Request) {
	id, ok := sessionID(w, r)
	if !ok {
		return
	}
	var in telemetry.DTCInput
	if !decode(w, r, &in) {
		return
	}
	n, err := h.service.StoreDTCs(r.Context(), id, in)
	h.mutation(w, map[string]int{"stored": n}, err)
}

// dtcs retrieves diagnostic trouble codes.
func (h *Handler) dtcs(w http.ResponseWriter, r *http.Request) {
	id, ok := sessionID(w, r)
	if !ok {
		return
	}
	v, err := h.service.DTCs(r.Context(), id)
	h.respond(w, map[string]any{"dtcs": v}, err)
}

// validateSessionInput shares the REST session metadata requirements with streaming ingestion.
func validateSessionInput(in telemetry.SessionInput) error {
	if !telemetry.ValidSource(in.Source) || in.StartedAt.IsZero() {
		return &telemetry.ValidationError{Message: "source and started_at are required and must be valid"}
	}
	return nil
}
