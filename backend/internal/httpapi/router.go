package httpapi

import (
	"context"
	"encoding/json"
	"io/fs"
	"log/slog"
	"net/http"
	"time"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
	"github.com/eliasmeireles/obd-blackbox/backend/pkg/auth"
)

// Service defines the application operations required by HTTP handlers.
type Service interface {
	Devices(context.Context, []string, []telemetry.Device) ([]telemetry.Device, error)
	StoreDiagnostic(context.Context, string, json.RawMessage) (int, error)
	Diagnostics(context.Context, int, string, string) ([]telemetry.Diagnostic, error)
	IngestLogs(context.Context, string, []telemetry.LogInput) (telemetry.BatchResult, error)
	Logs(context.Context, telemetry.LogQuery) ([]telemetry.AppLog, error)
	Upsert(context.Context, string, telemetry.SessionInput) (telemetry.Session, error)
	Get(context.Context, string) (telemetry.Session, error)
	Sessions(context.Context, int, string, string) ([]telemetry.Session, error)
	Ingest(context.Context, string, []telemetry.SampleInput) (telemetry.BatchResult, error)
	Samples(context.Context, string, telemetry.Query) ([]telemetry.Sample, error)
	Events(context.Context, string, int, string, string) ([]telemetry.Event, error)
	StoreDTCs(context.Context, string, telemetry.DTCInput) (int, error)
	DTCs(context.Context, string) ([]telemetry.DTC, error)
	Delete(context.Context, string) (int, error)
	DeleteSource(context.Context, string, string) (int, error)
	Ping(context.Context) error
}

// LiveSource supplies atomic snapshots and live subscriptions.
type LiveSource interface {
	Latest(string, string) (telemetry.Live, bool)
	Subscribe(string, string) (<-chan telemetry.Live, *telemetry.Live, func())
}

// DeviceService supplies connection lifetimes and command delivery to the transport handlers.
type DeviceService interface {
	Register(string, func(context.Context, telemetry.Command) error) *telemetry.DeviceConnection
	Remove(*telemetry.DeviceConnection)
	Devices() []telemetry.Device
	Execute(context.Context, string, string) (telemetry.CommandResult, error)
}

type Handler struct {
	service           Service
	hub               LiveSource
	timezone          *time.Location
	logger            *slog.Logger
	pingInterval      time.Duration
	devices           DeviceService
	commandTimeout    time.Duration
	configuredDevices []string
}

// NewRouter mounts health, authentication, telemetry and embedded static resources.
// Devices authenticate with API keys; dashboard users authenticate through signed cookies.
func NewRouter(service Service, hub LiveSource, sessions *Auth, login auth.LoginProvider, static fs.FS, zone *time.Location, logger *slog.Logger) http.Handler {
	h := &Handler{service: service, hub: hub, timezone: zone, logger: logger, pingInterval: 15 * time.Second, devices: telemetry.NewDeviceRegistry(), commandTimeout: 90 * time.Second, configuredDevices: sessions.KeyNames()}
	mux := http.NewServeMux()
	api := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("ok")) })
	mux.HandleFunc("GET /readyz", h.ready)
	mountLogin(mux, sessions, login)
	mux.HandleFunc("GET /login", func(w http.ResponseWriter, r *http.Request) { http.ServeFileFS(w, r, static, "index.html") })
	api.HandleFunc("GET /api/v1/auth/me", sessions.Me)
	h.mountAPI(api)
	mux.Handle("/api/v1/", sessions.Middleware(api))
	mux.Handle("/", http.FileServer(http.FS(static)))
	return h.logging(mux)
}

// ready checks the database with a bounded timeout.
func (h *Handler) ready(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	if h.service.Ping(ctx) != nil {
		writeError(w, 503, "database unavailable")
		return
	}
	w.Write([]byte("ok"))
}

// mountLogin installs the default login or the deployment's custom login provider.
func mountLogin(mux *http.ServeMux, sessions *Auth, login auth.LoginProvider) {
	methods := auth.Methods{Methods: []string{"api_key"}}
	if login != nil {
		login.Mount(mux, sessions)
		methods = login.Methods()
	}
	mux.HandleFunc("GET /api/v1/auth/methods", func(w http.ResponseWriter, r *http.Request) { writeJSON(w, 200, methods) })
	if login == nil || login.AllowAPIKeyLogin() {
		mux.HandleFunc("POST /api/v1/auth/login", sessions.Login)
	} else {
		mux.HandleFunc("POST /api/v1/auth/login", func(w http.ResponseWriter, r *http.Request) { writeError(w, 404, "API-key login is disabled") })
	}
	logout := sessions.Logout
	if external, ok := login.(auth.ExternalLogout); ok {
		logout = func(w http.ResponseWriter, r *http.Request) {
			_ = sessions.End(w, r)
			body := map[string]any{}
			if target := external.LogoutURL(r); target != "" {
				body["logoutUrl"] = target
			}
			writeJSON(w, 200, body)
		}
	}
	mux.HandleFunc("POST /api/v1/auth/logout", logout)
}

// mountAPI installs authenticated telemetry, history and device routes.
func (h *Handler) mountAPI(api *http.ServeMux) {
	api.HandleFunc("GET /api/v1/ingest", h.ingestSocket)
	api.HandleFunc("GET /api/v1/devices", h.connectedDevices)
	api.HandleFunc("POST /api/v1/commands", h.command)
	api.HandleFunc("GET /api/v1/diagnostics", h.diagnostics)
	api.HandleFunc("POST /api/v1/sessions/{id}/diagnostics", h.storeDiagnostic)
	api.HandleFunc("POST /api/v1/logs", h.storeLogs)
	api.HandleFunc("GET /api/v1/logs", h.logs)
	api.HandleFunc("GET /api/v1/logs.txt", h.logs)
	api.HandleFunc("PUT /api/v1/sessions/{id}", h.upsert)
	api.HandleFunc("GET /api/v1/sessions", h.sessions)
	api.HandleFunc("DELETE /api/v1/sessions/{id}", h.deleteSession)
	api.HandleFunc("DELETE /api/v1/sessions", h.deleteSessions)
	api.HandleFunc("GET /api/v1/sessions/{id}", h.session)
	api.HandleFunc("POST /api/v1/sessions/{id}/samples", h.ingest)
	api.HandleFunc("GET /api/v1/sessions/{id}/samples", h.samples)
	api.HandleFunc("POST /api/v1/sessions/{id}/dtcs", h.storeDTCs)
	api.HandleFunc("GET /api/v1/sessions/{id}/dtcs", h.dtcs)
	api.HandleFunc("GET /api/v1/sessions/{id}/events", h.events)
	api.HandleFunc("GET /api/v1/events", h.events)
	api.HandleFunc("GET /api/v1/sessions/{id}/export.csv", h.export)
	api.HandleFunc("GET /api/v1/live", h.live)
	api.HandleFunc("GET /api/v1/live/stream", h.stream)
	api.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) { writeError(w, 404, "not found") })
}
