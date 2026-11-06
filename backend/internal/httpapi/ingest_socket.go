package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"time"

	"github.com/coder/websocket"
	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
)

const (
	ingestPingInterval = 20 * time.Second
	ingestPingTimeout  = 10 * time.Second
)

// ingestSocket upgrades authenticated requests and logs one summary after disconnect.
func (h *Handler) ingestSocket(w http.ResponseWriter, r *http.Request) {
	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		CompressionMode: websocket.CompressionContextTakeover,
	})
	if err != nil {
		return
	}
	defer conn.CloseNow()
	conn.SetReadLimit(maxBody)
	start := time.Now()
	name, _ := r.Context().Value(identityKey{}).(string)
	device := h.devices.Register(name, func(ctx context.Context, command telemetry.Command) error {
		data, err := json.Marshal(command)
		if err != nil {
			return err
		}
		// coder/websocket serializes concurrent Write calls; only Read must remain sequential.
		return conn.Write(ctx, websocket.MessageText, data)
	})
	defer h.devices.Remove(device)
	request := context.WithValue(r.Context(), deviceConnectionKey{}, device)
	// Reads must outlive request cancellation so shutdown can send a GoingAway frame.
	ctx, cancel := context.WithCancel(context.WithoutCancel(r.Context()))
	done := make(chan string, 1)
	go func() { done <- maintainIngestSocket(r.Context(), ctx, conn, ingestPingInterval, ingestPingTimeout) }()
	count, reason := h.readIngestSocket(request, ctx, conn)
	cancel()
	if lifecycleReason := <-done; lifecycleReason != "" {
		reason = lifecycleReason
	}
	h.logger.Info("websocket ingestion ended", "identity", name, "duration", time.Since(start),
		"messages", count, "close_reason", reason)
}

// readIngestSocket processes and acknowledges messages sequentially on the handler goroutine.
func (h *Handler) readIngestSocket(request, reads context.Context, conn *websocket.Conn) (int, string) {
	count := 0
	for {
		kind, data, err := conn.Read(reads)
		if err != nil {
			return count, err.Error()
		}
		if kind != websocket.MessageText {
			conn.Close(websocket.StatusUnsupportedData, "text messages required")
			return count, "binary message"
		}
		if device, ok := request.Value(deviceConnectionKey{}).(*telemetry.DeviceConnection); ok {
			device.Touch()
		}
		reply := h.handleIngestMessage(request, data)
		count++
		data, err = json.Marshal(reply)
		if err == nil {
			err = conn.Write(reads, websocket.MessageText, data)
		}
		if err != nil {
			return count, err.Error()
		}
	}
}

// maintainIngestSocket handles keepalive and sends a graceful shutdown close frame.
func maintainIngestSocket(request, lifetime context.Context, conn *websocket.Conn, interval, timeout time.Duration) string {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-request.Done():
			conn.Close(websocket.StatusGoingAway, "server shutting down")
			return "server shutting down"
		case <-lifetime.Done():
			return ""
		case <-ticker.C:
			ctx, cancel := context.WithTimeout(request, timeout)
			stop := context.AfterFunc(lifetime, cancel)
			err := conn.Ping(ctx)
			cancel()
			stop()
			if err != nil {
				if request.Err() != nil {
					conn.Close(websocket.StatusGoingAway, "server shutting down")
					return "server shutting down"
				}
				if lifetime.Err() != nil {
					return ""
				}
				conn.Close(websocket.StatusGoingAway, "keepalive failed")
				return "keepalive failed"
			}
		}
	}
}
