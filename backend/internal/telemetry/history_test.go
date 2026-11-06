package telemetry

import (
	"context"
	"errors"
	"testing"

	"github.com/stretchr/testify/require"
)

// TestSourceFilters checks validation and forwarding to persistence.
func TestSourceFilters(t *testing.T) {
	for _, source := range []string{"", "obd", "sim"} {
		t.Run("given source "+source+" then lists forward it", func(t *testing.T) {
			r := &fakeRepository{}
			s := NewService(r, NewHub())
			_, err := s.Sessions(context.Background(), 50, source, "")
			require.NoError(t, err)
			require.Equal(t, source, r.source)
			_, err = s.Events(context.Background(), "", 100, source, "")
			require.NoError(t, err)
			require.Equal(t, source, r.source)
		})
	}
	for _, source := range []string{"all", "bad", "SIM"} {
		t.Run("given invalid source "+source+" then lists fail", func(t *testing.T) {
			s := NewService(&fakeRepository{}, NewHub())
			_, err := s.Sessions(context.Background(), 50, source, "")
			require.ErrorContains(t, err, "invalid source")
			_, err = s.Events(context.Background(), "", 100, source, "")
			require.ErrorContains(t, err, "invalid source")
		})
	}
}

// TestDeleteHistory checks deleted counts and live invalidation after success only.
func TestDeleteHistory(t *testing.T) {
	for _, source := range []string{"obd", "sim", "all"} {
		t.Run("given bulk source "+source+" then removed sessions are forgotten", func(t *testing.T) {
			h := NewHub()
			h.Publish(Live{SessionID: testID, Source: "sim"})
			s := NewService(&fakeRepository{deletedIDs: []string{testID}}, h)
			n, err := s.DeleteSource(context.Background(), source, "")
			require.NoError(t, err)
			require.Equal(t, 1, n)
			_, ok := h.Latest("sim", "")
			require.False(t, ok)
		})
	}
	t.Run("given a session then deletion clears its latest", func(t *testing.T) {
		h := NewHub()
		h.Publish(Live{SessionID: testID, Source: "obd"})
		n, err := NewService(&fakeRepository{deletedIDs: []string{testID}}, h).Delete(context.Background(), testID)
		require.NoError(t, err)
		require.Equal(t, 1, n)
		_, ok := h.Latest("", "")
		require.False(t, ok)
	})
	t.Run("given missing session then deletion returns not found", func(t *testing.T) {
		n, err := NewService(&fakeRepository{}, NewHub()).Delete(context.Background(), testID)
		require.ErrorIs(t, err, ErrNotFound)
		require.Zero(t, n)
	})
	t.Run("given no matching sessions then bulk deletion returns zero", func(t *testing.T) {
		n, err := NewService(&fakeRepository{}, NewHub()).DeleteSource(context.Background(), "sim", "")
		require.NoError(t, err)
		require.Zero(t, n)
	})
	for _, source := range []string{"", "bad"} {
		t.Run("given invalid bulk source then deletion fails", func(t *testing.T) {
			_, err := NewService(&fakeRepository{}, NewHub()).DeleteSource(context.Background(), source, "")
			require.ErrorContains(t, err, "invalid source")
		})
	}
	t.Run("given invalid id then deletion fails", func(t *testing.T) {
		_, err := NewService(&fakeRepository{}, NewHub()).Delete(context.Background(), "bad")
		require.ErrorContains(t, err, "invalid session UUID")
	})
	for _, bulk := range []bool{false, true} {
		t.Run("given storage failure then snapshots survive", func(t *testing.T) {
			h := NewHub()
			v := Live{SessionID: testID, Source: "sim"}
			h.Publish(v)
			failure := errors.New("offline")
			s := NewService(&fakeRepository{err: failure}, h)
			operations := map[bool]func(context.Context, string) (int, error){false: s.Delete, true: func(ctx context.Context, source string) (int, error) { return s.DeleteSource(ctx, source, "") }}
			arguments := map[bool]string{false: testID, true: "sim"}
			_, err := operations[bulk](context.Background(), arguments[bulk])
			require.ErrorIs(t, err, failure)
			latest, ok := h.Latest("sim", "")
			require.True(t, ok)
			require.Equal(t, v, latest)
		})
	}
}
