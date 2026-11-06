package httpapi

import (
	"bufio"
	"context"
	"io"

	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
	"github.com/stretchr/testify/require"
)

// TestLive checks empty and populated live snapshots.
func TestLive(t *testing.T) {
	t.Run("given no received sample then live returns no content", func(t *testing.T) {
		w := request(testRouter(&fakeService{}, telemetry.NewHub()), "GET", "/api/v1/live", "")
		require.Equal(t, 204, w.Code)
		require.Empty(t, w.Body.String())
	})
	t.Run("given latest sample then live includes session metadata", func(t *testing.T) {
		h := telemetry.NewHub()
		h.Publish(telemetry.Live{SessionID: testID, Sample: telemetry.Sample{T: 1, V: map[string]float64{}, Alerts: []string{}}})
		w := request(testRouter(&fakeService{session: telemetry.Session{ID: testID}}, h), "GET", "/api/v1/live", "")
		require.Equal(t, 200, w.Code)
		require.Contains(t, w.Body.String(), `"session":`)
		require.Contains(t, w.Body.String(), `"sample":{"t":1`)
	})
}

// pipeResponse streams handler output without requiring a network listener.
type pipeResponse struct {
	header http.Header
	writer *io.PipeWriter
}

// Header returns the response headers.
func (p *pipeResponse) Header() http.Header { return p.header }

// WriteHeader accepts the successful SSE status.
func (p *pipeResponse) WriteHeader(int) {}

// Write forwards SSE bytes to the test reader.
func (p *pipeResponse) Write(data []byte) (int, error) { return p.writer.Write(data) }

// Flush implements HTTP flushing for an unbuffered pipe.
func (p *pipeResponse) Flush() {}

// runStream invokes the handler and returns its reader and completion channel.
func runStream(ctx context.Context, h *Handler) (*bufio.Reader, <-chan struct{}, func()) {
	reader, writer := io.Pipe()
	done := make(chan struct{})
	response := &pipeResponse{header: http.Header{}, writer: writer}
	request := httptest.NewRequest("GET", "/", nil).WithContext(ctx)
	go func() { defer close(done); defer writer.Close(); h.stream(response, request) }()
	return bufio.NewReader(reader), done, func() { reader.Close(); writer.Close() }
}

// TestStream checks snapshots, fan-out, pings and cancellation without network access.
func TestStream(t *testing.T) {
	t.Run("given latest and future samples then SSE delivers both", func(t *testing.T) {
		hub := telemetry.NewHub()
		hub.Publish(telemetry.Live{SessionID: testID, Sample: telemetry.Sample{T: 1}})
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		h := &Handler{hub: hub, pingInterval: time.Hour}
		reader, done, closePipe := runStream(ctx, h)
		defer closePipe()
		line, err := reader.ReadString('\n')
		require.NoError(t, err)
		require.Equal(t, "event: sample\n", line)
		line, err = reader.ReadString('\n')
		require.NoError(t, err)
		require.Contains(t, line, `"t":1`)
		_, err = reader.ReadString('\n')
		require.NoError(t, err)
		hub.Publish(telemetry.Live{SessionID: testID, Sample: telemetry.Sample{T: 2}})
		line, err = reader.ReadString('\n')
		require.NoError(t, err)
		require.Equal(t, "event: sample\n", line)
		line, err = reader.ReadString('\n')
		require.NoError(t, err)
		require.Contains(t, line, `"t":2`)
		_, err = reader.ReadString('\n')
		require.NoError(t, err)
		cancel()
		<-done
	})
	t.Run("given no samples then periodic ping keeps stream alive", func(t *testing.T) {
		h := &Handler{hub: telemetry.NewHub(), pingInterval: time.Millisecond}
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		reader, done, closePipe := runStream(ctx, h)
		defer closePipe()
		line, err := reader.ReadString('\n')
		require.NoError(t, err)
		require.True(t, strings.HasPrefix(line, ": ping"))
		_, err = reader.ReadString('\n')
		require.NoError(t, err)
		cancel()
		<-done
	})
}

// TestLiveSources checks source selection and removal of live snapshots.
func TestLiveSources(t *testing.T) {
	for _, source := range []string{"sim", "obd"} {
		t.Run("given mixed samples then live returns only "+source, func(t *testing.T) {
			h := telemetry.NewHub()
			h.Publish(telemetry.Live{SessionID: "sim-session", Source: "sim", Sample: telemetry.Sample{T: 1}})
			h.Publish(telemetry.Live{SessionID: "obd-session", Source: "obd", Sample: telemetry.Sample{T: 2}})
			router := testRouter(&fakeService{}, h)
			w := request(router, "GET", "/api/v1/live?source="+source, "")
			require.Equal(t, 200, w.Code)
			require.Contains(t, w.Body.String(), `"source":"`+source+`"`)
			require.Contains(t, w.Body.String(), `"session_id":"`+source+`-session"`)
			h.Forget([]string{source + "-session"})
			w = request(router, "GET", "/api/v1/live?source="+source, "")
			require.Equal(t, 204, w.Code)
		})
	}
}

