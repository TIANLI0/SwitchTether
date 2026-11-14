package io.github.tianli0.switchtether.remote.pairing

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * A parsed pairing link (docs/PROTOCOL.md §3):
 *
 * `switchtether://pair?v=1&h=192.168.1.20,100.88.1.7&p=47615&fp=<sha256>&id=<pairingId>&s=<secret>&n=<name>`
 * plus, when the desktop's relay is on (docs/RELAY.md §5), `&r=<relay url>&rt=<relay token>`.
 *
 * The secret is single-use and short-lived; it must never be persisted.
 */
data class PairingLink(
    val version: Int,
    val hosts: List<String>,
    val port: Int,
    /** Lowercase hex SHA-256 of the desktop's leaf certificate DER (the TLS pin). */
    val fingerprint: String,
    val pairingId: String,
    val secret: String,
    val desktopName: String,
    /** Relay URL (`tcp://host:port`, `wss://host/v1/tunnel`), null when the desktop has no relay. */
    val relayUrl: String? = null,
    /** Relay access token checked by the desktop (not secret from the relay operator, see RELAY.md §6). */
    val relayToken: String? = null,
    /** Relay key (`rk`, base64url of 32 bytes): proves to the relay that this phone may use it. Secret. */
    val relayKey: String? = null,
) {
    override fun toString(): String =
        "PairingLink(v=$version, hosts=$hosts, port=$port, fp=${fingerprint.take(16)}…, id=$pairingId, secret=<redacted>, name=$desktopName, relay=${relayUrl ?: "none"})"

    companion object {
        const val SCHEME = "switchtether"
        const val SUPPORTED_VERSION = 1
        const val DEFAULT_PORT = 47615
        const val MAX_HOSTS = 8
        private val PLACEHOLDER_KEY = "A".repeat(43)

        private val HEX64 = Regex("^[0-9a-f]{64}$")
        private val PAIRING_ID = Regex("^[A-Za-z0-9._-]{1,128}$")
        private val BASE64URL = Regex("^[A-Za-z0-9_-]+$")

        // Hostname (incl. MagicDNS), IPv4, or IPv6 literal (optionally with a zone id).
        private val HOST = Regex("^[A-Za-z0-9.:%_-]{1,253}$")

        /** Parses and validates a pairing link. Throws [PairingLinkException] with a stable reason. */
        fun parse(input: String): PairingLink {
            val text = input.trim()
            val schemeEnd = text.indexOf("://")
            if (schemeEnd <= 0) throw PairingLinkException(Reason.NOT_A_PAIRING_LINK)
            val scheme = text.substring(0, schemeEnd)
            if (!scheme.equals(SCHEME, ignoreCase = true)) throw PairingLinkException(Reason.NOT_A_PAIRING_LINK)
            val rest = text.substring(schemeEnd + 3)
            val qIndex = rest.indexOf('?')
            val authority = (if (qIndex >= 0) rest.substring(0, qIndex) else rest).trimEnd('/')
            if (!authority.equals("pair", ignoreCase = true)) throw PairingLinkException(Reason.NOT_A_PAIRING_LINK)
            if (qIndex < 0) throw PairingLinkException(Reason.MISSING_FIELD, "v")
            val query = rest.substring(qIndex + 1).substringBefore('#')

            val params = LinkedHashMap<String, String>()
            for (part in query.split('&')) {
                if (part.isEmpty()) continue
                val eq = part.indexOf('=')
                val key = decode(if (eq >= 0) part.substring(0, eq) else part)
                val value = if (eq >= 0) decode(part.substring(eq + 1)) else ""
                if (params.containsKey(key)) throw PairingLinkException(Reason.DUPLICATE_FIELD, key)
                params[key] = value
            }

            val version = params["v"]?.toIntOrNull() ?: throw PairingLinkException(Reason.MISSING_FIELD, "v")
            if (version != SUPPORTED_VERSION) throw PairingLinkException(Reason.UNSUPPORTED_VERSION, version.toString())

            val hosts = (params["h"] ?: throw PairingLinkException(Reason.MISSING_FIELD, "h"))
                .split(',')
                .map { it.trim().removePrefix("[").removeSuffix("]") }
                .filter { it.isNotEmpty() }
                .distinct()
            if (hosts.isEmpty()) throw PairingLinkException(Reason.MISSING_FIELD, "h")
            if (hosts.size > MAX_HOSTS) throw PairingLinkException(Reason.INVALID_FIELD, "h")
            if (hosts.any { !HOST.matches(it) || it.startsWith('-') }) throw PairingLinkException(Reason.INVALID_FIELD, "h")

            val port = params["p"]?.let { it.toIntOrNull() ?: throw PairingLinkException(Reason.INVALID_FIELD, "p") }
                ?: DEFAULT_PORT
            if (port !in 1..65535) throw PairingLinkException(Reason.INVALID_FIELD, "p")

            val fp = (params["fp"] ?: throw PairingLinkException(Reason.MISSING_FIELD, "fp"))
                .replace(":", "").replace(" ", "").lowercase()
            if (!HEX64.matches(fp)) throw PairingLinkException(Reason.INVALID_FIELD, "fp")

            val id = params["id"] ?: throw PairingLinkException(Reason.MISSING_FIELD, "id")
            if (!PAIRING_ID.matches(id)) throw PairingLinkException(Reason.INVALID_FIELD, "id")

            val secret = params["s"] ?: throw PairingLinkException(Reason.MISSING_FIELD, "s")
            val secretBytes = runCatching {
                require(BASE64URL.matches(secret))
                Base64.getUrlDecoder().decode(secret)
            }.getOrNull()
            if (secretBytes == null || secretBytes.size != 32) throw PairingLinkException(Reason.INVALID_FIELD, "s")

            val name = sanitizeName(params["n"].orEmpty()).ifEmpty { hosts.first() }

            val relayUrl = params["r"]?.trim()?.takeIf { it.isNotEmpty() }
            val relayToken = params["rt"]?.trim()?.takeIf { it.isNotEmpty() }
            val relayKey = params["rk"]?.trim()?.takeIf { it.isNotEmpty() }
            if (relayKey != null && io.github.tianli0.switchtether.remote.relay.RelayConfig.decodeKey(relayKey) == null) {
                throw PairingLinkException(Reason.INVALID_FIELD, "rk")
            }
            // Validate the URL / token shape with a placeholder key (the relay is only used with a real one).
            if (relayUrl != null && io.github.tianli0.switchtether.remote.relay.RelayConfig.parseOrNull(relayUrl, relayToken, PLACEHOLDER_KEY) == null) {
                throw PairingLinkException(Reason.INVALID_FIELD, "r")
            }

            return PairingLink(
                version, hosts, port, fp, id, secret, name, relayUrl,
                if (relayUrl != null) relayToken else null,
                if (relayUrl != null) relayKey else null,
            )
        }

        fun parseOrNull(input: String): PairingLink? = runCatching { parse(input) }.getOrNull()

        private fun decode(s: String): String = try {
            URLDecoder.decode(s, StandardCharsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            throw PairingLinkException(Reason.INVALID_ENCODING)
        }

        /** Desktop names are display-only; strip control/bidi characters and cap the length. */
        fun sanitizeName(raw: String): String = raw
            .filter { !it.isISOControl() && Character.getType(it) != Character.FORMAT.toInt() }
            .trim()
            .take(64)
    }

    enum class Reason { NOT_A_PAIRING_LINK, MISSING_FIELD, DUPLICATE_FIELD, INVALID_FIELD, INVALID_ENCODING, UNSUPPORTED_VERSION }
}

class PairingLinkException(val reason: PairingLink.Reason, val field: String? = null) :
    IllegalArgumentException("invalid pairing link: $reason${field?.let { " ($it)" } ?: ""}")
