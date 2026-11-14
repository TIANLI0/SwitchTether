// Package relay implements the SwitchTether relay protocol v1 (docs/RELAY.md): a dumb,
// account-less byte pipe that splices a phone's connection to a desktop's data connection
// so that the end-to-end mutual-TLS session can cross NATs. The relay never sees
// plaintext; it only checks that a registering desktop owns its certificate fingerprint.
package relay

import (
	"bufio"
	"context"
	"crypto/subtle"
	"errors"
	"io"
	"log/slog"
	"net"
	"sync"
	"time"
)

// Server is a relay instance. Connections from any transport are handed to ServeConn.
type Server struct {
	cfg Config
	log *slog.Logger

	// baseCtx lives until the server is hard-closed; WebSocket connections derive from it.
	baseCtx    context.Context
	cancelBase context.CancelFunc
	// stopping is closed when Shutdown starts; waiting phones give up immediately.
	stopping chan struct{}

	mu       sync.Mutex
	closing  bool
	desktops map[string]*desktop                // fp -> current control connection
	pending  map[string]*pendingConn            // conn id -> phone waiting for ACCEPT
	byFP     map[string]map[string]*pendingConn // fp -> conn id -> pending
	tunnels  map[string]int                     // fp -> active tunnels
	conns    map[net.Conn]connKind              // every live connection
	wg       sync.WaitGroup
}

type connKind int

const (
	kindHandshake connKind = iota
	kindControl
	kindTunnel
)

type desktop struct {
	fp        string
	conn      net.Conn
	wmu       sync.Mutex
	closed    chan struct{}
	closeOnce sync.Once
}

func (d *desktop) close() {
	d.closeOnce.Do(func() {
		close(d.closed)
		_ = d.conn.Close()
	})
}

type resultKind int

const (
	resAccepted resultKind = iota
	resRejected
	resOffline
	resBusy
)

type acceptResult struct {
	kind resultKind
	conn net.Conn      // desktop data connection (resAccepted)
	br   *bufio.Reader // its reader, possibly holding already-received bytes
	done chan struct{} // closed by the phone side when the tunnel is over
}

type pendingConn struct {
	id     string
	fp     string
	result chan acceptResult // buffered(1); exactly one result is delivered
}

// NewServer creates a relay. A nil logger discards logs.
func NewServer(cfg Config, log *slog.Logger) (*Server, error) {
	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	if log == nil {
		log = slog.New(slog.DiscardHandler)
	}
	ctx, cancel := context.WithCancel(context.Background())
	return &Server{
		cfg:        cfg,
		log:        log,
		baseCtx:    ctx,
		cancelBase: cancel,
		stopping:   make(chan struct{}),
		desktops:   make(map[string]*desktop),
		pending:    make(map[string]*pendingConn),
		byFP:       make(map[string]map[string]*pendingConn),
		tunnels:    make(map[string]int),
		conns:      make(map[net.Conn]connKind),
	}, nil
}

// Config returns the server's configuration.
func (s *Server) Config() Config { return s.cfg }

// Closing reports whether Shutdown has started.
func (s *Server) Closing() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.closing
}

func (s *Server) track(c net.Conn) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closing {
		return false
	}
	s.conns[c] = kindHandshake
	s.wg.Add(1)
	return true
}

func (s *Server) untrack(c net.Conn) {
	s.mu.Lock()
	delete(s.conns, c)
	s.mu.Unlock()
	s.wg.Done()
}

func (s *Server) setKind(c net.Conn, k connKind) {
	s.mu.Lock()
	if _, ok := s.conns[c]; ok {
		s.conns[c] = k
	}
	s.mu.Unlock()
}

// Serve accepts raw TCP connections from ln until ln is closed.
func (s *Server) Serve(ln net.Listener) error {
	var backoff time.Duration
	for {
		c, err := ln.Accept()
		if err != nil {
			if s.Closing() || errors.Is(err, net.ErrClosed) {
				return nil
			}
			var ne net.Error
			if errors.As(err, &ne) && ne.Timeout() {
				continue
			}
			// e.g. EMFILE: back off instead of spinning.
			backoff = min(max(backoff*2, 5*time.Millisecond), time.Second)
			s.log.Warn("accept failed", "err", err, "retry_in", backoff)
			time.Sleep(backoff)
			continue
		}
		backoff = 0
		go s.ServeConn(c, c.RemoteAddr().String())
	}
}

