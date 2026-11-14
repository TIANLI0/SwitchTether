package relay_test

// End-to-end tests: the relay runs in-process on loopback, a fake desktop registers with a
// real ECDSA P-256 identity, a fake phone connects, and a real crypto/tls mutual-TLS
// handshake with certificate pinning runs THROUGH the relay over raw TCP and WebSocket.

import (
	"bufio"
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/TIANLI0/SwitchTether/relay/internal/relay"
)

const (
	tcpT = "tcp"
	wsT  = "ws"
)

// ---------------------------------------------------------------------------------------
// relay under test

type testRelay struct {
	srv     *relay.Server
	tcpAddr string
	wsURL   string
	httpURL string
}

// testKey is the relay key every test client uses unless a test says otherwise.
var testKey = newKey()

func newKey() []byte {
	s, err := relay.GenerateKey()
	if err != nil {
		panic(err)
	}
	k, err := relay.ParseKey(s)
	if err != nil {
		panic(err)
	}
	return k
}

func fastConfig() relay.Config {
	c := relay.DefaultConfig()
	c.HandshakeTimeout = 5 * time.Second
	c.AcceptTimeout = 5 * time.Second
	c.WriteTimeout = 5 * time.Second
	c.Keys = [][]byte{testKey}
	return c
}

func startRelay(t *testing.T, cfg relay.Config) *testRelay {
	t.Helper()
	return startRelayWithLog(t, cfg, nil)
}

func startRelayWithLog(t *testing.T, cfg relay.Config, log *slog.Logger) *testRelay {
	t.Helper()
	srv, err := relay.NewServer(cfg, log)
	if err != nil {
		t.Fatal(err)
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	go func() { _ = srv.Serve(ln) }()
	hs := httptest.NewServer(srv.HTTPHandler())
	r := &testRelay{
		srv:     srv,
		tcpAddr: ln.Addr().String(),
		wsURL:   "ws" + strings.TrimPrefix(hs.URL, "http") + relay.TunnelPath,
		httpURL: hs.URL,
	}
	t.Cleanup(func() {
		_ = ln.Close()
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := srv.Shutdown(ctx); err != nil {
			t.Errorf("relay shutdown: %v", err)
		}
		hs.Close()
	})
	return r
}

// lineConn is a client connection that reads handshake lines through a buffer and then
// keeps reading through the same buffer, so no stream byte is lost.
type lineConn struct {
	net.Conn
	br *bufio.Reader
}

func (c *lineConn) Read(p []byte) (int, error) { return c.br.Read(p) }

func (c *lineConn) CloseWrite() error {
	if cw, ok := c.Conn.(interface{ CloseWrite() error }); ok {
		return cw.CloseWrite()
	}
	return errors.New("no half-close on this transport")
}

func (c *lineConn) send(t *testing.T, line string) {
	t.Helper()
	if _, err := c.Write([]byte(line + "\n")); err != nil {
		t.Fatalf("write %q: %v", line, err)
	}
}

func (c *lineConn) readLine() (string, error) {
	_ = c.SetReadDeadline(time.Now().Add(10 * time.Second))
	defer c.SetReadDeadline(time.Time{})
	s, err := c.br.ReadString('\n')
	return strings.TrimSuffix(s, "\n"), err
}

func (c *lineConn) expect(t *testing.T, want string) {
	t.Helper()
	got, err := c.readLine()
	if err != nil || got != want {
		t.Fatalf("got line %q (err %v), want %q", got, err, want)
	}
}

// expectClosed asserts that the peer closes the connection within d.
func expectClosed(t *testing.T, c net.Conn, d time.Duration) {
	t.Helper()
	_ = c.SetReadDeadline(time.Now().Add(d))
	buf := make([]byte, 64)
	for {
		_, err := c.Read(buf)
		if err == nil {
			continue
		}
		var ne net.Error
		if errors.As(err, &ne) && ne.Timeout() {
			t.Fatalf("connection still open after %s", d)
		}
		return
	}
}

func (r *testRelay) dialConn(transport string) (*lineConn, error) {
	var c net.Conn
	switch transport {
	case tcpT:
		tc, err := net.DialTimeout("tcp", r.tcpAddr, 5*time.Second)
		if err != nil {
			return nil, err
		}
		c = tc
	case wsT:
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		ws, _, err := websocket.Dial(ctx, r.wsURL, nil)
		if err != nil {
			return nil, err
		}
		c = websocket.NetConn(context.Background(), ws, websocket.MessageBinary)
	default:
		return nil, fmt.Errorf("unknown transport %q", transport)
	}
	return &lineConn{Conn: c, br: bufio.NewReader(c)}, nil
}

func (r *testRelay) dial(t *testing.T, transport string) *lineConn {
	t.Helper()
	c, err := r.dialConn(transport)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.Close() })
	return c
}

// ---------------------------------------------------------------------------------------
// identities

type identity struct {
	key  *ecdsa.PrivateKey
	der  []byte
	fp   string
	cert tls.Certificate
}

func newIdentity(t *testing.T, curve elliptic.Curve, cn string) identity {
	t.Helper()
	key, err := ecdsa.GenerateKey(curve, rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	serial, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 127))
	tmpl := &x509.Certificate{
		SerialNumber: serial,
		Subject:      pkix.Name{CommonName: cn, Organization: []string{"SwitchTether"}},
		DNSNames:     []string{"switchtether.local"},
		NotBefore:    time.Date(2024, 1, 1, 0, 0, 0, 0, time.UTC),
		NotAfter:     time.Date(2124, 1, 1, 0, 0, 0, 0, time.UTC),
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	return identity{key: key, der: der, fp: fingerprint(der), cert: tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}}
}

func fingerprint(der []byte) string {
	sum := sha256.Sum256(der)
	return hex.EncodeToString(sum[:])
}

func b64(b []byte) string { return base64.RawURLEncoding.EncodeToString(b) }

