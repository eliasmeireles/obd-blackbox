package config

import (
	"strings"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

// validEnvironment returns fictional test settings.
func validEnvironment() map[string]string {
	return map[string]string{"DATABASE_URL": "postgres://localhost/example", "API_KEYS": "example:" + strings.Repeat("x", 32), "COOKIE_SECRET": strings.Repeat("s", 32)}
}

// TestParse validates defaults and malformed configuration without secrets in errors.
func TestParse(t *testing.T) {
	t.Run("given required settings then defaults are loaded", func(t *testing.T) {
		env := validEnvironment()
		c, err := Parse(func(k string) string { return env[k] })
		require.NoError(t, err)
		require.Equal(t, ":8080", c.HTTPAddr)
		require.Equal(t, 720*time.Hour, c.SessionTTL)
		require.True(t, c.CookieSecure)
		require.Equal(t, "UTC", c.Timezone.String())
	})
	for _, tc := range []struct{ key, value string }{{"DATABASE_URL", ""}, {"COOKIE_SECRET", "short"}, {"API_KEYS", "broken"}, {"API_KEYS", "example:" + strings.Repeat("x", 32) + ",example:" + strings.Repeat("y", 32)}, {"API_KEYS", "a|b:" + strings.Repeat("x", 32)}, {"SESSION_TTL", "-1h"}, {"SESSION_TTL", "oops"}, {"COOKIE_SECURE", "oops"}, {"DISPLAY_TIMEZONE", "not/a/zone"}, {"LOG_LEVEL", "oops"}} {
		t.Run("given invalid "+tc.key+" then parsing fails", func(t *testing.T) {
			env := validEnvironment()
			env[tc.key] = tc.value
			_, err := Parse(func(k string) string { return env[k] })
			require.Error(t, err)
			require.Contains(t, err.Error(), tc.key)
		})
	}
	t.Run("given overrides then custom settings are loaded", func(t *testing.T) {
		env := validEnvironment()
		env["COOKIE_SECURE"] = "false"
		env["SESSION_TTL"] = "1h"
		env["LOG_LEVEL"] = "debug"
		env["HTTP_ADDR"] = ":9090"
		env["DISPLAY_TIMEZONE"] = "UTC"
		c, err := Parse(func(k string) string { return env[k] })
		require.NoError(t, err)
		require.False(t, c.CookieSecure)
		require.Equal(t, time.Hour, c.SessionTTL)
		require.Equal(t, ":9090", c.HTTPAddr)
	})
	t.Run("given environment variables then Load reads them", func(t *testing.T) {
		for key, value := range validEnvironment() {
			t.Setenv(key, value)
		}
		_, err := Load()
		require.NoError(t, err)
	})
}

// TestSelfHostingConfiguration checks canonical URLs, retention and unique device keys.
func TestSelfHostingConfiguration(t *testing.T) {
	for _, tc := range []struct{ key, value string }{
		{"APP_URL", "relative"}, {"APP_URL", "https://example.com/path"}, {"APP_URL", "ftp://example.com"},
		{"APP_URL", "https://example.com?key=example"}, {"LOG_RETENTION", "0h"}, {"LOG_PRUNE_INTERVAL", "bad"},
		{"API_KEYS", "first:" + strings.Repeat("x", 32) + ",second:" + strings.Repeat("x", 32)}, {"API_KEYS", ""},
	} {
		t.Run("given invalid "+tc.key+" then startup rejects it clearly", func(t *testing.T) {
			env := validEnvironment()
			env[tc.key] = tc.value
			_, err := Parse(func(key string) string { return env[key] })
			require.ErrorContains(t, err, tc.key)
		})
	}
	t.Run("given public URL and retention overrides then settings are available to the server", func(t *testing.T) {
		env := validEnvironment()
		env["APP_URL"] = "https://obd.example.com/"
		env["LOG_RETENTION"] = "48h"
		env["LOG_PRUNE_INTERVAL"] = "2h"
		cfg, err := Parse(func(key string) string { return env[key] })
		require.NoError(t, err)
		require.Equal(t, "https://obd.example.com", cfg.AppURL)
		require.Equal(t, 48*time.Hour, cfg.LogRetention)
		require.Equal(t, 2*time.Hour, cfg.LogPruneInterval)
	})
}
