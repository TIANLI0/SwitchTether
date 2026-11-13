//! TLS configuration: TLS 1.3 only, mandatory client certificates.
//!
//! Client certificates are self-signed (generated in the Android Keystore), so
//! they cannot be validated against a CA. Instead:
//!
//! 1. During the handshake rustls verifies the client's CertificateVerify
//!    signature against the presented certificate (proof of private-key
//!    possession). Certificates of revoked devices are rejected right here.
//! 2. After the handshake, the accept loop computes SHA-256(client cert DER)
//!    and the API layer authorizes requests by looking that fingerprint up in
//!    the device registry. Unknown fingerprints can only reach pairing endpoints.

use std::collections::HashSet;
use std::sync::Arc;

use parking_lot::RwLock;
use rustls::client::danger::HandshakeSignatureValid;
use rustls::crypto::{CryptoProvider, WebPkiSupportedAlgorithms};
use rustls::pki_types::{CertificateDer, UnixTime};
use rustls::server::danger::{ClientCertVerified, ClientCertVerifier};
use rustls::{CertificateError, DigitallySignedStruct, DistinguishedName, Error, SignatureScheme};

use crate::identity::ServerIdentity;
use crate::util::sha256_hex;

/// Fingerprints that must be rejected during the handshake.
pub type RevokedSet = Arc<RwLock<HashSet<String>>>;

pub fn provider() -> Arc<CryptoProvider> {
    Arc::new(rustls::crypto::ring::default_provider())
}

#[derive(Debug)]
pub struct PinnedRegistryClientVerifier {
    algs: WebPkiSupportedAlgorithms,
    revoked: RevokedSet,
}

impl PinnedRegistryClientVerifier {
    pub fn new(provider: &CryptoProvider, revoked: RevokedSet) -> Self {
        Self {
            algs: provider.signature_verification_algorithms,
            revoked,
        }
    }
}

impl ClientCertVerifier for PinnedRegistryClientVerifier {
    fn offer_client_auth(&self) -> bool {
        true
    }

    fn client_auth_mandatory(&self) -> bool {
        true
    }

    fn root_hint_subjects(&self) -> &[DistinguishedName] {
        &[]
    }

    fn verify_client_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _now: UnixTime,
    ) -> Result<ClientCertVerified, Error> {
        // Must at least be a parseable X.509 end-entity certificate.
        webpki::EndEntityCert::try_from(end_entity)
            .map_err(|_| Error::InvalidCertificate(CertificateError::BadEncoding))?;
        let fp = sha256_hex(end_entity.as_ref());
        if self.revoked.read().contains(&fp) {
            return Err(Error::InvalidCertificate(CertificateError::Revoked));
        }
        Ok(ClientCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, Error> {
        // TLS 1.2 is disabled in the server config; refuse defensively.
        Err(Error::PeerIncompatible(
            rustls::PeerIncompatible::Tls12NotOffered,
        ))
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, Error> {
        rustls::crypto::verify_tls13_signature(message, cert, dss, &self.algs)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.algs.supported_schemes()
    }
}

pub fn server_config(
    identity: &ServerIdentity,
    revoked: RevokedSet,
) -> anyhow::Result<Arc<rustls::ServerConfig>> {
    let provider = provider();
    let verifier = Arc::new(PinnedRegistryClientVerifier::new(&provider, revoked));
    let mut cfg = rustls::ServerConfig::builder_with_provider(provider)
        .with_protocol_versions(&[&rustls::version::TLS13])?
        .with_client_cert_verifier(verifier)
        .with_single_cert(vec![identity.certificate()], identity.private_key())?;
    cfg.alpn_protocols = vec![b"http/1.1".to_vec()];
    // No session resumption tickets: every connection re-proves the client key.
    cfg.send_tls13_tickets = 0;
    cfg.session_storage = Arc::new(rustls::server::NoServerSessionStorage {});
    Ok(Arc::new(cfg))
}

/// Client-side helpers (used by integration tests and by the desktop's own
/// self-check). The Android app implements the same policy with OkHttp.
pub mod client {
    use super::*;
    use rustls::client::danger::{ServerCertVerified, ServerCertVerifier};
    use rustls::pki_types::ServerName;

    /// Accepts exactly one server certificate (by SHA-256 of its DER).
    #[derive(Debug)]
    pub struct PinnedServerVerifier {
        pub fingerprint: String,
        algs: WebPkiSupportedAlgorithms,
    }

    impl PinnedServerVerifier {
        pub fn new(fingerprint: impl Into<String>) -> Self {
            Self {
                fingerprint: fingerprint.into(),
                algs: provider().signature_verification_algorithms,
            }
        }
    }

    impl ServerCertVerifier for PinnedServerVerifier {
        fn verify_server_cert(
            &self,
            end_entity: &CertificateDer<'_>,
            _intermediates: &[CertificateDer<'_>],
            _server_name: &ServerName<'_>,
            _ocsp: &[u8],
            _now: UnixTime,
        ) -> Result<ServerCertVerified, Error> {
            if crate::util::ct_eq(&sha256_hex(end_entity.as_ref()), &self.fingerprint) {
                Ok(ServerCertVerified::assertion())
            } else {
                Err(Error::InvalidCertificate(
                    CertificateError::ApplicationVerificationFailure,
                ))
            }
        }
        fn verify_tls12_signature(
            &self,
            _m: &[u8],
            _c: &CertificateDer<'_>,
            _d: &DigitallySignedStruct,
        ) -> Result<HandshakeSignatureValid, Error> {
            Err(Error::PeerIncompatible(
                rustls::PeerIncompatible::Tls12NotOffered,
            ))
        }
        fn verify_tls13_signature(
            &self,
            message: &[u8],
            cert: &CertificateDer<'_>,
            dss: &DigitallySignedStruct,
        ) -> Result<HandshakeSignatureValid, Error> {
            rustls::crypto::verify_tls13_signature(message, cert, dss, &self.algs)
        }
        fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
            self.algs.supported_schemes()
        }
    }

    /// Build a TLS 1.3 client config with a client identity and a pinned server.
    pub fn client_config(
        client_identity: &ServerIdentity,
        server_fingerprint: &str,
    ) -> anyhow::Result<Arc<rustls::ClientConfig>> {
        let mut cfg = rustls::ClientConfig::builder_with_provider(provider())
            .with_protocol_versions(&[&rustls::version::TLS13])?
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(PinnedServerVerifier::new(
                server_fingerprint,
            )))
            .with_client_auth_cert(
                vec![client_identity.certificate()],
                client_identity.private_key(),
            )?;
        cfg.alpn_protocols = vec![b"http/1.1".to_vec()];
        Ok(Arc::new(cfg))
    }
}
