package telemetry

import (
	"context"
	"encoding/json"
)

// MaxDiagnosticsBody bounds a complete diagnostic snapshot, including unknown fields.
const MaxDiagnosticsBody = 64 << 10

// Diagnostic preserves the complete device snapshot and its session timestamp.
type Diagnostic struct {
	SessionID string          `json:"session_id"`
	T         int64           `json:"t"`
	Data      json.RawMessage `json:"data"`
}

// ValidateDiagnostic accepts an object with a positive timestamp and a known reason.
func ValidateDiagnostic(data json.RawMessage) (int64, error) {
	if len(data) > MaxDiagnosticsBody {
		return 0, invalid("diagnostics exceeds 64 KiB")
	}
	var object map[string]json.RawMessage
	if json.Unmarshal(data, &object) != nil || object == nil {
		return 0, invalid("diagnostics must be a JSON object")
	}
	var t int64
	var reason string
	if json.Unmarshal(object["t"], &t) != nil || t <= 0 {
		return 0, invalid("t must be positive")
	}
	if json.Unmarshal(object["reason"], &reason) != nil {
		return 0, invalid("invalid reason")
	}
	switch reason {
	case "connect", "manual", "before_clear", "after_clear":
		return t, nil
	default:
		return 0, invalid("invalid reason")
	}
}

// StoreDiagnostic validates and stores a retryable snapshot without normalizing its fields.
func (s *Service) StoreDiagnostic(ctx context.Context, id string, data json.RawMessage) (int, error) {
	if !ValidID(id) {
		return 0, invalid("invalid session UUID")
	}
	t, err := ValidateDiagnostic(data)
	if err != nil {
		return 0, err
	}
	return s.repo.InsertDiagnostic(ctx, Diagnostic{SessionID: id, T: t, Data: data})
}

// Diagnostics lists the newest snapshots of an optional telemetry source.
func (s *Service) Diagnostics(ctx context.Context, limit int, source, device string) ([]Diagnostic, error) {
	if err := ValidateSourceFilter(source); err != nil {
		return nil, err
	}
	if limit < 1 || limit > 200 {
		return nil, invalid("invalid limit")
	}
	return s.repo.Diagnostics(ctx, limit, source, device)
}
