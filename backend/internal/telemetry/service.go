package telemetry

import (
	"cmp"
	"context"
	"fmt"
	"math"
	"regexp"
	"slices"
	"sync"
	"time"
)

// Repository is the persistence contract consumed by the service.
type Repository interface {
	Devices(context.Context) ([]Device, error)
	InsertDiagnostic(context.Context, Diagnostic) (int, error)
	Diagnostics(context.Context, int, string, string) ([]Diagnostic, error)
	InsertLogs(context.Context, string, []LogInput) (BatchResult, error)
	Logs(context.Context, LogQuery) ([]AppLog, error)
	PruneLogs(context.Context, time.Time) error
	Upsert(context.Context, string, SessionInput) (Session, error)
	Get(context.Context, string) (Session, error)
	Sessions(context.Context, int, string, string) ([]Session, error)
	InsertSamples(context.Context, string, []SampleInput, []Event) (BatchResult, error)
	Samples(context.Context, string, Query) ([]Sample, error)
	Events(context.Context, string, int, string, string) ([]Event, error)
	InsertDTCs(context.Context, string, DTCInput) (int, error)
	DTCs(context.Context, string) ([]DTC, error)
	Delete(context.Context, string) ([]string, error)
	DeleteSource(context.Context, string, string) ([]string, error)
	Ping(context.Context) error
}
type Publisher interface {
	Publish(Live)
	Forget([]string)
}
type Service struct {
	mu   sync.Mutex
	repo Repository
	hub  Publisher
}

// liveBatchLimit separates streaming batches from offline backlog uploads.
const liveBatchLimit = 10

var uuidPattern = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)
var dtcPattern = regexp.MustCompile(`^[PBCU][0-9A-F]{4}$`)
var alertIDs = map[string]bool{"LOW_VOLTAGE": true, "OVERHEAT": true, "IDLE_DROP": true, "NO_THROTTLE_RESPONSE": true, "LEAN_MIXTURE": true, "RICH_MIXTURE": true}

// NewService creates application operations backed by a repository and live publisher.
func NewService(repo Repository, hub Publisher) *Service { return &Service{repo: repo, hub: hub} }

// ValidID reports whether id uses UUID syntax.
func ValidID(id string) bool { return uuidPattern.MatchString(id) }

// Upsert validates and creates or updates an idempotent session.
func (s *Service) Upsert(ctx context.Context, id string, in SessionInput) (Session, error) {
	if !ValidID(id) {
		return Session{}, invalid("invalid session UUID")
	}
	if !ValidSource(in.Source) {
		return Session{}, invalid("invalid source")
	}
	if in.StartedAt.IsZero() {
		return Session{}, invalid("started_at is required")
	}
	if in.Vehicle == "" {
		in.Vehicle = "obd-ii"
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.repo.Upsert(ctx, id, in)
}

// Ingest validates the entire batch before storing it and publishing after commit.
func (s *Service) Ingest(ctx context.Context, id string, batch []SampleInput) (BatchResult, error) {
	if len(batch) > 1000 {
		return BatchResult{}, invalid("sample index 1000: batch exceeds 1000 samples")
	}
	events := []Event{}
	for i := range batch {
		v := &batch[i]
		if err := validateSample(*v); err != nil {
			return BatchResult{}, invalid("sample index %d: %v", i, err)
		}
		if v.Alerts == nil {
			v.Alerts = []string{}
		}
		if v.Event != nil {
			events = append(events, Event{SessionID: id, T: v.T, Label: v.Event.Label, Automatic: v.Event.Automatic})
		}
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	session, err := s.repo.Get(ctx, id)
	if err != nil {
		return BatchResult{}, err
	}
	result, err := s.repo.InsertSamples(ctx, id, batch, events)
	if err == nil {
		s.publishBatch(id, session.Source, session.Device, batch)
	}
	return result, err
}

// publishBatch preserves streaming samples in time order and collapses offline backlogs.
func (s *Service) publishBatch(id, source, device string, batch []SampleInput) {
	if len(batch) == 0 {
		return
	}
	compare := func(a, b SampleInput) int { return cmp.Compare(a.T, b.T) }
	live := []SampleInput{slices.MaxFunc(batch, compare)}
	if len(batch) <= liveBatchLimit {
		live = slices.Clone(batch)
		slices.SortStableFunc(live, compare)
	}
	for _, sample := range live {
		s.hub.Publish(Live{SessionID: id, Source: source, Device: device, Sample: sample.Output()})
	}
}

// validateSample rejects invalid timestamps, signals and alert identifiers.
func validateSample(v SampleInput) error {
	if v.T <= 0 {
		return invalid("t must be positive")
	}
	for key, n := range v.V {
		if math.IsNaN(n) || math.IsInf(n, 0) {
			return invalid("signal value must be finite")
		}
		for _, known := range Signals {
			if key == known && math.IsInf(float64(float32(n)), 0) {
				return invalid("typed signal value exceeds REAL range")
			}
		}
	}
	for _, a := range v.Alerts {
		if !alertIDs[a] {
			return invalid("unknown alert")
		}
	}
	if v.Event != nil && v.Event.Label == "" {
		return invalid("event label is required")
	}
	return nil
}

// StoreDTCs validates diagnostic codes before persisting them.
func (s *Service) StoreDTCs(ctx context.Context, id string, in DTCInput) (int, error) {
	if in.T <= 0 {
		return 0, invalid("t must be positive")
	}
	if in.Kind != "stored" && in.Kind != "pending" && in.Kind != "permanent" {
		return 0, invalid("invalid kind")
	}
	for _, code := range in.Codes {
		if !dtcPattern.MatchString(code) {
			return 0, invalid("invalid DTC code")
		}
	}
	return s.repo.InsertDTCs(ctx, id, in)
}

// Get retrieves a session.
func (s *Service) Get(ctx context.Context, id string) (Session, error) { return s.repo.Get(ctx, id) }

// Sessions lists sessions in descending start order.
func (s *Service) Sessions(ctx context.Context, limit int, source, device string) ([]Session, error) {
	if err := ValidateSourceFilter(source); err != nil {
		return nil, err
	}
	return s.repo.Sessions(ctx, limit, source, device)
}

// Samples lists a session's samples with optional downsampling.
func (s *Service) Samples(ctx context.Context, id string, q Query) ([]Sample, error) {
	return s.repo.Samples(ctx, id, q)
}

// Events lists events, optionally scoped to a session.
func (s *Service) Events(ctx context.Context, id string, limit int, source, device string) ([]Event, error) {
	if err := ValidateSourceFilter(source); err != nil {
		return nil, err
	}
	return s.repo.Events(ctx, id, limit, source, device)
}

// DTCs retrieves recorded diagnostic codes.
func (s *Service) DTCs(ctx context.Context, id string) ([]DTC, error) { return s.repo.DTCs(ctx, id) }

// Ping checks storage readiness.
func (s *Service) Ping(ctx context.Context) error { return s.repo.Ping(ctx) }

// ValidationError identifies invalid client input.
type ValidationError struct{ Message string }

// Error returns the validation message.
func (e *ValidationError) Error() string { return e.Message }

// invalid creates a typed input error without exposing storage failures.
func invalid(format string, args ...any) error {
	return &ValidationError{Message: fmt.Sprintf(format, args...)}
}