func (id identity) sign(t *testing.T, nonce, fp string) []byte {
	t.Helper()
	d := sha256.Sum256(relay.SignedMessage(nonce, fp))
	sig, err := ecdsa.SignASN1(rand.Reader, id.key, d[:])
	if err != nil {
		t.Fatal(err)
	}
	return sig
}

// pinPeer returns a VerifyPeerCertificate callback that accepts exactly one certificate.
func pinPeer(fp string) func([][]byte, [][]*x509.Certificate) error {
	return func(raw [][]byte, _ [][]*x509.Certificate) error {
		if len(raw) == 0 || subtle.ConstantTimeCompare([]byte(fingerprint(raw[0])), []byte(fp)) != 1 {
			return errors.New("certificate pin mismatch")
		}
		return nil
	}
}

// ---------------------------------------------------------------------------------------
// fake desktop

type decision int

const (
	accept decision = iota
	reject
	ignore
	hangUp // close the control connection
)

type fakeDesktop struct {
	t             *testing.T
	r             *testRelay
	id            identity
	ctl           *lineConn
	dataTransport string
	token         string           // expected relay token; others are rejected
	key           []byte           // relay key; nil means testKey
	decide        func() decision  // for a correct token; default accept
	handle        func(c net.Conn) // raw stream after OK; default: TLS echo server
	phoneFP       string           // pinned client certificate for the default handler
	noPong        bool             // do not answer PING
	pings         atomic.Int32     // PINGs received
	incoming      chan string      // conn ids announced by INCOMING
	handled       chan struct{}    // one value per finished data connection
	ctlDone       chan struct{}    // closed when the control loop ends
	wg            sync.WaitGroup
	stopping      atomic.Bool // set by cleanup: errors after this are teardown noise
	connMu        sync.Mutex
	dataConns     []net.Conn
}

func newDesktop(t *testing.T, r *testRelay, dataTransport string) *fakeDesktop {
	return &fakeDesktop{
		t: t, r: r, id: newIdentity(t, elliptic.P256(), "desktop"),
		dataTransport: dataTransport, token: "relay-token_" + fmt.Sprint(time.Now().UnixNano()),
		incoming: make(chan string, 64), ctlDone: make(chan struct{}), handled: make(chan struct{}, 64),
	}
}

// listen performs LISTEN/CHALLENGE/AUTH and returns the relay's final answer.
func (d *fakeDesktop) listen(transport string) (*lineConn, string) {
	t := d.t
	t.Helper()
	c := d.r.dial(t, transport)
	c.send(t, "ST-RELAY/1 LISTEN "+d.id.fp)
	line, err := c.readLine()
	if err != nil || !strings.HasPrefix(line, "CHALLENGE ") {
		t.Fatalf("expected CHALLENGE, got %q (%v)", line, err)
	}
	nonce := strings.TrimPrefix(line, "CHALLENGE ")
	key := d.key
	if key == nil {
		key = testKey
	}
	c.send(t, "AUTH "+b64(d.id.der)+" "+b64(d.id.sign(t, nonce, d.id.fp))+" "+relay.ComputeMAC(key, relay.RoleListen, nonce, d.id.fp))
	ans, _ := c.readLine()
	return c, ans
}

// start registers the desktop and serves its control connection in the background.
func (d *fakeDesktop) start(transport string) *fakeDesktop {
	t := d.t
	t.Helper()
	c, ans := d.listen(transport)
	if ans != "OK" {
		t.Fatalf("desktop registration answered %q", ans)
	}
	d.ctl = c
	go d.controlLoop()
	t.Cleanup(func() {
		d.stopping.Store(true)
		_ = d.ctl.Close()
		<-d.ctlDone
		d.connMu.Lock()
		for _, c := range d.dataConns {
			_ = c.Close()
		}
		d.connMu.Unlock()
		d.wg.Wait()
	})
	return d
}

// errorf reports a failure in a desktop goroutine, unless the test is already tearing down.
func (d *fakeDesktop) errorf(format string, args ...any) {
	if !d.stopping.Load() {
		d.t.Errorf(format, args...)
	}
}

func (d *fakeDesktop) controlLoop() {
	defer close(d.ctlDone)
	for {
		line, err := d.ctl.br.ReadString('\n')
		if err != nil {
			return
		}
		f := strings.Fields(line)
		switch {
		case len(f) == 1 && f[0] == "PING":
			d.pings.Add(1)
			if !d.noPong {
				_, _ = d.ctl.Write([]byte("PONG\n"))
			}
		case len(f) == 1 && f[0] == "PONG":
		case len(f) == 3 && f[0] == "INCOMING":
			conn, token := f[1], f[2]
			d.incoming <- conn
			dec := accept
			if d.decide != nil {
				dec = d.decide()
			}
			if subtle.ConstantTimeCompare([]byte(token), []byte(d.token)) != 1 {
				dec = reject
			}
			switch dec {
			case reject:
				_, _ = d.ctl.Write([]byte("REJECT " + conn + "\n"))
			case hangUp:
				_ = d.ctl.Close()
				return
			case accept:
				d.wg.Add(1)
				go func() {
					defer d.wg.Done()
					d.acceptConn(conn)
				}()
			}
		default:
			d.t.Errorf("desktop: unexpected control line %q", line)
			return
		}
	}
}

func (d *fakeDesktop) acceptConn(conn string) {
	defer func() {
		select {
		case d.handled <- struct{}{}:
		default:
		}
	}()
	c, err := d.r.dialConn(d.dataTransport)
	if err != nil {
		d.errorf("desktop: dial data connection: %v", err)
		return
	}
	defer c.Close()
	d.connMu.Lock()
	d.dataConns = append(d.dataConns, c)
	d.connMu.Unlock()
	if _, err := c.Write([]byte("ST-RELAY/1 ACCEPT " + d.id.fp + " " + conn + "\n")); err != nil {
		d.errorf("desktop: write ACCEPT: %v", err)
		return
	}
	if line, err := c.readLine(); line != "OK" {
		d.errorf("desktop: ACCEPT answered %q (%v)", line, err)
		return
	}
	if d.handle != nil {
		d.handle(c)
		return
	}
	d.tlsEcho(c)
}

