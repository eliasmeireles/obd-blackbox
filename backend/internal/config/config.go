// Package config loads and validates startup configuration without exposing secrets.
package config

import (
	"fmt"
	"log/slog"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
	_ "time/tzdata"
)

type Config struct {
	HTTPAddr, DatabaseURL, CookieSecret, AppURL string
	Keys                                        map[string]string
	SessionTTL                                  time.Duration
	LogRetention, LogPruneInterval              time.Duration
	CookieSecure                                bool
	Timezone                                    *time.Location
	LogLevel                                    slog.Level
}

// Load reads environment variables and rejects missing or malformed settings.
func Load() (Config, error) { return Parse(os.Getenv) }

// Parse loads configuration through an injectable environment lookup.
func Parse(get func(string) string) (Config, error) {
	c := Config{HTTPAddr: value(get, "HTTP_ADDR", ":8080"), DatabaseURL: get("DATABASE_URL"), CookieSecret: get("COOKIE_SECRET"), Keys: map[string]string{}}
	if c.DatabaseURL == "" {
		return c, fmt.Errorf("DATABASE_URL is required")
	}
	if len(c.CookieSecret) < 32 {
		return c, fmt.Errorf("COOKIE_SECRET must contain at least 32 bytes")
	}
	keys, err := parseKeys(get("API_KEYS"))
	if err != nil {
		return c, err
	}
	c.Keys = keys
	c.SessionTTL, err = time.ParseDuration(value(get, "SESSION_TTL", "720h"))
	if err != nil || c.SessionTTL <= 0 {
		return c, fmt.Errorf("SESSION_TTL must be a positive duration")
	}
	c.CookieSecure, err = strconv.ParseBool(value(get, "COOKIE_SECURE", "true"))
	if err != nil {
		return c, fmt.Errorf("COOKIE_SECURE must be a boolean")
	}
	c.Timezone, err = time.LoadLocation(value(get, "DISPLAY_TIMEZONE", "UTC"))
	if err != nil {
		return c, fmt.Errorf("DISPLAY_TIMEZONE is invalid")
	}
	if err = c.LogLevel.UnmarshalText([]byte(value(get, "LOG_LEVEL", "info"))); err != nil {
		return c, fmt.Errorf("LOG_LEVEL is invalid")
	}
	c.AppURL = strings.TrimRight(value(get, "APP_URL", "http://localhost:8080"), "/")
	appURL, err := url.Parse(c.AppURL)
	if err != nil || (appURL.Scheme != "http" && appURL.Scheme != "https") || appURL.Host == "" || appURL.User != nil || appURL.RawQuery != "" || appURL.Fragment != "" || appURL.Path != "" {
		return c, fmt.Errorf("APP_URL must be an absolute HTTP(S) origin")
	}
	if err := c.parseLogRetention(get); err != nil {
		return c, err
	}
	return c, nil
}

// value returns an environment value or its default.
func value(get func(string) string, key, fallback string) string {
	if v := get(key); v != "" {
		return v
	}
	return fallback
}

// parseKeys validates unique device names and key values without exposing secrets.
func parseKeys(value string) (map[string]string, error) {
	keys := map[string]string{}
	for _, pair := range strings.Split(value, ",") {
		name, key, ok := strings.Cut(strings.TrimSpace(pair), ":")
		if !ok || name == "" || strings.ContainsAny(name, "|\r\n\t ") || len(key) < 32 {
			return nil, fmt.Errorf("API_KEYS must contain name:key pairs with keys of at least 32 characters")
		}
		if _, exists := keys[name]; exists {
			return nil, fmt.Errorf("API_KEYS contains duplicate names")
		}
		for _, existing := range keys {
			if key == existing {
				return nil, fmt.Errorf("API_KEYS contains duplicate keys")
			}
		}
		keys[name] = key
	}

	return keys, nil
}

// parseLogRetention loads positive retention and cleanup durations.
func (c *Config) parseLogRetention(get func(string) string) error {
	var err error
	c.LogRetention, err = time.ParseDuration(value(get, "LOG_RETENTION", "720h"))
	if err != nil || c.LogRetention <= 0 {
		return fmt.Errorf("LOG_RETENTION must be a positive duration")
	}
	c.LogPruneInterval, err = time.ParseDuration(value(get, "LOG_PRUNE_INTERVAL", "1h"))
	if err != nil || c.LogPruneInterval <= 0 {
		return fmt.Errorf("LOG_PRUNE_INTERVAL must be a positive duration")
	}

	return nil
}
