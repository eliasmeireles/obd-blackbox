package httpapi

import (
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/stretchr/testify/require"
)

// TestDecode rejects malformed JSON, oversized requests and multiple values.
func TestDecode(t *testing.T) {
	for _, body := range []string{`{`, `{} {}`, strings.Repeat(" ", maxBody) + `{}`} {
		t.Run("given invalid input then decoding fails", func(t *testing.T) {
			w := httptest.NewRecorder()
			require.False(t, decode(w, httptest.NewRequest("POST", "/", strings.NewReader(body)), new(any)))
			require.Equal(t, 400, w.Code)
		})
	}
	t.Run("given one JSON body then decoding succeeds", func(t *testing.T) {
		require.True(t, decode(httptest.NewRecorder(), httptest.NewRequest("POST", "/", strings.NewReader(`{}`)), new(any)))
	})
}

// TestQuery checks defaults, filtering and interval validation.
func TestQuery(t *testing.T) {
	t.Run("given valid parameters then the query is populated", func(t *testing.T) {
		q, err := query(httptest.NewRequest("GET", "/?from=1&to=1000&step=200&limit=10", nil))
		require.NoError(t, err)
		require.Equal(t, int64(200), q.Step)
		require.Equal(t, 10, q.Limit)
		require.Equal(t, int64(1), q.From)
		require.Equal(t, int64(1000), q.To)
	})
	t.Run("given defaults then the sample limit is 5000", func(t *testing.T) {
		q, err := query(httptest.NewRequest("GET", "/", nil))
		require.NoError(t, err)
		require.Equal(t, 5000, q.Limit)
	})
	for _, params := range []string{"from=-1", "to=bad", "limit=0", "limit=50001", "limit=bad", "step=199", "step=0", "step=bad", "from=10&to=1"} {
		t.Run("given invalid "+params+" then query fails", func(t *testing.T) {
			_, err := query(httptest.NewRequest("GET", "/?"+params, nil))
			require.Error(t, err)
		})
	}
}