// TestStreamSources checks source filtering of both initial and subsequent events.
func TestStreamSources(t *testing.T) {
	for _, source := range []string{"sim", "obd"} {
		t.Run("given a source then SSE sends only matching samples", func(t *testing.T) {
			hub := telemetry.NewHub()
			hub.Publish(telemetry.Live{SessionID: "selected", Source: source, Sample: telemetry.Sample{T: 1}})
			hub.Publish(telemetry.Live{SessionID: "other", Source: map[string]string{"sim": "obd", "obd": "sim"}[source], Sample: telemetry.Sample{T: 9}})
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			reader, writer := io.Pipe()
			defer reader.Close()
			defer writer.Close()
			done := make(chan struct{})
			h := &Handler{hub: hub, pingInterval: time.Hour}
			r := httptest.NewRequest("GET", "/?source="+source, nil).WithContext(ctx)
			go func() {
				defer close(done)
				defer writer.Close()
				h.stream(&pipeResponse{header: http.Header{}, writer: writer}, r)
			}()
			stream := bufio.NewReader(reader)
			line, err := stream.ReadString('\n')
			require.NoError(t, err)
			require.Equal(t, "event: sample\n", line)
			line, err = stream.ReadString('\n')
			require.NoError(t, err)
			require.Contains(t, line, `"source":"`+source+`"`)
			require.Contains(t, line, `"t":1`)
			_, err = stream.ReadString('\n')
			require.NoError(t, err)
			hub.Publish(telemetry.Live{Source: map[string]string{"sim": "obd", "obd": "sim"}[source], Sample: telemetry.Sample{T: 99}})
			hub.Publish(telemetry.Live{SessionID: "selected", Source: source, Sample: telemetry.Sample{T: 2}})
			line, err = stream.ReadString('\n')
			require.NoError(t, err)
			require.Equal(t, "event: sample\n", line)
			line, err = stream.ReadString('\n')
			require.NoError(t, err)
			require.Contains(t, line, `"source":"`+source+`"`)
			require.Contains(t, line, `"t":2`)
			_, err = stream.ReadString('\n')
			require.NoError(t, err)
			cancel()
			<-done
		})
	}
}

// TestLiveDeviceFilter checks latest snapshots across devices sharing the same source.
func TestLiveDeviceFilter(t *testing.T) {
	t.Run("given samples from several devices then live returns only the selected device", func(t *testing.T) {
		hub := telemetry.NewHub()
		hub.Publish(telemetry.Live{SessionID: "first", Device: "phone-a", Source: "obd", Sample: telemetry.Sample{T: 1}})
		hub.Publish(telemetry.Live{SessionID: "second", Device: "phone-b", Source: "obd", Sample: telemetry.Sample{T: 2}})
		router := testRouter(&fakeService{}, hub)
		filtered := request(router, "GET", "/api/v1/live?device=phone-a&source=obd", "")
		require.Equal(t, 200, filtered.Code)
		require.Contains(t, filtered.Body.String(), `"device":"phone-a"`)
		require.Contains(t, filtered.Body.String(), `"session_id":"first"`)
		latest := request(router, "GET", "/api/v1/live", "")
		require.Contains(t, latest.Body.String(), `"session_id":"second"`)
		require.Equal(t, 204, request(router, "GET", "/api/v1/live?device=unknown", "").Code)
	})
}

// TestStreamDeviceIsolation checks initial and subsequent SSE events without opening sockets.
func TestStreamDeviceIsolation(t *testing.T) {
	t.Run("given a selected device then its stream excludes every other device", func(t *testing.T) {
		hub := telemetry.NewHub()
		hub.Publish(telemetry.Live{SessionID: "first", Device: "phone-a", Source: "obd", Sample: telemetry.Sample{T: 1}})
		hub.Publish(telemetry.Live{SessionID: "second", Device: "phone-b", Source: "obd", Sample: telemetry.Sample{T: 9}})
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		reader, writer := io.Pipe()
		defer reader.Close()
		defer writer.Close()
		done := make(chan struct{})
		h := &Handler{hub: hub, pingInterval: time.Hour}
		r := httptest.NewRequest("GET", "/?source=obd&device=phone-a", nil).WithContext(ctx)
		go func() {
			defer close(done)
			defer writer.Close()
			h.stream(&pipeResponse{header: http.Header{}, writer: writer}, r)
		}()
		stream := bufio.NewReader(reader)
		line, err := stream.ReadString('\n')
		require.NoError(t, err)
		require.Equal(t, "event: sample\n", line)
		line, err = stream.ReadString('\n')
		require.NoError(t, err)
		require.Contains(t, line, `"device":"phone-a"`)
		require.Contains(t, line, `"t":1`)
		_, err = stream.ReadString('\n')
		require.NoError(t, err)
		hub.Publish(telemetry.Live{Device: "phone-b", Source: "obd", Sample: telemetry.Sample{T: 99}})
		hub.Publish(telemetry.Live{Device: "phone-a", Source: "sim", Sample: telemetry.Sample{T: 88}})
		hub.Publish(telemetry.Live{Device: "phone-a", Source: "obd", Sample: telemetry.Sample{T: 2}})
		line, err = stream.ReadString('\n')
		require.NoError(t, err)
		require.Equal(t, "event: sample\n", line)
		line, err = stream.ReadString('\n')
		require.NoError(t, err)
		require.Contains(t, line, `"device":"phone-a"`)
		require.Contains(t, line, `"t":2`)
		_, err = stream.ReadString('\n')
		require.NoError(t, err)
		cancel()
		<-done
	})
}
