package httpapi

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
	"github.com/stretchr/testify/require"
)

// commandTestRouter exposes real handlers with auth and an injectable response deadline.
func commandTestRouter(timeout time.Duration) (http.Handler, *Handler) {
	h := &Handler{service: &fakeService{}, devices: telemetry.NewDeviceRegistry(), commandTimeout: timeout, logger: slog.New(slog.NewTextHandler(io.Discard, nil))}
	mux := http.NewServeMux()
	mux.HandleFunc("POST /api/v1/commands", h.command)
	mux.HandleFunc("GET /api/v1/devices", h.connectedDevices)
	mux.HandleFunc("GET /api/v1/ingest", h.ingestSocket)
	auth := NewAuth(map[string]string{"example": testKey}, strings.Repeat("s", 32), time.Hour, false, "http://localhost:8080")
	return auth.Middleware(mux), h
}

// commandRequest invokes handlers with a signed dashboard cookie.
func commandRequest(router http.Handler, body string) *httptest.ResponseRecorder {
	r := httptest.NewRequest("POST", "/api/v1/commands", strings.NewReader(body))
	auth := NewAuth(nil, strings.Repeat("s", 32), time.Hour, false, "http://localhost:8080")
	r.AddCookie(&http.Cookie{Name: "tm_session", Value: auth.Sign("example-subject")})
	w := httptest.NewRecorder()
	router.ServeHTTP(w, r)
	return w
}

type commandResponse struct {
	status int
	body   string
	err    error
}

// launchCommand sends a real asynchronous HTTP request while tests exercise the device socket.
func launchCommand(t *testing.T, server *httptest.Server, body string) <-chan commandResponse {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	t.Cleanup(cancel)
	r, err := http.NewRequestWithContext(ctx, "POST", server.URL+"/api/v1/commands", strings.NewReader(body))
	require.NoError(t, err)
	auth := NewAuth(nil, strings.Repeat("s", 32), time.Hour, false, "http://localhost:8080")
	r.AddCookie(&http.Cookie{Name: "tm_session", Value: auth.Sign("example-subject")})
	out := make(chan commandResponse, 1)
	go performCommand(server.Client(), r, out)
	return out
}

// performCommand returns transport failures to the test goroutine.
func performCommand(client *http.Client, r *http.Request, out chan<- commandResponse) {
	response, err := client.Do(r)
	if err != nil {
		out <- commandResponse{err: err}
		return
	}
	defer response.Body.Close()
	body, err := io.ReadAll(response.Body)
	out <- commandResponse{status: response.StatusCode, body: string(body), err: err}
}

// readCommand decodes the next server-to-app command.
func readCommand(t *testing.T, ctx context.Context, conn *websocket.Conn) telemetry.Command {
	t.Helper()
	kind, data, err := conn.Read(ctx)
	require.NoError(t, err)
	require.Equal(t, websocket.MessageText, kind)
	var command telemetry.Command
	require.NoError(t, json.Unmarshal(data, &command))
	require.Equal(t, "command", command.Type)
	require.True(t, telemetry.ValidID(command.ID))
	return command
}

// TestCommandHTTP checks authorization, validation, device resolution and response deadlines.
func TestCommandHTTP(t *testing.T) {
	t.Run("given an API key then commands are forbidden", func(t *testing.T) {
		router, _ := commandTestRouter(time.Second)
		require.Equal(t, 403, request(router, "POST", "/api/v1/commands", `{"name":"clear_dtcs"}`).Code)
	})
	t.Run("given no authentication then commands are unauthorized", func(t *testing.T) {
		router, _ := commandTestRouter(time.Second)
		w := httptest.NewRecorder()
		router.ServeHTTP(w, httptest.NewRequest("POST", "/api/v1/commands", strings.NewReader(`{"name":"clear_dtcs"}`)))
		require.Equal(t, 401, w.Code)
	})
	t.Run("given no connected device then the dashboard receives conflict", func(t *testing.T) {
		router, _ := commandTestRouter(time.Second)
		w := commandRequest(router, `{"name":"read_diagnostics"}`)
		require.Equal(t, 409, w.Code)
		require.JSONEq(t, `{"error":"the app is not connected"}`, w.Body.String())
	})
	for _, body := range []string{`{"name":"unknown"}`, `{}`, `null`, `{"name":3}`, `broken`, `{"name":"clear_dtcs"} {}`} {
		t.Run("given invalid command then bad request is returned", func(t *testing.T) {
			router, _ := commandTestRouter(time.Second)
			require.Equal(t, 400, commandRequest(router, body).Code)
		})
	}
	t.Run("given no reply then an injected deadline returns gateway timeout", func(t *testing.T) {
		router, h := commandTestRouter(time.Millisecond)
		h.devices.Register("example", func(context.Context, telemetry.Command) error { return nil })
		w := commandRequest(router, `{"name":"read_diagnostics"}`)
		require.Equal(t, 504, w.Code)
	})
}

