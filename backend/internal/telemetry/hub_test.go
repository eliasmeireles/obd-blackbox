package telemetry

import (
	"sync"
	"testing"

	"github.com/stretchr/testify/require"
)

// TestHub checks snapshots, fan-out, slow subscribers and idempotent cleanup.
func TestHub(t *testing.T) {
	t.Run("given a new hub then there is no latest value", func(t *testing.T) {
		h := NewHub()
		_, ok := h.Latest("", "")
		require.False(t, ok)
		_, latest, cancel := h.Subscribe("", "")
		defer cancel()
		require.Nil(t, latest)
	})
	t.Run("given subscribers then every subscriber receives published samples", func(t *testing.T) {
		h := NewHub()
		a, _, ca := h.Subscribe("", "")
		defer ca()
		b, _, cb := h.Subscribe("", "")
		defer cb()
		v := Live{SessionID: "example", Sample: Sample{T: 1}}
		h.Publish(v)
		require.Equal(t, v, <-a)
		require.Equal(t, v, <-b)
		latest, ok := h.Latest("", "")
		require.True(t, ok)
		require.Equal(t, v, latest)
		_, snapshot, cc := h.Subscribe("", "")
		defer cc()
		require.Equal(t, v, *snapshot)
	})
	t.Run("given a full subscriber then publishers never block", func(t *testing.T) {
		h := NewHub()
		ch, _, cancel := h.Subscribe("", "")
		var wg sync.WaitGroup
		wg.Go(func() {
			for i := range 1000 {
				h.Publish(Live{Sample: Sample{T: int64(i)}})
			}
		})
		wg.Wait()
		require.Len(t, ch, 64)
		last, ok := h.Latest("", "")
		require.True(t, ok)
		require.Equal(t, int64(999), last.Sample.T)
		cancel()
		cancel()
		require.Empty(t, h.subscribers)
	})
}

// TestHubSources checks isolation, snapshots and forgetting removed sessions.
func TestHubSources(t *testing.T) {
	t.Run("given mixed sources then snapshots and subscriptions stay separate", func(t *testing.T) {
		h := NewHub()
		sim := Live{SessionID: "sim-session", Source: "sim", Sample: Sample{T: 1}}
		obd := Live{SessionID: "obd-session", Source: "obd", Sample: Sample{T: 2}}
		h.Publish(sim)
		ch, snapshot, cancel := h.Subscribe("sim", "")
		defer cancel()
		require.Equal(t, sim, *snapshot)
		h.Publish(obd)
		require.Empty(t, ch)
		latest, ok := h.Latest("sim", "")
		require.True(t, ok)
		require.Equal(t, sim, latest)
		latest, ok = h.Latest("obd", "")
		require.True(t, ok)
		require.Equal(t, obd, latest)
		latest, ok = h.Latest("", "")
		require.True(t, ok)
		require.Equal(t, obd, latest)
		sim.Sample.T = 3
		h.Publish(sim)
		require.Equal(t, sim, <-ch)
		h.Forget([]string{"sim-session"})
		_, ok = h.Latest("sim", "")
		require.False(t, ok)
		_, ok = h.Latest("", "")
		require.False(t, ok)
		latest, ok = h.Latest("obd", "")
		require.True(t, ok)
		require.Equal(t, obd, latest)
		_, snapshot, stop := h.Subscribe("sim", "")
		defer stop()
		require.Nil(t, snapshot)
	})
	t.Run("given queued removed samples then forget retains only other sessions", func(t *testing.T) {
		h := NewHub()
		ch, _, cancel := h.Subscribe("", "")
		defer cancel()
		h.Publish(Live{SessionID: "removed", Source: "sim"})
		retained := Live{SessionID: "retained", Source: "obd"}
		h.Publish(retained)
		h.Forget([]string{"removed"})
		require.Len(t, ch, 1)
		require.Equal(t, retained, <-ch)
		h.Forget([]string{"retained"})
		_, ok := h.Latest("obd", "")
		require.False(t, ok)
	})
}

// TestHubConcurrentForget checks forgetting while publishers and consumers are active.
func TestHubConcurrentForget(t *testing.T) {
	t.Run("given an active subscriber then forgetting never blocks", func(t *testing.T) {
		h := NewHub()
		ch, _, cancel := h.Subscribe("", "")
		defer cancel()
		var consumer sync.WaitGroup
		consumer.Go(func() {
			for range ch {
			}
		})
		var operations sync.WaitGroup
		operations.Go(func() {
			for range 1000 {
				h.Publish(Live{SessionID: "removed", Source: "sim"})
			}
		})
		operations.Go(func() {
			for range 1000 {
				h.Forget([]string{"removed"})
			}
		})
		operations.Wait()
		h.Forget([]string{"removed"})
		_, ok := h.Latest("sim", "")
		require.False(t, ok)
		cancel()
		consumer.Wait()
	})
}

// TestHubDevices checks device and source isolation for snapshots, streams and deletion.
func TestHubDevices(t *testing.T) {
	t.Run("given two devices with the same source then a subscriber receives only its device", func(t *testing.T) {
		hub := NewHub()
		first := Live{SessionID: "first", Device: "phone-a", Source: "obd", Sample: Sample{T: 10}}
		second := Live{SessionID: "second", Device: "phone-b", Source: "obd", Sample: Sample{T: 20}}
		hub.Publish(first)
		hub.Publish(second)
		ch, snapshot, stop := hub.Subscribe("obd", "phone-a")
		defer stop()
		require.Equal(t, first, *snapshot)
		hub.Publish(second)
		hub.Publish(Live{SessionID: "simulation", Device: "phone-a", Source: "sim"})
		require.Empty(t, ch)
		first.Sample.T = 30
		hub.Publish(first)
		require.Equal(t, first, <-ch)
		latest, ok := hub.Latest("", "phone-b")
		require.True(t, ok)
		require.Equal(t, second, latest)
		hub.Forget([]string{"first"})
		_, ok = hub.Latest("obd", "phone-a")
		require.False(t, ok)
		latest, ok = hub.Latest("obd", "phone-b")
		require.True(t, ok)
		require.Equal(t, second, latest)
	})
}
