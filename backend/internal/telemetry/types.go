// Package telemetry defines telemetry models and application operations.
package telemetry

import (
	"errors"
	"time"
)

var ErrNotFound = errors.New("session not found")
var Signals = []string{"speed_kmh", "rpm", "coolant_c", "load_pct", "timing_deg", "pedal_pct", "throttle_pct", "cmd_throttle_pct", "map_kpa", "intake_c", "stft_pct", "ltft_pct", "o2_b1s1_v", "voltage_v", "fuel_level_pct", "fuel_rate_lph_est"}

type Session struct {
	Device       string `json:"device"`
	ID           string `json:"id"`
	Source       string `json:"source"`
	Vehicle      string `json:"vehicle"`
	AppVersion   string `json:"app_version"`
	StartedAt    int64  `json:"started_at"`
	LastSampleAt *int64 `json:"last_sample_at"`
	SampleCount  int64  `json:"sample_count"`
	EventCount   int64  `json:"event_count"`
}
type SessionInput struct {
	Device     string    `json:"-"`
	Source     string    `json:"source"`
	Vehicle    string    `json:"vehicle"`
	AppVersion string    `json:"app_version"`
	StartedAt  time.Time `json:"started_at"`
}
type EventInput struct {
	Label     string `json:"label"`
	Automatic bool   `json:"automatic"`
}
type SampleInput struct {
	T      int64              `json:"t"`
	V      map[string]float64 `json:"v"`
	Alerts []string           `json:"alerts"`
	Event  *EventInput        `json:"event"`
}
type Sample struct {
	T      int64              `json:"t"`
	V      map[string]float64 `json:"v"`
	Alerts []string           `json:"alerts"`
	Event  *string            `json:"event"`
}
type Event struct {
	SessionID string `json:"session_id"`
	T         int64  `json:"t"`
	Label     string `json:"label"`
	Automatic bool   `json:"automatic"`
}
type DTCInput struct {
	T     int64    `json:"t"`
	Kind  string   `json:"kind"`
	Codes []string `json:"codes"`
}
type DTC struct {
	T    int64  `json:"t"`
	Kind string `json:"kind"`
	Code string `json:"code"`
}
type BatchResult struct {
	Accepted   int `json:"accepted"`
	Duplicates int `json:"duplicates"`
}
type Query struct {
	From, To int64
	Limit    int
	Step     int64
}
type Live struct {
	Device    string `json:"device"`
	Source    string `json:"source"`
	SessionID string `json:"session_id"`
	Sample    Sample `json:"sample"`
}

// Output converts an incoming sample into its public read representation.
func (s SampleInput) Output() Sample {
	out := Sample{T: s.T, V: s.V, Alerts: s.Alerts}
	if out.V == nil {
		out.V = map[string]float64{}
	}
	if out.Alerts == nil {
		out.Alerts = []string{}
	}
	if s.Event != nil {
		label := s.Event.Label
		out.Event = &label
	}
	return out
}
