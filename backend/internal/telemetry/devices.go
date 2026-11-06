package telemetry

import (
	"context"
	"errors"
	"sort"
	"sync"
	"time"
)

var ErrDeviceDisconnected = errors.New("o app não está conectado")

// Device describes the latest open ingestion connection for an authenticated identity.
type Device struct {
	Name           string `json:"name"`
	ConnectedSince *int64 `json:"connected_since"`
	Connected      bool   `json:"connected"`
	LastSeen       *int64 `json:"last_seen"`
}

// Command is a server-to-device request with a unique correlation identifier.
type Command struct {
	Type string `json:"type"`
	ID   string `json:"id"`
	Name string `json:"name"`
}

// CommandResult is the device's outcome, delivered only to its requesting caller.
type CommandResult struct {
	OK      bool   `json:"ok"`
	Message string `json:"message"`
}

// DeviceConnection owns pending commands for one particular WebSocket lifetime.
type DeviceConnection struct {
	device   Device
	send     func(context.Context, Command) error
	mu       sync.Mutex
	pending  map[string]chan CommandResult
	closed   <-chan struct{}
	lifetime context.Context
	cancel   context.CancelFunc
}

// DeviceRegistry tracks the latest connection per identity without sharing pending replies.
type DeviceRegistry struct {
	mu          sync.Mutex
	connections map[string]*DeviceConnection
}

// NewDeviceRegistry initializes an empty connection registry.
func NewDeviceRegistry() *DeviceRegistry {
	return &DeviceRegistry{connections: make(map[string]*DeviceConnection)}
}

// Register replaces any previous connection and immediately fails its pending commands.
func (r *DeviceRegistry) Register(name string, send func(context.Context, Command) error) *DeviceConnection {
	r.mu.Lock()
	defer r.mu.Unlock()
	if old := r.connections[name]; old != nil {
		old.close()
	}
	lifetime, cancel := context.WithCancel(context.Background())
	now := time.Now().UnixMilli()
	conn := &DeviceConnection{device: Device{Name: name, Connected: true, ConnectedSince: &now, LastSeen: &now}, send: send, pending: make(map[string]chan CommandResult), closed: lifetime.Done(), lifetime: lifetime, cancel: cancel}
	r.connections[name] = conn
	return conn
}

// Remove closes pending requests and removes only the matching connection generation.
func (r *DeviceRegistry) Remove(conn *DeviceConnection) {
	r.mu.Lock()
	defer r.mu.Unlock()
	conn.close()
	if r.connections[conn.device.Name] == conn {
		delete(r.connections, conn.device.Name)
	}
}

// Devices returns a stable non-nil list of currently registered devices.
func (r *DeviceRegistry) Devices() []Device {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := make([]Device, 0, len(r.connections))
	for _, conn := range r.connections {
		conn.mu.Lock()
		out = append(out, conn.device)
		conn.mu.Unlock()
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return out
}

// connection resolves an explicit identity, or the sole connected device when omitted.
func (r *DeviceRegistry) connection(name string) *DeviceConnection {
	r.mu.Lock()
	defer r.mu.Unlock()
	if name != "" {
		return r.connections[name]
	}
	if len(r.connections) == 1 {
		for _, conn := range r.connections {
			return conn
		}
	}
	return nil
}

// close signals disconnection exactly once while serializing reply delivery.
func (c *DeviceConnection) close() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.cancel()
}

// Deliver correlates a reply on this connection, ignoring late or duplicate results.
func (c *DeviceConnection) Deliver(id string, result CommandResult) {
	c.mu.Lock()
	defer c.mu.Unlock()
	select {
	case <-c.closed:
		return
	default:
	}
	if ch := c.pending[id]; ch != nil {
		delete(c.pending, id)
		ch <- result
	}
}

// Touch records receipt of a device frame without changing its connection lifetime.
func (c *DeviceConnection) Touch() {
	c.mu.Lock()
	defer c.mu.Unlock()
	now := time.Now().UnixMilli()
	c.device.LastSeen = &now
}