// tlsEcho is the desktop's "normal TLS server": mutual TLS, pinned phone certificate,
// sends a greeting and then echoes everything.
func (d *fakeDesktop) tlsEcho(c net.Conn) {
	srv := tls.Server(c, &tls.Config{
		MinVersion:            tls.VersionTLS13,
		Certificates:          []tls.Certificate{d.id.cert},
		ClientAuth:            tls.RequireAnyClientCert,
		VerifyPeerCertificate: pinPeer(d.phoneFP),
	})
	defer srv.Close()
	_ = srv.SetDeadline(time.Now().Add(30 * time.Second))
	if err := srv.Handshake(); err != nil {
		d.errorf("desktop: TLS handshake: %v", err)
		return
	}
	if _, err := srv.Write([]byte("hello from desktop\n")); err != nil {
		d.errorf("desktop: write greeting: %v", err)
		return
	}
	if _, err := io.Copy(srv, srv); err != nil {
		d.errorf("desktop: echo: %v", err)
	}
}

// ---------------------------------------------------------------------------------------
// fake phone

// connect sends CONNECT, answers the CHALLENGE with the test relay key and returns the
// connection and the relay's final answer.
func connect(t *testing.T, r *testRelay, transport, fp, token string) (*lineConn, string) {
	t.Helper()
	return connectWith(t, r, transport, fp, token, func(nonce string) string {
		return "KEY " + relay.ComputeMAC(testKey, relay.RoleConnect, nonce, fp)
	})
}

// connectWith is connect with a custom answer to CHALLENGE (keyLine returns the full line).
func connectWith(t *testing.T, r *testRelay, transport, fp, token string, keyLine func(nonce string) string) (*lineConn, string) {
	t.Helper()
	c := r.dial(t, transport)
	line := "ST-RELAY/1 CONNECT " + fp
	if token != "" {
		line += " " + token
	}
	c.send(t, line)
	ch, _ := c.readLine()
	nonce, ok := strings.CutPrefix(ch, "CHALLENGE ")
	if !ok {
		return c, ch // an ERR before the challenge (e.g. bad_request)
	}
	c.send(t, keyLine(nonce))
	ans, _ := c.readLine()
	return c, ans
}

func phoneTLS(t *testing.T, c net.Conn, phone identity, desktopFP string) *tls.Conn {
	t.Helper()
	tc := tls.Client(c, &tls.Config{
		MinVersion:            tls.VersionTLS13,
		ServerName:            "switchtether.local",
		InsecureSkipVerify:    true, // replaced by the pin below, exactly like the real phone
		VerifyPeerCertificate: pinPeer(desktopFP),
		Certificates:          []tls.Certificate{phone.cert},
	})
	_ = tc.SetDeadline(time.Now().Add(30 * time.Second))
	if err := tc.Handshake(); err != nil {
		t.Fatalf("phone: TLS handshake through relay: %v", err)
	}
	return tc
}

// exchange runs the full end-to-end scenario on an established tunnel.
func exchange(t *testing.T, tc *tls.Conn, size int) {
	t.Helper()
	br := bufio.NewReader(tc)
	greet, err := br.ReadString('\n')
	if err != nil || greet != "hello from desktop\n" {
		t.Fatalf("phone: greeting %q (%v)", greet, err)
	}
	payload := make([]byte, size)
	_, _ = rand.Read(payload)
	werr := make(chan error, 1)
	go func() {
		_, err := tc.Write(payload)
		werr <- err
	}()
	echo := make([]byte, size)
	if _, err := io.ReadFull(br, echo); err != nil {
		t.Fatalf("phone: read echo: %v", err)
	}
	if err := <-werr; err != nil {
		t.Fatalf("phone: write: %v", err)
	}
	if !bytes.Equal(payload, echo) {
		t.Fatal("phone: echoed data differs")
	}
}

// ---------------------------------------------------------------------------------------
// tests: happy path

func TestEndToEndTLSThroughRelay(t *testing.T) {
	cases := []struct{ phone, control, data string }{
		{tcpT, tcpT, tcpT},
		{wsT, wsT, wsT},
		{wsT, tcpT, tcpT}, // phone over WebSocket, desktop over TCP
		{tcpT, wsT, wsT},  // phone over TCP, desktop over WebSocket
		{tcpT, tcpT, wsT}, // desktop mixes transports
	}
	for _, tc := range cases {
		t.Run(fmt.Sprintf("phone=%s/control=%s/data=%s", tc.phone, tc.control, tc.data), func(t *testing.T) {
			r := startRelay(t, fastConfig())
			phone := newIdentity(t, elliptic.P256(), "phone")
			d := newDesktop(t, r, tc.data)
			d.phoneFP = phone.fp
			d.start(tc.control)

			// Two tunnels at once to the same desktop.
			var wg sync.WaitGroup
			for i := range 2 {
				wg.Add(1)
				go func() {
					defer wg.Done()
					c, ans := connect(t, r, tc.phone, d.id.fp, d.token)
					if ans != "OK" {
						t.Errorf("phone %d: CONNECT answered %q", i, ans)
						return
					}
					tlsConn := phoneTLS(t, c, phone, d.id.fp)
					defer tlsConn.Close()
					st := tlsConn.ConnectionState()
					if st.Version != tls.VersionTLS13 || fingerprint(st.PeerCertificates[0].Raw) != d.id.fp {
						t.Errorf("unexpected TLS state: version %x", st.Version)
					}
					exchange(t, tlsConn, 1<<20)
				}()
			}
			wg.Wait()
			// The desktop must see a clean end of both TLS sessions.
			for range 2 {
				select {
				case <-d.handled:
				case <-time.After(10 * time.Second):
					t.Fatal("desktop data connection did not finish")
				}
			}
		})
	}
}

