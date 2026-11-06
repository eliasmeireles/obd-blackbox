package httpapi

import (
	"bytes"
	"context"
	"encoding/json"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
)

type ingestMessage struct {
	Diagnostics json.RawMessage      `json:"diagnostics"`
	CommandID   string               `json:"command_id"`
	OK          *bool                `json:"ok"`
	Message     string               `json:"message"`
	Logs        []telemetry.LogInput `json:"logs"`
	Type        string               `json:"type"`
	Seq         int64                `json:"seq"`
	ID          string               `json:"id"`
	SessionID   string               `json:"session_id"`
	Samples     []json.RawMessage    `json:"samples"`
}

type ingestReply struct {
	Type   string `json:"type"`
	Seq    int64  `json:"seq"`
	Status int    `json:"status,omitempty"`
	Error  string `json:"error,omitempty"`
	*telemetry.BatchResult
}

// messageSequence recovers a readable counter even when later JSON is malformed.
func messageSequence(data []byte) int64 {
	decoder := json.NewDecoder(bytes.NewReader(data))
	token, err := decoder.Token()
	if err != nil || token != json.Delim('{') {
		return 0
	}
	for decoder.More() {
		key, err := decoder.Token()
		if err != nil {
			return 0
		}
		if key == "seq" {
			var seq int64
			if decoder.Decode(&seq) == nil && seq > 0 {
				return seq
			}
			return 0
		}
		if decoder.Decode(new(json.RawMessage)) != nil {
			return 0
		}
	}
	return 0
}

// handleIngestMessage processes a single message through the shared REST service.
func (h *Handler) handleIngestMessage(ctx context.Context, data []byte) ingestReply {
	reply := ingestReply{Type: "error", Seq: messageSequence(data), Status: 400, Error: "invalid message"}
	var in ingestMessage
	if json.Unmarshal(data, &in) != nil || in.Seq <= 0 {
		return reply
	}
	var result *telemetry.BatchResult
	var err error
	switch in.Type {
	case "diagnostics":
		_, err = h.service.StoreDiagnostic(ctx, in.SessionID, in.Diagnostics)
	case "command_result":
		err = ingestCommandResult(ctx, in)
	case "session":
		err = h.ingestSession(ctx, in.ID, data)
	case "logs":
		result, err = h.ingestLogs(ctx, in.Logs)
	case "samples":
		result, err = h.ingestSamples(ctx, in.SessionID, in.Samples)
	default:
		reply.Error = "unknown message type"
		return reply
	}
	if err != nil {
		reply.Status, reply.Error = h.applicationError(err)
		return reply
	}
	return ingestReply{Type: "ack", Seq: in.Seq, BatchResult: result}
}

// ingestSession applies the same UUID, metadata and service validation as PUT sessions.
func (h *Handler) ingestSession(ctx context.Context, id string, data []byte) error {
	if !telemetry.ValidID(id) {
		return &telemetry.ValidationError{Message: "invalid session UUID"}
	}
	var in telemetry.SessionInput
	if json.Unmarshal(data, &in) != nil {
		return &telemetry.ValidationError{Message: "invalid message"}
	}
	if err := validateSessionInput(in); err != nil {
		return err
	}
	in.Device, _ = ctx.Value(identityKey{}).(string)
	_, err := h.service.Upsert(ctx, id, in)
	return err
}

// ingestSamples shares indexed parsing and domain validation with POST samples.
func (h *Handler) ingestSamples(ctx context.Context, id string, raw []json.RawMessage) (*telemetry.BatchResult, error) {
	if !telemetry.ValidID(id) {
		return nil, &telemetry.ValidationError{Message: "invalid session UUID"}
	}
	batch, err := parseSamples(raw)
	if err != nil {
		return nil, &telemetry.ValidationError{Message: err.Error()}
	}
	result, err := h.service.Ingest(ctx, id, batch)
	return &result, err
}
