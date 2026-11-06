package httpapi

import (
	"context"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
	"github.com/stretchr/testify/require"
)

type socketService struct {
	*fakeService
}

// Ingest returns controlled accepted and duplicate counts.
func (s *socketService) Ingest(context.Context, string, []telemetry.SampleInput) (telemetry.BatchResult, error) {
	return telemetry.BatchResult{Accepted: 1, Duplicates: 1}, s.err
}

// socketServer starts the real authenticated router with a controlled service.
func socketServer(t *testing.T, service Service, contexts ...context.Context) *httptest.Server {
	t.Helper()
	auth := NewAuth(map[string]string{"example": testKey}, strings.Repeat("s", 32), time.Hour, false, "http://localhost:8080")
	server := httptest.NewUnstartedServer(NewRouter(service, telemetry.NewHub(), auth, nil, nil, time.UTC, slog.New(slog.NewTextHandler(io.Discard, nil))))
	for _, ctx := range contexts {
		server.Config.BaseContext = func(net.Listener) context.Context { return ctx }
	}
	server.Start()
	t.Cleanup(server.Close)
	return server
}

// dialIngest opens an authenticated WebSocket with a bounded test lifetime.
func dialIngest(t *testing.T, server *httptest.Server) (context.Context, *websocket.Conn) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	t.Cleanup(cancel)
	conn, response, err := websocket.Dial(ctx, server.URL+"/api/v1/ingest", &websocket.DialOptions{
		HTTPHeader:      http.Header{"Authorization": {"Bearer " + testKey}},
		CompressionMode: websocket.CompressionContextTakeover,
	})
	require.NoError(t, err)
	require.Equal(t, "permessage-deflate", response.Header.Get("Sec-WebSocket-Extensions"))
	t.Cleanup(func() { conn.CloseNow() })
	return ctx, conn
}

// exchangeIngest writes a text message and checks its ordered reply.
func exchangeIngest(t *testing.T, ctx context.Context, conn *websocket.Conn, message, want string) {
	t.Helper()
	require.NoError(t, conn.Write(ctx, websocket.MessageText, []byte(message)))
	kind, data, err := conn.Read(ctx)
	require.NoError(t, err)
	require.Equal(t, websocket.MessageText, kind)
	require.JSONEq(t, want, string(data))
}

// TestIngestSocket checks protocol replies, recovery and compression through the real router.
func TestIngestSocket(t *testing.T) {
	t.Run("given session and sample messages then ordered acknowledgements preserve counts", func(t *testing.T) {
		server := socketServer(t, &socketService{fakeService: &fakeService{}})
		ctx, conn := dialIngest(t, server)
		exchangeIngest(t, ctx, conn, `{"type":"session","seq":1,"id":"`+testID+`","source":"sim","vehicle":"obd-ii","started_at":"2026-11-05T21:31:33-03:00","app_version":"0.1.0"}`, `{"type":"ack","seq":1}`)
		exchangeIngest(t, ctx, conn, `{"type":"samples","seq":2,"session_id":"`+testID+`","samples":[{"t":1},{"t":1}]}`, `{"type":"ack","seq":2,"accepted":1,"duplicates":1}`)
	})
	t.Run("given an unknown session then a not found error is returned", func(t *testing.T) {
		ctx, conn := dialIngest(t, socketServer(t, &fakeService{err: telemetry.ErrNotFound}))
		exchangeIngest(t, ctx, conn, `{"type":"samples","seq":2,"session_id":"`+testID+`","samples":[{"t":1}]}`, `{"type":"error","seq":2,"status":404,"error":"session not found"}`)
	})
	t.Run("given invalid messages then the connection continues to answer", func(t *testing.T) {
		ctx, conn := dialIngest(t, socketServer(t, &fakeService{}))
		exchangeIngest(t, ctx, conn, `broken`, `{"type":"error","seq":0,"status":400,"error":"invalid message"}`)
		exchangeIngest(t, ctx, conn, `{"seq":3,"type":`, `{"type":"error","seq":3,"status":400,"error":"invalid message"}`)
		exchangeIngest(t, ctx, conn, `{"seq":4,"type":"unknown"}`, `{"type":"error","seq":4,"status":400,"error":"unknown message type"}`)
		exchangeIngest(t, ctx, conn, `{"seq":5,"type":"samples","session_id":"`+testID+`","samples":[]}`, `{"type":"ack","seq":5,"accepted":1,"duplicates":0}`)
	})
}