func TestPhoneWithWrongPinFailsHandshake(t *testing.T) {
	r := startRelay(t, fastConfig())
	phone := newIdentity(t, elliptic.P256(), "phone")
	d := newDesktop(t, r, tcpT)
	d.phoneFP = phone.fp
	d.handle = func(c net.Conn) { // desktop TLS server; expected to fail
		srv := tls.Server(c, &tls.Config{Certificates: []tls.Certificate{d.id.cert}, ClientAuth: tls.RequireAnyClientCert})
		_ = srv.SetDeadline(time.Now().Add(10 * time.Second))
		_ = srv.Handshake()
		_ = srv.Close()
	}
	d.start(tcpT)
	c, ans := connect(t, r, tcpT, d.id.fp, d.token)
	if ans != "OK" {
		t.Fatalf("CONNECT answered %q", ans)
	}
	impostor := newIdentity(t, elliptic.P256(), "not the desktop")
	tc := tls.Client(c, &tls.Config{
		InsecureSkipVerify: true, VerifyPeerCertificate: pinPeer(impostor.fp),
		Certificates: []tls.Certificate{phone.cert},
	})
	_ = tc.SetDeadline(time.Now().Add(10 * time.Second))
	if err := tc.Handshake(); err == nil {
		t.Fatal("TLS handshake succeeded with a wrong pin")
	}
}

func TestPipelinedBytesAfterConnect(t *testing.T) {
	r := startRelay(t, fastConfig())
	d := newDesktop(t, r, tcpT)
	got := make(chan string, 1)
	d.handle = func(c net.Conn) {
		b := make([]byte, 5)
		_, _ = io.ReadFull(c, b)
		got <- string(b)
	}
	d.start(tcpT)
	c := r.dial(t, tcpT)
	c.send(t, "ST-RELAY/1 CONNECT "+d.id.fp+" "+d.token)
	ch, _ := c.readLine()
	nonce, ok := strings.CutPrefix(ch, "CHALLENGE ")
	if !ok {
		t.Fatalf("expected CHALLENGE, got %q", ch)
	}
	// KEY and the first stream bytes in one write: the relay must not lose them.
	mac := relay.ComputeMAC(testKey, relay.RoleConnect, nonce, d.id.fp)
	if _, err := c.Write([]byte("KEY " + mac + "\nearly")); err != nil {
		t.Fatal(err)
	}
	c.expect(t, "OK")
	select {
	case s := <-got:
		if s != "early" {
			t.Fatalf("desktop received %q", s)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("pipelined bytes never arrived")
	}
}

func TestHalfClose(t *testing.T) {
	for _, phoneT := range []string{tcpT, wsT} {
		t.Run("phone="+phoneT, func(t *testing.T) {
			r := startRelay(t, fastConfig())
			d := newDesktop(t, r, tcpT)
			d.handle = func(c net.Conn) {
				// Read until the phone's EOF, then answer and close.
				b, err := io.ReadAll(c)
				if err != nil || string(b) != "request" {
					t.Errorf("desktop read %q (%v)", b, err)
					return
				}
				if _, err := c.Write([]byte("response")); err != nil {
					t.Errorf("desktop write after half-close: %v", err)
				}
			}
			d.start(tcpT)
			c, ans := connect(t, r, phoneT, d.id.fp, d.token)
			if ans != "OK" {
				t.Fatalf("CONNECT answered %q", ans)
			}
			if _, err := c.Write([]byte("request")); err != nil {
				t.Fatal(err)
			}
			if phoneT == tcpT {
				if err := c.CloseWrite(); err != nil {
					t.Fatal(err)
				}
				_ = c.SetReadDeadline(time.Now().Add(5 * time.Second))
				b, err := io.ReadAll(c)
				if err != nil || string(b) != "response" {
					t.Fatalf("phone read %q (%v)", b, err)
				}
				return
			}
			// WebSocket has no half-close: closing ends both directions, and the desktop
			// (TCP) sees EOF on its read side.
			_ = c.Close()
			d.wg.Wait()
		})
	}
}

// ---------------------------------------------------------------------------------------
// tests: authentication

func TestAuthFailures(t *testing.T) {
	r := startRelay(t, fastConfig())
	good := newIdentity(t, elliptic.P256(), "desktop")
	other := newIdentity(t, elliptic.P256(), "other")
	p384 := newIdentity(t, elliptic.P384(), "p384")

	mac := func(key []byte, role, n, fp string) string { return " " + relay.ComputeMAC(key, role, n, fp) }
	goodAuth := func(n string) string {
		return "AUTH " + b64(good.der) + " " + b64(good.sign(t, n, good.fp))
	}
	cases := []struct {
		name     string
		listenFP string
		auth     func(nonce string) string
	}{
		{"wrong signature", good.fp, func(n string) string {
			return "AUTH " + b64(good.der) + " " + b64(other.sign(t, n, good.fp)) + mac(testKey, relay.RoleListen, n, good.fp)
		}},
		{"signature over wrong nonce", good.fp, func(n string) string {
			return "AUTH " + b64(good.der) + " " + b64(good.sign(t, "bm90LXRoZS1ub25jZQ", good.fp)) + mac(testKey, relay.RoleListen, n, good.fp)
		}},
		{"fingerprint mismatch", good.fp, func(n string) string {
			return "AUTH " + b64(other.der) + " " + b64(other.sign(t, n, good.fp)) + mac(testKey, relay.RoleListen, n, good.fp)
		}},
		{"not P-256", p384.fp, func(n string) string {
			return "AUTH " + b64(p384.der) + " " + b64(p384.sign(t, n, p384.fp)) + mac(testKey, relay.RoleListen, n, p384.fp)
		}},
		// Relay key (docs/RELAY.md 2.0): a valid desktop signature is not enough.
		{"missing relay key MAC", good.fp, goodAuth},
		{"MAC with an unknown relay key", good.fp, func(n string) string {
			return goodAuth(n) + mac(newKey(), relay.RoleListen, n, good.fp)
		}},
		{"MAC for the connect role", good.fp, func(n string) string {
			return goodAuth(n) + mac(testKey, relay.RoleConnect, n, good.fp)
		}},
		{"MAC over another nonce", good.fp, func(n string) string {
			return goodAuth(n) + mac(testKey, relay.RoleListen, "bm90LXRoZS1ub25jZQ", good.fp)
		}},
		{"MAC over another fingerprint", good.fp, func(n string) string {
			return goodAuth(n) + mac(testKey, relay.RoleListen, n, other.fp)
		}},
		{"relay key sent instead of a MAC", good.fp, func(n string) string {
			return goodAuth(n) + " " + b64(testKey)
		}},
		{"malformed MAC", good.fp, func(n string) string {
			return goodAuth(n) + " not+base64"
		}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			for _, tr := range []string{tcpT, wsT} {
				c := r.dial(t, tr)
				c.send(t, "ST-RELAY/1 LISTEN "+tc.listenFP)
				line, _ := c.readLine()
				nonce, ok := strings.CutPrefix(line, "CHALLENGE ")
				if !ok {
					t.Fatalf("expected CHALLENGE, got %q", line)
				}
				c.send(t, tc.auth(nonce))
				c.expect(t, "ERR auth_failed")
				expectClosed(t, c, 5*time.Second)
			}
		})
	}

	// None of them registered: the desktop is offline for phones.
	if _, ans := connect(t, r, tcpT, good.fp, "x"); ans != "ERR offline" {
		t.Fatalf("CONNECT after failed auth answered %q", ans)
	}

	t.Run("malformed AUTH", func(t *testing.T) {
		c := r.dial(t, tcpT)
		c.send(t, "ST-RELAY/1 LISTEN "+good.fp)
		if line, _ := c.readLine(); !strings.HasPrefix(line, "CHALLENGE ") {
			t.Fatalf("got %q", line)
		}
		c.send(t, "AUTH only-one-field")
		c.expect(t, "ERR bad_request expected AUTH <cert> <sig> <mac>")
	})

	t.Run("nonce is fresh per connection", func(t *testing.T) {
		seen := map[string]bool{}
		for range 5 {
			c := r.dial(t, tcpT)
			c.send(t, "ST-RELAY/1 LISTEN "+good.fp)
			line, _ := c.readLine()
			if seen[line] {
				t.Fatal("CHALLENGE repeated")
			}
			seen[line] = true
			_ = c.Close()
		}
	})
}

