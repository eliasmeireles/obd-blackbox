// Package auth defines the dashboard login extension for self-hosted deployments.
package auth

import "net/http"

// Methods describes the login choices presented by the dashboard.
type Methods struct {
	Methods  []string `json:"methods"`
	LoginURL string   `json:"login_url,omitempty"`
	Label    string   `json:"label,omitempty"`
}

// Sessions manages signed dashboard cookies for identities authorized by a provider.
// Providers must verify their authentication flow before calling Start.
type Sessions interface {
	Start(http.ResponseWriter, *http.Request, string) error
	End(http.ResponseWriter, *http.Request) error
	BaseURL() string
}

// LoginProvider mounts public login routes and describes the available methods.
// The built-in API-key login is mounted only when AllowAPIKeyLogin returns true.
// Reserved /api/v1/auth routes are owned by the server.
type LoginProvider interface {
	Mount(*http.ServeMux, Sessions)
	Methods() Methods
	AllowAPIKeyLogin() bool
}

// ExternalLogout can be implemented by a LoginProvider whose identity provider keeps its own session.
// After the dashboard cookie is cleared, the logout response carries this URL and the dashboard sends the
// browser there (an OIDC end-session URL, for example). An empty URL means nothing else to end.
type ExternalLogout interface {
	LogoutURL(*http.Request) string
}
