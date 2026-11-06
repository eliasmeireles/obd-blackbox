package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
)

const maxBody = 2 << 20

// writeJSON serializes UTF-8 API responses.
func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

// writeError emits the shared error envelope.
func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}

// decode bounds input and rejects malformed or multiple JSON values.
func decode(w http.ResponseWriter, r *http.Request, v any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, maxBody)
	decoder := json.NewDecoder(r.Body)
	if err := decoder.Decode(v); err != nil {
		writeError(w, 400, "invalid JSON or body exceeds 2 MiB")
		return false
	}
	if err := decoder.Decode(new(any)); !errors.Is(err, io.EOF) {
		writeError(w, 400, "expected one JSON value")
		return false
	}
	return true
}

// respond maps application errors to HTTP status codes.
func (h *Handler) respond(w http.ResponseWriter, v any, err error) {
	if err != nil {
		status, message := h.applicationError(err)
		writeError(w, status, message)
		return
	}
	writeJSON(w, 200, v)
}

// sessionID rejects invalid UUID path parameters.
func sessionID(w http.ResponseWriter, r *http.Request) (string, bool) {
	id := r.PathValue("id")
	if !telemetry.ValidID(id) {
		writeError(w, 400, "invalid session UUID")
		return "", false
	}
	return id, true
}

// number reads an optional nonnegative numeric query parameter.
func number(r *http.Request, key string, fallback, max int64) (int64, error) {
	v := r.URL.Query().Get(key)
	if v == "" {
		return fallback, nil
	}
	n, err := strconv.ParseInt(v, 10, 64)
	if err != nil || n < 0 || (max > 0 && n > max) {
		return 0, errors.New("invalid " + key)
	}
	return n, nil
}

// query parses chart bounds, limits and downsampling intervals.
func query(r *http.Request) (telemetry.Query, error) {
	q := telemetry.Query{}
	var err error
	q.From, err = number(r, "from", 0, 0)
	if err != nil {
		return q, err
	}
	q.To, err = number(r, "to", 0, 0)
	if err != nil {
		return q, err
	}
	limit, err := number(r, "limit", 5000, 50000)
	if err != nil || limit == 0 {
		return q, errors.New("invalid limit")
	}
	q.Limit = int(limit)
	q.Step, err = number(r, "step", 0, 0)
	if err != nil {
		return q, err
	}
	if r.URL.Query().Has("step") && q.Step < 200 {
		return q, errors.New("step must be at least 200 ms")
	}
	if q.To > 0 && q.From > q.To {
		return q, errors.New("from must not exceed to")
	}
	return q, nil
}

// limit reads a bounded positive list size.
func limit(w http.ResponseWriter, r *http.Request, fallback, max int64) (int, bool) {
	n, err := number(r, "limit", fallback, max)
	if err != nil || n == 0 {
		writeError(w, 400, "invalid limit")
		return 0, false
	}
	return int(n), true
}

// mutation distinguishes validation errors from unavailable persistence.
func (h *Handler) mutation(w http.ResponseWriter, v any, err error) {
	h.respond(w, v, err)
}

// applicationError maps domain errors and logs private persistence failures.
func (h *Handler) applicationError(err error) (int, string) {
	var invalid *telemetry.ValidationError
	switch {
	case errors.As(err, &invalid):
		return 400, invalid.Error()
	case errors.Is(err, telemetry.ErrNotFound):
		return 404, "session not found"
	default:
		h.logger.Error("request failed", "error", err)
		return 500, "internal server error"
	}
}
