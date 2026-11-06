package httpapi

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
	"github.com/stretchr/testify/require"
)

// dashboardRequest invokes the router using a signed fictional dashboard identity.
func dashboardRequest(router http.Handler, method, path string) *httptest.ResponseRecorder {
	auth := NewAuth(nil, strings.Repeat("s", 32), time.Hour, false, "http://localhost:8080")
	r := httptest.NewRequest(method, path, nil)
	r.AddCookie(&http.Cookie{Name: "tm_session", Value: auth.Sign("example-subject")})
	w := httptest.NewRecorder()
	router.ServeHTTP(w, r)
	return w
}

// TestHistoryDeletes verifies dashboard authorization and deletion responses.
func TestHistoryDeletes(t *testing.T) {
	for _, tc := range []struct{ path, want string }{
		{"/sessions/" + testID, `{"deleted":1}`},
		{"/sessions?source=sim", `{"deleted":2}`},
		{"/sessions?source=obd", `{"deleted":2}`},
		{"/sessions?source=all", `{"deleted":2}`},
	} {
		t.Run("given dashboard then DELETE "+tc.path+" succeeds", func(t *testing.T) {
			w := dashboardRequest(testRouter(&fakeService{}, telemetry.NewHub()), "DELETE", "/api/v1"+tc.path)
			require.Equal(t, 200, w.Code)
			require.JSONEq(t, tc.want, w.Body.String())
		})
		t.Run("given device key then DELETE "+tc.path+" is forbidden", func(t *testing.T) {
			w := request(testRouter(&fakeService{}, telemetry.NewHub()), "DELETE", "/api/v1"+tc.path, "")
			require.Equal(t, 403, w.Code)
			require.JSONEq(t, `{"error":"forbidden"}`, w.Body.String())
		})
	}
	for _, path := range []string{"/sessions", "/sessions?source=", "/sessions?source=invalid", "/sessions/bad"} {
		t.Run("given invalid deletion input then bad request is returned", func(t *testing.T) {
			w := dashboardRequest(testRouter(&fakeService{}, telemetry.NewHub()), "DELETE", "/api/v1"+path)
			require.Equal(t, 400, w.Code)
		})
	}
	for _, tc := range []struct {
		err    error
		status int
	}{{telemetry.ErrNotFound, 404}, {errors.New("offline"), 500}} {
		t.Run("given deletion failure then the appropriate status is returned", func(t *testing.T) {
			w := dashboardRequest(testRouter(&fakeService{err: tc.err}, telemetry.NewHub()), "DELETE", "/api/v1/sessions/"+testID)
			require.Equal(t, tc.status, w.Code)
		})
	}
}

// TestListSources checks source forwarding and invalid query rejection.
func TestListSources(t *testing.T) {
	for _, path := range []string{"/sessions", "/events"} {
		for _, tc := range []struct{ source, query string }{{"obd", "?source=obd"}, {"sim", "?source=sim"}, {"", ""}} {
			t.Run("given source "+tc.source+" then "+path+" forwards the filter", func(t *testing.T) {
				s := &fakeService{}
				w := request(testRouter(s, telemetry.NewHub()), "GET", "/api/v1"+path+tc.query, "")
				require.Equal(t, 200, w.Code)
				require.Equal(t, tc.source, s.source)
			})
		}
	}
	for _, path := range []string{"/sessions", "/events", "/live", "/live/stream"} {
		for _, source := range []string{"all", "invalid", ""} {
			t.Run("given invalid source then "+path+" returns bad request", func(t *testing.T) {
				w := request(testRouter(&fakeService{}, telemetry.NewHub()), "GET", "/api/v1"+path+"?source="+source, "")
				require.Equal(t, 400, w.Code)
			})
		}
	}
}

// TestDeviceFilters checks forwarding for every history listing and bulk deletion.
func TestDeviceFilters(t *testing.T) {
	for _, path := range []string{"/sessions", "/events", "/diagnostics"} {
		t.Run("given a device filter then "+path+" forwards it to the service", func(t *testing.T) {
			service := &fakeService{}
			w := request(testRouter(service, telemetry.NewHub()), "GET", "/api/v1"+path+"?device=phone-a", "")
			require.Equal(t, 200, w.Code)
			require.Equal(t, "phone-a", service.device)
		})
	}
	t.Run("given a device filter then bulk deletion forwards device and source", func(t *testing.T) {
		service := &fakeService{}
		w := dashboardRequest(testRouter(service, telemetry.NewHub()), "DELETE", "/api/v1/sessions?source=all&device=phone-a")
		require.Equal(t, 200, w.Code)
		require.Equal(t, "phone-a", service.device)
		require.Equal(t, "all", service.source)
	})
	for _, path := range []string{"/sessions", "/events", "/diagnostics", "/live", "/live/stream"} {
		t.Run("given an empty device filter then "+path+" rejects it", func(t *testing.T) {
			w := request(testRouter(&fakeService{}, telemetry.NewHub()), "GET", "/api/v1"+path+"?device=", "")
			require.Equal(t, 400, w.Code)
		})
	}
	t.Run("given a key and signed cookie then an explicit bearer still cannot delete", func(t *testing.T) {
		r := httptest.NewRequest("DELETE", "/api/v1/sessions?source=all", nil)
		r.Header.Set("Authorization", "Bearer "+testKey)
		r.AddCookie(&http.Cookie{Name: "tm_session", Value: NewAuth(nil, strings.Repeat("s", 32), time.Hour, false, "http://localhost:8080").Sign("example")})
		w := httptest.NewRecorder()
		testRouter(&fakeService{}, telemetry.NewHub()).ServeHTTP(w, r)
		require.Equal(t, 403, w.Code)
	})
}