// TestCommandSocket exercises results, acknowledgements, registry lifetime and fast failures.
func TestCommandSocket(t *testing.T) {
	t.Run("given a connected app then its result reaches the HTTP caller and is acknowledged", func(t *testing.T) {
		router, h := commandTestRouter(time.Second)
		server := httptest.NewServer(router)
		defer server.Close()
		ctx, conn := dialIngest(t, server)
		require.Eventually(t, func() bool { return len(h.devices.Devices()) == 1 }, time.Second, time.Millisecond)
		listed := request(router, "GET", "/api/v1/devices", "")
		require.Equal(t, 200, listed.Code)
		require.Contains(t, listed.Body.String(), `"name":"example"`)
		response := launchCommand(t, server, `{"name":"clear_dtcs"}`)
		command := readCommand(t, ctx, conn)
		require.Equal(t, "clear_dtcs", command.Name)
		exchangeIngest(t, ctx, conn, `{"type":"command_result","seq":1,"command_id":"`+command.ID+`","ok":true,"message":"codes cleared"}`, `{"type":"ack","seq":1}`)
		result := <-response
		require.NoError(t, result.err)
		require.Equal(t, 200, result.status)
		require.JSONEq(t, `{"ok":true,"message":"codes cleared"}`, result.body)
		exchangeIngest(t, ctx, conn, `{"type":"command_result","seq":2,"command_id":"`+command.ID+`","ok":true}`, `{"type":"ack","seq":2}`)
		require.NoError(t, conn.CloseNow())
		require.Eventually(t, func() bool { return len(h.devices.Devices()) == 0 }, time.Second, time.Millisecond)
	})
	t.Run("given disconnect during a command then HTTP fails fast", func(t *testing.T) {
		router, _ := commandTestRouter(90 * time.Second)
		server := httptest.NewServer(router)
		defer server.Close()
		ctx, conn := dialIngest(t, server)
		exchangeIngest(t, ctx, conn, `{"type":"session","seq":1,"id":"`+testID+`","source":"obd","started_at":"2026-11-06T00:00:00Z"}`, `{"type":"ack","seq":1}`)
		response := launchCommand(t, server, `{"name":"read_diagnostics","device":"example"}`)
		readCommand(t, ctx, conn)
		require.NoError(t, conn.CloseNow())
		result := <-response
		require.NoError(t, result.err)
		require.Equal(t, 409, result.status)
	})
	t.Run("given reconnect during a command then the older connection fails its caller", func(t *testing.T) {
		router, _ := commandTestRouter(90 * time.Second)
		server := httptest.NewServer(router)
		defer server.Close()
		ctx, conn := dialIngest(t, server)
		exchangeIngest(t, ctx, conn, `{"type":"session","seq":1,"id":"`+testID+`","source":"obd","started_at":"2026-11-06T00:00:00Z"}`, `{"type":"ack","seq":1}`)
		response := launchCommand(t, server, `{"name":"read_diagnostics"}`)
		readCommand(t, ctx, conn)
		_, replacement := dialIngest(t, server)
		exchangeIngest(t, ctx, replacement, `{"type":"command_result","seq":1,"command_id":"bad","ok":true}`, `{"type":"error","seq":1,"status":400,"error":"invalid command result"}`)
		result := <-response
		require.NoError(t, result.err)
		require.Equal(t, 409, result.status)
	})
}

