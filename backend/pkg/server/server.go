// Package server exposes the application entrypoint for custom deployments.
package server

import (
	"context"
	"os"
	"os/signal"
	"syscall"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/app"
	"github.com/eliasmeireles/obd-blackbox/backend/pkg/auth"
)

// Option customizes the server before any dependencies are initialized.
type Option func(*options)

type options struct{ login auth.LoginProvider }

// WithLogin installs a dashboard login provider; device bearer keys remain enabled.
func WithLogin(provider auth.LoginProvider) Option {
	return func(o *options) { o.login = provider }
}

// Run loads environment configuration and serves until cancellation or a stop signal.
func Run(ctx context.Context, opts ...Option) error {
	settings := options{}
	for _, option := range opts {
		option(&settings)
	}
	lifetime, stop := signal.NotifyContext(ctx, os.Interrupt, syscall.SIGTERM)
	defer stop()
	return app.Run(lifetime, settings.login)
}
