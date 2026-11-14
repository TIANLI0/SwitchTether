package relay_test

// Relay keys (docs/RELAY.md §2.0): only clients holding a configured key may use the relay.

import (
	"crypto/elliptic"
	"errors"
	"net"
	"strings"
	"testing"
	"time"

	"github.com/TIANLI0/SwitchTether/relay/internal/relay"
)

func keyLine(key []byte, role, fp string) func(string) string {
	return func(nonce string) string { return "KEY " + relay.ComputeMAC(key, role, nonce, fp) }
}

// expectNoIncoming fails if the desktop was told about any phone.
func expectNoIncoming(t *testing.T, d *fakeDesktop) {
	t.Helper()
	select {
	case id := <-d.incoming:
		t.Fatalf("desktop received INCOMING %s for an unauthenticated phone", id[:4])
	case <-time.After(200 * time.Millisecond):
	}
}

func TestPhoneRelayKey(t *testing.T) {
	r := startRelay(t, fastConfig())
	d := newDesktop(t, r, tcpT).start(tcpT)
	other := newIdentity(t, elliptic.P256(), "other")

	cases := map[string]func(string) string{
		"missing MAC":          func(string) string { return "KEY" },
		"empty MAC":            func(string) string { return "KEY " },
		"unknown key":          keyLine(newKey(), relay.RoleConnect, d.id.fp),
		"listen role":          keyLine(testKey, relay.RoleListen, d.id.fp),
		"MAC for another fp":   keyLine(testKey, relay.RoleConnect, other.fp),
		"MAC over other nonce": func(string) string { return keyLine(testKey, relay.RoleConnect, d.id.fp)("bm90LXRoZS1ub25jZQ") },
		"the key itself":       func(string) string { return "KEY " + b64(testKey) },
		"malformed MAC":        func(string) string { return "KEY m/a/c" },
	}
	for name, kl := range cases {
		for _, tr := range []string{tcpT, wsT} {
			c, ans := connectWith(t, r, tr, d.id.fp, d.token, kl)
			if ans != "ERR auth_failed" {
				t.Errorf("%s over %s: answered %q", name, tr, ans)
				continue
			}
			expectClosed(t, c, 5*time.Second)
		}
	}
	// Never announced to the desktop, even with the right desktop token.
	expectNoIncoming(t, d)

	// Something other than KEY after the challenge is a protocol error.
	if _, ans := connectWith(t, r, tcpT, d.id.fp, d.token, func(string) string { return "AUTH x y z" }); ans != "ERR bad_request expected KEY <mac>" {
		t.Errorf("AUTH instead of KEY: %q", ans)
	}

	// Without the key nobody learns whether a desktop is online.
	if _, ans := connectWith(t, r, tcpT, other.fp, "tok", keyLine(newKey(), relay.RoleConnect, other.fp)); ans != "ERR auth_failed" {
		t.Errorf("unknown desktop, wrong key: %q", ans)
	}
	if _, ans := connect(t, r, tcpT, other.fp, "tok"); ans != "ERR offline" {
		t.Errorf("unknown desktop, right key: %q", ans)
	}

	// And the right key still works.
	if _, ans := connect(t, r, tcpT, d.id.fp, "wrong-token"); ans != "ERR rejected" {
		t.Errorf("right key, wrong desktop token: %q", ans)
	}
	select {
	case <-d.incoming:
	case <-time.After(5 * time.Second):
		t.Fatal("authenticated phone was not announced")
	}
}

func TestPhoneSilentAfterChallenge(t *testing.T) {
	cfg := fastConfig()
	cfg.HandshakeTimeout = 300 * time.Millisecond
	r := startRelay(t, cfg)
	d := newDesktop(t, r, tcpT).start(tcpT)
	c := r.dial(t, tcpT)
	c.send(t, "ST-RELAY/1 CONNECT "+d.id.fp+" "+d.token)
	if line, _ := c.readLine(); !strings.HasPrefix(line, "CHALLENGE ") {
		t.Fatalf("got %q", line)
	}
	expectClosed(t, c, 5*time.Second)
	expectNoIncoming(t, d)
}

