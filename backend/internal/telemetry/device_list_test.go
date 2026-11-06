package telemetry

import (
	"context"
	"errors"
	"testing"

	"github.com/stretchr/testify/require"
)

// TestDeviceList checks stable identity merging, timestamps and repository failures.
func TestDeviceList(t *testing.T) {
	t.Run("given configured persisted and connected devices then every identity appears once", func(t *testing.T) {
		old, now := int64(10), int64(20)
		repo := &fakeRepository{knownDevices: []Device{{Name: "retired", LastSeen: &old}, {Name: "phone", LastSeen: &old}}}
		connected := Device{Name: "phone", Connected: true, ConnectedSince: &now, LastSeen: &now}
		devices, err := NewService(repo, nil).Devices(context.Background(), []string{"unused", "phone"}, []Device{connected})
		require.NoError(t, err)
		require.Equal(t, []Device{connected, {Name: "retired", LastSeen: &old}, {Name: "unused"}}, devices)
	})
	t.Run("given a newer persisted timestamp then connection metadata preserves it", func(t *testing.T) {
		old, now := int64(10), int64(20)
		repo := &fakeRepository{knownDevices: []Device{{Name: "phone", LastSeen: &now}}}
		devices, err := NewService(repo, nil).Devices(context.Background(), nil, []Device{{Name: "phone", Connected: true, LastSeen: &old}})
		require.NoError(t, err)
		require.Equal(t, &now, devices[0].LastSeen)
		require.True(t, devices[0].Connected)
	})
	t.Run("given no identities then the list is non-null and empty", func(t *testing.T) {
		devices, err := NewService(&fakeRepository{}, nil).Devices(context.Background(), nil, nil)
		require.NoError(t, err)
		require.NotNil(t, devices)
		require.Empty(t, devices)
	})
	t.Run("given unavailable storage then its error is returned", func(t *testing.T) {
		failure := errors.New("offline")
		_, err := NewService(&fakeRepository{err: failure}, nil).Devices(context.Background(), nil, nil)
		require.ErrorIs(t, err, failure)
	})
}
