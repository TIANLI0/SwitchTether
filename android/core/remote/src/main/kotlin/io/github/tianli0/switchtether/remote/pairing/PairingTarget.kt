package io.github.tianli0.switchtether.remote.pairing

/**
 * What the phone pairs with (docs/PROTOCOL.md §3):
 *
 * - [Link]: QR code / pairing link with the pinned fingerprint and a one-time secret.
 * - [Code]: the 6-digit pairing code shown on the desktop, for a computer found on the LAN
 *   ("附近的电脑", fingerprint from its advertisement) or typed by address (fingerprint unknown,
 *   trusted on first use). On this path the verification code compared on both screens after
 *   pairing is what detects a machine-in-the-middle, so the UI insists on it.
 */
sealed interface PairingTarget {
    val hosts: List<String>
    val port: Int
    val desktopName: String

    /** Certificate pin known before connecting (QR / LAN advertisement); null = trust on first use. */
    val fingerprint: String?

    data class Link(val link: PairingLink) : PairingTarget {
        override val hosts get() = link.hosts
        override val port get() = link.port
        override val desktopName get() = link.desktopName
        override val fingerprint: String get() = link.fingerprint
        override fun toString(): String = "Link($link)"
    }

    data class Code(
        override val desktopName: String,
        override val hosts: List<String>,
        override val port: Int,
        override val fingerprint: String?,
        val code: String,
    ) : PairingTarget {
        override fun toString(): String = "Code(name=$desktopName, hosts=$hosts, port=$port, fp=${fingerprint?.take(16)}, code=<redacted>)"
    }

    companion object {
        const val CODE_LENGTH = 6

        /** Digits only ("123 456" → "123456"); null unless exactly six digits remain. */
        fun normalizeCode(input: String): String? =
            input.filter { it in '0'..'9' }.takeIf { it.length == CODE_LENGTH }
    }
}

/** A computer address typed by the user: `host`, `host:port`, `[v6]:port` or a bare IPv6 literal. */
data class ManualAddress(val host: String, val port: Int) {
    companion object {
        private val HOST = Regex("^[A-Za-z0-9.:%_-]{1,253}$")

        fun parse(input: String, defaultPort: Int = PairingLink.DEFAULT_PORT): ManualAddress? {
            var text = input.trim()
            text = text.substringAfter("://") // tolerate "https://host:port/"
            text = text.substringBefore('/')
            if (text.isEmpty()) return null
            val (host, portText) = when {
                text.startsWith('[') -> {
                    val end = text.indexOf(']')
                    if (end < 0) return null
                    val rest = text.substring(end + 1)
                    text.substring(1, end) to (if (rest.startsWith(':')) rest.substring(1) else if (rest.isEmpty()) null else return null)
                }
                text.count { it == ':' } == 1 -> text.substringBefore(':') to text.substringAfter(':')
                else -> text to null // hostname, IPv4 or bare IPv6 literal
            }
            if (host.isEmpty() || !HOST.matches(host) || host.startsWith('-')) return null
            val port = if (portText == null) defaultPort else portText.toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return ManualAddress(host, port)
        }
    }
}
