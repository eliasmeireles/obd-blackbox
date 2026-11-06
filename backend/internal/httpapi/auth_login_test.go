package httpapi

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

// TestAPIKeyLogin checks valid, absent and invalid dashboard credentials.
func TestAPIKeyLogin(t *testing.T) {
	t.Run("given a valid key then a signed dashboard cookie is issued", func(t *testing.T) {
		a := testAuth()
		w := httptest.NewRecorder()
		a.Login(w, httptest.NewRequest("POST", "/api/v1/auth/login", strings.NewReader(`{"key":"`+testKey+`"}`)))
		require.Equal(t, 200, w.Code)
		require.JSONEq(t, `{"name":"example"}`, w.Body.String())
		require.Len(t, w.Result().Cookies(), 1)
		cookie := w.Result().Cookies()[0]
		require.True(t, cookie.HttpOnly)
		require.True(t, cookie.Secure)
		require.Equal(t, http.SameSiteStrictMode, cookie.SameSite)
		require.Equal(t, 3600, cookie.MaxAge)
		name, valid := a.Verify(cookie.Value)
		require.True(t, valid)
		require.Equal(t, "example", name)
	})
	for _, body := range []string{"", `{"key":"wrong"}`, `{"key":""}`, `{}`, `null`} {
		t.Run("given missing or wrong credentials then login is unauthorized", func(t *testing.T) {
			w := httptest.NewRecorder()
			testAuth().Login(w, httptest.NewRequest("POST", "/", strings.NewReader(body)))
			require.Equal(t, 401, w.Code)
			require.Empty(t, w.Result().Cookies())
		})
	}
	t.Run("given malformed JSON then login rejects the request", func(t *testing.T) {
		w := httptest.NewRecorder()
		testAuth().Login(w, httptest.NewRequest("POST", "/", strings.NewReader(`{"key":1}`)))
		require.Equal(t, 400, w.Code)
	})
	t.Run("given a configured delay then failed login waits before responding", func(t *testing.T) {
		a := testAuth()
		a.delay = 5 * time.Millisecond
		started := time.Now()
		a.Login(httptest.NewRecorder(), httptest.NewRequest("POST", "/", strings.NewReader(`{}`)))
		require.GreaterOrEqual(t, time.Since(started), a.delay)
	})
	t.Run("given canceled login then no cookie is created", func(t *testing.T) {
		a := testAuth()
		a.delay = time.Hour
		ctx, cancel := context.WithCancel(context.Background())
		cancel()
		w := httptest.NewRecorder()
		a.Login(w, httptest.NewRequest("POST", "/", strings.NewReader(`{}`)).WithContext(ctx))
		require.Empty(t, w.Result().Cookies())
	})
}

// TestProviderSessions checks authorized identities, cookie cleanup and invalid names.
func TestProviderSessions(t *testing.T) {
	t.Run("given an external identity then Start signs it and End expires it", func(t *testing.T) {
		a := testAuth()
		r := httptest.NewRequest("GET", "/auth/callback", nil)
		w := httptest.NewRecorder()
		require.NoError(t, a.Start(w, r, "external-subject"))
		name, valid := a.Verify(w.Result().Cookies()[0].Value)
		require.True(t, valid)
		require.Equal(t, "external-subject", name)
		require.Equal(t, "http://localhost:8080", a.BaseURL())
		logout := httptest.NewRecorder()
		a.Logout(logout, r)
		require.Equal(t, 200, logout.Code)
		require.Equal(t, -1, logout.Result().Cookies()[0].MaxAge)
		require.Empty(t, logout.Result().Cookies()[0].Value)
	})
	for _, identity := range []string{"", "a|b", "a\nb", "a\rb"} {
		t.Run("given an invalid provider identity then no cookie is created", func(t *testing.T) {
			w := httptest.NewRecorder()
			require.Error(t, testAuth().Start(w, httptest.NewRequest("GET", "/", nil), identity))
			require.Empty(t, w.Result().Cookies())
		})
	}
}
