package httpapi

import (
	"errors"
	"testing"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
	"github.com/stretchr/testify/require"
)

// TestSessionRoutes verifies the JSON contract for every session and analysis route.
func TestSessionRoutes(t *testing.T) {
	s := &fakeService{session: telemetry.Session{ID: testID, Source: "sim", Vehicle: "obd-ii", StartedAt: 1}, sample: telemetry.Sample{T: 1, V: map[string]float64{"rpm": 800}, Alerts: []string{}}}
	router := testRouter(s, telemetry.NewHub())
	for _, tc := range []struct{ method, path, body, want string }{
		{"PUT", "/sessions/" + testID, `{"source":"sim","started_at":"2026-11-05T00:00:00Z"}`, `{"device":"","id":"` + testID + `","source":"sim","vehicle":"obd-ii","app_version":"","started_at":1,"last_sample_at":null,"sample_count":0,"event_count":0}`},
		{"GET", "/sessions/" + testID, "", `{"device":"","id":"` + testID + `","source":"sim","vehicle":"obd-ii","app_version":"","started_at":1,"last_sample_at":null,"sample_count":0,"event_count":0}`},
		{"GET", "/sessions", "", `{"sessions":[{"device":"","id":"` + testID + `","source":"sim","vehicle":"obd-ii","app_version":"","started_at":1,"last_sample_at":null,"sample_count":0,"event_count":0}]}`},
		{"POST", "/sessions/" + testID + "/samples", `{"samples":[{"t":1}]}`, `{"accepted":1,"duplicates":0}`},
		{"GET", "/sessions/" + testID + "/samples", "", `{"samples":[{"t":1,"v":{"rpm":800},"alerts":[],"event":null}]}`},
		{"POST", "/sessions/" + testID + "/dtcs", `{"t":1,"kind":"stored","codes":["P0101"]}`, `{"stored":1}`},
		{"GET", "/sessions/" + testID + "/dtcs", "", `{"dtcs":[{"t":1,"kind":"stored","code":"P0101"}]}`},
		{"GET", "/sessions/" + testID + "/events", "", `{"events":[{"session_id":"` + testID + `","t":1,"label":"mark","automatic":true}]}`},
		{"GET", "/events", "", `{"events":[{"session_id":"` + testID + `","t":1,"label":"mark","automatic":true}]}`},
	} {
		t.Run("given valid "+tc.method+" "+tc.path+" then JSON is returned", func(t *testing.T) {
			w := request(router, tc.method, "/api/v1"+tc.path, tc.body)
			require.Equal(t, 200, w.Code)
			require.JSONEq(t, tc.want, w.Body.String())
			require.Contains(t, w.Header().Get("Content-Type"), "application/json")
		})
	}
}

// TestSessionErrors checks validation, missing sessions and sanitized database errors.
func TestSessionErrors(t *testing.T) {
	for _, tc := range []struct{ method, path, body string }{{"GET", "/sessions/bad", ""}, {"PUT", "/sessions/" + testID, `{"source":"bad"}`}, {"POST", "/sessions/" + testID + "/samples", `broken`}, {"POST", "/sessions/" + testID + "/dtcs", `{"t":"bad"}`}, {"GET", "/sessions?limit=501", ""}, {"GET", "/sessions/" + testID + "/samples?step=100", ""}, {"GET", "/sessions/" + testID + "/samples?from=no", ""}, {"GET", "/sessions/" + testID + "/events?limit=0", ""}} {
		t.Run("given invalid "+tc.path+" then bad request is returned", func(t *testing.T) {
			w := request(testRouter(&fakeService{}, telemetry.NewHub()), tc.method, "/api/v1"+tc.path, tc.body)
			require.Equal(t, 400, w.Code)
			require.Contains(t, w.Body.String(), `"error"`)
		})
	}
	for _, tc := range []struct {
		err     error
		status  int
		message string
	}{{telemetry.ErrNotFound, 404, "session not found"}, {errors.New("private database error"), 500, "internal server error"}, {&telemetry.ValidationError{Message: "sample index 0: invalid t"}, 400, "sample index 0: invalid t"}} {
		t.Run("given service failure then proper error status is returned", func(t *testing.T) {
			w := request(testRouter(&fakeService{err: tc.err}, telemetry.NewHub()), "POST", "/api/v1/sessions/"+testID+"/samples", `{"samples":[]}`)
			require.Equal(t, tc.status, w.Code)
			require.JSONEq(t, `{"error":"`+tc.message+`"}`, w.Body.String())
		})
	}
}

// TestSessionDeviceIdentity checks that client JSON cannot override the authenticated device.
func TestSessionDeviceIdentity(t *testing.T) {
	t.Run("given REST session metadata then the bearer identity supplies device", func(t *testing.T) {
		service := &fakeService{}
		w := request(testRouter(service, telemetry.NewHub()), "PUT", "/api/v1/sessions/"+testID, `{"source":"obd","device":"spoofed","started_at":"2026-11-05T00:00:00Z"}`)
		require.Equal(t, 200, w.Code)
		require.Equal(t, "example", service.input.Device)
	})
}
