package httpapi

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
	"github.com/stretchr/testify/require"
)

// IngestLogs provides real domain validation and fake persistence for HTTP tests.
func (f *fakeService) IngestLogs(ctx context.Context, device string, batch []telemetry.LogInput) (telemetry.BatchResult, error) {
	f.logDevice = device
	return telemetry.NewService(&httpLogRepository{service: f}, nil).IngestLogs(ctx, device, batch)
}

// Logs captures the parsed filter and returns the fixture.
func (f *fakeService) Logs(_ context.Context, q telemetry.LogQuery) ([]telemetry.AppLog, error) {
	f.logQuery = q
	return f.logEntries, f.err
}

type httpLogRepository struct {
	telemetry.Repository
	service *fakeService
}

// InsertLogs returns controlled counts after domain validation.
func (r *httpLogRepository) InsertLogs(_ context.Context, _ string, b []telemetry.LogInput) (telemetry.BatchResult, error) {
	r.service.logBatch = b
	return telemetry.BatchResult{Accepted: 1, Duplicates: len(b) - 1}, r.service.err
}

// TestLogHTTP verifies authentication, batch validation, rendering and storage failures.
func TestLogHTTP(t *testing.T) {
	body := `{"logs":[{"id":"` + testID + `","t":1,"level":"warn","tag":"elm","message":"m"},{"id":"` + testID + `","t":1,"level":"warn","tag":"elm","message":"m"}]}`
	t.Run("given a retry batch then counts and authenticated identity are returned", func(t *testing.T) {
		f := &fakeService{}
		w := request(testRouter(f, telemetry.NewHub()), "POST", "/api/v1/logs", body)
		require.Equal(t, 200, w.Code)
		require.JSONEq(t, `{"accepted":1,"duplicates":1}`, w.Body.String())
		require.Equal(t, "example", f.logDevice)
	})

	t.Run("given an oversized unicode message then HTTP truncates it and ignores supplied device", func(t *testing.T) {
		f := &fakeService{}
		body := `{"device":"untrusted","logs":[{"id":"` + testID + `","t":1,"level":"info","tag":"app","message":"` + strings.Repeat("🚙", 4001) + `"}]}`
		w := request(testRouter(f, telemetry.NewHub()), "POST", "/api/v1/logs", body)
		require.Equal(t, 200, w.Code)
		require.Equal(t, "example", f.logDevice)
		require.Len(t, f.logBatch, 1)
		require.Equal(t, 4000, len([]rune(f.logBatch[0].Message)))
	})
	t.Run("given dashboard then uploads use the signed identity and reads are allowed", func(t *testing.T) {
		f := &fakeService{}
		router := testRouter(f, telemetry.NewHub())
		auth := NewAuth(nil, strings.Repeat("s", 32), time.Hour, false, "http://localhost:8080")
		r := httptest.NewRequest("POST", "/api/v1/logs", strings.NewReader(body))
		r.AddCookie(&http.Cookie{Name: "tm_session", Value: auth.Sign("example-subject")})
		w := httptest.NewRecorder()
		router.ServeHTTP(w, r)
		require.Equal(t, 200, w.Code)
		require.Equal(t, "example-subject", f.logDevice)
		require.Equal(t, 200, dashboardRequest(router, "GET", "/api/v1/logs").Code)
		require.Equal(t, 200, dashboardRequest(router, "GET", "/api/v1/logs.txt").Code)
	})
	for _, body := range []string{`{}`, `{"logs":[]}`, `{"logs":[{}]}`, `{"logs":[{"id":"bad"}]}`, `{"logs":[null]}`, `{"logs":"bad"}`, `{} {}`, `broken`} {
		t.Run("given invalid logs then HTTP returns bad request", func(t *testing.T) {
			w := request(testRouter(&fakeService{}, telemetry.NewHub()), "POST", "/api/v1/logs", body)
			require.Equal(t, 400, w.Code)
		})
	}
	for _, path := range []string{"/api/v1/logs", "/api/v1/logs.txt"} {
		t.Run("given no credentials then logs are protected", func(t *testing.T) {
			w := httptest.NewRecorder()
			testRouter(&fakeService{}, telemetry.NewHub()).ServeHTTP(w, httptest.NewRequest("GET", path, nil))
			require.Equal(t, 401, w.Code)
		})
	}
	t.Run("given an unauthenticated upload then it is rejected", func(t *testing.T) {
		w := httptest.NewRecorder()
		testRouter(&fakeService{}, telemetry.NewHub()).ServeHTTP(w, httptest.NewRequest("POST", "/api/v1/logs", strings.NewReader(body)))
		require.Equal(t, 401, w.Code)
	})
	t.Run("given stored logs then JSON and text use milliseconds and configured timezone", func(t *testing.T) {
		f := &fakeService{logEntries: []telemetry.AppLog{{LogInput: telemetry.LogInput{ID: testID, T: 1793977200123, Level: "warn", Tag: "elm", Message: "first\r\nsecond\nthird\rfourth", SessionID: new(testID)}, Device: "example"}}}
		zone := time.FixedZone("example", -3*3600)
		h := &Handler{service: f, timezone: zone, logger: slog.New(slog.NewTextHandler(io.Discard, nil))}
		w := httptest.NewRecorder()
		h.logs(w, httptest.NewRequest("GET", "/api/v1/logs", nil))
		timestamp := time.UnixMilli(f.logEntries[0].T).In(zone).Format(logTimeFormat)
		require.JSONEq(t, fmt.Sprintf(`{"logs":[{"id":%q,"device":"example","t":%q,"level":"warn","tag":"elm","message":"first\r\nsecond\nthird\rfourth","session_id":%q}]}`, testID, timestamp, testID), w.Body.String())
		w = httptest.NewRecorder()
		h.logs(w, httptest.NewRequest("GET", "/api/v1/logs.txt", nil))
		require.Equal(t, "text/plain; charset=utf-8", w.Header().Get("Content-Type"))
		require.Equal(t, timestamp+" example WARN elm first↵second↵third↵fourth\n", w.Body.String())
	})
	t.Run("given empty results then JSON contains an empty array", func(t *testing.T) {
		w := request(testRouter(&fakeService{}, telemetry.NewHub()), "GET", "/api/v1/logs", "")
		require.JSONEq(t, `{"logs":[]}`, w.Body.String())
	})
	for _, method := range []string{"GET", "POST"} {
		t.Run("given storage failure then private errors are hidden", func(t *testing.T) {
			w := request(testRouter(&fakeService{err: errors.New("private")}, telemetry.NewHub()), method, "/api/v1/logs", body)
			require.Equal(t, 500, w.Code)
			require.JSONEq(t, `{"error":"internal server error"}`, w.Body.String())
		})
	}
}
