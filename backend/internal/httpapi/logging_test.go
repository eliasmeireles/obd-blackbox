package httpapi

import (
	"bytes"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/stretchr/testify/require"
)

// TestLogging checks response tracking, flush forwarding and credential omission.
func TestLogging(t *testing.T) {
	t.Run("given a request then only nonsecret request fields are logged", func(t *testing.T) {
		var buf bytes.Buffer
		h := &Handler{logger: slog.New(slog.NewJSONHandler(&buf, nil))}
		r := httptest.NewRequest("GET", "/example?key=private", nil)
		r.Header.Set("Authorization", "Bearer private")
		r.Header.Set("Cookie", "private")
		w := httptest.NewRecorder()
		h.logging(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("ok")) })).ServeHTTP(w, r)
		require.Contains(t, buf.String(), `"status":200`)
		require.Contains(t, buf.String(), `"path":"/example"`)
		require.NotContains(t, buf.String(), "private")
	})
	t.Run("given repeated headers and flush then the first status is retained", func(t *testing.T) {
		base := httptest.NewRecorder()
		w := &responseLog{ResponseWriter: base}
		w.WriteHeader(201)
		w.WriteHeader(500)
		w.Flush()
		require.Equal(t, 201, w.status)
		require.True(t, base.Flushed)
		require.Same(t, base, w.Unwrap())
	})
}
