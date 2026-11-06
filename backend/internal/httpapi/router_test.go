package httpapi

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"testing/fstest"
	"time"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
	loginauth "github.com/eliasmeireles/obd-blackbox/backend/pkg/auth"
	"github.com/stretchr/testify/require"
)

const testID = "00000000-0000-0000-0000-000000000001"

var testKey = strings.Repeat("x", 32)

type fakeService struct {
	device       string
	input        telemetry.SessionInput
	knownDevices []telemetry.Device
	logDevice    string
	logQuery     telemetry.LogQuery
	logEntries   []telemetry.AppLog
	logBatch     []telemetry.LogInput
	err          error
	source       string
	q            telemetry.Query
	session      telemetry.Session
	sample       telemetry.Sample
	calls        int
}

// Upsert returns controlled session metadata.
func (f *fakeService) Upsert(_ context.Context, _ string, input telemetry.SessionInput) (telemetry.Session, error) {
	f.input = input
	return f.session, f.err
}

// Get returns a controlled session or error.
func (f *fakeService) Get(context.Context, string) (telemetry.Session, error) {
	return f.session, f.err
}

// Sessions returns a controlled session list.
func (f *fakeService) Sessions(_ context.Context, _ int, source, device string) ([]telemetry.Session, error) {
	f.source = source
	f.device = device
	return []telemetry.Session{f.session}, f.err
}

// Ingest returns an accepted sample count.
func (f *fakeService) Ingest(context.Context, string, []telemetry.SampleInput) (telemetry.BatchResult, error) {
	return telemetry.BatchResult{Accepted: 1}, f.err
}

// Samples captures query parameters and returns a controlled sample list.
func (f *fakeService) Samples(_ context.Context, _ string, q telemetry.Query) ([]telemetry.Sample, error) {
	f.q = q
	f.calls++
	return []telemetry.Sample{f.sample}, f.err
}

// Events returns a controlled event list.
func (f *fakeService) Events(_ context.Context, _ string, _ int, source, device string) ([]telemetry.Event, error) {
	f.source = source
	f.device = device
	return []telemetry.Event{{SessionID: testID, T: 1, Label: "mark", Automatic: true}}, f.err
}

// StoreDTCs returns an inserted diagnostic count.
func (f *fakeService) StoreDTCs(context.Context, string, telemetry.DTCInput) (int, error) {
	return 1, f.err
}

// DTCs returns a diagnostic list.
func (f *fakeService) DTCs(context.Context, string) ([]telemetry.DTC, error) {
	return []telemetry.DTC{{T: 1, Kind: "stored", Code: "P0101"}}, f.err
}

// Ping returns a controlled readiness result.
func (f *fakeService) Ping(context.Context) error { return f.err }

// testRouter constructs the public router with an in-memory dashboard.
func testRouter(s *fakeService, h *telemetry.Hub) http.Handler {
	auth := NewAuth(map[string]string{"example": testKey}, strings.Repeat("s", 32), time.Hour, false, "http://localhost:8080")
	return NewRouter(s, h, auth, nil, fstest.MapFS{"index.html": &fstest.MapFile{Data: []byte("dashboard")}}, time.UTC, slog.New(slog.NewTextHandler(io.Discard, nil)))
}

// request invokes a handler with a device bearer key.
func request(router http.Handler, method, path, body string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, path, strings.NewReader(body))
	r.Header.Set("Authorization", "Bearer "+testKey)
	w := httptest.NewRecorder()
	router.ServeHTTP(w, r)
	return w
}

// TestRouter checks unauthenticated surfaces, middleware and routing.
func TestRouter(t *testing.T) {
	t.Run("given public resources then no authentication is required", func(t *testing.T) {
		router := testRouter(&fakeService{}, telemetry.NewHub())
		for _, path := range []string{"/healthz", "/readyz", "/"} {
			w := httptest.NewRecorder()
			router.ServeHTTP(w, httptest.NewRequest("GET", path, nil))
			require.Equal(t, 200, w.Code)
		}
	})
	t.Run("given an unauthenticated API call then JSON unauthorized is returned", func(t *testing.T) {
		w := httptest.NewRecorder()
		testRouter(&fakeService{}, telemetry.NewHub()).ServeHTTP(w, httptest.NewRequest("GET", "/api/v1/sessions", nil))
		require.Equal(t, 401, w.Code)
		require.JSONEq(t, `{"error":"unauthorized"}`, w.Body.String())
	})
	t.Run("given an unknown API path then JSON not found is returned", func(t *testing.T) {
		w := request(testRouter(&fakeService{}, telemetry.NewHub()), "GET", "/api/v1/unknown", "")
		require.Equal(t, 404, w.Code)
		require.JSONEq(t, `{"error":"not found"}`, w.Body.String())
	})
	t.Run("given an unavailable database then readiness fails", func(t *testing.T) {
		w := request(testRouter(&fakeService{err: errors.New("offline")}, telemetry.NewHub()), "GET", "/readyz", "")
		require.Equal(t, 503, w.Code)
		require.JSONEq(t, `{"error":"database unavailable"}`, w.Body.String())
	})
}

// Delete reports a removed session.
func (f *fakeService) Delete(context.Context, string) (int, error) { return 1, f.err }

// DeleteSource reports removed sessions.
func (f *fakeService) DeleteSource(_ context.Context, source, device string) (int, error) {
	f.source = source
	f.device = device
	return 2, f.err
}

