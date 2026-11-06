package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
	"github.com/stretchr/testify/require"
)

type httpDiagnosticRepository struct {
	telemetry.Repository
	service *fakeService
}

// InsertDiagnostic supplies controlled persistence after real service validation.
func (r *httpDiagnosticRepository) InsertDiagnostic(_ context.Context, in telemetry.Diagnostic) (int, error) {
	return 1, r.service.err
}

// StoreDiagnostic runs the same domain validation as production for HTTP and socket tests.
func (f *fakeService) StoreDiagnostic(ctx context.Context, id string, raw json.RawMessage) (int, error) {
	return telemetry.NewService(&httpDiagnosticRepository{service: f}, nil).StoreDiagnostic(ctx, id, raw)
}

// Diagnostics captures source and limit and supplies a complete snapshot.
func (f *fakeService) Diagnostics(_ context.Context, n int, source, device string) ([]telemetry.Diagnostic, error) {
	f.source = source
	f.device = device
	f.q.Limit = n
	return []telemetry.Diagnostic{{SessionID: testID, T: 1, Data: json.RawMessage(`{"t":1,"reason":"manual"}`)}}, f.err
}

// TestDiagnosticHTTP checks bounded snapshot ingestion and source-aware listing.
func TestDiagnosticHTTP(t *testing.T) {
	path := "/api/v1/sessions/" + testID + "/diagnostics"
	for _, tc := range []struct {
		body   string
		err    error
		status int
	}{
		{`{"t":1,"reason":"manual","vin":null}`, nil, 200},
		{`{"t":1,"reason":"manual"}`, telemetry.ErrNotFound, 404},
		{`{"t":1,"reason":"manual"}`, errors.New("private"), 500},
		{`{}`, nil, 400}, {`null`, nil, 400}, {`[]`, nil, 400}, {`{"t":1,"reason":"bad"}`, nil, 400}, {`{"t":1,"reason":"connect"} {}`, nil, 400},
		{`{"t":1,"reason":"connect","padding":"` + strings.Repeat("x", telemetry.MaxDiagnosticsBody) + `"}`, nil, 400},
	} {
		t.Run("given a snapshot then HTTP maps validation and persistence results", func(t *testing.T) {
			w := request(testRouter(&fakeService{err: tc.err}, telemetry.NewHub()), "POST", path, tc.body)
			require.Equal(t, tc.status, w.Code)
		})
	}
	t.Run("given valid snapshot then stored count is returned", func(t *testing.T) {
		w := request(testRouter(&fakeService{}, telemetry.NewHub()), "POST", path, `{"t":1,"reason":"connect"}`)
		require.JSONEq(t, `{"stored":1}`, w.Body.String())
	})
	for _, source := range []string{"obd", "sim"} {
		t.Run("given "+source+" then listing forwards the filter", func(t *testing.T) {
			f := &fakeService{}
			w := request(testRouter(f, telemetry.NewHub()), "GET", "/api/v1/diagnostics?source="+source+"&limit=200", "")
			require.Equal(t, 200, w.Code)
			require.Equal(t, source, f.source)
			require.Equal(t, 200, f.q.Limit)
			require.JSONEq(t, `{"diagnostics":[{"session_id":"`+testID+`","t":1,"data":{"t":1,"reason":"manual"}}]}`, w.Body.String())
		})
	}
	for _, path := range []string{"/api/v1/diagnostics?source=bad", "/api/v1/diagnostics?source=", "/api/v1/diagnostics?limit=201", "/api/v1/diagnostics?limit=0"} {
		t.Run("given invalid query or session then bad request is returned", func(t *testing.T) {
			router := testRouter(&fakeService{}, telemetry.NewHub())
			require.Equal(t, 400, request(router, "GET", path, "").Code)
		})
	}
}

// TestDiagnosticSocket checks acknowledgements and recoverable errors through a real connection.
func TestDiagnosticSocket(t *testing.T) {
	t.Run("given invalid snapshots then a following valid snapshot is acknowledged", func(t *testing.T) {
		ctx, conn := dialIngest(t, socketServer(t, &fakeService{}))
		exchangeIngest(t, ctx, conn, `{"type":"diagnostics","seq":1,"session_id":"`+testID+`","diagnostics":null}`, `{"type":"error","seq":1,"status":400,"error":"diagnostics must be a JSON object"}`)
		exchangeIngest(t, ctx, conn, `{"type":"diagnostics","seq":2,"session_id":"`+testID+`","diagnostics":{"t":1,"reason":"manual"}}`, `{"type":"ack","seq":2}`)
		exchangeIngest(t, ctx, conn, `{"type":"diagnostics","seq":3,"session_id":"bad","diagnostics":{"t":1,"reason":"manual"}}`, `{"type":"error","seq":3,"status":400,"error":"invalid session UUID"}`)
		exchangeIngest(t, ctx, conn, `{"type":"diagnostics","seq":4,"session_id":"`+testID+`","diagnostics":{"t":1,"reason":"connect","padding":"`+strings.Repeat("x", telemetry.MaxDiagnosticsBody)+`"}}`, `{"type":"error","seq":4,"status":400,"error":"diagnostics exceeds 64 KiB"}`)
	})
	t.Run("given unknown session then socket returns not found", func(t *testing.T) {
		ctx, conn := dialIngest(t, socketServer(t, &fakeService{err: telemetry.ErrNotFound}))
		exchangeIngest(t, ctx, conn, `{"type":"diagnostics","seq":1,"session_id":"`+testID+`","diagnostics":{"t":1,"reason":"connect"}}`, `{"type":"error","seq":1,"status":404,"error":"session not found"}`)
	})
}

// TestDiagnosticMessages validates diagnostic envelopes without listening sockets.
func TestDiagnosticMessages(t *testing.T) {
	for _, tc := range []struct {
		raw    string
		err    error
		status int
	}{
		{`{"t":1,"reason":"connect"}`, nil, 0},
		{`{"t":1,"reason":"manual"}`, telemetry.ErrNotFound, 404},
		{`null`, nil, 400},
		{`{"t":1,"reason":"connect","padding":"` + strings.Repeat("x", telemetry.MaxDiagnosticsBody) + `"}`, nil, 400},
	} {
		t.Run("given a diagnostic envelope then normal acknowledgements and errors are returned", func(t *testing.T) {
			h := &Handler{service: &fakeService{err: tc.err}}
			reply := h.handleIngestMessage(context.Background(), []byte(`{"type":"diagnostics","seq":9,"session_id":"`+testID+`","diagnostics":`+tc.raw+`}`))
			require.Equal(t, int64(9), reply.Seq)
			require.Equal(t, tc.status, reply.Status)
			require.Nil(t, reply.BatchResult)
		})
	}
}
