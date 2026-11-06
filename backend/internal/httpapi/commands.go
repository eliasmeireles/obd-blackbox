package httpapi

import (
	"context"
	"errors"
	"net/http"
	"time"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
)

type deviceConnectionKey struct{}

type commandInput struct {
	Name   string `json:"name"`
	Device string `json:"device"`
}

// connectedDevices lists configured and historical identities with their connection status.
func (h *Handler) connectedDevices(w http.ResponseWriter, r *http.Request) {
	devices, err := h.service.Devices(r.Context(), h.configuredDevices, h.devices.Devices())
	h.respond(w, map[string]any{"devices": devices}, err)
}

// command requires dashboard authentication and waits for the selected app's response.
func (h *Handler) command(w http.ResponseWriter, r *http.Request) {
	if !requireDashboardSession(w, r) {
		return
	}
	started := time.Now()
	var in commandInput
	if !decode(w, r, &in) {
		return
	}
	devices := h.devices.Devices()
	if in.Device == "" && len(devices) > 1 {
		writeError(w, 400, "device is required when multiple devices are connected")
		return
	}
	if in.Device == "" && len(devices) == 1 {
		in.Device = devices[0].Name
	}
	if in.Name != "clear_dtcs" && in.Name != "read_diagnostics" {
		h.logCommand(r, in, "invalid", started)
		writeError(w, 400, "comando desconhecido")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), h.commandTimeout)
	defer cancel()
	result, err := h.devices.Execute(ctx, in.Device, in.Name)
	outcome := "ok"
	if err != nil {
		outcome = err.Error()
	} else if !result.OK {
		outcome = "failed"
	}
	h.logCommand(r, in, outcome, started)
	commandResponseJSON(w, result, err)
}

// logCommand records the requesting identity and outcome without recording credentials.
func (h *Handler) logCommand(r *http.Request, in commandInput, outcome string, started time.Time) {
	identity, _ := r.Context().Value(identityKey{}).(string)
	h.logger.Info("remote command", "identity", identity, "device", in.Device, "name", in.Name, "outcome", outcome, "duration", time.Since(started))
}

// commandResponseJSON maps app replies, response deadlines and disconnections to HTTP responses.
func commandResponseJSON(w http.ResponseWriter, result telemetry.CommandResult, err error) {
	switch {
	case errors.Is(err, context.DeadlineExceeded):
		writeError(w, 504, "o app não respondeu a tempo")
	case err != nil:
		writeError(w, 409, telemetry.ErrDeviceDisconnected.Error())
	default:
		writeJSON(w, 200, result)
	}
}

// ingestCommandResult accepts replies only from the socket that received the command.
func ingestCommandResult(ctx context.Context, in ingestMessage) error {
	conn, ok := ctx.Value(deviceConnectionKey{}).(*telemetry.DeviceConnection)
	if !ok || !telemetry.ValidID(in.CommandID) || in.OK == nil {
		return &telemetry.ValidationError{Message: "invalid command result"}
	}
	conn.Deliver(in.CommandID, telemetry.CommandResult{OK: *in.OK, Message: in.Message})
	return nil
}
