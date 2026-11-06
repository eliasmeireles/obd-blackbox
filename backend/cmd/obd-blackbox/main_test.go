package main

import (
	"os"
	"os/exec"
	"testing"

	"github.com/stretchr/testify/require"
)

// TestMain runs the real command only inside the entrypoint subprocess.
func TestMain(m *testing.M) { os.Exit(runTests(m)) }

// runTests switches the subprocess into the real application entrypoint.
func runTests(m *testing.M) int {
	if os.Getenv("OBD_BLACKBOX_TEST_ENTRYPOINT") == "1" {
		main()
		return 0
	}
	return m.Run()
}

// TestEntrypoint verifies invalid configuration produces an actionable nonzero exit.
func TestEntrypoint(t *testing.T) {
	t.Run("given invalid startup configuration then command fails clearly", func(t *testing.T) {
		cmd := exec.Command(os.Args[0])
		cmd.Env = append(os.Environ(), "OBD_BLACKBOX_TEST_ENTRYPOINT=1", "DATABASE_URL=")
		output, err := cmd.CombinedOutput()
		require.Error(t, err)
		require.Contains(t, string(output), "DATABASE_URL is required")
		require.Contains(t, string(output), "obd-blackbox stopped")
	})
}