// TestIngestHandshake checks authentication before upgrade and default origin protection.
func TestIngestHandshake(t *testing.T) {
	for _, tc := range []struct {
		name   string
		header http.Header
		status int
	}{
		{"missing key", http.Header{}, 401},
		{"invalid key", http.Header{"Authorization": {"Bearer invalid"}}, 401},
		{"cross site origin", http.Header{"Authorization": {"Bearer " + testKey}, "Origin": {"https://untrusted.example"}}, 403},
	} {
		t.Run("given "+tc.name+" then the handshake is rejected", func(t *testing.T) {
			server := socketServer(t, &fakeService{})
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			conn, response, err := websocket.Dial(ctx, server.URL+"/api/v1/ingest", &websocket.DialOptions{HTTPHeader: tc.header})
			require.Error(t, err)
			require.Nil(t, conn)
			require.NotNil(t, response)
			require.Equal(t, tc.status, response.StatusCode)
		})
	}
}

// TestIngestProtocolClose checks binary and oversized frame close codes.
func TestIngestProtocolClose(t *testing.T) {
	for _, tc := range []struct {
		name   string
		kind   websocket.MessageType
		data   []byte
		status websocket.StatusCode
	}{
		{"binary data", websocket.MessageBinary, []byte("{}"), websocket.StatusUnsupportedData},
		{"oversized data", websocket.MessageText, []byte(strings.Repeat("x", maxBody+1)), websocket.StatusMessageTooBig},
	} {
		t.Run("given "+tc.name+" then the connection closes with the protocol status", func(t *testing.T) {
			ctx, conn := dialIngest(t, socketServer(t, &fakeService{}))
			require.NoError(t, conn.Write(ctx, tc.kind, tc.data))
			_, _, err := conn.Read(ctx)
			require.Equal(t, tc.status, websocket.CloseStatus(err))
		})
	}
}

// TestIngestShutdown checks shutdown cancellation on an idle upgraded connection.
func TestIngestShutdown(t *testing.T) {
	t.Run("given request cancellation then the server closes going away", func(t *testing.T) {
		requests, cancel := context.WithCancel(context.Background())
		defer cancel()
		server := socketServer(t, &fakeService{}, requests)
		ctx, conn := dialIngest(t, server)
		cancel()
		_, _, err := conn.Read(ctx)
		require.Equal(t, websocket.StatusGoingAway, websocket.CloseStatus(err))
	})
}

// TestIngestKeepalive checks ping handling with a short test interval.
func TestIngestKeepalive(t *testing.T) {
	t.Run("given a client ignoring pings then keepalive closes the connection", func(t *testing.T) {
		result := make(chan string, 1)
		server := httptest.NewServer(keepaliveHandler(result))
		defer server.Close()
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		conn, _, err := websocket.Dial(ctx, server.URL, &websocket.DialOptions{OnPingReceived: func(context.Context, []byte) bool { return false }})
		require.NoError(t, err)
		defer conn.CloseNow()
		_, _, err = conn.Read(ctx)
		require.Equal(t, websocket.StatusGoingAway, websocket.CloseStatus(err))
		require.Equal(t, "keepalive failed", <-result)
	})
}

// keepaliveHandler exposes the keepalive loop with a short test interval.
func keepaliveHandler(result chan<- string) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, nil)
		if err != nil {
			result <- err.Error()
			return
		}
		defer conn.CloseNow()
		result <- maintainIngestSocket(r.Context(), r.Context(), conn, time.Millisecond, 10*time.Millisecond)
	})
}

// TestIngestSocketLogs verifies log acknowledgements and recovery after validation failure.
func TestIngestSocketLogs(t *testing.T) {
	t.Run("given invalid logs then a following valid batch is still acknowledged", func(t *testing.T) {
		ctx, conn := dialIngest(t, socketServer(t, &fakeService{}))
		exchangeIngest(t, ctx, conn, `{"type":"logs","seq":1,"logs":[]}`, `{"type":"error","seq":1,"status":400,"error":"logs batch must contain 1 to 1000 entries"}`)
		exchangeIngest(t, ctx, conn, `{"type":"logs","seq":2,"logs":[{"id":"`+testID+`","t":1,"level":"info","tag":"app","message":"m"},{"id":"`+testID+`","t":1,"level":"info","tag":"app","message":"m"}]}`, `{"type":"ack","seq":2,"accepted":1,"duplicates":1}`)
	})
}
