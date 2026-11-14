package relay

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"strings"
)

// Relay-key MAC roles (docs/RELAY.md §2.0).
const (
	RoleListen  = "listen"
	RoleConnect = "connect"
)

// KeySize is the length of a relay key in bytes.
const KeySize = 32

// ErrBadKey reports a relay key that is not 32 bytes of base64url. The message never
// contains the key itself.
var ErrBadKey = errors.New("relay key must be 32 bytes, base64url without padding (see `switchtether-relay genkey`)")

// GenerateKey returns a fresh relay key: 32 random bytes, base64url without padding.
func GenerateKey() (string, error) { return randomB64(KeySize) }

// ParseKey decodes a relay key. Surrounding whitespace is ignored.
func ParseKey(s string) ([]byte, error) {
	b, err := base64.RawURLEncoding.Strict().DecodeString(strings.TrimSpace(s))
	if err != nil || len(b) != KeySize {
		return nil, ErrBadKey
	}
	return b, nil
}

// MACMessage is the exact byte string a client MACs with the relay key:
// "switchtether-relay-v1 " + role + "\n" + nonce + "\n" + fp, where nonce is the base64url
// text from CHALLENGE.
func MACMessage(role, nonce, fp string) []byte {
	return []byte(AuthDomain + " " + role + "\n" + nonce + "\n" + fp)
}

// ComputeMAC returns base64url(HMAC-SHA256(key, MACMessage(role, nonce, fp))) without padding.
func ComputeMAC(key []byte, role, nonce, fp string) string {
	m := hmac.New(sha256.New, key)
	m.Write(MACMessage(role, nonce, fp))
	return base64.RawURLEncoding.EncodeToString(m.Sum(nil))
}

// keyRing is an immutable set of relay keys.
type keyRing struct {
	keys [][]byte
}

// verify reports whether mac (base64url, padding tolerated) matches any key. Every key is
// tried, so the time taken does not depend on which key (if any) matched. It returns the
// 1-based index of the matching key for logs (never the key).
func (r *keyRing) verify(role, nonce, fp, mac string) (int, bool) {
	got, err := decodeB64URL(mac)
	if err != nil || len(got) != sha256.Size || r == nil {
		return 0, false
	}
	msg := MACMessage(role, nonce, fp)
	match := 0
	for i, k := range r.keys {
		m := hmac.New(sha256.New, k)
		m.Write(msg)
		if hmac.Equal(m.Sum(nil), got) && match == 0 {
			match = i + 1
		}
	}
	return match, match != 0
}
