package telemetry

import (
	"context"
	"crypto/rand"
	"fmt"
)

// Execute sends a uniquely identified request and waits for its result, cancellation or disconnect.
func (r *DeviceRegistry) Execute(ctx context.Context, device, name string) (CommandResult, error) {
	if err := ctx.Err(); err != nil {
		return CommandResult{}, err
	}
	conn := r.connection(device)
	if conn == nil {
		return CommandResult{}, ErrDeviceDisconnected
	}
	id := commandID()
	ch := make(chan CommandResult, 1)
	conn.mu.Lock()
	select {
	case <-conn.closed:
		conn.mu.Unlock()
		return CommandResult{}, ErrDeviceDisconnected
	default:
		conn.pending[id] = ch
		conn.mu.Unlock()
	}
	defer func() {
		conn.mu.Lock()
		delete(conn.pending, id)
		conn.mu.Unlock()
	}()
	sendContext, cancelSend := context.WithCancel(ctx)
	stop := context.AfterFunc(conn.lifetime, cancelSend)
	defer func() {
		stop()
		cancelSend()
	}()
	if err := conn.send(sendContext, Command{Type: "command", ID: id, Name: name}); err != nil {
		r.Remove(conn)
		if ctx.Err() != nil {
			return CommandResult{}, ctx.Err()
		}
		return CommandResult{}, ErrDeviceDisconnected
	}
	select {
	case result := <-ch:
		return result, nil
	case <-conn.closed:
		return CommandResult{}, ErrDeviceDisconnected
	case <-ctx.Done():
		return CommandResult{}, ctx.Err()
	}
}

// commandID generates an RFC 4122 version 4 UUID without additional dependencies.
func commandID() string {
	var id [16]byte
	_, _ = rand.Read(id[:])
	id[6] = (id[6] & 0x0f) | 0x40
	id[8] = (id[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", id[:4], id[4:6], id[6:8], id[8:10], id[10:])
}
