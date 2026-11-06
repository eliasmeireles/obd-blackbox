package httpapi

import (
	"context"
	"encoding/base64"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

// testAuth creates deterministic authentication settings.
func testAuth() *Auth {
	a := NewAuth(map[string]string{"example": testKey}, strings.Repeat("s", 32), time.Hour, true, "http://localhost:8080")
	a.delay = 0
	a.now = func() time.Time { return time.Unix(1000, 0) }
	return a
}

// TestAuthKeys checks digest comparisons and unknown keys.
func TestAuthKeys(t *testing.T) {
	t.Run("given a configured key then identity matches", func(t *testing.T) {
		name, ok := testAuth().KeyName(testKey)
		require.True(t, ok)
		require.Equal(t, "example", name)
	})
	t.Run("given an unknown key then access fails", func(t *testing.T) { _, ok := testAuth().KeyName("invalid"); require.False(t, ok) })
}

// TestAuthCookie covers signed cookies, tampering, malformed identities and expiration.
func TestAuthCookie(t *testing.T) {
	t.Run("given a signed cookie then identity verifies", func(t *testing.T) {
		a := testAuth()
		value := a.Sign("example")
		name, ok := a.Verify(value)
		require.True(t, ok)
		require.Equal(t, "example", name)
		raw, err := base64.RawURLEncoding.DecodeString(value)
		require.NoError(t, err)
		require.True(t, strings.HasPrefix(string(raw), "example|4600|"))
	})
	t.Run("given an expired cookie then it is rejected", func(t *testing.T) {
		a := testAuth()
		value := a.Sign("example")
		a.now = func() time.Time { return time.Unix(4600, 0) }
		_, ok := a.Verify(value)
		require.False(t, ok)
	})
	for _, value := range []string{"!", base64.RawURLEncoding.EncodeToString([]byte("bad")), base64.RawURLEncoding.EncodeToString([]byte("example|4600|00"))} {
		t.Run("given a malformed cookie then verification fails", func(t *testing.T) { _, ok := testAuth().Verify(value); require.False(t, ok) })
	}
	t.Run("given a tampered payload then signature validation fails", func(t *testing.T) {
		a := testAuth()
		raw, err := base64.RawURLEncoding.DecodeString(a.Sign("example"))
		require.NoError(t, err)
		raw[0] = 'X'
		_, ok := a.Verify(base64.RawURLEncoding.EncodeToString(raw))
		require.False(t, ok)
	})
}

// TestAuthHandlers checks the authenticated identity response.
func TestAuthHandlers(t *testing.T) {
	t.Run("given identity then me returns its name", func(t *testing.T) {
		r := httptest.NewRequest("GET", "/", nil).WithContext(context.WithValue(context.Background(), identityKey{}, "example"))
		w := httptest.NewRecorder()
		testAuth().Me(w, r)
		require.JSONEq(t, `{"name":"example"}`, w.Body.String())
	})
}

// TestAuthMiddleware checks bearer and cookie access and explicit invalid credentials.
func TestAuthMiddleware(t *testing.T) {
	for _, tc := range []struct {
		name, header, cookie string
		status               int
	}{{"bearer", "Bearer " + testKey, "", 200}, {"cookie", "", testAuth().Sign("example"), 200}, {"missing", "", "", 401}, {"invalid bearer", "Bearer bad", testAuth().Sign("example"), 401}, {"wrong scheme", "Basic abc", "", 401}, {"malformed", "Bearer", "", 401}, {"invalid cookie", "", "invalid", 401}} {
		t.Run("given "+tc.name+" then middleware enforces authentication", func(t *testing.T) {
			r := httptest.NewRequest("GET", "/", nil)
			r.Header.Set("Authorization", tc.header)
			r.AddCookie(&http.Cookie{Name: "tm_session", Value: tc.cookie})
			w := httptest.NewRecorder()
			testAuth().Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				require.Equal(t, "example", r.Context().Value(identityKey{}))
				w.WriteHeader(200)
			})).ServeHTTP(w, r)
			require.Equal(t, tc.status, w.Code)
		})
	}
}
