package io.github.tianli0.switchtether.remote.stepup

/**
 * The exact bytes signed by the step-up key (docs/PROTOCOL.md §4):
 *
 * `"switchtether-stepup-v1\n" + deviceId + "\n" + challengeId + "\n" + nonce + "\n" + scope`
 *
 * UTF-8, no trailing newline. Signed with ECDSA P-256 / SHA-256 (ASN.1 DER signature).
 */
object StepUpPayload {
    const val DOMAIN = "switchtether-stepup-v1"

    fun build(deviceId: String, challengeId: String, nonce: String, scope: String): ByteArray {
        for ((name, v) in listOf("deviceId" to deviceId, "challengeId" to challengeId, "nonce" to nonce, "scope" to scope)) {
            require(v.isNotEmpty() && '\n' !in v && '\r' !in v) { "$name must be non-empty and single-line" }
        }
        return "$DOMAIN\n$deviceId\n$challengeId\n$nonce\n$scope".toByteArray(Charsets.UTF_8)
    }
}