// ServeConn runs the relay protocol on one connection (any transport) and returns when the
// connection is finished. It always closes c.
func (s *Server) ServeConn(c net.Conn, remote string) {
	if !s.track(c) {
		_ = c.Close()
		return
	}
	defer s.untrack(c)
	defer c.Close()

	_ = c.SetDeadline(time.Now().Add(s.cfg.HandshakeTimeout))
	br := bufio.NewReaderSize(c, s.cfg.MaxLineBytes+1)
	line, err := ReadLine(br)
	if err != nil {
		s.failHandshake(c, remote, err)
		return
	}
	h, err := ParseHello(line)
	if err != nil {
		s.failHandshake(c, remote, err)
		return
	}
	switch h.Kind {
	case KindListen:
		s.serveControl(c, br, h.FP, remote)
	case KindAccept:
		s.serveAccept(c, br, h)
	case KindConnect:
		s.servePhone(c, br, h)
	}
}

// failHandshake answers a protocol error (if it is one) and closes.
func (s *Server) failHandshake(c net.Conn, remote string, err error) {
	var pe *ProtoError
	switch {
	case errors.As(err, &pe):
	case errors.Is(err, ErrLineTooLong):
		pe = protoErr(CodeBadRequest, "line too long")
	default:
		// I/O error, EOF or handshake timeout: nothing useful to say.
		s.log.Debug("handshake aborted", "remote", remote, "err", err)
		return
	}
	s.log.Debug("handshake refused", "remote", remote, "code", pe.Code)
	s.sendErr(c, pe)
}

// sendErr writes `ERR ...`, then closes politely so the line is not lost to a TCP reset
// caused by unread input.
func (s *Server) sendErr(c net.Conn, pe *ProtoError) {
	_ = writeLine(c, pe.Line(), s.cfg.WriteTimeout)
	if hc, ok := c.(interface{ CloseWrite() error }); ok {
		_ = hc.CloseWrite()
		_ = c.SetReadDeadline(time.Now().Add(time.Second))
		_, _ = io.Copy(io.Discard, io.LimitReader(c, 256*1024))
	}
	_ = c.Close()
}

func writeLine(c net.Conn, line string, timeout time.Duration) error {
	_ = c.SetWriteDeadline(time.Now().Add(timeout))
	_, err := c.Write([]byte(line + "\n"))
	return err
}

// ---------------------------------------------------------------------------------------
// Desktop control connection (§2.1)

func (s *Server) serveControl(c net.Conn, br *bufio.Reader, fp, remote string) {
	log := s.log.With("fp", ShortFP(fp))
	nonce, err := NewNonce()
	if err != nil {
		log.Error("nonce generation failed", "err", err)
		return
	}
	if err := writeLine(c, "CHALLENGE "+nonce, s.cfg.WriteTimeout); err != nil {
		return
	}
	line, err := ReadLine(br)
	if err != nil {
		s.failHandshake(c, remote, err)
		return
	}
	certDER, sig, err := ParseAuth(line)
	if err != nil {
		s.failHandshake(c, remote, err)
		return
	}
	if err := VerifyAuth(fp, nonce, certDER, sig); err != nil {
		log.Info("desktop authentication failed", "reason", err.Error(), "remote", remote)
		s.sendErr(c, protoErr(CodeAuthFailed, ""))
		return
	}

	d := &desktop{fp: fp, conn: c, closed: make(chan struct{})}
	s.mu.Lock()
	if s.closing {
		s.mu.Unlock()
		return
	}
	old := s.desktops[fp]
	if old == nil && len(s.desktops) >= s.cfg.MaxDesktops {
		s.mu.Unlock()
		log.Warn("desktop refused: relay full", "max_desktops", s.cfg.MaxDesktops)
		s.sendErr(c, protoErr(CodeBusy, "too many desktops"))
		return
	}
	s.desktops[fp] = d
	s.conns[c] = kindControl
	s.mu.Unlock()
	if old != nil {
		old.close()
		log.Info("desktop control connection replaced")
	} else {
		log.Info("desktop online")
	}
	defer s.desktopGone(d, log)

	_ = c.SetDeadline(time.Time{})
	if err := d.writeLine("OK", s.cfg.WriteTimeout); err != nil {
		return
	}
	go s.pingLoop(d)

	for {
		_ = c.SetReadDeadline(time.Now().Add(s.cfg.DesktopTimeout))
		line, err := ReadLine(br)
		if err != nil {
			var pe *ProtoError
			switch {
			case errors.As(err, &pe):
			case errors.Is(err, ErrLineTooLong):
				pe = protoErr(CodeBadRequest, "line too long")
			}
			if pe != nil {
				_ = d.writeLine(pe.Line(), s.cfg.WriteTimeout)
			}
			if !errors.Is(err, net.ErrClosed) {
				log.Debug("control connection ended", "err", err)
			}
			return
		}
		cl, err := ParseControlLine(line)
		if err != nil {
			var pe *ProtoError
			errors.As(err, &pe)
			_ = d.writeLine(pe.Line(), s.cfg.WriteTimeout)
			log.Info("desktop sent an invalid control line; dropping it")
			return
		}
		switch cl.Kind {
		case CtlPing:
			if d.writeLine(CtlPong, s.cfg.WriteTimeout) != nil {
				return
			}
		case CtlPong:
			// activity only; the read deadline was already extended
		case CtlReject:
			s.reject(fp, cl.Conn)
		}
	}
}

