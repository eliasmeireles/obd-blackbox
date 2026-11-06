package postgres

import (
	"context"
	"testing"
	"time"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
	"github.com/stretchr/testify/require"
)

// TestRepositoryDevices checks historical identities from sessions and standalone app logs.
func TestRepositoryDevices(t *testing.T) {
	t.Run("given session and log identities then every non-empty device appears once", func(t *testing.T) {
		repo := New(testDatabase(t))
		ctx := context.Background()
		_, err := repo.Upsert(ctx, testID, telemetry.SessionInput{Source: "obd", Device: "phone-a", StartedAt: time.UnixMilli(1)})
		require.NoError(t, err)
		_, err = repo.InsertLogs(ctx, "phone-a", []telemetry.LogInput{{ID: testID, T: 1, Level: "info", Tag: "app", Message: "example"}})
		require.NoError(t, err)
		_, err = repo.InsertLogs(ctx, "retired", []telemetry.LogInput{{ID: obdID, T: 1, Level: "info", Tag: "app", Message: "example"}})
		require.NoError(t, err)
		devices, err := repo.Devices(ctx)
		require.NoError(t, err)
		require.Len(t, devices, 2)
		require.Equal(t, "phone-a", devices[0].Name)
		require.NotNil(t, devices[0].LastSeen)
		require.False(t, devices[0].Connected)
		require.Equal(t, "retired", devices[1].Name)
	})
	t.Run("given only legacy sessions then empty device names are omitted", func(t *testing.T) {
		repo := New(testDatabase(t))
		createSession(t, repo)
		devices, err := repo.Devices(context.Background())
		require.NoError(t, err)
		require.NotNil(t, devices)
		require.Empty(t, devices)
	})
}
