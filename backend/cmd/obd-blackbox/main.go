// Command obd-blackbox runs the telemetry backend.
package main

import (
	"context"
	"log/slog"
	"os"

	"github.com/eliasmeireles/obd-blackbox/backend/pkg/server"
)

// main starts the application and reports a sanitized startup failure.
func main() {
	if err := server.Run(context.Background()); err != nil {
		slog.Error("obd-blackbox stopped", "error", err)
		os.Exit(1)
	}
}