func (d *desktop) writeLine(line string, timeout time.Duration) error {
	d.wmu.Lock()
	defer d.wmu.Unlock()
	err := writeLine(d.conn, line, timeout)
	if err != nil {
		d.close()
	}
	return err
}

func (s *Server) pingLoop(d *desktop) {
	t := time.NewTicker(s.cfg.PingInterval)
	defer t.Stop()
	for {
		select {
		case <-d.closed:
			return
		case <-t.C:
			if d.writeLine(CtlPing, s.cfg.WriteTimeout) != nil {
				return
			}
		}
	}
}

// desktopGone unregisters d (unless it was already replaced) and fails its pending phones.
func (s *Server) desktopGone(d *desktop, log *slog.Logger) {
	d.close()
	var failed []*pendingConn
	s.mu.Lock()
	if s.desktops[d.fp] == d {
		delete(s.desktops, d.fp)
		for _, p := range s.byFP[d.fp] {
			failed = append(failed, p)
		}
		for _, p := range failed {
			s.removePendingLocked(p)
		}
		log.Info("desktop offline", "failed_pending", len(failed))
	}
	s.mu.Unlock()
	for _, p := range failed {
		p.result <- acceptResult{kind: resOffline}
	}
}

func (s *Server) reject(fp, id string) {
	s.mu.Lock()
	p := s.pending[id]
	if p == nil || p.fp != fp {
		s.mu.Unlock()
		return // already timed out or accepted; not an error
	}
	s.removePendingLocked(p)
	s.mu.Unlock()
	p.result <- acceptResult{kind: resRejected}
}

func (s *Server) removePendingLocked(p *pendingConn) {
	delete(s.pending, p.id)
	if m := s.byFP[p.fp]; m != nil {
		delete(m, p.id)
		if len(m) == 0 {
			delete(s.byFP, p.fp)
		}
	}
}

// takePending removes p if it is still pending; false means somebody else already
// removed it and will deliver a result.
func (s *Server) takePending(p *pendingConn) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.pending[p.id] != p {
		return false
	}
	s.removePendingLocked(p)
	return true
}

// ---------------------------------------------------------------------------------------
// Phone connection (§2.3)

func (s *Server) servePhone(c net.Conn, cbr *bufio.Reader, h Hello) {
	log := s.log.With("fp", ShortFP(h.FP))
	_ = c.SetDeadline(time.Time{})

	id, err := NewConnID()
	if err != nil {
		log.Error("connection id generation failed", "err", err)
		return
	}
	p := &pendingConn{id: id, fp: h.FP, result: make(chan acceptResult, 1)}

	s.mu.Lock()
	d := s.desktops[h.FP]
	switch {
	case d == nil || s.closing:
		s.mu.Unlock()
		s.sendErr(c, protoErr(CodeOffline, ""))
		return
	case len(s.byFP[h.FP]) >= s.cfg.MaxPendingPerDesktop || s.tunnels[h.FP] >= s.cfg.MaxTunnelsPerDesktop:
		s.mu.Unlock()
		log.Info("phone refused: desktop limits reached")
		s.sendErr(c, protoErr(CodeBusy, ""))
		return
	}
	s.pending[id] = p
	if s.byFP[h.FP] == nil {
		s.byFP[h.FP] = make(map[string]*pendingConn)
	}
	s.byFP[h.FP][id] = p
	s.mu.Unlock()

	var r acceptResult
	// The token goes to the desktop only; it is never logged.
	if err := d.writeLine(incomingLine(id, h.Token), s.cfg.WriteTimeout); err != nil {
		if s.takePending(p) {
			s.sendErr(c, protoErr(CodeOffline, ""))
			return
		}
		r = <-p.result
	} else {
		timer := time.NewTimer(s.cfg.AcceptTimeout)
		select {
		case r = <-p.result:
		case <-timer.C:
			if s.takePending(p) {
				log.Debug("desktop did not accept in time", "conn", shortID(id))
				s.sendErr(c, protoErr(CodeTimeout, ""))
				return
			}
			r = <-p.result
		case <-s.stopping:
			if s.takePending(p) {
				s.sendErr(c, protoErr(CodeOffline, "relay shutting down"))
				return
			}
			r = <-p.result
		}
		timer.Stop()
	}

	switch r.kind {
	case resRejected:
		s.sendErr(c, protoErr(CodeRejected, ""))
	case resOffline:
		s.sendErr(c, protoErr(CodeOffline, ""))
	case resBusy:
		s.sendErr(c, protoErr(CodeBusy, ""))
	case resAccepted:
		s.runTunnel(c, cbr, r, h.FP, id, log)
	}
}

