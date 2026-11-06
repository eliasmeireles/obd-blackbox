package telemetry

import (
	"context"
	"sort"
)

// Devices merges configured keys, persisted identities and active WebSocket connections.
func (s *Service) Devices(ctx context.Context, configured []string, connected []Device) ([]Device, error) {
	seen, err := s.repo.Devices(ctx)
	if err != nil {
		return nil, err
	}
	byName := make(map[string]Device, len(configured)+len(seen)+len(connected))
	for _, name := range configured {
		byName[name] = Device{Name: name}
	}
	for _, device := range seen {
		byName[device.Name] = device
	}
	for _, device := range connected {
		previous := byName[device.Name]
		if previous.LastSeen != nil && (device.LastSeen == nil || *previous.LastSeen > *device.LastSeen) {
			device.LastSeen = previous.LastSeen
		}
		byName[device.Name] = device
	}
	devices := make([]Device, 0, len(byName))
	for _, device := range byName {
		devices = append(devices, device)
	}
	sort.Slice(devices, func(i, j int) bool { return devices[i].Name < devices[j].Name })
	return devices, nil
}
