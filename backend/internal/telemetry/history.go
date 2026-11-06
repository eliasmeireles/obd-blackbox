package telemetry

import "context"

// ValidSource reports whether source identifies real or simulated telemetry.
func ValidSource(source string) bool { return source == "obd" || source == "sim" }

// ValidateSourceFilter accepts an optional real or simulated source.
func ValidateSourceFilter(source string) error {
	if source != "" && !ValidSource(source) {
		return invalid("invalid source")
	}
	return nil
}

// Delete removes one session and forgets its live snapshots after storage succeeds.
func (s *Service) Delete(ctx context.Context, id string) (int, error) {
	if !ValidID(id) {
		return 0, invalid("invalid session UUID")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	ids, err := s.repo.Delete(ctx, id)
	if err != nil {
		return 0, err
	}
	if len(ids) == 0 {
		return 0, ErrNotFound
	}
	s.hub.Forget(ids)
	return len(ids), nil
}

// DeleteSource removes sessions of a source, or all sessions when source is all.
func (s *Service) DeleteSource(ctx context.Context, source, device string) (int, error) {
	if source != "all" && !ValidSource(source) {
		return 0, invalid("invalid source")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	ids, err := s.repo.DeleteSource(ctx, source, device)
	if err != nil {
		return 0, err
	}
	s.hub.Forget(ids)
	return len(ids), nil
}
