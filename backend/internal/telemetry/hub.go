package telemetry

import "sync"

type liveFilter struct{ source, device string }

// Hub broadcasts samples without blocking ingestion.
type Hub struct {
	mu          sync.Mutex
	latest      map[liveFilter]Live
	subscribers map[chan Live]liveFilter
}

// NewHub creates an empty live hub keyed by both device and source.
func NewHub() *Hub {
	return &Hub{latest: map[liveFilter]Live{}, subscribers: map[chan Live]liveFilter{}}
}

// Publish records the latest received sample overall and for each source and device filter.
func (h *Hub) Publish(v Live) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, filter := range []liveFilter{{}, {source: v.Source}, {device: v.Device}, {source: v.Source, device: v.Device}} {
		h.latest[filter] = v
	}
	for ch, filter := range h.subscribers {
		if (filter.source != "" && filter.source != v.Source) || (filter.device != "" && filter.device != v.Device) {
			continue
		}
		select {
		case ch <- v:
		default:
		}
	}
}

// latestFor returns a snapshot while the caller holds the hub lock.
func (h *Hub) latestFor(source, device string) *Live {
	v, ok := h.latest[liveFilter{source: source, device: device}]
	if !ok {
		return nil
	}
	return &v
}

// Latest returns the latest sample matching the optional source and device filters.
func (h *Hub) Latest(source, device string) (Live, bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	v := h.latestFor(source, device)
	if v == nil {
		return Live{}, false
	}
	return *v, true
}

// Subscribe atomically registers a subscriber and snapshot matching source and device.
func (h *Hub) Subscribe(source, device string) (<-chan Live, *Live, func()) {
	h.mu.Lock()
	defer h.mu.Unlock()
	ch := make(chan Live, 64)
	h.subscribers[ch] = liveFilter{source: source, device: device}
	var latest *Live
	if v := h.latestFor(source, device); v != nil {
		snapshot := *v
		latest = &snapshot
	}
	var once sync.Once
	return ch, latest, func() { once.Do(func() { h.mu.Lock(); defer h.mu.Unlock(); delete(h.subscribers, ch); close(ch) }) }
}

// Forget clears snapshots and queued samples belonging to removed sessions.
func (h *Hub) Forget(ids []string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	removed := make(map[string]bool, len(ids))
	for _, id := range ids {
		removed[id] = true
	}
	for filter, v := range h.latest {
		if removed[v.SessionID] {
			delete(h.latest, filter)
		}
	}
	for ch := range h.subscribers {
		pruneQueue(ch, removed)
	}
}

// pruneQueue removes deleted sessions without waiting for concurrent consumers.
func pruneQueue(ch chan Live, removed map[string]bool) {
	retained := make([]Live, 0, len(ch))
	for range len(ch) {
		select {
		case v := <-ch:
			if !removed[v.SessionID] {
				retained = append(retained, v)
			}
		default:
		}
	}
	for _, v := range retained {
		ch <- v
	}
}