// TestCommandDelivery checks HTTP-to-message correlation without opening sockets.
func TestCommandDelivery(t *testing.T) {
	t.Run("given an app result then the dashboard HTTP caller receives it", func(t *testing.T) {
		router, h := commandTestRouter(time.Second)
		sent := make(chan telemetry.Command, 1)
		conn := h.devices.Register("example", func(_ context.Context, command telemetry.Command) error {
			sent <- command
			return nil
		})
		response := make(chan *httptest.ResponseRecorder, 1)
		go func() { response <- commandRequest(router, `{"name":"read_diagnostics"}`) }()
		command := <-sent
		ctx := context.WithValue(context.Background(), deviceConnectionKey{}, conn)
		reply := h.handleIngestMessage(ctx, []byte(`{"type":"command_result","seq":4,"command_id":"`+command.ID+`","ok":false,"message":"desligue o motor"}`))
		require.Equal(t, "ack", reply.Type)
		require.Equal(t, int64(4), reply.Seq)
		w := <-response
		require.Equal(t, 200, w.Code)
		require.JSONEq(t, `{"ok":false,"message":"desligue o motor"}`, w.Body.String())
	})
	for _, body := range []string{`{"type":"command_result","seq":1,"command_id":"bad","ok":true}`, `{"type":"command_result","seq":2,"command_id":"` + testID + `"}`, `{"type":"command_result","seq":3,"command_id":"` + testID + `","ok":null}`, `{"type":"command_result","seq":4,"command_id":"` + testID + `","ok":"yes"}`} {
		t.Run("given an invalid result then message validation returns bad request", func(t *testing.T) {
			_, h := commandTestRouter(time.Second)
			conn := h.devices.Register("example", func(context.Context, telemetry.Command) error { return nil })
			ctx := context.WithValue(context.Background(), deviceConnectionKey{}, conn)
			reply := h.handleIngestMessage(ctx, []byte(body))
			require.Equal(t, "error", reply.Type)
			require.Equal(t, 400, reply.Status)
		})
	}
	t.Run("given a reply outside a device socket then it is rejected", func(t *testing.T) {
		_, h := commandTestRouter(time.Second)
		reply := h.handleIngestMessage(context.Background(), []byte(`{"type":"command_result","seq":1,"command_id":"`+testID+`","ok":true}`))
		require.Equal(t, 400, reply.Status)
	})
}

// TestMultipleDeviceCommands checks explicit device selection in a multi-device deployment.
func TestMultipleDeviceCommands(t *testing.T) {
	t.Run("given several connected devices then an omitted device is rejected", func(t *testing.T) {
		router, h := commandTestRouter(time.Second)
		h.devices.Register("phone-a", func(context.Context, telemetry.Command) error { return nil })
		h.devices.Register("phone-b", func(context.Context, telemetry.Command) error { return nil })
		w := commandRequest(router, `{"name":"read_diagnostics"}`)
		require.Equal(t, 400, w.Code)
		require.Contains(t, w.Body.String(), "device is required")
	})
	t.Run("given an explicit device then only the selected device receives the command", func(t *testing.T) {
		router, h := commandTestRouter(time.Second)
		other := make(chan telemetry.Command, 1)
		selected := make(chan telemetry.Command, 1)
		h.devices.Register("phone-a", func(_ context.Context, command telemetry.Command) error { other <- command; return nil })
		conn := h.devices.Register("phone-b", func(_ context.Context, command telemetry.Command) error { selected <- command; return nil })
		response := make(chan *httptest.ResponseRecorder, 1)
		go func() { response <- commandRequest(router, `{"name":"read_diagnostics","device":"phone-b"}`) }()
		command := <-selected
		conn.Deliver(command.ID, telemetry.CommandResult{OK: true})
		require.Equal(t, 200, (<-response).Code)
		require.Empty(t, other)
	})
}