// ---------------------------------------------------------------------------------------
// Desktop data connection (§2.2)

func (s *Server) serveAccept(c net.Conn, br *bufio.Reader, h Hello) {
	s.mu.Lock()
	p := s.pending[h.Conn]
	if p == nil || subtle.ConstantTimeCompare([]byte(p.fp), []byte(h.FP)) != 1 {
		s.mu.Unlock()
		s.sendErr(c, protoErr(CodeUnknownConn, ""))
		return
	}
	s.removePendingLocked(p)
	if s.tunnels[h.FP] >= s.cfg.MaxTunnelsPerDesktop {
		s.mu.Unlock()
		p.result <- acceptResult{kind: resBusy}
		s.sendErr(c, protoErr(CodeBusy, ""))
		return
	}
	s.tunnels[h.FP]++
	s.mu.Unlock()

	done := make(chan struct{})
	p.result <- acceptResult{kind: resAccepted, conn: c, br: br, done: done}
	<-done // the phone side owns c now
}

func (s *Server) runTunnel(phone net.Conn, phoneBR *bufio.Reader, r acceptResult, fp, id string, log *slog.Logger) {
	desk := r.conn
	defer func() {
		_ = desk.Close()
		s.mu.Lock()
		if s.tunnels[fp]--; s.tunnels[fp] <= 0 {
			delete(s.tunnels, fp)
		}
		s.mu.Unlock()
		close(r.done)
	}()
	if writeLine(desk, "OK", s.cfg.WriteTimeout) != nil || writeLine(phone, "OK", s.cfg.WriteTimeout) != nil {
		return
	}
	_ = desk.SetDeadline(time.Time{})
	_ = phone.SetDeadline(time.Time{})
	s.setKind(phone, kindTunnel)
	s.setKind(desk, kindTunnel)

	start := time.Now()
	log.Debug("tunnel open", "conn", shortID(id))
	up, down := splice(phone, phoneBR, desk, r.br, s.cfg.IdleTimeout, s.cfg.BandwidthBytesPerSec)
	log.Debug("tunnel closed", "conn", shortID(id), "phone_to_desktop", up, "desktop_to_phone", down,
		"duration", time.Since(start).Round(time.Millisecond))
}

// ---------------------------------------------------------------------------------------
// Shutdown

// Shutdown stops accepting work, closes control and handshaking connections at once and
// gives running tunnels until ctx is done before closing them. Listeners must be closed
// by the caller. It returns ctx.Err() if tunnels had to be cut.
func (s *Server) Shutdown(ctx context.Context) error {
	s.mu.Lock()
	if !s.closing {
		s.closing = true
		close(s.stopping)
	}
	var now []net.Conn
	for c, k := range s.conns {
		if k != kindTunnel {
			now = append(now, c)
		}
	}
	s.mu.Unlock()
	for _, c := range now {
		_ = c.Close()
	}

	done := make(chan struct{})
	go func() { s.wg.Wait(); close(done) }()
	select {
	case <-done:
		s.cancelBase()
		return nil
	case <-ctx.Done():
	}
	s.cancelBase()
	s.mu.Lock()
	var rest []net.Conn
	for c := range s.conns {
		rest = append(rest, c)
	}
	s.mu.Unlock()
	for _, c := range rest {
		_ = c.Close()
	}
	<-done
	return ctx.Err()
}