// ---------------------------------------------------------------------------------------
// tests: phone-side failures

func TestReject(t *testing.T) {
	r := startRelay(t, fastConfig())
	d := newDesktop(t, r, tcpT)
	d.decide = func() decision { return reject }
	d.start(tcpT)
	for _, tr := range []string{tcpT, wsT} {
		c, ans := connect(t, r, tr, d.id.fp, d.token)
		if ans != "ERR rejected" {
			t.Fatalf("%s: CONNECT answered %q", tr, ans)
		}
		expectClosed(t, c, 5*time.Second)
	}
}

func TestWrongTokenIsRejectedByDesktop(t *testing.T) {
	r := startRelay(t, fastConfig())
	d := newDesktop(t, r, tcpT).start(tcpT)
	for _, tok := range []string{"wrong-token", ""} { // "" sends no token: forwarded as "-"
		if _, ans := connect(t, r, tcpT, d.id.fp, tok); ans != "ERR rejected" {
			t.Fatalf("token %q: CONNECT answered %q", tok, ans)
		}
	}
}

func TestOffline(t *testing.T) {
	r := startRelay(t, fastConfig())
	unknown := newIdentity(t, elliptic.P256(), "nobody")
	for _, tr := range []string{tcpT, wsT} {
		if _, ans := connect(t, r, tr, unknown.fp, "tok"); ans != "ERR offline" {
			t.Fatalf("%s: CONNECT answered %q", tr, ans)
		}
	}
}

func TestDesktopGoneWhilePhoneWaits(t *testing.T) {
	r := startRelay(t, fastConfig())
	d := newDesktop(t, r, tcpT)
	d.decide = func() decision { return hangUp }
	d.start(tcpT)
	if _, ans := connect(t, r, tcpT, d.id.fp, d.token); ans != "ERR offline" {
		t.Fatalf("CONNECT answered %q", ans)
	}
	// The registration is gone too.
	if _, ans := connect(t, r, tcpT, d.id.fp, d.token); ans != "ERR offline" {
		t.Fatalf("second CONNECT answered %q", ans)
	}
}

func TestAcceptTimeoutAndLateAccept(t *testing.T) {
	cfg := fastConfig()
	cfg.AcceptTimeout = 300 * time.Millisecond
	r := startRelay(t, cfg)
	d := newDesktop(t, r, tcpT)
	d.decide = func() decision { return ignore }
	d.start(tcpT)

	start := time.Now()
	_, ans := connect(t, r, wsT, d.id.fp, d.token)
	if ans != "ERR timeout" {
		t.Fatalf("CONNECT answered %q", ans)
	}
	if el := time.Since(start); el < 250*time.Millisecond {
		t.Fatalf("timed out after only %s", el)
	}
	conn := <-d.incoming
	c := r.dial(t, tcpT)
	c.send(t, "ST-RELAY/1 ACCEPT "+d.id.fp+" "+conn)
	c.expect(t, "ERR unknown_conn")
}

