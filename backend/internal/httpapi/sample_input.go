package httpapi

import (
	"encoding/json"
	"fmt"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
)

// parseSamples decodes individual samples so malformed numeric input includes its batch index.
func parseSamples(raw []json.RawMessage) ([]telemetry.SampleInput, error) {
	if len(raw) > 1000 {
		return nil, fmt.Errorf("sample index 1000: batch exceeds 1000 samples")
	}
	batch := make([]telemetry.SampleInput, len(raw))
	for i, data := range raw {
		sample, err := parseSample(data)
		if err != nil {
			return nil, fmt.Errorf("sample index %d: %w", i, err)
		}
		batch[i] = sample
	}
	return batch, nil
}

// parseSample rejects null or nonnumeric signal values instead of silently interpreting them as zero.
func parseSample(data []byte) (telemetry.SampleInput, error) {
	var in struct {
		T      int64                 `json:"t"`
		V      map[string]*float64   `json:"v"`
		Alerts []string              `json:"alerts"`
		Event  *telemetry.EventInput `json:"event"`
	}
	if err := json.Unmarshal(data, &in); err != nil {
		return telemetry.SampleInput{}, fmt.Errorf("invalid sample JSON")
	}
	out := telemetry.SampleInput{T: in.T, V: map[string]float64{}, Alerts: in.Alerts, Event: in.Event}
	for key, value := range in.V {
		if value == nil {
			return out, fmt.Errorf("signal values must be finite numbers")
		}
		out.V[key] = *value
	}
	return out, nil
}
