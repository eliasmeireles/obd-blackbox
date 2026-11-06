package telemetry

import (
	"context"
	"errors"
	"fmt"
	"math"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

const testID = "00000000-0000-0000-0000-000000000001"

type fakeRepository struct {
	err          error
	source       string
	device       string
	knownDevices []Device
	deletedIDs   []string
	getCalls     int
	batch        []SampleInput
	events       []Event
	input        SessionInput
	result       BatchResult
}

// Upsert captures validated metadata.
func (f *fakeRepository) Upsert(_ context.Context, id string, in SessionInput) (Session, error) {
	f.input = in
	return Session{ID: id}, f.err
}

// Get returns a fictional session.
func (f *fakeRepository) Get(context.Context, string) (Session, error) {
	f.getCalls++
	return Session{ID: testID, Source: "sim", Device: f.input.Device}, f.err
}

// Sessions returns an empty list.
func (f *fakeRepository) Sessions(_ context.Context, _ int, source, device string) ([]Session, error) {
	f.source = source
	f.device = device
	return []Session{}, f.err
}

// InsertSamples captures the atomic batch.
func (f *fakeRepository) InsertSamples(_ context.Context, _ string, b []SampleInput, e []Event) (BatchResult, error) {
	f.batch = b
	f.events = e
	return f.result, f.err
}

// Samples returns an empty list.
func (f *fakeRepository) Samples(context.Context, string, Query) ([]Sample, error) {
	return []Sample{}, f.err
}

// Events returns an empty list.
func (f *fakeRepository) Events(_ context.Context, _ string, _ int, source, device string) ([]Event, error) {
	f.source = source
	f.device = device
	return []Event{}, f.err
}

// InsertDTCs reports one stored code.
func (f *fakeRepository) InsertDTCs(context.Context, string, DTCInput) (int, error) { return 1, f.err }

// DTCs returns an empty list.
func (f *fakeRepository) DTCs(context.Context, string) ([]DTC, error) { return []DTC{}, f.err }

// Ping returns the configured readiness failure.
func (f *fakeRepository) Ping(context.Context) error { return f.err }

// TestServiceIngest checks validation, extraction, duplicates and publishing after success.
func TestServiceIngest(t *testing.T) {
	t.Run("given retries then duplicates are returned and newest sample is published", func(t *testing.T) {
		r := &fakeRepository{result: BatchResult{Accepted: 1, Duplicates: 1}}
		h := NewHub()
		s := NewService(r, h)
		result, err := s.Ingest(context.Background(), testID, []SampleInput{{T: 2, V: map[string]float64{"future": 1}, Event: &EventInput{Label: "mark", Automatic: true}}, {T: 1}})
		require.NoError(t, err)
		require.Equal(t, r.result, result)
		require.Equal(t, []Event{{SessionID: testID, T: 2, Label: "mark", Automatic: true}}, r.events)
		latest, ok := h.Latest("", "")
		require.True(t, ok)
		require.Equal(t, int64(2), latest.Sample.T)
		require.Equal(t, "sim", latest.Source)
		require.Equal(t, 1, r.getCalls)
	})
	for _, tc := range []struct {
		name    string
		samples []SampleInput
	}{{"timestamp", []SampleInput{{T: 0}}}, {"NaN", []SampleInput{{T: 1, V: map[string]float64{"rpm": math.NaN()}}}}, {"infinity", []SampleInput{{T: 1, V: map[string]float64{"rpm": math.Inf(1)}}}},
		{"REAL overflow", []SampleInput{{T: 1, V: map[string]float64{"rpm": math.MaxFloat64}}}}, {"alert", []SampleInput{{T: 1, Alerts: []string{"unknown"}}}}, {"event", []SampleInput{{T: 1, Event: &EventInput{}}}}, {"batch size", make([]SampleInput, 1001)}} {
		t.Run("given invalid "+tc.name+" then batch is rejected with index", func(t *testing.T) {
			r := &fakeRepository{}
			_, err := NewService(r, NewHub()).Ingest(context.Background(), testID, tc.samples)
			require.ErrorContains(t, err, "sample index")
			require.Nil(t, r.batch)
		})
	}
	t.Run("given storage failure then nothing is published", func(t *testing.T) {
		h := NewHub()
		_, err := NewService(&fakeRepository{err: errors.New("offline")}, h).Ingest(context.Background(), testID, []SampleInput{{T: 1}})
		require.Error(t, err)
		_, ok := h.Latest("", "")
		require.False(t, ok)
	})
	t.Run("given empty batch then nothing is published", func(t *testing.T) {
		h := NewHub()
		_, err := NewService(&fakeRepository{}, h).Ingest(context.Background(), testID, nil)
		require.NoError(t, err)
		_, ok := h.Latest("", "")
		require.False(t, ok)
	})
}

// TestServiceMetadata checks session and DTC validation and read delegation.
func TestServiceMetadata(t *testing.T) {
	t.Run("given valid metadata then vehicle defaults to obd-ii", func(t *testing.T) {
		r := &fakeRepository{}
		s := NewService(r, NewHub())
		_, err := s.Upsert(context.Background(), testID, SessionInput{Source: "sim", StartedAt: time.Now()})
		require.NoError(t, err)
		require.Equal(t, "obd-ii", r.input.Vehicle)
	})
	for _, tc := range []struct {
		id, source string
		started    time.Time
	}{{"bad", "sim", time.Now()}, {testID, "bad", time.Now()}, {testID, "sim", time.Time{}}} {
		t.Run("given invalid metadata then session is rejected", func(t *testing.T) {
			_, err := NewService(&fakeRepository{}, NewHub()).Upsert(context.Background(), tc.id, SessionInput{Source: tc.source, StartedAt: tc.started})
			require.Error(t, err)
		})
	}
	for _, in := range []DTCInput{{T: 0, Kind: "stored"}, {T: 1, Kind: "bad"}, {T: 1, Kind: "stored", Codes: []string{"bad"}}} {
		t.Run("given invalid DTCs then storage is not called", func(t *testing.T) {
			_, err := NewService(&fakeRepository{}, NewHub()).StoreDTCs(context.Background(), testID, in)
			require.Error(t, err)
		})
	}
	t.Run("given reads and valid DTCs then repository results are returned", func(t *testing.T) {
		s := NewService(&fakeRepository{}, NewHub())
		ctx := context.Background()
		_, err := s.Get(ctx, testID)
		require.NoError(t, err)
		_, err = s.Sessions(ctx, 50, "", "")
		require.NoError(t, err)
		_, err = s.Samples(ctx, testID, Query{})
		require.NoError(t, err)
		_, err = s.Events(ctx, "", 100, "", "")
		require.NoError(t, err)
		_, err = s.DTCs(ctx, testID)
		require.NoError(t, err)
		require.NoError(t, s.Ping(ctx))
		n, err := s.StoreDTCs(ctx, testID, DTCInput{T: 1, Kind: "stored", Codes: []string{"P0101"}})
		require.NoError(t, err)
		require.Equal(t, 1, n)
	})
}

// TestFutureSignal checks finite future signals remain valid JSONB values.
func TestFutureSignal(t *testing.T) {
	t.Run("given a large finite unknown signal then ingestion accepts it", func(t *testing.T) {
		_, err := NewService(&fakeRepository{}, NewHub()).Ingest(context.Background(), testID, []SampleInput{{T: 1, V: map[string]float64{"future": math.MaxFloat64}}})
		require.NoError(t, err)
	})
}

// Delete returns configured session identities.
func (f *fakeRepository) Delete(context.Context, string) ([]string, error) {
	return f.deletedIDs, f.err
}

// DeleteSource returns configured session identities.
func (f *fakeRepository) DeleteSource(context.Context, string, string) ([]string, error) {
	return f.deletedIDs, f.err
}

// TestServiceLivePublishing checks the streaming threshold and chronological delivery.
func TestServiceLivePublishing(t *testing.T) {
	for _, tc := range []struct {
		count    int
		expected []int64
	}{{1, []int64{1}}, {10, []int64{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}}, {11, []int64{11}}} {
		count := tc.count
		t.Run(fmt.Sprintf("given %d samples then live publishing respects the batch threshold", count), func(t *testing.T) {
			repo := &fakeRepository{}
			hub := NewHub()
			ch, _, cancel := hub.Subscribe("sim", "")
			defer cancel()
			batch := make([]SampleInput, count)
			for i := range batch {
				batch[i] = SampleInput{T: int64(count - i)}
			}
			_, err := NewService(repo, hub).Ingest(context.Background(), testID, batch)
			require.NoError(t, err)
			want := len(tc.expected)
			require.Len(t, ch, want)
			received := make([]int64, 0, want)
			for range want {
				value := <-ch
				require.Equal(t, "sim", value.Source)
				require.Equal(t, testID, value.SessionID)
				received = append(received, value.Sample.T)
			}
			require.Equal(t, tc.expected, received)
			require.Equal(t, int64(count), repo.batch[0].T)
		})
	}
}

// Devices supplies persisted identities for service fixtures.
func (f *fakeRepository) Devices(context.Context) ([]Device, error) { return f.knownDevices, f.err }

// TestServiceDeviceFlow checks device filter forwarding and live sample attribution.
func TestServiceDeviceFlow(t *testing.T) {
	t.Run("given an authenticated device then history filters and live attribution preserve it", func(t *testing.T) {
		repo := &fakeRepository{input: SessionInput{Device: "phone-a"}}
		hub := NewHub()
		service := NewService(repo, hub)
		_, err := service.Sessions(context.Background(), 50, "obd", "phone-a")
		require.NoError(t, err)
		require.Equal(t, "phone-a", repo.device)
		_, err = service.Events(context.Background(), "", 50, "obd", "phone-b")
		require.NoError(t, err)
		require.Equal(t, "phone-b", repo.device)
		_, err = service.Diagnostics(context.Background(), 20, "obd", "phone-a")
		require.NoError(t, err)
		require.Equal(t, "phone-a", repo.device)
		_, err = service.Ingest(context.Background(), testID, []SampleInput{{T: 1}})
		require.NoError(t, err)
		live, ok := hub.Latest("sim", "phone-a")
		require.True(t, ok)
		require.Equal(t, "phone-a", live.Device)
	})
}
