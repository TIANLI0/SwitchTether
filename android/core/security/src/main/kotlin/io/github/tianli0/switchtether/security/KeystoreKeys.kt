package io.github.tianli0.switchtether.security

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.tianli0.switchtether.remote.tls.ClientIdentity
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.UUID
import javax.security.auth.x500.X500Principal

/** TLS client identity backed by a non-exportable Android Keystore key. */
class KeystoreClientIdentity(
    override val alias: String,
    override val privateKey: PrivateKey,
    override val certificate: X509Certificate,
) : ClientIdentity

/**
 * Android Keystore keys used by SwitchTether. Private keys never leave the Keystore.
 *
 * - **Identity** (`st-id-<uuid>`): P-256, PURPOSE_SIGN, digests SHA-256 and NONE. Its auto-generated
 *   self-signed certificate is the TLS client certificate; the desktop identifies the phone by the
 *   SHA-256 of that certificate. DIGEST_NONE is required because Conscrypt's TLS client-auth path
 *   asks the key to sign the already-hashed handshake transcript (`NONEwithECDSA`).
 *   A fresh identity is created for every new pairing and deleted on unpair.
 * - **Step-up** (`st-su-<uuid>`): P-256, SHA-256, `setUserAuthenticationRequired(true)`:
 *   API 30+: every signature needs BiometricPrompt (BIOMETRIC_STRONG or device credential);
 *   API 29: usable for [STEP_UP_VALIDITY_SECONDS] after a successful prompt.
 *   Only created when the device has a secure lock screen.
 */
class KeystoreKeys(private val context: Context) {
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }

    fun isDeviceSecure(): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    fun newIdentityAlias(): String = "$IDENTITY_PREFIX${UUID.randomUUID()}"

    fun newStepUpAlias(): String = "$STEP_UP_PREFIX${UUID.randomUUID()}"

    fun createIdentity(alias: String = newIdentityAlias()): KeystoreClientIdentity {
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
            .setCertificateSubject(X500Principal("CN=SwitchTether Android"))
            .setCertificateSerialNumber(BigInteger(63, SecureRandom()).add(BigInteger.ONE))
            .setCertificateNotBefore(Date(now - 24L * 3600 * 1000))
            .setCertificateNotAfter(Date(now + 20L * 365 * 24 * 3600 * 1000))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply {
            initialize(spec)
            generateKeyPair()
        }
        return loadIdentity(alias) ?: error("identity key was not persisted")
    }

    fun loadIdentity(alias: String): KeystoreClientIdentity? {
        val entry = runCatching { keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry }.getOrNull() ?: return null
        val cert = entry.certificate as? X509Certificate ?: return null
        return KeystoreClientIdentity(alias, entry.privateKey, cert)
    }

    /** Creates the step-up key, or returns null when there is no secure lock screen. */
    fun createStepUpKey(alias: String = newStepUpAlias()): String? {
        if (!isDeviceSecure()) return null
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(STEP_UP_VALIDITY_SECONDS)
        }
        return runCatching {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply {
                initialize(builder.build())
                generateKeyPair()
            }
            alias
        }.getOrNull()
    }

    /** Base64 (standard) SubjectPublicKeyInfo DER of the step-up key, sent as `stepUpPublicKey`. */
    fun stepUpPublicKeySpki(alias: String): String? {
        val cert = runCatching { keyStore.getCertificate(alias) }.getOrNull() ?: return null
        return Base64.encodeToString(cert.publicKey.encoded, Base64.NO_WRAP)
    }

    fun stepUpPrivateKey(alias: String): PrivateKey? =
        runCatching { keyStore.getKey(alias, null) as? PrivateKey }.getOrNull()

    fun exists(alias: String): Boolean = runCatching { keyStore.containsAlias(alias) }.getOrDefault(false)

    fun delete(alias: String?) {
        if (alias.isNullOrEmpty()) return
        runCatching { keyStore.deleteEntry(alias) }
    }

    /** Deletes every SwitchTether key except [keep] (cleans up after interrupted pairings). */
    fun deleteAllExcept(keep: Set<String>) {
        val aliases = runCatching { keyStore.aliases().toList() }.getOrDefault(emptyList())
        aliases.filter { (it.startsWith(IDENTITY_PREFIX) || it.startsWith(STEP_UP_PREFIX)) && it !in keep }.forEach(::delete)
    }

    /** Where the key lives (TEE / StrongBox / software), for the security info screen. */
    fun securityLevel(alias: String): String {
        val key = runCatching { keyStore.getKey(alias, null) as? PrivateKey }.getOrNull() ?: return "missing"
        return runCatching {
            val info = KeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE).getKeySpec(key, KeyInfo::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                when (info.securityLevel) {
                    KeyProperties.SECURITY_LEVEL_STRONGBOX -> "strongbox"
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "tee"
                    KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
                    else -> "unknown"
                }
            } else {
                @Suppress("DEPRECATION")
                if (info.isInsideSecureHardware) "tee" else "software"
            }
        }.getOrDefault("unknown")
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val IDENTITY_PREFIX = "st-id-"
        const val STEP_UP_PREFIX = "st-su-"
        const val STEP_UP_VALIDITY_SECONDS = 30
    }
}
