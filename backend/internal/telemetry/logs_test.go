package telemetry

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

type logRepository struct {
	*fakeRepository
	logs   []LogInput
	device string
	query  LogQuery
	cutoff time.Time
}

// InsertLogs captures the normalized batch.
func (r *logRepository) InsertLogs(_ context.Context, device string, logs []LogInput) (BatchResult, error) {
	r.logs, r.device = logs, device
	return BatchResult{Accepted: 1, Duplicates: 1}, r.err
}

// Logs captures filters.
func (r *logRepository) Logs(_ context.Context, q LogQuery) ([]AppLog, error) {
	r.query = q
	return []AppLog{}, r.err
}

// PruneLogs captures the cutoff.
func (r *logRepository) PruneLogs(_ context.Context, t time.Time) error { r.cutoff = t; return r.err }

// InsertLogs satisfies unrelated service fixtures.
func (r *fakeRepository) InsertLogs(context.Context, string, []LogInput) (BatchResult, error) {
	return r.result, r.err
}

// Logs satisfies unrelated service fixtures.
func (r *fakeRepository) Logs(context.Context, LogQuery) ([]AppLog, error) { return []AppLog{}, r.err }

// PruneLogs satisfies unrelated service fixtures.
func (r *fakeRepository) PruneLogs(context.Context, time.Time) error { return r.err }

// TestLogValidation verifies all validation paths before persistence.
func TestLogValidation(t *testing.T) {
	valid := LogInput{ID: testID, T: 1, Level: "warn", Tag: "elm", Message: "message"}
	for _, tc := range []struct {
		name  string
		entry LogInput
	}{
		{"uuid", LogInput{ID: "bad", T: 1, Level: "warn", Tag: "elm", Message: "m"}},
		{"timestamp", LogInput{ID: testID, Level: "warn", Tag: "elm", Message: "m"}},
		{"level", LogInput{ID: testID, T: 1, Level: "fatal", Tag: "elm", Message: "m"}},
		{"tag", LogInput{ID: testID, T: 1, Level: "warn", Tag: "ELM", Message: "m"}},
		{"empty tag", LogInput{ID: testID, T: 1, Level: "warn", Message: "m"}},
		{"long tag", LogInput{ID: testID, T: 1, Level: "warn", Tag: strings.Repeat("a", 33), Message: "m"}},
		{"message", LogInput{ID: testID, T: 1, Level: "warn", Tag: "elm"}},
		{"session", LogInput{ID: testID, T: 1, Level: "warn", Tag: "elm", Message: "m", SessionID: new("bad")}},
	} {
		t.Run("given invalid "+tc.name+" then storage is untouched", func(t *testing.T) {
			repo := &logRepository{fakeRepository: &fakeRepository{}}
			_, err := NewService(repo, NewHub()).IngestLogs(context.Background(), "example", []LogInput{tc.entry})
			require.ErrorContains(t, err, "log index 0")
			require.Nil(t, repo.logs)
		})
	}
	for _, batch := range [][]LogInput{nil, make([]LogInput, 1001)} {
		t.Run("given invalid batch size then the batch is rejected", func(t *testing.T) {
			_, err := NewService(&fakeRepository{}, NewHub()).IngestLogs(context.Background(), "example", batch)
			require.Error(t, err)
		})
	}
	t.Run("given valid unicode messages then truncation preserves 4000 runes", func(t *testing.T) {
		repo := &logRepository{fakeRepository: &fakeRepository{}}
		valid.Message = strings.Repeat("ç🚙", 2500)
		valid.SessionID = new(testID)
		result, err := NewService(repo, NewHub()).IngestLogs(context.Background(), "example", []LogInput{valid, valid})
		require.NoError(t, err)
		require.Equal(t, BatchResult{Accepted: 1, Duplicates: 1}, result)
		require.Equal(t, "example", repo.device)
		require.Equal(t, 4000, len([]rune(repo.logs[0].Message)))
		require.Equal(t, 5000, len([]rune(valid.Message)))
	})
	t.Run("given maximum valid batch then all levels are accepted", func(t *testing.T) {
		repo := &logRepository{fakeRepository: &fakeRepository{}}
		batch := make([]LogInput, 1000)
		for i := range batch {
			batch[i] = valid
			batch[i].Level = []string{"debug", "info", "warn", "error"}[i%4]
		}
		_, err := NewService(repo, NewHub()).IngestLogs(context.Background(), "example", batch)
		require.NoError(t, err)
		require.Len(t, repo.logs, 1000)
	})
}

// TestLogService verifies query validation, delegation and error propagation.
func TestLogService(t *testing.T) {
	t.Run("given valid operations then repository receives filters and cutoff", func(t *testing.T) {
		repo := &logRepository{fakeRepository: &fakeRepository{}}
		s := NewService(repo, NewHub())
		q := LogQuery{Limit: 500, Order: "asc", Level: "warn", Tag: "elm", Device: "example", SessionID: testID}
		logs, err := s.Logs(context.Background(), q)
		require.NoError(t, err)
		require.Empty(t, logs)
		require.Equal(t, q, repo.query)
		cutoff := time.Now()
		require.NoError(t, s.PruneLogs(context.Background(), cutoff))
		require.Equal(t, cutoff, repo.cutoff)
		repo.err = errors.New("offline")
		_, err = s.IngestLogs(context.Background(), "example", []LogInput{{ID: testID, T: 1, Level: "info", Tag: "app", Message: "m"}})
		require.ErrorIs(t, err, repo.err)
		_, err = s.Logs(context.Background(), q)
		require.ErrorIs(t, err, repo.err)
		require.ErrorIs(t, s.PruneLogs(context.Background(), cutoff), repo.err)
	})
	for _, q := range []LogQuery{
		{Limit: 0, Order: "asc"}, {Limit: 5001, Order: "asc"}, {Limit: 1, Order: "bad"},
		{Limit: 1, Order: "asc", Level: "bad"}, {Limit: 1, Order: "asc", Tag: "!"}, {Limit: 1, Order: "asc", SessionID: "bad"},
		{Limit: 1, Order: "asc", Since: new(time.Unix(2, 0)), Until: new(time.Unix(1, 0))},
	} {
		t.Run("given invalid filters then the service rejects the query", func(t *testing.T) {
			_, err := NewService(&fakeRepository{}, NewHub()).Logs(context.Background(), q)
			require.Error(t, err)
		})
	}
}
