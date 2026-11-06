package server

import (
	"context"
	"net/http"
	"testing"

	"github.com/eliasmeireles/obd-blackbox/backend/pkg/auth"
	"github.com/stretchr/testify/require"
)

type fakeLogin struct{}

// Mount satisfies the public provider contract without mounting test routes.
func (*fakeLogin) Mount(*http.ServeMux, auth.Sessions) {}

// Methods advertises a custom login method.
func (*fakeLogin) Methods() auth.Methods { return auth.Methods{Methods: []string{"custom"}} }

// AllowAPIKeyLogin disables the dashboard key form for this provider.
func (*fakeLogin) AllowAPIKeyLogin() bool { return false }

// TestWithLogin checks custom provider selection through the public server API.
func TestWithLogin(t *testing.T) {
	t.Run("given a custom provider then the option retains it", func(t *testing.T) {
		provider := &fakeLogin{}
		settings := options{}
		WithLogin(provider)(&settings)
		require.Same(t, provider, settings.login)
	})
	t.Run("given missing required configuration then Run fails before connecting", func(t *testing.T) {
		t.Setenv("DATABASE_URL", "")
		require.ErrorContains(t, Run(context.Background(), WithLogin(&fakeLogin{})), "DATABASE_URL is required")
	})
}