func TestUnknownConn(t *testing.T) {
	r := startRelay(t, fastConfig())
	d := newDesktop(t, r, tcpT)
	d.decide = func() decision { return ignore }
	d.start(tcpT)

	t.Run("never issued", func(t *testing.T) {
		for _, tr := range []string{tcpT, wsT} {
			c := r.dial(t, tr)
			c.send(t, "ST-RELAY/1 ACCEPT "+d.id.fp+" AAAAAAAAAAAAAAAAAAAAAA")
			c.expect(t, "ERR unknown_conn")
		}
	})

	t.Run("pending conn of another desktop", func(t *testing.T) {
		got := make(chan string, 1)
		go func() {
			_, ans := connect(t, r, tcpT, d.id.fp, d.token)
			got <- ans
		}()
		conn := <-d.incoming
		other := newIdentity(t, elliptic.P256(), "other")
		c := r.dial(t, tcpT)
		c.send(t, "ST-RELAY/1 ACCEPT "+other.fp+" "+conn)
		c.expect(t, "ERR unknown_conn")

		// The right desktop can still take it.
		c2 := r.dial(t, tcpT)
		c2.send(t, "ST-RELAY/1 ACCEPT "+d.id.fp+" "+conn)
		c2.expect(t, "OK")
		if ans := <-got; ans != "OK" {
			t.Fatalf("phone got %q", ans)
		}
		// Accepting twice is not possible.
		c3 := r.dial(t, tcpT)
		c3.send(t, "ST-RELAY/1 ACCEPT "+d.id.fp+" "+conn)
		c3.expect(t, "ERR unknown_conn")
	})
}

// ---------------------------------------------------------------------------------------
// tests: malformed input

func TestMalformedHandshake(t *testing.T) {
	cfg := fastConfig()
	cfg.MaxLineBytes = 256
	cfg.HandshakeTimeout = 300 * time.Millisecond
	r := startRelay(t, cfg)
	fp := newIdentity(t, elliptic.P256(), "x").fp

	for _, tr := range []string{tcpT, wsT} {
		t.Run(tr, func(t *testing.T) {
			// Oversized line (no newline within the limit).
			c := r.dial(t, tr)
			if _, err := c.Write([]byte(strings.Repeat("A", 1000))); err != nil {
				t.Fatal(err)
			}
			c.expect(t, "ERR bad_request line too long")
			expectClosed(t, c, 5*time.Second)

			// A line exactly at the limit is read (and then rejected for its content).
			c = r.dial(t, tr)
			c.send(t, strings.Repeat("A", 256))
			c.expect(t, "ERR bad_request not a relay handshake")
			c = r.dial(t, tr)
			c.send(t, strings.Repeat("A", 257))
			c.expect(t, "ERR bad_request line too long")

			// The longest valid token is accepted; offline is only reported after the key.
			_, ans := connect(t, r, tr, fp, strings.Repeat("a", 128))
			if ans != "ERR offline" {
				t.Fatalf("CONNECT with a 128-char token answered %q", ans)
			}

			c = r.dial(t, tr)
			c.send(t, "ST-RELAY/2 CONNECT "+fp+" tok")
			c.expect(t, "ERR version this relay speaks ST-RELAY/1")

			c = r.dial(t, tr)
			c.send(t, "GET / HTTP/1.1")
			c.expect(t, "ERR bad_request not a relay handshake")

			c = r.dial(t, tr)
			c.send(t, "ST-RELAY/1 LISTEN NOTAFINGERPRINT")
			c.expect(t, "ERR bad_request malformed fingerprint")

			// Silent client: dropped after the handshake timeout.
			c = r.dial(t, tr)
			start := time.Now()
			expectClosed(t, c, 5*time.Second)
			if el := time.Since(start); el < 200*time.Millisecond {
				t.Fatalf("closed after %s, before the handshake timeout", el)
			}
		})
	}
}

