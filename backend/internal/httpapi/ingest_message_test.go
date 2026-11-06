package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"testing"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
	"github.com/stretchr/testify/require"
)

// TestMessageSequence verifies counter recovery from malformed envelopes.
func TestMessageSequence(t *testing.T) {
	for _, tc := range []struct {
		data string
		seq  int64
	}{
		{`{"seq":3,"type":`, 3},
		{`{"type":"samples","seq":4,"samples":`, 4},
		{`{"seq":-1}`, 0},
		{`{"seq":1.5}`, 0},
		{`{"seq":"1"}`, 0},
		{`{"type":`, 0},
		{`[]`, 0},
		{`null`, 0},
	} {
		t.Run("given "+tc.data+" then a readable positive counter is recovered", func(t *testing.T) {
			require.Equal(t, tc.seq, messageSequence([]byte(tc.data)))
		})
	}
}

// TestIngestMessage checks shared validation and sanitized service failures.
func TestIngestMessage(t *testing.T) {
	for _, tc := range []struct {
		data    string
		err     error
		status  int
		message string
	}{
		{`{"type":"logs","seq":1,"logs":[]}`, nil, 400, "logs batch must contain 1 to 1000 entries"},
		{`{"type":"logs","seq":1,"logs":[{"id":"bad"}]}`, nil, 400, "log index 0: invalid log UUID"},
		{`{"type":"logs","seq":1,"logs":[{"id":"` + testID + `","t":1,"level":"fatal","tag":"app","message":"m"}]}`, nil, 400, "log index 0: invalid level"},
		{`{"type":"logs","seq":1,"logs":"invalid"}`, nil, 400, "invalid message"},
		{`{"type":"logs","seq":1,"logs":[{"id":"` + testID + `","t":1,"level":"info","tag":"app","message":"m"}]}`, errors.New("private"), 500, "internal server error"},
		{`{"type":"session","seq":1,"id":"bad"}`, nil, 400, "invalid session UUID"},
		{`{"type":"session","seq":1,"id":"` + testID + `"}`, nil, 400, "source and started_at are required and must be valid"},
		{`{"type":"session","seq":1,"id":"` + testID + `","started_at":12}`, nil, 400, "invalid message"},
		{`{"type":"samples","seq":1,"session_id":"bad"}`, nil, 400, "invalid session UUID"},
		{`{"type":"samples","seq":1,"session_id":"` + testID + `","samples":[{"t":1,"v":{"rpm":null}}]}`, nil, 400, "sample index 0: signal values must be finite numbers"},
		{`{"type":"samples","seq":1,"session_id":"` + testID + `"}`, &telemetry.ValidationError{Message: "sample index 0: t must be positive"}, 400, "sample index 0: t must be positive"},
		{`{"type":"samples","seq":1,"session_id":"` + testID + `"}`, errors.New("private persistence failure"), 500, "internal server error"},
		{`{"type":"samples","seq":0}`, nil, 400, "invalid message"},
		{`{"seq":1,"type":12}`, nil, 400, "invalid message"},
	} {
		t.Run("given "+tc.data+" then invalid input or service errors are mapped", func(t *testing.T) {
			h := &Handler{service: &fakeService{err: tc.err}, logger: slog.New(slog.NewTextHandler(io.Discard, nil))}
			reply := h.handleIngestMessage(context.Background(), []byte(tc.data))
			require.Equal(t, "error", reply.Type)
			require.Equal(t, tc.status, reply.Status)
			require.Equal(t, tc.message, reply.Error)
		})
	}
}

// TestIngestAcknowledgements checks reply shape without requiring listening sockets.
func TestIngestAcknowledgements(t *testing.T) {
	for _, tc := range []struct{ data, want string }{
		{`{"type":"logs","seq":3,"logs":[{"id":"` + testID + `","t":1,"level":"info","tag":"app","message":"m"},{"id":"` + testID + `","t":1,"level":"info","tag":"app","message":"m"}]}`, `{"type":"ack","seq":3,"accepted":1,"duplicates":1}`},
		{`{"type":"session","seq":1,"id":"` + testID + `","source":"sim","started_at":"2026-11-05T00:00:00Z"}`, `{"type":"ack","seq":1}`},
		{`{"type":"samples","seq":2,"session_id":"` + testID + `","samples":[{"t":1},{"t":1}]}`, `{"type":"ack","seq":2,"accepted":1,"duplicates":1}`},
	} {
		t.Run("given "+tc.data+" then the acknowledgement has exactly the protocol fields", func(t *testing.T) {
			h := &Handler{service: &socketService{fakeService: &fakeService{}}}
			data, err := json.Marshal(h.handleIngestMessage(context.Background(), []byte(tc.data)))
			require.NoError(t, err)
			require.JSONEq(t, tc.want, string(data))
		})
	}
}

// TestLogMessageRecovery checks shared validation and identity without opening sockets.
func TestLogMessageRecovery(t *testing.T) {
	t.Run("given an invalid log message then following messages still work", func(t *testing.T) {
		f := &fakeService{}
		h := &Handler{service: f}
		ctx := context.WithValue(context.Background(), identityKey{}, "example")
		reply := h.handleIngestMessage(ctx, []byte(`{"type":"logs","seq":1,"logs":[]}`))
		require.Equal(t, 400, reply.Status)
		reply = h.handleIngestMessage(ctx, []byte(`{"type":"logs","seq":2,"logs":[{"id":"`+testID+`","t":1,"level":"info","tag":"app","message":"m"}]}`))
		require.Equal(t, "ack", reply.Type)
		require.Equal(t, int64(2), reply.Seq)
		require.Equal(t, "example", f.logDevice)
		require.Equal(t, &telemetry.BatchResult{Accepted: 1}, reply.BatchResult)
	})
}

// TestSocketSessionIdentity checks that session messages inherit the authenticated identity.
func TestSocketSessionIdentity(t *testing.T) {
	t.Run("given socket metadata then the authenticated identity supplies device", func(t *testing.T) {
		service := &fakeService{}
		h := &Handler{service: service}
		ctx := context.WithValue(context.Background(), identityKey{}, "phone-a")
		reply := h.handleIngestMessage(ctx, []byte(`{"type":"session","seq":1,"id":"`+testID+`","source":"obd","device":"spoofed","started_at":"2026-11-05T00:00:00Z"}`))
		require.Equal(t, "ack", reply.Type)
		require.Equal(t, "phone-a", service.input.Device)
	})
}
