package relay

import (
	"crypto"
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"math/big"
	"os"
	"testing"
	"time"
)

func selfSigned(t *testing.T, key crypto.Signer) (der []byte, fp string) {
	t.Helper()
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(42),
		Subject:      pkix.Name{CommonName: "test desktop", Organization: []string{"SwitchTether"}},
		DNSNames:     []string{"switchtether.local"},
		NotBefore:    time.Date(2024, 1, 1, 0, 0, 0, 0, time.UTC),
		NotAfter:     time.Date(2124, 1, 1, 0, 0, 0, 0, time.UTC),
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, key.Public(), key)
	if err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(der)
	return der, hex.EncodeToString(sum[:])
}

func signAuth(t *testing.T, key *ecdsa.PrivateKey, nonce, fp string) []byte {
	t.Helper()
	d := sha256.Sum256(SignedMessage(nonce, fp))
	sig, err := ecdsa.SignASN1(rand.Reader, key, d[:])
	if err != nil {
		t.Fatal(err)
	}
	return sig
}

func TestSignedMessage(t *testing.T) {
	got := string(SignedMessage("bm9uY2U", testFP))
	want := "switchtether-relay-v1\nbm9uY2U\n" + testFP
	if got != want {
		t.Fatalf("SignedMessage = %q", got)
	}
}

func TestVerifyAuth(t *testing.T) {
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	der, fp := selfSigned(t, key)
	nonce, err := NewNonce()
	if err != nil {
		t.Fatal(err)
	}
	if raw, err := base64.RawURLEncoding.DecodeString(nonce); err != nil || len(raw) != 32 {
		t.Fatalf("nonce %q is not 32 bytes base64url", nonce)
	}
	sig := signAuth(t, key, nonce, fp)

	if err := VerifyAuth(fp, nonce, der, sig); err != nil {
		t.Fatalf("valid auth rejected: %v", err)
	}

	other, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	otherDER, otherFP := selfSigned(t, other)
	otherNonce, _ := NewNonce()

	cases := []struct {
		name string
		fp   string
		n    string
		der  []byte
		sig  []byte
		want error
	}{
		{"wrong nonce", fp, otherNonce, der, sig, ErrBadSignature},
		{"signed by another key", fp, nonce, der, signAuth(t, other, nonce, fp), ErrBadSignature},
		{"signature over another fp", fp, nonce, der, signAuth(t, key, nonce, otherFP), ErrBadSignature},
		{"cert of another desktop", fp, nonce, otherDER, signAuth(t, other, nonce, fp), ErrFingerprintMismatch},
		{"claims another fp", otherFP, nonce, der, signAuth(t, key, nonce, otherFP), ErrFingerprintMismatch},
		{"truncated signature", fp, nonce, der, sig[:len(sig)-1], ErrBadSignature},
		{"garbage signature", fp, nonce, der, []byte("not a signature"), ErrBadSignature},
	}
	for _, tc := range cases {
		if err := VerifyAuth(tc.fp, tc.n, tc.der, tc.sig); !errors.Is(err, tc.want) {
			t.Errorf("%s: err = %v, want %v", tc.name, err, tc.want)
		}
	}

	garbage := []byte("definitely not DER")
	sum := sha256.Sum256(garbage)
	if err := VerifyAuth(hex.EncodeToString(sum[:]), nonce, garbage, sig); !errors.Is(err, ErrBadCertificate) {
		t.Errorf("garbage cert: err = %v", err)
	}
}

func TestVerifyAuthRejectsOtherKeyTypes(t *testing.T) {
	nonce, _ := NewNonce()
	p384, _ := ecdsa.GenerateKey(elliptic.P384(), rand.Reader)
	rsaKey, _ := rsa.GenerateKey(rand.Reader, 2048)
	_, edKey, _ := ed25519.GenerateKey(rand.Reader)
	for name, key := range map[string]crypto.Signer{"P-384": p384, "RSA": rsaKey, "Ed25519": edKey} {
		der, fp := selfSigned(t, key)
		msg := SignedMessage(nonce, fp)
		var sig []byte
		var err error
		switch k := key.(type) {
		case ed25519.PrivateKey:
			sig = ed25519.Sign(k, msg)
		default:
			d := sha256.Sum256(msg)
			sig, err = k.Sign(rand.Reader, d[:], crypto.SHA256)
		}
		if err != nil {
			t.Fatal(err)
		}
		if err := VerifyAuth(fp, nonce, der, sig); !errors.Is(err, ErrNotP256) {
			t.Errorf("%s: err = %v, want ErrNotP256", name, err)
		}
	}
}

// TestAuthVector checks the published cross-implementation vector in testdata (public
// certificate and signature only; the throwaway private key was discarded).
func TestAuthVector(t *testing.T) {
	raw, err := os.ReadFile("testdata/auth_vector.json")
	if err != nil {
		t.Fatal(err)
	}
	var v struct {
		FP, Nonce, CertDER, Sig, SignedMessageHex string
	}
	if err := json.Unmarshal(raw, &v); err != nil {
		t.Fatal(err)
	}
	der, _ := decodeB64URL(v.CertDER)
	sig, _ := decodeB64URL(v.Sig)
	if got := hex.EncodeToString(SignedMessage(v.Nonce, v.FP)); got != v.SignedMessageHex {
		t.Fatalf("signed message mismatch:\n got %s\nwant %s", got, v.SignedMessageHex)
	}
	if err := VerifyAuth(v.FP, v.Nonce, der, sig); err != nil {
		t.Fatalf("vector rejected: %v", err)
	}
	if err := VerifyAuth(v.FP, v.Nonce+"x", der, sig); err == nil {
		t.Fatal("vector accepted with a modified nonce")
	}
}

func TestNewConnID(t *testing.T) {
	seen := map[string]bool{}
	for range 100 {
		id, err := NewConnID()
		if err != nil || !ValidConnID(id) || seen[id] {
			t.Fatalf("bad conn id %q (%v)", id, err)
		}
		seen[id] = true
	}
}
