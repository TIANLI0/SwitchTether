package relay

import (
	"errors"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

const copyBufSize = 32 * 1024

var bufPool = sync.Pool{New: func() any { b := make([]byte, copyBufSize); return &b }}

type closeWriter interface{ CloseWrite() error }

// splice copies a→b and b→a until both directions are finished, the tunnel has been idle
// (no byte in either direction) for idle, or a write fails. ar and br are the readers for a
// and b (they may hold bytes buffered during the handshake).
//
// Half-close: when one side reaches EOF, the other side's write half is shut down if it
// supports it (TCP) and the opposite direction keeps flowing. A WebSocket has no half-close,
// so EOF towards a WebSocket closes it entirely (docs/RELAY.md §1).
//
// bandwidth > 0 caps each direction at that many bytes per second.
// It returns the bytes copied a→b and b→a. Both connections are closed on return.
func splice(a net.Conn, ar io.Reader, b net.Conn, br io.Reader, idle time.Duration, bandwidth int64) (ab, ba int64) {
	var last atomic.Int64
	last.Store(time.Now().UnixNano())
	stop := make(chan struct{})
	var stopOnce sync.Once
	closeBoth := func() {
		stopOnce.Do(func() {
			close(stop)
			_ = a.Close()
			_ = b.Close()
		})
	}

	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		ab = pipe(b, ar, &last, stop, bandwidth, closeBoth)
	}()
	go func() {
		defer wg.Done()
		ba = pipe(a, br, &last, stop, bandwidth, closeBoth)
	}()

	finished := make(chan struct{})
	go func() {
		t := time.NewTimer(idle)
		defer t.Stop()
		for {
			select {
			case <-finished:
				return
			case <-t.C:
				since := time.Since(time.Unix(0, last.Load()))
				if since >= idle {
					closeBoth()
					return
				}
				t.Reset(idle - since)
			}
		}
	}()

	wg.Wait()
	close(finished)
	closeBoth()
	return ab, ba
}

// pipe copies src into dst and handles the end of the stream as described on splice.
func pipe(dst net.Conn, src io.Reader, last *atomic.Int64, stop <-chan struct{}, bandwidth int64, closeBoth func()) int64 {
	bp := bufPool.Get().(*[]byte)
	defer bufPool.Put(bp)
	buf := *bp
	var lim *rateLimiter
	if bandwidth > 0 {
		lim = newRateLimiter(bandwidth)
		if int64(len(buf)) > bandwidth {
			buf = buf[:bandwidth]
		}
	}

	var total int64
	for {
		n, rerr := src.Read(buf)
		if n > 0 {
			last.Store(time.Now().UnixNano())
			if lim != nil && !lim.wait(n, stop) {
				return total
			}
			w, werr := dst.Write(buf[:n])
			total += int64(w)
			if werr != nil || w != n {
				closeBoth()
				return total
			}
			last.Store(time.Now().UnixNano())
		}
		if rerr != nil {
			if errors.Is(rerr, io.EOF) {
				if cw, ok := dst.(closeWriter); ok {
					if cw.CloseWrite() == nil {
						return total
					}
				}
				_ = dst.Close()
				return total
			}
			closeBoth()
			return total
		}
	}
}

// rateLimiter is a token bucket with a one-second burst.
type rateLimiter struct {
	rate   float64
	tokens float64
	last   time.Time
}

func newRateLimiter(bytesPerSec int64) *rateLimiter {
	return &rateLimiter{rate: float64(bytesPerSec), tokens: float64(bytesPerSec), last: time.Now()}
}

// wait consumes n tokens, sleeping if the bucket is in debt. It returns false if stop
// was closed while waiting.
func (l *rateLimiter) wait(n int, stop <-chan struct{}) bool {
	now := time.Now()
	l.tokens = min(l.tokens+now.Sub(l.last).Seconds()*l.rate, l.rate)
	l.last = now
	l.tokens -= float64(n)
	if l.tokens >= 0 {
		return true
	}
	d := time.Duration(-l.tokens / l.rate * float64(time.Second))
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-t.C:
		return true
	case <-stop:
		return false
	}
}
