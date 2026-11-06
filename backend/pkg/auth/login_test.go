package auth

import (
	"encoding/json"
	"testing"

	"github.com/stretchr/testify/require"
)

// TestMethods checks the public login description wire format.
func TestMethods(t *testing.T) {
	for _, tc := range []struct {
		methods Methods
		want    string
	}{
		{Methods{Methods: []string{"api_key"}}, `{"methods":["api_key"]}`},
		{Methods{Methods: []string{"oidc"}, LoginURL: "/auth/login", Label: "Entrar com SSO"}, `{"methods":["oidc"],"login_url":"/auth/login","label":"Entrar com SSO"}`},
	} {
		t.Run("given login methods then optional provider details are serialized", func(t *testing.T) {
			data, err := json.Marshal(tc.methods)
			require.NoError(t, err)
			require.JSONEq(t, tc.want, string(data))
		})
	}
}
