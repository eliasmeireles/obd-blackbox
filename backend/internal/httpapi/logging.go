package httpapi

import (
	"net/http"
	"time"
)

type responseLog struct {
	http.ResponseWriter
	status int
}

// WriteHeader records the first HTTP response status.
func (w *responseLog) WriteHeader(status int) {
	if w.status != 0 {
		return
	}
	w.status = status
	w.ResponseWriter.WriteHeader(status)
}

// Write records implicit successful response headers.
func (w *responseLog) Write(data []byte) (int, error) {
	if w.status == 0 {
		w.WriteHeader(200)
	}
	return w.ResponseWriter.Write(data)
}

// Unwrap exposes the original writer to the response controller.
func (w *responseLog) Unwrap() http.ResponseWriter { return w.ResponseWriter }

// Flush forwards streaming flushes to the underlying connection.
func (w *responseLog) Flush() {
	if w.status == 0 {
		w.WriteHeader(200)
	}
	http.NewResponseController(w.ResponseWriter).Flush()
}

// logging records method, path, status and elapsed time without credentials.
func (h *Handler) logging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		logged := &responseLog{ResponseWriter: w}
		next.ServeHTTP(logged, r)
		if logged.status == http.StatusSwitchingProtocols {
			return
		}
		status := logged.status
		if status == 0 {
			status = 200
		}
		h.logger.Info("http request", "method", r.Method, "path", r.URL.Path, "status", status, "duration", time.Since(start))
	})
}
