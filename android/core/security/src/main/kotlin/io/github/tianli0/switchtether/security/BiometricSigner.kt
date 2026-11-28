package io.github.tianli0.switchtether.security

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.Signature
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Why a step-up signature could not be produced. */
class StepUpSignException(val reason: Reason, message: String? = null, cause: Throwable? = null) : Exception(message ?: reason.name, cause) {
    enum class Reason { NO_KEY, KEY_INVALIDATED, CANCELED, FAILED }
}

/**
 * Signs a step-up payload (docs/PROTOCOL.md §4) with the Keystore step-up key after the user
 * authenticates with BiometricPrompt. The key requires per-use authentication, so the [Signature]
 * is wrapped in a [BiometricPrompt.CryptoObject]; BIOMETRIC_STRONG or device credential are allowed.
 */
class BiometricSigner(private val keys: KeystoreKeys) {
    suspend fun sign(activity: FragmentActivity, alias: String, payload: ByteArray, title: String, subtitle: String): ByteArray {
        val key = keys.stepUpPrivateKey(alias) ?: throw StepUpSignException(StepUpSignException.Reason.NO_KEY)
        val signature = try {
            Signature.getInstance("SHA256withECDSA").apply { initSign(key) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw StepUpSignException(StepUpSignException.Reason.KEY_INVALIDATED, cause = e)
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .setConfirmationRequired(true)
            .build()
        val result = authenticate(activity, info, BiometricPrompt.CryptoObject(signature))
        val s = result.cryptoObject?.signature ?: throw StepUpSignException(StepUpSignException.Reason.FAILED, "no crypto object")
        return withContext(Dispatchers.IO) {
            s.update(payload)
            s.sign()
        }
    }

    private suspend fun authenticate(
        activity: FragmentActivity,
        info: BiometricPrompt.PromptInfo,
        crypto: BiometricPrompt.CryptoObject?,
    ): BiometricPrompt.AuthenticationResult = suspendCancellableCoroutine { cont ->
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (cont.isActive) cont.resume(result)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (!cont.isActive) return
                    val reason = when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_CANCELED ->
                            StepUpSignException.Reason.CANCELED
                        else -> StepUpSignException.Reason.FAILED
                    }
                    cont.resumeWithException(StepUpSignException(reason, errString.toString()))
                }
                // onAuthenticationFailed = one bad attempt; the prompt stays open, nothing to do.
            },
        )
        if (crypto != null) prompt.authenticate(info, crypto) else prompt.authenticate(info)
        cont.invokeOnCancellation { prompt.cancelAuthentication() }
    }
}
