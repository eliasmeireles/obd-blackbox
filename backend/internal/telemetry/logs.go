package telemetry

import (
	"context"
	"regexp"
	"time"
)

// LogInput is a device-generated log entry with an optional telemetry session.
type LogInput struct {
	ID        string  `json:"id"`
	T         int64   `json:"t"`
	Level     string  `json:"level"`
	Tag       string  `json:"tag"`
	Message   string  `json:"message"`
	SessionID *string `json:"session_id"`
}

// AppLog associates an entry with its authenticated device identity.
type AppLog struct {
	LogInput
	Device string `json:"device"`
}

// LogQuery describes inclusive time bounds, exact filters and result ordering.
type LogQuery struct {
	Since, Until                  *time.Time
	Level, Tag, Device, SessionID string
	Limit                         int
	Order                         string
}

var logTagPattern = regexp.MustCompile(`^[a-z0-9_-]{1,32}$`)

// LogLevelRank maps increasing severity to a positive rank; zero means invalid.
func LogLevelRank(level string) int {
	switch level {
	case "debug":
		return 1
	case "info":
		return 2
	case "warn":
		return 3
	case "error":
		return 4
	default:
		return 0
	}
}

// IngestLogs validates a complete batch and truncates messages before atomic insertion.
func (s *Service) IngestLogs(ctx context.Context, device string, batch []LogInput) (BatchResult, error) {
	if len(batch) == 0 || len(batch) > 1000 {
		return BatchResult{}, invalid("logs batch must contain 1 to 1000 entries")
	}
	normalized := make([]LogInput, len(batch))
	for i, entry := range batch {
		if err := validateLog(entry); err != nil {
			return BatchResult{}, invalid("log index %d: %v", i, err)
		}
		runes := []rune(entry.Message)
		if len(runes) > 4000 {
			entry.Message = string(runes[:4000])
		}
		normalized[i] = entry
	}
	return s.repo.InsertLogs(ctx, device, normalized)
}

// validateLog checks required fields and optional session identity.
func validateLog(entry LogInput) error {
	switch {
	case !ValidID(entry.ID):
		return invalid("invalid log UUID")
	case entry.T <= 0:
		return invalid("t must be positive")
	case LogLevelRank(entry.Level) == 0:
		return invalid("invalid level")
	case !logTagPattern.MatchString(entry.Tag):
		return invalid("invalid tag")
	case entry.Message == "":
		return invalid("message is required")
	case entry.SessionID != nil && !ValidID(*entry.SessionID):
		return invalid("invalid session UUID")
	}
	return nil
}

// Logs retrieves matching entries after validating query bounds and filters.
func (s *Service) Logs(ctx context.Context, q LogQuery) ([]AppLog, error) {
	if err := ValidateLogQuery(q); err != nil {
		return nil, err
	}
	return s.repo.Logs(ctx, q)
}

// ValidateLogQuery rejects invalid filters before they reach storage.
func ValidateLogQuery(q LogQuery) error {
	switch {
	case q.Level != "" && LogLevelRank(q.Level) == 0:
		return invalid("invalid level")
	case q.Tag != "" && !logTagPattern.MatchString(q.Tag):
		return invalid("invalid tag")
	case q.SessionID != "" && !ValidID(q.SessionID):
		return invalid("invalid session UUID")
	case q.Limit < 1 || q.Limit > 5000:
		return invalid("invalid limit")
	case q.Order != "asc" && q.Order != "desc":
		return invalid("invalid order")
	case q.Since != nil && q.Until != nil && q.Since.After(*q.Until):
		return invalid("since must not exceed until")
	}
	return nil
}

// PruneLogs deletes entries logged before cutoff, independent of receipt time.
func (s *Service) PruneLogs(ctx context.Context, cutoff time.Time) error {
	return s.repo.PruneLogs(ctx, cutoff)
}
