package httpapi

import (
	"encoding/json"
	"fmt"
	"net/http"
	"time"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
)

// live returns the most recently received sample together with its session.
func (h *Handler) live(w http.ResponseWriter, r *http.Request) {
	source, valid := sourceFilter(w, r)
	if !valid {
		return
	}
	device, valid := deviceFilter(w, r)
	if !valid {
		return
	}
	v, ok := h.hub.Latest(source, device)
	if !ok {
		w.WriteHeader(204)
		return
	}
	session, err := h.service.Get(r.Context(), v.SessionID)
	h.respond(w, map[string]any{"session": session, "session_id": v.SessionID, "source": v.Source, "device": v.Device, "sample": v.Sample}, err)
}

// stream sends current and future samples until the client disconnects.
func (h *Handler) stream(w http.ResponseWriter, r *http.Request) {
	source, valid := sourceFilter(w, r)
	if !valid {
		return
	}
	device, valid := deviceFilter(w, r)
	if !valid {
		return
	}
	if _, ok := w.(http.Flusher); !ok {
		writeError(w, 500, "streaming unavailable")
		return
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("X-Accel-Buffering", "no")
	ch, latest, unsubscribe := h.hub.Subscribe(source, device)
	defer unsubscribe()
	if latest != nil {
		if err := sendSample(w, *latest); err != nil {
			return
		}
	} else {
		if http.NewResponseController(w).Flush() != nil {
			return
		}
	}
	ticker := time.NewTicker(h.pingInterval)
	defer ticker.Stop()
	for {
		select {
		case <-r.Context().Done():
			return
		case v, ok := <-ch:
			if !ok {
				return
			}
			if sendSample(w, v) != nil {
				return
			}
		case <-ticker.C:
			if _, err := fmt.Fprint(w, ": ping\n\n"); err != nil {
				return
			}
			if http.NewResponseController(w).Flush() != nil {
				return
			}
		}
	}
}

// sendSample writes one SSE sample event and immediately flushes it.
func sendSample(w http.ResponseWriter, v telemetry.Live) error {
	data, err := json.Marshal(v)
	if err != nil {
		return err
	}
	if _, err = fmt.Fprintf(w, "event: sample\ndata: %s\n\n", data); err != nil {
		return err
	}
	return http.NewResponseController(w).Flush()
}
