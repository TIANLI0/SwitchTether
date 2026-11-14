package relay

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/x509"
	"encoding/asn1"
	"encoding/base64"
	"encoding/hex"
	"errors"
)

// AuthDomain is the domain-separation prefix of the signed message (docs/RELAY.md §2.1).
const AuthDomain = "switchtether-relay-v1"

// NewNonce returns 32 random bytes, base64url without padding, as sent in CHALLENGE.
func NewNonce() (string, error) { return randomB64(32) }

// NewConnID returns 16 random bytes, base64url without padding.
func NewConnID() (string, error) { return randomB64(16) }

func randomB64(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}

// SignedMessage is the exact byte string the desktop signs:
// "switchtether-relay-v1\n" + nonce + "\n" + fp, where nonce is the base64url text from the
// CHALLENGE line (not the decoded bytes) and fp is the lowercase hex fingerprint.
func SignedMessage(nonce, fp string) []byte {
	return []byte(AuthDomain + "\n" + nonce + "\n" + fp)
}

// Authentication failures. They are all reported to the peer as `ERR auth_failed`; the
// distinction only exists for tests and debug logs.
var (
	ErrFingerprintMismatch = errors.New("certificate does not match fingerprint")
	ErrBadCertificate      = errors.New("certificate cannot be parsed")
	ErrNotP256             = errors.New("certificate key is not ECDSA P-256")
	ErrBadSignature        = errors.New("signature verification failed")
)

// VerifyAuth checks that sha256(certDER) == fp, that the certificate key is ECDSA P-256
// and that sig is a valid ASN.1 ECDSA/SHA-256 signature over SignedMessage(nonce, fp).
//
// Only the SubjectPublicKeyInfo is taken from the certificate: the relay does not care
// about validity periods, extensions or the self-signature, and must not reject a desktop
// identity over X.509 details it never relies on (the fingerprint is the identity).
func VerifyAuth(fp, nonce string, certDER, sig []byte) error {
	sum := sha256.Sum256(certDER)
	got := make([]byte, hex.EncodedLen(len(sum)))
	hex.Encode(got, sum[:])
	if subtle.ConstantTimeCompare(got, []byte(fp)) != 1 {
		return ErrFingerprintMismatch
	}
	pub, err := certPublicKey(certDER)
	if err != nil {
		return ErrBadCertificate
	}
	ec, ok := pub.(*ecdsa.PublicKey)
	if !ok || ec.Curve != elliptic.P256() {
		return ErrNotP256
	}
	digest := sha256.Sum256(SignedMessage(nonce, fp))
	if !ecdsa.VerifyASN1(ec, digest[:], sig) {
		return ErrBadSignature
	}
	return nil
}

// certPublicKey extracts and parses the SubjectPublicKeyInfo of an X.509 certificate with a
// minimal ASN.1 walk (RFC 5280 section 4.1):
//
//	Certificate ::= SEQUENCE { tbsCertificate, signatureAlgorithm, signatureValue }
//	TBSCertificate ::= SEQUENCE { [0] version OPTIONAL, serialNumber, signature, issuer,
//	                              validity, subject, subjectPublicKeyInfo, ... }
func certPublicKey(der []byte) (any, error) {
	var cert struct {
		TBS    asn1.RawValue
		SigAlg asn1.RawValue
		Sig    asn1.BitString
	}
	rest, err := asn1.Unmarshal(der, &cert)
	if err != nil || len(rest) != 0 || cert.TBS.Class != asn1.ClassUniversal || cert.TBS.Tag != asn1.TagSequence {
		return nil, ErrBadCertificate
	}
	body := cert.TBS.Bytes
	pos := 0 // positional element index, not counting the optional [0] version
	first := true
	for len(body) > 0 {
		var v asn1.RawValue
		rest, err := asn1.Unmarshal(body, &v)
		if err != nil {
			return nil, ErrBadCertificate
		}
		body = rest
		if first && v.Class == asn1.ClassContextSpecific && v.Tag == 0 {
			first = false
			continue
		}
		first = false
		if pos == 5 {
			return x509.ParsePKIXPublicKey(v.FullBytes)
		}
		pos++
	}
	return nil, ErrBadCertificate
}