func TestKeyRotation(t *testing.T) {
	keyA, keyB := newKey(), newKey()
	cfg := fastConfig()
	cfg.Keys = [][]byte{keyA, keyB}
	r := startRelay(t, cfg)

	// Desktop with key A, phone with key B: both accepted.
	d := newDesktop(t, r, tcpT)
	d.key = keyA
	d.handle = func(c net.Conn) { _, _ = c.Read(make([]byte, 1)) }
	d.start(wsT)
	if _, ans := connectWith(t, r, tcpT, d.id.fp, d.token, keyLine(keyB, relay.RoleConnect, d.id.fp)); ans != "OK" {
		t.Fatalf("phone with key B: %q", ans)
	}

	// Retire key A.
	if err := r.srv.SetKeys([][]byte{keyB}); err != nil {
		t.Fatal(err)
	}
	if err := r.srv.SetKeys(nil); !errors.Is(err, relay.ErrNoKeys) {
		t.Fatalf("SetKeys(nil): %v", err)
	}
	d2 := newDesktop(t, r, tcpT)
	d2.key = keyA
	if _, ans := d2.listen(tcpT); ans != "ERR auth_failed" {
		t.Fatalf("new desktop with retired key A: %q", ans)
	}
	d2.key = keyB
	if _, ans := d2.listen(tcpT); ans != "OK" {
		t.Fatalf("new desktop with key B: %q", ans)
	}
	if _, ans := connectWith(t, r, tcpT, d.id.fp, d.token, keyLine(keyA, relay.RoleConnect, d.id.fp)); ans != "ERR auth_failed" {
		t.Fatalf("phone with retired key A: %q", ans)
	}
	// An already authenticated control connection is not cut by a key change.
	if _, ans := connectWith(t, r, wsT, d.id.fp, d.token, keyLine(keyB, relay.RoleConnect, d.id.fp)); ans != "OK" {
		t.Fatalf("phone with key B after rotation: %q", ans)
	}
}

func TestInsecureOpen(t *testing.T) {
	cfg := fastConfig()
	cfg.Keys = nil
	if _, err := relay.NewServer(cfg, nil); !errors.Is(err, relay.ErrNoKeys) {
		t.Fatalf("relay without keys started: %v", err)
	}
	cfg.InsecureOpen = true
	r := startRelay(t, cfg)

	// Any syntactically valid MAC is accepted ...
	d := newDesktop(t, r, tcpT)
	d.key = newKey()
	d.handle = func(c net.Conn) { _, _ = c.Read(make([]byte, 1)) }
	d.start(tcpT)
	if _, ans := connectWith(t, r, wsT, d.id.fp, d.token, func(string) string { return "KEY anything" }); ans != "OK" {
		t.Fatalf("insecure open, arbitrary MAC: %q", ans)
	}
	// ... but the field is still required.
	if _, ans := connectWith(t, r, tcpT, d.id.fp, d.token, func(string) string { return "KEY" }); ans != "ERR auth_failed" {
		t.Fatalf("insecure open, missing MAC: %q", ans)
	}
	c := r.dial(t, tcpT)
	c.send(t, "ST-RELAY/1 LISTEN "+d.id.fp)
	line, _ := c.readLine()
	nonce := strings.TrimPrefix(line, "CHALLENGE ")
	c.send(t, "AUTH "+b64(d.id.der)+" "+b64(d.id.sign(t, nonce, d.id.fp)))
	c.expect(t, "ERR auth_failed")
	// The desktop signature is still checked.
	other := newIdentity(t, elliptic.P256(), "other")
	c = r.dial(t, tcpT)
	c.send(t, "ST-RELAY/1 LISTEN "+d.id.fp)
	line, _ = c.readLine()
	nonce = strings.TrimPrefix(line, "CHALLENGE ")
	c.send(t, "AUTH "+b64(d.id.der)+" "+b64(other.sign(t, nonce, d.id.fp))+" anything")
	c.expect(t, "ERR auth_failed")
}
