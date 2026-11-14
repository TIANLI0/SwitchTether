package relay

import (
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"strings"
	"testing"
)

func TestGenerateAndParseKey(t *testing.T) {
	seen := map[string]bool{}
	for range 50 {
		k, err := GenerateKey()
		if err != nil {
			t.Fatal(err)
		}
		if len(k) != 43 || strings.ContainsAny(k, "=+/") || seen[k] {
			t.Fatalf("bad key %q", k)
		}
		seen[k] = true
		b, err := ParseKey("  " + k + "\n")
		if err != nil || len(b) != KeySize {
			t.Fatalf("ParseKey(%q) = %d bytes, %v", k, len(b), err)
		}
	}
	k, _ := GenerateKey()
	for _, bad := range []string{"", "short", k[:42], k + "A", k + "=", strings.Replace(k, k[:1], "+", 1),
		base64.RawURLEncoding.EncodeToString(make([]byte, 31)), base64.RawURLEncoding.EncodeToString(make([]byte, 33))} {
		if _, err := ParseKey(bad); !errors.Is(err, ErrBadKey) {
			t.Errorf("ParseKey(%q) err = %v", bad, err)
		}
	}
	if strings.Contains(ErrBadKey.Error(), k) {
		t.Fatal("error message contains a key")
	}
}

func TestKeyRingVerify(t *testing.T) {
	k1, _ := ParseKey(mustKey(t))
	k2, _ := ParseKey(mustKey(t))
	k3, _ := ParseKey(mustKey(t))
	nonce, _ := NewNonce()
	ring := &keyRing{keys: [][]byte{k1, k2}}

	if i, ok := ring.verify(RoleListen, nonce, testFP, ComputeMAC(k1, RoleListen, nonce, testFP)); !ok || i != 1 {
		t.Fatalf("key 1: %d %v", i, ok)
	}
	if i, ok := ring.verify(RoleConnect, nonce, testFP, ComputeMAC(k2, RoleConnect, nonce, testFP)); !ok || i != 2 {
		t.Fatalf("key 2: %d %v", i, ok)
	}
	good := ComputeMAC(k1, RoleListen, nonce, testFP)
	otherNonce, _ := NewNonce()
	for name, mac := range map[string]string{
		"unknown key":   ComputeMAC(k3, RoleListen, nonce, testFP),
		"wrong role":    ComputeMAC(k1, RoleConnect, nonce, testFP),
		"wrong nonce":   ComputeMAC(k1, RoleListen, otherNonce, testFP),
		"wrong fp":      ComputeMAC(k1, RoleListen, nonce, strings.Repeat("f", 64)),
		"truncated":     good[:42],
		"garbage":       "not!base64",
		"empty":         "",
		"the key":       base64.RawURLEncoding.EncodeToString(k1),
		"flipped char":  flipLast(good),
		"padded+junk":   good + "=x",
		"longer by one": good + "A",
	} {
		if _, ok := ring.verify(RoleListen, nonce, testFP, mac); ok {
			t.Errorf("%s accepted", name)
		}
	}
	if _, ok := ring.verify(RoleListen, nonce, testFP, good+"="); !ok {
		t.Error("padded MAC rejected")
	}
	var empty *keyRing
	if _, ok := empty.verify(RoleListen, nonce, testFP, good); ok {
		t.Error("nil ring accepted a MAC")
	}
}

func flipLast(s string) string {
	b := []byte(s)
	if b[len(b)-1] == 'A' {
		b[len(b)-1] = 'B'
	} else {
		b[len(b)-1] = 'A'
	}
	return string(b)
}

func mustKey(t *testing.T) string {
	t.Helper()
	k, err := GenerateKey()
	if err != nil {
		t.Fatal(err)
	}
	return k
}

// TestRelayKeyVector checks the published cross-implementation vector (a throwaway test key).
func TestRelayKeyVector(t *testing.T) {
	raw, err := os.ReadFile("testdata/relay_key_vector.json")
	if err != nil {
		t.Fatal(err)
	}
	var v struct {
		Key, Nonce, FP string
		Cases          []struct{ Role, MessageHex, MAC string }
	}
	if err := json.Unmarshal(raw, &v); err != nil {
		t.Fatal(err)
	}
	key, err := ParseKey(v.Key)
	if err != nil {
		t.Fatal(err)
	}
	if len(v.Cases) != 2 {
		t.Fatalf("%d cases", len(v.Cases))
	}
	ring := &keyRing{keys: [][]byte{key}}
	for _, c := range v.Cases {
		if got := hex.EncodeToString(MACMessage(c.Role, v.Nonce, v.FP)); got != c.MessageHex {
			t.Errorf("%s: message mismatch:\n got %s\nwant %s", c.Role, got, c.MessageHex)
		}
		if got := ComputeMAC(key, c.Role, v.Nonce, v.FP); got != c.MAC {
			t.Errorf("%s: MAC %s, want %s", c.Role, got, c.MAC)
		}
		if _, ok := ring.verify(c.Role, v.Nonce, v.FP, c.MAC); !ok {
			t.Errorf("%s: vector MAC rejected", c.Role)
		}
	}
}

func TestConfigRequiresKeys(t *testing.T) {
	c := DefaultConfig()
	if err := c.Validate(); !errors.Is(err, ErrNoKeys) {
		t.Fatalf("no keys: %v", err)
	}
	if _, err := NewServer(c, nil); !errors.Is(err, ErrNoKeys) {
		t.Fatalf("NewServer without keys: %v", err)
	}
	c.InsecureOpen = true
	if err := c.Validate(); err != nil {
		t.Fatalf("insecure open: %v", err)
	}
	c.InsecureOpen = false
	c.Keys = [][]byte{make([]byte, 31)}
	if err := c.Validate(); !errors.Is(err, ErrBadKey) {
		t.Fatalf("short key: %v", err)
	}
	c.Keys = [][]byte{make([]byte, 32)}
	s, err := NewServer(c, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.SetKeys(nil); !errors.Is(err, ErrNoKeys) {
		t.Fatalf("SetKeys(nil) on a keyed relay: %v", err)
	}
}
