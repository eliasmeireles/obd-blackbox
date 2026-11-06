package telemetry

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

// TestCommands checks correlation, cancellation, disconnect and replacement wakeups.
func TestCommands(t *testing.T) {
	t.Run("given missing or ambiguous devices then no command can be sent", func(t *testing.T) {
		registry := NewDeviceRegistry()
		_, err := registry.Execute(context.Background(), "", "read_diagnostics")
		require.ErrorIs(t, err, ErrDeviceDisconnected)
		registry.Register("one", discardCommand)
		registry.Register("two", discardCommand)
		_, err = registry.Execute(context.Background(), "", "read_diagnostics")
		require.ErrorIs(t, err, ErrDeviceDisconnected)
	})
	t.Run("given a device reply then its command UUID and outcome are correlated", func(t *testing.T) {
		registry := NewDeviceRegistry()
		sent := make(chan Command, 1)
		conn := registry.Register("phone", func(_ context.Context, command Command) error {
			sent <- command
			return nil
		})
		results := make(chan CommandResult, 1)
		failures := make(chan error, 1)
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		go func() {
			result, err := registry.Execute(ctx, "", "clear_dtcs")
			results <- result
			failures <- err
		}()
		command := <-sent
		require.True(t, ValidID(command.ID))
		require.Equal(t, "command", command.Type)
		require.Equal(t, "clear_dtcs", command.Name)
		want := CommandResult{OK: false, Message: "desligue o motor"}
		conn.Deliver(command.ID, want)
		require.Equal(t, want, <-results)
		require.NoError(t, <-failures)
		require.Empty(t, conn.pending)
	})
	t.Run("given a transport failure then the connection is removed", func(t *testing.T) {
		registry := NewDeviceRegistry()
		registry.Register("phone", func(context.Context, Command) error { return errors.New("closed") })
		_, err := registry.Execute(context.Background(), "phone", "read_diagnostics")
		require.ErrorIs(t, err, ErrDeviceDisconnected)
		require.Empty(t, registry.Devices())
	})
	t.Run("given no reply then timeout removes the pending command", func(t *testing.T) {
		registry := NewDeviceRegistry()
		conn := registry.Register("phone", discardCommand)
		ctx, cancel := context.WithTimeout(context.Background(), time.Millisecond)
		defer cancel()
		_, err := registry.Execute(ctx, "phone", "read_diagnostics")
		require.ErrorIs(t, err, context.DeadlineExceeded)
		require.Empty(t, conn.pending)
	})
	for _, replace := range []bool{false, true} {
		t.Run("given disconnect or replacement then pending commands fail immediately", func(t *testing.T) {
			registry := NewDeviceRegistry()
			sent := make(chan Command, 1)
			conn := registry.Register("phone", func(_ context.Context, command Command) error {
				sent <- command
				return nil
			})
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			defer cancel()
			failures := make(chan error, 1)
			go func() {
				_, err := registry.Execute(ctx, "phone", "read_diagnostics")
				failures <- err
			}()
			<-sent
			actions := map[bool]func(){false: func() { registry.Remove(conn) }, true: func() { registry.Register("phone", discardCommand) }}
			actions[replace]()
			require.ErrorIs(t, <-failures, ErrDeviceDisconnected)
			require.Empty(t, conn.pending)
		})
	}
}

// TestCommandID checks UUID version and uniqueness.
func TestCommandID(t *testing.T) {
	t.Run("given generated IDs then each is a unique version four UUID", func(t *testing.T) {
		first, second := commandID(), commandID()
		require.True(t, ValidID(first))
		require.True(t, ValidID(second))
		require.NotEqual(t, first, second)
		require.Equal(t, byte('4'), first[14])
	})
}

// TestCommandSendCancellation checks connection closure also interrupts an unfinished write.
func TestCommandSendCancellation(t *testing.T) {
	t.Run("given a blocked send then replacing the connection fails the command immediately", func(t *testing.T) {
		registry := NewDeviceRegistry()
		started := make(chan struct{})
		registry.Register("phone", func(ctx context.Context, _ Command) error {
			close(started)
			<-ctx.Done()
			return ctx.Err()
		})
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		failures := make(chan error, 1)
		go func() {
			_, err := registry.Execute(ctx, "phone", "read_diagnostics")
			failures <- err
		}()
		<-started
		registry.Register("phone", discardCommand)
		require.ErrorIs(t, <-failures, ErrDeviceDisconnected)
		require.Len(t, registry.Devices(), 1)
	})
	t.Run("given the deadline expires during send then the timeout is preserved", func(t *testing.T) {
		registry := NewDeviceRegistry()
		registry.Register("phone", func(ctx context.Context, _ Command) error {
			<-ctx.Done()
			return ctx.Err()
		})
		ctx, cancel := context.WithTimeout(context.Background(), time.Millisecond)
		defer cancel()
		_, err := registry.Execute(ctx, "phone", "read_diagnostics")
		require.ErrorIs(t, err, context.DeadlineExceeded)
	})
	t.Run("given an already canceled caller then no command is sent", func(t *testing.T) {
		ctx, cancel := context.WithCancel(context.Background())
		cancel()
		_, err := NewDeviceRegistry().Execute(ctx, "phone", "read_diagnostics")
		require.ErrorIs(t, err, context.Canceled)
	})
}
