package httpapi

import (
	"net/http"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
)

// sourceFilter validates an optional telemetry source query parameter.
func sourceFilter(w http.ResponseWriter, r *http.Request) (string, bool) {
	source := r.URL.Query().Get("source")
	if err := telemetry.ValidateSourceFilter(source); err != nil || (source == "" && r.URL.Query().Has("source")) {
		writeError(w, 400, "invalid source")
		return "", false
	}
	return source, true
}

// requireDashboardSession restricts history deletion to authenticated dashboard identities.
func requireDashboardSession(w http.ResponseWriter, r *http.Request) bool {
	dashboard, _ := r.Context().Value(dashboardSessionKey{}).(bool)
	if !dashboard {
		writeError(w, 403, "forbidden")
		return false
	}
	return true
}

// deleteSession deletes a session and its associated history.
func (h *Handler) deleteSession(w http.ResponseWriter, r *http.Request) {
	if !requireDashboardSession(w, r) {
		return
	}
	id, ok := sessionID(w, r)
	if !ok {
		return
	}
	n, err := h.service.Delete(r.Context(), id)
	h.mutation(w, map[string]int{"deleted": n}, err)
}

// deleteSessions requires an explicit source before removing history in bulk.
func (h *Handler) deleteSessions(w http.ResponseWriter, r *http.Request) {
	if !requireDashboardSession(w, r) {
		return
	}
	source := r.URL.Query().Get("source")
	if source != "all" && !telemetry.ValidSource(source) {
		writeError(w, 400, "invalid source")
		return
	}
	device, ok := deviceFilter(w, r)
	if !ok {
		return
	}
	n, err := h.service.DeleteSource(r.Context(), source, device)
	h.mutation(w, map[string]int{"deleted": n}, err)
}

// deviceFilter validates an optional exact device name query parameter.
func deviceFilter(w http.ResponseWriter, r *http.Request) (string, bool) {
	device := r.URL.Query().Get("device")
	if device == "" && r.URL.Query().Has("device") {
		writeError(w, 400, "invalid device")
		return "", false
	}
	return device, true
}
