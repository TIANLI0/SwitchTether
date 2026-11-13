//! Desktop TLS identity: a self-signed ECDSA P-256 certificate whose SHA-256
//! fingerprint is pinned by paired phones (delivered out-of-band in the QR code).
//!
//! The private key is kept in the [`SecretStore`] (OS keychain when available).
//! The certificate (public) is stored next to the remote database. If the key is
//! lost, a new identity is generated and every phone must re-pair — keys are
//! never exported or recovered.

use std::fs;
use std::path::{Path, PathBuf};

use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};

use crate::secret_store::SecretStore;
use crate::util::sha256_hex;

const KEY_SECRET_NAME: &str = "identity-key";
const CERT_FILE: &str = "identity.crt.der";

#[derive(Clone)]
pub struct ServerIdentity {
    pub cert_der: Vec<u8>,
    key_pkcs8_der: Vec<u8>,
    pub fingerprint: String,
}

impl std::fmt::Debug for ServerIdentity {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ServerIdentity")
            .field("fingerprint", &self.fingerprint)
            .finish_non_exhaustive()
    }
}

impl ServerIdentity {
    /// Generate a fresh identity (does not persist).
    pub fn generate(common_name: &str) -> anyhow::Result<Self> {
        let key_pair = rcgen::KeyPair::generate_for(&rcgen::PKCS_ECDSA_P256_SHA256)?;
        let mut params = rcgen::CertificateParams::new(vec!["switchtether.local".to_string()])?;
        let mut dn = rcgen::DistinguishedName::new();
        dn.push(rcgen::DnType::CommonName, common_name);
        dn.push(rcgen::DnType::OrganizationName, "SwitchTether");
        params.distinguished_name = dn;
        params.not_before = rcgen::date_time_ymd(2024, 1, 1);
        params.not_after = rcgen::date_time_ymd(2124, 1, 1);
        params.serial_number = Some(rcgen::SerialNumber::from_slice(
            &crate::util::random_bytes::<16>(),
        ));
        let cert = params.self_signed(&key_pair)?;
        let cert_der = cert.der().to_vec();
        let key_pkcs8_der = key_pair.serialize_der();
        Ok(Self {
            fingerprint: sha256_hex(&cert_der),
            cert_der,
            key_pkcs8_der,
        })
    }

    pub fn certificate(&self) -> CertificateDer<'static> {
        CertificateDer::from(self.cert_der.clone())
    }

    pub fn private_key(&self) -> PrivateKeyDer<'static> {
        PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(self.key_pkcs8_der.clone()))
    }

    /// Load the persisted identity or create (and persist) a new one.
    /// Returns `(identity, created)`.
    pub fn load_or_create(
        dir: &Path,
        store: &dyn SecretStore,
        common_name: &str,
    ) -> anyhow::Result<(Self, bool)> {
        let cert_path = dir.join(CERT_FILE);
        let key_b64 = store.get(KEY_SECRET_NAME)?;
        if let (Some(key_b64), Ok(cert_der)) = (key_b64, fs::read(&cert_path)) {
            use base64::Engine as _;
            if let Ok(key) = base64::engine::general_purpose::STANDARD.decode(key_b64.trim()) {
                if Self::key_matches_cert(&key, &cert_der) {
                    return Ok((
                        Self {
                            fingerprint: sha256_hex(&cert_der),
                            cert_der,
                            key_pkcs8_der: key,
                        },
                        false,
                    ));
                }
            }
            log::warn!("[remote] stored identity is inconsistent; generating a new one (phones must re-pair)");
        }
        let id = Self::generate(common_name)?;
        id.persist(dir, store)?;
        Ok((id, true))
    }

    pub fn persist(&self, dir: &Path, store: &dyn SecretStore) -> anyhow::Result<()> {
        use base64::Engine as _;
        fs::create_dir_all(dir)?;
        store.set(
            KEY_SECRET_NAME,
            &base64::engine::general_purpose::STANDARD.encode(&self.key_pkcs8_der),
        )?;
        crate::secret_store::write_private_file(&dir.join(CERT_FILE), &self.cert_der)?;
        Ok(())
    }

    /// Delete the persisted identity (used by "reset remote identity").
    pub fn destroy(dir: &Path, store: &dyn SecretStore) -> anyhow::Result<()> {
        store.delete(KEY_SECRET_NAME)?;
        let p: PathBuf = dir.join(CERT_FILE);
        if p.exists() {
            fs::remove_file(p)?;
        }
        Ok(())
    }

    fn key_matches_cert(key_pkcs8: &[u8], cert_der: &[u8]) -> bool {
        // Sign a probe with the key and verify it against the certificate's
        // public key using webpki via rustls' verifier helper.
        let Ok(kp) = ring::signature::EcdsaKeyPair::from_pkcs8(
            &ring::signature::ECDSA_P256_SHA256_ASN1_SIGNING,
            key_pkcs8,
            &ring::rand::SystemRandom::new(),
        ) else {
            return false;
        };
        let msg = b"switchtether-identity-probe";
        let Ok(sig) = kp.sign(&ring::rand::SystemRandom::new(), msg) else {
            return false;
        };
        let cert = CertificateDer::from(cert_der.to_vec());
        let Ok(ee) = webpki_end_entity(&cert) else {
            return false;
        };
        ee.verify_signature(
            webpki::ring::ECDSA_P256_SHA256,
            msg,
            sig.as_ref(),
        )
        .is_ok()
    }
}

fn webpki_end_entity<'a>(
    cert: &'a CertificateDer<'a>,
) -> Result<webpki::EndEntityCert<'a>, webpki::Error> {
    webpki::EndEntityCert::try_from(cert)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::secret_store::MemoryStore;

    #[test]
    fn generate_and_reload() {
        let tmp = tempfile::tempdir().unwrap();
        let store = MemoryStore::default();
        let (a, created) = ServerIdentity::load_or_create(tmp.path(), &store, "test").unwrap();
        assert!(created);
        assert_eq!(a.fingerprint.len(), 64);
        let (b, created) = ServerIdentity::load_or_create(tmp.path(), &store, "test").unwrap();
        assert!(!created);
        assert_eq!(a.fingerprint, b.fingerprint);
    }

    #[test]
    fn lost_key_forces_new_identity() {
        let tmp = tempfile::tempdir().unwrap();
        let store = MemoryStore::default();
        let (a, _) = ServerIdentity::load_or_create(tmp.path(), &store, "test").unwrap();
        store.delete(KEY_SECRET_NAME).unwrap();
        let (b, created) = ServerIdentity::load_or_create(tmp.path(), &store, "test").unwrap();
        assert!(created);
        assert_ne!(a.fingerprint, b.fingerprint);
    }

    #[test]
    fn mismatched_key_forces_new_identity() {
        let tmp = tempfile::tempdir().unwrap();
        let store = MemoryStore::default();
        let (a, _) = ServerIdentity::load_or_create(tmp.path(), &store, "test").unwrap();
        let other = ServerIdentity::generate("other").unwrap();
        use base64::Engine as _;
        store
            .set(
                KEY_SECRET_NAME,
                &base64::engine::general_purpose::STANDARD.encode(&other.key_pkcs8_der),
            )
            .unwrap();
        let (b, created) = ServerIdentity::load_or_create(tmp.path(), &store, "test").unwrap();
        assert!(created);
        assert_ne!(a.fingerprint, b.fingerprint);
    }
}
