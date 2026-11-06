package telemetry

import (
	"testing"

	"github.com/stretchr/testify/require"
)

// TestSampleOutput checks nullable fields and event projection.
func TestSampleOutput(t *testing.T) {
	t.Run("given empty fields then arrays and maps are initialized", func(t *testing.T) {
		s := SampleInput{T: 1}.Output()
		require.NotNil(t, s.V)
		require.NotNil(t, s.Alerts)
		require.Nil(t, s.Event)
	})
	t.Run("given an event then the label is projected", func(t *testing.T) {
		s := SampleInput{T: 2, V: map[string]float64{"rpm": 800}, Event: &EventInput{Label: "mark"}}.Output()
		require.Equal(t, "mark", *s.Event)
		require.Equal(t, 800.0, s.V["rpm"])
	})
}
