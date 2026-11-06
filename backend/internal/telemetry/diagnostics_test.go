package telemetry

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"

	"github.com/stretchr/testify/require"
)

// InsertDiagnostic satisfies existing service fixtures.
func (r *fakeRepository) InsertDiagnostic(context.Context, Diagnostic) (int, error) { return 1, r.err }

// Diagnostics captures the requested source for existing service fixtures.
func (r *fakeRepository) Diagnostics(_ context.Context, _ int, source, device string) ([]Diagnostic, error) {
	r.source = source
	r.device = device
	return []Diagnostic{}, r.err
}

type diagnosticRepository struct {
	*fakeRepository
	entry Diagnostic
	limit int
}

// InsertDiagnostic records the original snapshot for validation tests.
func (r *diagnosticRepository) InsertDiagnostic(_ context.Context, entry Diagnostic) (int, error) {
	r.entry = entry
	return 1, r.err
}

// Diagnostics captures bounded queries for service tests.
func (r *diagnosticRepository) Diagnostics(_ context.Context, limit int, source, device string) ([]Diagnostic, error) {
	r.limit = limit
	r.source = source
	r.device = device
	return []Diagnostic{}, r.err
}

// TestDiagnosticValidation checks object, size, timestamp and reason requirements.
func TestDiagnosticValidation(t *testing.T) {
	for _, data := range []string{`null`, `[]`, `1`, `"text"`, `{}`, `{"t":0,"reason":"connect"}`, `{"t":-1,"reason":"connect"}`, `{"t":1.5,"reason":"connect"}`, `{"t":"1","reason":"connect"}`, `{"t":1}`, `{"t":1,"reason":null}`, `{"t":1,"reason":"unknown"}`, `{"t":1,"reason":"manual"} {}`, strings.Repeat(" ", MaxDiagnosticsBody+1)} {
		t.Run("given invalid snapshot then it is rejected", func(t *testing.T) {
			_, err := ValidateDiagnostic(json.RawMessage(data))
			require.Error(t, err)
		})
	}
	for _, reason := range []string{"connect", "manual", "before_clear", "after_clear"} {
		t.Run("given "+reason+" then nullable and unknown fields are accepted", func(t *testing.T) {
			data := json.RawMessage(`{"t":123,"reason":"` + reason + `","vin":null,"values":{"future":12},"extra":{"new":true}}`)
			timestamp, err := ValidateDiagnostic(data)
			require.NoError(t, err)
			require.Equal(t, int64(123), timestamp)
		})
	}
	t.Run("given exactly the size limit then the original snapshot is accepted", func(t *testing.T) {
		data := `{"t":1,"reason":"manual"}`
		_, err := ValidateDiagnostic(json.RawMessage(data + strings.Repeat(" ", MaxDiagnosticsBody-len(data))))
		require.NoError(t, err)
	})
}

// TestDiagnosticService checks raw preservation, filtering and failure propagation.
func TestDiagnosticService(t *testing.T) {
	t.Run("given valid input then the original object reaches storage", func(t *testing.T) {
		repo := &diagnosticRepository{fakeRepository: &fakeRepository{}}
		service := NewService(repo, nil)
		raw := json.RawMessage(`{ "t":123,"reason":"manual","values":{"future":null}}`)
		n, err := service.StoreDiagnostic(context.Background(), testID, raw)
		require.NoError(t, err)
		require.Equal(t, 1, n)
		require.Equal(t, Diagnostic{SessionID: testID, T: 123, Data: raw}, repo.entry)
		entries, err := service.Diagnostics(context.Background(), 20, "obd", "")
		require.NoError(t, err)
		require.Empty(t, entries)
		require.Equal(t, "obd", repo.source)
		require.Equal(t, 20, repo.limit)
	})
	t.Run("given invalid inputs then no snapshot reaches storage", func(t *testing.T) {
		repo := &diagnosticRepository{fakeRepository: &fakeRepository{}}
		service := NewService(repo, nil)
		_, err := service.StoreDiagnostic(context.Background(), "bad", json.RawMessage(`{"t":1,"reason":"manual"}`))
		require.Error(t, err)
		_, err = service.StoreDiagnostic(context.Background(), testID, json.RawMessage(`{}`))
		require.Error(t, err)
		require.Empty(t, repo.entry)
		_, err = service.Diagnostics(context.Background(), 20, "bad", "")
		require.Error(t, err)
		_, err = service.Diagnostics(context.Background(), 0, "", "")
		require.Error(t, err)
		_, err = service.Diagnostics(context.Background(), 201, "", "")
		require.Error(t, err)
	})
	for _, failure := range []error{ErrNotFound, errors.New("offline")} {
		t.Run("given persistence failure then the error is preserved", func(t *testing.T) {
			service := NewService(&fakeRepository{err: failure}, nil)
			_, err := service.StoreDiagnostic(context.Background(), testID, json.RawMessage(`{"t":1,"reason":"manual"}`))
			require.ErrorIs(t, err, failure)
			_, err = service.Diagnostics(context.Background(), 20, "", "")
			require.ErrorIs(t, err, failure)
		})
	}
}