// Devices supplies persisted device fixtures merged through the real service.
func (f *fakeService) Devices(ctx context.Context, configured []string, connected []telemetry.Device) ([]telemetry.Device, error) {
	return telemetry.NewService(&deviceListRepository{devices: f.knownDevices, err: f.err}, nil).Devices(ctx, configured, connected)
}

type deviceListRepository struct {
	telemetry.Repository
	devices []telemetry.Device
	err     error
}

// Devices supplies the stored identities for handler fixtures.
func (r *deviceListRepository) Devices(context.Context) ([]telemetry.Device, error) {
	return r.devices, r.err
}

type fakeLoginProvider struct{ allowAPIKey bool }

// Mount simulates a provider callback after successful external verification.
func (f *fakeLoginProvider) Mount(mux *http.ServeMux, sessions loginauth.Sessions) {
	mux.HandleFunc("GET /auth/callback", func(w http.ResponseWriter, r *http.Request) {
		_ = sessions.Start(w, r, "external-subject")
		w.WriteHeader(204)
	})
}

// Methods describes the custom provider for the dashboard.
func (f *fakeLoginProvider) Methods() loginauth.Methods {
	return loginauth.Methods{Methods: []string{"oidc"}, LoginURL: "/auth/login", Label: "Entrar com SSO"}
}

// AllowAPIKeyLogin selects whether the built-in dashboard key login is available.
func (f *fakeLoginProvider) AllowAPIKeyLogin() bool { return f.allowAPIKey }

// TestLoginRoutes checks default methods, provider replacement and public login routes.
func TestLoginRoutes(t *testing.T) {
	t.Run("given default authentication then methods and key login are public", func(t *testing.T) {
		router := testRouter(&fakeService{}, telemetry.NewHub())
		methods := httptest.NewRecorder()
		router.ServeHTTP(methods, httptest.NewRequest("GET", "/api/v1/auth/methods", nil))
		require.Equal(t, 200, methods.Code)
		require.JSONEq(t, `{"methods":["api_key"]}`, methods.Body.String())
		login := httptest.NewRecorder()
		router.ServeHTTP(login, httptest.NewRequest("POST", "/api/v1/auth/login", strings.NewReader(`{"key":"`+testKey+`"}`)))
		require.Equal(t, 200, login.Code)
		me := httptest.NewRequest("GET", "/api/v1/auth/me", nil)
		me.AddCookie(login.Result().Cookies()[0])
		response := httptest.NewRecorder()
		router.ServeHTTP(response, me)
		require.JSONEq(t, `{"name":"example"}`, response.Body.String())
		logout := httptest.NewRecorder()
		router.ServeHTTP(logout, httptest.NewRequest("POST", "/api/v1/auth/logout", nil))
		require.Equal(t, -1, logout.Result().Cookies()[0].MaxAge)
	})
	t.Run("given a custom provider then methods are replaced and API key login is disabled", func(t *testing.T) {
		a := testAuth()
		router := NewRouter(&fakeService{}, telemetry.NewHub(), a, &fakeLoginProvider{}, fstest.MapFS{}, time.UTC, slog.Default())
		methods := httptest.NewRecorder()
		router.ServeHTTP(methods, httptest.NewRequest("GET", "/api/v1/auth/methods", nil))
		require.JSONEq(t, `{"methods":["oidc"],"login_url":"/auth/login","label":"Entrar com SSO"}`, methods.Body.String())
		require.Equal(t, 404, request(router, "POST", "/api/v1/auth/login", `{"key":"`+testKey+`"}`).Code)
		require.Equal(t, 200, request(router, "GET", "/api/v1/sessions", "").Code)
		callback := httptest.NewRecorder()
		router.ServeHTTP(callback, httptest.NewRequest("GET", "/auth/callback", nil))
		me := httptest.NewRequest("GET", "/api/v1/auth/me", nil)
		me.AddCookie(callback.Result().Cookies()[0])
		response := httptest.NewRecorder()
		router.ServeHTTP(response, me)
		require.JSONEq(t, `{"name":"external-subject"}`, response.Body.String())
	})
	t.Run("given a provider allowing API keys then the built-in login remains available", func(t *testing.T) {
		router := NewRouter(&fakeService{}, telemetry.NewHub(), testAuth(), &fakeLoginProvider{allowAPIKey: true}, fstest.MapFS{}, time.UTC, slog.Default())
		require.Equal(t, 200, request(router, "POST", "/api/v1/auth/login", `{"key":"`+testKey+`"}`).Code)
	})
}

// TestDevicesRoute checks configured, historical and currently connected identities.
func TestDevicesRoute(t *testing.T) {
	t.Run("given configured and historical devices then disconnected devices remain visible", func(t *testing.T) {
		seen := int64(123)
		service := &fakeService{knownDevices: []telemetry.Device{{Name: "retired", LastSeen: &seen}}}
		w := request(testRouter(service, telemetry.NewHub()), "GET", "/api/v1/devices", "")
		require.Equal(t, 200, w.Code)
		require.JSONEq(t, `{"devices":[{"name":"example","connected":false,"connected_since":null,"last_seen":null},{"name":"retired","connected":false,"connected_since":null,"last_seen":123}]}`, w.Body.String())
	})
	t.Run("given a connected device then its connection metadata is returned", func(t *testing.T) {
		router, h := commandTestRouter(time.Second)
		h.devices.Register("phone", func(context.Context, telemetry.Command) error { return nil })
		w := request(router, "GET", "/api/v1/devices", "")
		require.Equal(t, 200, w.Code)
		require.Contains(t, w.Body.String(), `"connected":true`)
		require.Contains(t, w.Body.String(), `"name":"phone"`)
	})
}
