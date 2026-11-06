// Package httpapi exposes authenticated telemetry HTTP endpoints.
package httpapi

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"
)

type identityKey struct{}
type dashboardSessionKey struct{}
type keyDigest struct {
	name   string
	digest [32]byte
}
type Auth struct {
	keys   []keyDigest
	secret []byte
	ttl    time.Duration
	secure bool
	now    func() time.Time
	delay  time.Duration
	appURL string
}

// NewAuth hashes API keys and constructs signed cookie authentication.
func NewAuth(keys map[string]string, secret string, ttl time.Duration, secure bool, appURL string) *Auth {
	a := &Auth{secret: []byte(secret), ttl: ttl, secure: secure, now: time.Now, delay: 300 * time.Millisecond, appURL: appURL}
	for name, key := range keys {
		a.keys = append(a.keys, keyDigest{name: name, digest: sha256.Sum256([]byte(key))})
	}
	return a
}

// KeyName checks every SHA-256 digest in constant time.
func (a *Auth) KeyName(key string) (string, bool) {
	digest := sha256.Sum256([]byte(key))
	name := ""
	valid := 0
	for _, candidate := range a.keys {
		match := subtle.ConstantTimeCompare(digest[:], candidate.digest[:])
		valid |= match
		if match == 1 {
			name = candidate.name
		}
	}
	return name, valid == 1
}

// Sign creates a base64url session containing identity, expiration and HMAC.
func (a *Auth) Sign(name string) string {
	payload := name + "|" + strconv.FormatInt(a.now().Add(a.ttl).Unix(), 10)
	mac := a.mac(payload)
	return base64.RawURLEncoding.EncodeToString([]byte(payload + "|" + hex.EncodeToString(mac)))
}

// Verify checks the cookie signature, expiry and provider-authorized identity.
func (a *Auth) Verify(value string) (string, bool) {
	data, err := base64.RawURLEncoding.DecodeString(value)
	if err != nil {
		return "", false
	}
	parts := strings.Split(string(data), "|")
	if len(parts) != 3 {
		return "", false
	}
	sig, err := hex.DecodeString(parts[2])
	if err != nil || !hmac.Equal(sig, a.mac(parts[0]+"|"+parts[1])) {
		return "", false
	}
	expires, err := strconv.ParseInt(parts[1], 10, 64)
	if err != nil || expires <= a.now().Unix() {
		return "", false
	}
	if parts[0] == "" || strings.ContainsAny(parts[0], "\r\n") {
		return "", false
	}
	return parts[0], true
}

// mac signs an identity and expiration payload.
func (a *Auth) mac(payload string) []byte {
	m := hmac.New(sha256.New, a.secret)
	m.Write([]byte(payload))
	return m.Sum(nil)
}

// Middleware requires bearer or dashboard session authentication.
func (a *Auth) Middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		name, ok := a.authenticate(r)
		if !ok {
			writeError(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		ctx := context.WithValue(r.Context(), identityKey{}, name)
		ctx = context.WithValue(ctx, dashboardSessionKey{}, r.Header.Get("Authorization") == "")
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

// authenticate checks an explicit bearer token before a dashboard cookie.
func (a *Auth) authenticate(r *http.Request) (string, bool) {
	if header := r.Header.Get("Authorization"); header != "" {
		scheme, token, ok := strings.Cut(header, " ")
		if !ok || !strings.EqualFold(scheme, "Bearer") {
			return "", false
		}
		return a.KeyName(token)
	}
	cookie, err := r.Cookie("tm_session")
	if err != nil {
		return "", false
	}
	return a.Verify(cookie.Value)
}

// Me returns the authenticated identity.
func (a *Auth) Me(w http.ResponseWriter, r *http.Request) {
	name, _ := r.Context().Value(identityKey{}).(string)
	writeJSON(w, 200, map[string]string{"name": name})
}

// setCookie writes the consistent dashboard cookie attributes.
func (a *Auth) setCookie(w http.ResponseWriter, value string, maxAge int, expires time.Time) {
	http.SetCookie(w, &http.Cookie{Name: "tm_session", Value: value, Path: "/", HttpOnly: true, Secure: a.secure, SameSite: http.SameSiteStrictMode, MaxAge: maxAge, Expires: expires})
}

// KeyNames returns the configured device identities without exposing their credentials.
func (a *Auth) KeyNames() []string {
	names := make([]string, 0, len(a.keys))
	for _, key := range a.keys {
		names = append(names, key.name)
	}
	sort.Strings(names)
	return names
}
