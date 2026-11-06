package httpapi

import (
	"fmt"
	"net/http"
	"strings"
	"time"
)

// Login validates an API key and opens a dashboard session with its device name.
func (a *Auth) Login(w http.ResponseWriter, r *http.Request) {
	timer := time.NewTimer(a.delay)
	defer timer.Stop()
	select {
	case <-timer.C:
	case <-r.Context().Done():
		return
	}
	if r.Body == nil || r.ContentLength == 0 {
		writeError(w, 401, "unauthorized")
		return
	}
	var in struct {
		Key string `json:"key"`
	}
	if !decode(w, r, &in) {
		return
	}
	name, ok := a.KeyName(in.Key)
	if !ok {
		writeError(w, 401, "unauthorized")
		return
	}
	if err := a.Start(w, r, name); err != nil {
		writeError(w, 500, "session unavailable")
		return
	}
	writeJSON(w, 200, map[string]string{"name": name})
}

// Start signs a session for an identity already authenticated by a login provider.
func (a *Auth) Start(w http.ResponseWriter, _ *http.Request, identity string) error {
	if identity == "" || strings.ContainsAny(identity, "|\r\n") {
		return fmt.Errorf("invalid dashboard identity")
	}
	a.setCookie(w, a.Sign(identity), int(a.ttl.Seconds()), a.now().Add(a.ttl))
	return nil
}

// End expires the dashboard session cookie.
func (a *Auth) End(w http.ResponseWriter, _ *http.Request) error {
	a.setCookie(w, "", -1, time.Unix(1, 0))
	return nil
}

// BaseURL returns the deployment's canonical external URL for provider redirects.
func (a *Auth) BaseURL() string { return a.appURL }

// Logout expires the browser cookie, including when it has already expired.
func (a *Auth) Logout(w http.ResponseWriter, r *http.Request) {
	_ = a.End(w, r)
	writeJSON(w, 200, map[string]any{})
}
