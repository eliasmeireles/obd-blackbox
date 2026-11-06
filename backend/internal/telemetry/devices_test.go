package telemetry

import (
	"context"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

// discardCommand accepts commands without replying.
func discardCommand(context.Context, Command) error { return nil }

// TestDeviceRegistry checks connection replacement and generation-safe removal.
func TestDeviceRegistry(t *testing.T) {
	t.Run("given reconnects then the latest connection wins and older removal preserves it", func(t *testing.T) {
		registry := NewDeviceRegistry()
		require.Empty(t, registry.Devices())
		before := time.Now().UnixMilli()
		first := registry.Register("phone", discardCommand)
		require.Len(t, registry.Devices(), 1)
		require.GreaterOrEqual(t, *registry.Devices()[0].ConnectedSince, before)
		second := registry.Register("phone", discardCommand)
		require.Same(t, second, registry.connection("phone"))
		require.Same(t, second, registry.connection(""))
		require.Eventually(t, func() bool {
			select {
			case <-first.closed:
				return true
			default:
				return false
			}
		}, time.Second, time.Millisecond)
		registry.Remove(first)
		require.Len(t, registry.Devices(), 1)
		require.Same(t, second, registry.connection(""))
		registry.Remove(second)
		registry.Remove(second)
		require.Empty(t, registry.Devices())
		require.Nil(t, registry.connection("phone"))
	})
	t.Run("given multiple devices then omission is ambiguous and listing is sorted", func(t *testing.T) {
		registry := NewDeviceRegistry()
		second := registry.Register("second", discardCommand)
		first := registry.Register("first", discardCommand)
		require.Nil(t, registry.connection(""))
		require.Same(t, first, registry.connection("first"))
		require.Equal(t, []Device{first.device, second.device}, registry.Devices())
	})
}

// TestDeviceReplyDelivery checks unknown and duplicate results remain nonblocking.
func TestDeviceReplyDelivery(t *testing.T) {
	t.Run("given duplicate replies then only the first reaches its waiter", func(t *testing.T) {
		registry := NewDeviceRegistry()
		conn := registry.Register("phone", discardCommand)
		result := CommandResult{OK: true, Message: "done"}
		replies := make(chan CommandResult, 1)
		conn.pending[testID] = replies
		conn.Deliver("unknown", result)
		conn.Deliver(testID, result)
		conn.Deliver(testID, CommandResult{OK: false})
		require.Len(t, replies, 1)
		require.Equal(t, result, <-replies)
		require.Empty(t, conn.pending)
		registry.Remove(conn)
		conn.Deliver(testID, result)
		require.Empty(t, replies)
	})
}

// TestDeviceLastSeen checks activity tracking without changing the connection start.
func TestDeviceLastSeen(t *testing.T) {
	t.Run("given a received frame then last seen advances and connected since is preserved", func(t *testing.T) {
		registry := NewDeviceRegistry()
		conn := registry.Register("phone", discardCommand)
		started := *conn.device.ConnectedSince
		conn.Touch()
		device := registry.Devices()[0]
		require.True(t, device.Connected)
		require.Equal(t, started, *device.ConnectedSince)
		require.GreaterOrEqual(t, *device.LastSeen, started)
	})
}
