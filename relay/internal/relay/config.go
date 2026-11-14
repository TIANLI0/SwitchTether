package relay

import (
	"fmt"
	"time"
)

// Config holds the relay's limits and timers (docs/RELAY.md §4). The zero value is not
// usable; start from DefaultConfig.
type Config struct {
	// MaxLineBytes is the longest accepted handshake/control line, excluding the newline.
	MaxLineBytes int
	// HandshakeTimeout bounds the time from connect until the handshake lines are done
	// (for a phone: until CONNECT has been read; the ACCEPT wait is AcceptTimeout).
	HandshakeTimeout time.Duration
	// AcceptTimeout is how long a phone waits for the desktop's ACCEPT.
	AcceptTimeout time.Duration
	// MaxPendingPerDesktop limits phone connections waiting for ACCEPT/REJECT per desktop.
	MaxPendingPerDesktop int
	// MaxTunnelsPerDesktop limits spliced tunnels per desktop fingerprint.
	MaxTunnelsPerDesktop int
	// MaxDesktops limits concurrently registered desktops (control connections).
	MaxDesktops int
	// IdleTimeout closes a tunnel when no byte has moved in either direction for this long.
	IdleTimeout time.Duration
	// BandwidthBytesPerSec caps each tunnel direction; 0 means unlimited.
	BandwidthBytesPerSec int64
	// PingInterval is how often the relay sends PING on a control connection.
	PingInterval time.Duration
	// DesktopTimeout drops a control connection that has sent nothing for this long.
	DesktopTimeout time.Duration
	// WriteTimeout bounds a single line write (handshake answers, INCOMING, PING).
	WriteTimeout time.Duration
}

// DefaultConfig returns the defaults from docs/RELAY.md §4.
func DefaultConfig() Config {
	return Config{
		MaxLineBytes:         8 * 1024,
		HandshakeTimeout:     10 * time.Second,
		AcceptTimeout:        10 * time.Second,
		MaxPendingPerDesktop: 16,
		MaxTunnelsPerDesktop: 32,
		MaxDesktops:          10000,
		IdleTimeout:          10 * time.Minute,
		BandwidthBytesPerSec: 0,
		PingInterval:         30 * time.Second,
		DesktopTimeout:       90 * time.Second,
		WriteTimeout:         10 * time.Second,
	}
}

// Validate reports configuration values that cannot work.
func (c Config) Validate() error {
	switch {
	case c.MaxLineBytes < 256:
		return fmt.Errorf("max line bytes must be at least 256 (got %d)", c.MaxLineBytes)
	case c.HandshakeTimeout <= 0, c.AcceptTimeout <= 0, c.IdleTimeout <= 0,
		c.PingInterval <= 0, c.DesktopTimeout <= 0, c.WriteTimeout <= 0:
		return fmt.Errorf("timeouts and intervals must be positive")
	case c.DesktopTimeout <= c.PingInterval:
		return fmt.Errorf("desktop timeout (%s) must be longer than the ping interval (%s)", c.DesktopTimeout, c.PingInterval)
	case c.MaxPendingPerDesktop < 1, c.MaxTunnelsPerDesktop < 1, c.MaxDesktops < 1:
		return fmt.Errorf("limits must be at least 1")
	case c.BandwidthBytesPerSec < 0:
		return fmt.Errorf("bandwidth must not be negative")
	}
	return nil
}
