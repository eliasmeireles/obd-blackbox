package httpapi

import (
	"encoding/json"
	"testing"

	"github.com/stretchr/testify/require"
)

// TestParseSamples checks invalid numeric signals retain their offending batch index.
func TestParseSamples(t *testing.T) {
	for _, input := range []string{`{"t":1,"v":{"rpm":null}}`, `{"t":1,"v":{"rpm":"bad"}}`, `{"t":1,"v":{"rpm":1e999}}`} {
		t.Run("given malformed numeric input then error includes the sample index", func(t *testing.T) {
			_, err := parseSamples([]json.RawMessage{json.RawMessage(`{"t":1}`), json.RawMessage(input)})
			require.ErrorContains(t, err, "sample index 1")
		})
	}
	t.Run("given an oversized batch then parsing stops at the first excess index", func(t *testing.T) {
		_, err := parseSamples(make([]json.RawMessage, 1001))
		require.ErrorContains(t, err, "sample index 1000")
	})
	t.Run("given valid signal values then known and future values are preserved", func(t *testing.T) {
		batch, err := parseSamples([]json.RawMessage{json.RawMessage(`{"t":1,"v":{"rpm":800,"future":2},"event":{"label":"mark"}}`)})
		require.NoError(t, err)
		require.Equal(t, 800.0, batch[0].V["rpm"])
		require.Equal(t, 2.0, batch[0].V["future"])
		require.Equal(t, "mark", batch[0].Event.Label)
	})
}