func TestWebSocketTextMessageIsInvalid(t *testing.T) {
	r := startRelay(t, fastConfig())
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	ws, _, err := websocket.Dial(ctx, r.wsURL, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer ws.CloseNow()
	if err := ws.Write(ctx, websocket.MessageText, []byte("ST-RELAY/1 CONNECT x\n")); err != nil {
		t.Fatal(err)
	}
	_, _, err = ws.Read(ctx)
	if websocket.CloseStatus(err) != websocket.StatusUnsupportedData {
		t.Fatalf("expected close 1003 (unsupported data), got %v", err)
	}
}

func TestControlConnectionGarbage(t *testing.T) {
	cfg := fastConfig()
	cfg.MaxLineBytes = 1024 // still fits the AUTH line
	r := startRelay(t, cfg)

	d := newDesktop(t, r, tcpT)
	c, ans := d.listen(tcpT)
	if ans != "OK" {
		t.Fatal(ans)
	}
	c.send(t, "HELLO")
	c.expect(t, "ERR bad_request unknown control line")
	expectClosed(t, c, 5*time.Second)

	c, ans = d.listen(wsT)
	if ans != "OK" {
		t.Fatal(ans)
	}
	if _, err := c.Write([]byte(strings.Repeat("P", 1100))); err != nil {
		t.Fatal(err)
	}
	c.expect(t, "ERR bad_request line too long")
	expectClosed(t, c, 5*time.Second)

	// A REJECT for an unknown conn id is ignored; PING is answered.
	c, ans = d.listen(tcpT)
	if ans != "OK" {
		t.Fatal(ans)
	}
	c.send(t, "REJECT AAAAAAAAAAAAAAAAAAAAAA")
	c.send(t, "PING")
	c.expect(t, "PONG")
}

// ---------------------------------------------------------------------------------------
// tests: limits, replacement, keepalive, idle

func TestLimitPendingPerDesktop(t *testing.T) {
	cfg := fastConfig()
	cfg.MaxPendingPerDesktop = 2
	cfg.AcceptTimeout = 2 * time.Second
	r := startRelay(t, cfg)
	d := newDesktop(t, r, tcpT)
	d.decide = func() decision { return ignore }
	d.start(tcpT)

	answers := make(chan string, 2)
	for range 2 {
		go func() {
			_, ans := connect(t, r, tcpT, d.id.fp, d.token)
			answers <- ans
		}()
		<-d.incoming
	}
	if _, ans := connect(t, r, wsT, d.id.fp, d.token); ans != "ERR busy" {
		t.Fatalf("third pending phone answered %q", ans)
	}
	for range 2 {
		if ans := <-answers; ans != "ERR timeout" {
			t.Fatalf("pending phone answered %q", ans)
		}
	}
	// Slots are free again (a wrong token is rejected by the desktop instead of ignored).
	if _, ans := connect(t, r, tcpT, d.id.fp, "wrong"); ans != "ERR rejected" {
		t.Fatalf("after timeouts: %q", ans)
	}
}

func TestLimitTunnelsPerDesktop(t *testing.T) {
	cfg := fastConfig()
	cfg.MaxTunnelsPerDesktop = 1
	r := startRelay(t, cfg)
	d := newDesktop(t, r, tcpT)
	hold := make(chan struct{})
	d.handle = func(c net.Conn) { <-hold }
	d.start(tcpT)

	c1, ans := connect(t, r, tcpT, d.id.fp, d.token)
	if ans != "OK" {
		t.Fatalf("first tunnel: %q", ans)
	}
	if _, ans := connect(t, r, tcpT, d.id.fp, d.token); ans != "ERR busy" {
		t.Fatalf("second tunnel: %q", ans)
	}
	close(hold) // desktop closes the first tunnel
	expectClosed(t, c1, 5*time.Second)
	_ = c1.Close() // a real phone closes on EOF; only then is the tunnel slot free
	waitFor(t, func() bool {
		c, ans := connect(t, r, tcpT, d.id.fp, d.token)
		_ = c.Close()
		return ans == "OK"
	})
}

func TestLimitDesktopsAndReplacement(t *testing.T) {
	cfg := fastConfig()
	cfg.MaxDesktops = 1
	r := startRelay(t, cfg)

	d1 := newDesktop(t, r, tcpT).start(tcpT)
	d2 := newDesktop(t, r, tcpT)
	if _, ans := d2.listen(wsT); ans != "ERR busy too many desktops" {
		t.Fatalf("second desktop answered %q", ans)
	}

	// The same fingerprint replaces its old control connection even when full.
	d1b := &fakeDesktop{t: t, r: r, id: d1.id, dataTransport: wsT, token: d1.token,
		incoming: make(chan string, 8), ctlDone: make(chan struct{}), handled: make(chan struct{}, 8),
		handle: func(c net.Conn) { _, _ = io.Copy(io.Discard, c) }}
	d1b.start(wsT)
	select {
	case <-d1.ctlDone:
	case <-time.After(5 * time.Second):
		t.Fatal("old control connection was not closed on replacement")
	}
	// Phones now reach the new control connection.
	c, ans := connect(t, r, tcpT, d1.id.fp, d1.token)
	if ans != "OK" {
		t.Fatalf("CONNECT after replacement: %q", ans)
	}
	_ = c.Close()
	select {
	case <-d1b.incoming:
	case <-time.After(5 * time.Second):
		t.Fatal("INCOMING did not reach the new control connection")
	}
	select {
	case <-d1b.handled:
	case <-time.After(5 * time.Second):
		t.Fatal("tunnel via the new control connection did not finish")
	}
}

func TestKeepalive(t *testing.T) {
	cfg := fastConfig()
	cfg.PingInterval = 50 * time.Millisecond
	cfg.DesktopTimeout = 300 * time.Millisecond
	r := startRelay(t, cfg)

	alive := newDesktop(t, r, tcpT).start(wsT)
	silent := newDesktop(t, r, tcpT)
	silent.noPong = true
	silent.start(tcpT)

	select {
	case <-silent.ctlDone:
	case <-time.After(5 * time.Second):
		t.Fatal("silent desktop was not dropped")
	}
	if silent.pings.Load() == 0 {
		t.Fatal("silent desktop never received PING")
	}
	if _, ans := connect(t, r, tcpT, silent.id.fp, silent.token); ans != "ERR offline" {
		t.Fatalf("dropped desktop: CONNECT answered %q", ans)
	}

	time.Sleep(400 * time.Millisecond) // longer than DesktopTimeout
	select {
	case <-alive.ctlDone:
		t.Fatal("desktop answering PONG was dropped")
	default:
	}
	if n := alive.pings.Load(); n < 3 {
		t.Fatalf("only %d PINGs", n)
	}
}

func TestIdleTimeout(t *testing.T) {
	cfg := fastConfig()
	cfg.IdleTimeout = 300 * time.Millisecond
	r := startRelay(t, cfg)
	d := newDesktop(t, r, tcpT)
	d.handle = func(c net.Conn) { _, _ = io.Copy(c, c) } // raw echo
	d.start(tcpT)

	// Active tunnel: a byte every 100 ms keeps it open well past the idle timeout.
	c, ans := connect(t, r, tcpT, d.id.fp, d.token)
	if ans != "OK" {
		t.Fatal(ans)
	}
	b := make([]byte, 1)
	var lastIO time.Time
	for range 10 {
		if _, err := c.Write([]byte{'x'}); err != nil {
			t.Fatalf("active tunnel closed: %v", err)
		}
		_ = c.SetReadDeadline(time.Now().Add(2 * time.Second))
		if _, err := io.ReadFull(c, b); err != nil {
			t.Fatalf("active tunnel closed: %v", err)
		}
		lastIO = time.Now()
		time.Sleep(100 * time.Millisecond)
	}
	// Now idle: closed ~300 ms after the last byte.
	expectClosed(t, c, 5*time.Second)
	if el := time.Since(lastIO); el < 250*time.Millisecond {
		t.Fatalf("closed after %s", el)
	}
}

func TestBandwidthLimit(t *testing.T) {
	cfg := fastConfig()
	cfg.BandwidthBytesPerSec = 64 * 1024
	r := startRelay(t, cfg)
	d := newDesktop(t, r, tcpT)
	recv := make(chan int, 1)
	d.handle = func(c net.Conn) {
		n, _ := io.Copy(io.Discard, c)
		recv <- int(n)
	}
	d.start(tcpT)
	c, ans := connect(t, r, tcpT, d.id.fp, d.token)
	if ans != "OK" {
		t.Fatal(ans)
	}
	start := time.Now()
	// 1 s burst + 1 s at the limit.
	if _, err := c.Write(make([]byte, 128*1024)); err != nil {
		t.Fatal(err)
	}
	_ = c.CloseWrite()
	if n := <-recv; n != 128*1024 {
		t.Fatalf("received %d bytes", n)
	}
	if el := time.Since(start); el < 800*time.Millisecond {
		t.Fatalf("128 KiB at 64 KiB/s took only %s", el)
	}
}

// ---------------------------------------------------------------------------------------
// tests: HTTP surface and shutdown

func TestHTTPSurface(t *testing.T) {
	r := startRelay(t, fastConfig())
	get := func(method, path string) (int, string) {
		req, _ := http.NewRequest(method, r.httpURL+path, nil)
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		b, _ := io.ReadAll(resp.Body)
		return resp.StatusCode, string(b)
	}
	if code, body := get("GET", "/healthz"); code != 200 || body != "ok\n" {
		t.Fatalf("/healthz: %d %q", code, body)
	}
	for _, p := range []string{"/", "/metrics", "/stats", "/debug/pprof/", "/v1/desktops"} {
		if code, _ := get("GET", p); code != 404 {
			t.Errorf("GET %s: %d, want 404", p, code)
		}
	}
	if code, _ := get("POST", "/v1/tunnel"); code != 405 {
		t.Errorf("POST /v1/tunnel: %d", code)
	}
	if code, _ := get("GET", "/v1/tunnel"); code != 426 && code != 400 {
		t.Errorf("plain GET /v1/tunnel: %d", code)
	}
}

func TestShutdown(t *testing.T) {
	srv, err := relay.NewServer(fastConfig(), nil)
	if err != nil {
		t.Fatal(err)
	}
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	served := make(chan struct{})
	go func() { _ = srv.Serve(ln); close(served) }()
	hs := httptest.NewServer(srv.HTTPHandler())
	defer hs.Close()
	r := &testRelay{srv: srv, tcpAddr: ln.Addr().String(), wsURL: "ws" + strings.TrimPrefix(hs.URL, "http") + relay.TunnelPath}

	d := newDesktop(t, r, tcpT)
	hold := make(chan struct{})
	defer close(hold)
	d.handle = func(c net.Conn) { <-hold }
	d.start(wsT)
	tunnel, ans := connect(t, r, tcpT, d.id.fp, d.token)
	if ans != "OK" {
		t.Fatal(ans)
	}

	_ = ln.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel()
	start := time.Now()
	if err := srv.Shutdown(ctx); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("Shutdown with a held tunnel returned %v", err)
	}
	if time.Since(start) > 3*time.Second {
		t.Fatal("Shutdown did not respect its deadline")
	}
	<-served
	select {
	case <-d.ctlDone: // control connection closed immediately
	case <-time.After(3 * time.Second):
		t.Fatal("control connection survived shutdown")
	}
	expectClosed(t, tunnel, 3*time.Second)
	// New WebSocket connections are refused.
	resp, err := http.Get(hs.URL + relay.HealthPath)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("/healthz during shutdown: %d", resp.StatusCode)
	}
}

func waitFor(t *testing.T, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("condition not met within 5s")
}

// lockedBuffer collects log output from many goroutines.
type lockedBuffer struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (l *lockedBuffer) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.Write(p)
}

func (l *lockedBuffer) String() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.String()
}

func TestLogsContainNoSecrets(t *testing.T) {
	var logs lockedBuffer
	log := slog.New(slog.NewTextHandler(&logs, &slog.HandlerOptions{Level: slog.LevelDebug}))
	r := startRelayWithLog(t, fastConfig(), log)
	phone := newIdentity(t, elliptic.P256(), "phone")
	d := newDesktop(t, r, tcpT)
	d.phoneFP = phone.fp
	d.start(tcpT)

	c, ans := connect(t, r, tcpT, d.id.fp, d.token)
	if ans != "OK" {
		t.Fatal(ans)
	}
	tc := phoneTLS(t, c, phone, d.id.fp)
	exchange(t, tc, 4096)
	_ = tc.Close()
	<-d.handled
	conn := <-d.incoming
	if _, ans := connect(t, r, wsT, d.id.fp, "a-wrong-token-value"); ans != "ERR rejected" {
		t.Fatal(ans)
	}
	other := newIdentity(t, elliptic.P256(), "other")
	bad := r.dial(t, tcpT)
	bad.send(t, "ST-RELAY/1 LISTEN "+d.id.fp)
	line, _ := bad.readLine()
	nonce := strings.TrimPrefix(line, "CHALLENGE ")
	goodMAC := relay.ComputeMAC(testKey, relay.RoleListen, nonce, d.id.fp)
	bad.send(t, "AUTH "+b64(other.der)+" "+b64(other.sign(t, nonce, d.id.fp))+" "+goodMAC)
	bad.expect(t, "ERR auth_failed")
	// A phone with a wrong relay key.
	var wrongMAC string
	if _, ans := connectWith(t, r, tcpT, d.id.fp, d.token, func(n string) string {
		wrongMAC = relay.ComputeMAC(newKey(), relay.RoleConnect, n, d.id.fp)
		return "KEY " + wrongMAC
	}); ans != "ERR auth_failed" {
		t.Fatal(ans)
	}

	waitFor(t, func() bool { return strings.Contains(logs.String(), "tunnel closed") })
	out := logs.String()
	if !strings.Contains(out, "fp="+d.id.fp[:8]) {
		t.Fatalf("expected the shortened fingerprint in the logs:\n%s", out)
	}
	for name, secret := range map[string]string{
		"token": d.token, "wrong token": "a-wrong-token-value", "full fingerprint": d.id.fp,
		"phone fingerprint": phone.fp, "conn id": conn, "nonce": nonce,
		"relay key": b64(testKey), "relay key MAC": goodMAC, "wrong MAC": wrongMAC,
	} {
		if strings.Contains(out, secret) {
			t.Errorf("logs contain the %s:\n%s", name, out)
		}
	}
}
