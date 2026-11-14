package io.github.tianli0.switchtether.remote.discovery

/**
 * LAN auto-discovery (mDNS / DNS-SD). The desktop advertises `_switchtether._tcp` on its remote
 * port when its network mode is lan/any, with TXT records:
 * `v=1`, `fp=<64-hex server cert SHA-256>`, `id=<serverId>`, `n=<server name>`.
 *
 * Discovery only finds *addresses*. Trust still comes from the pinned certificate: a discovered
 * endpoint is used only for a desktop whose `fp` equals the paired fingerprint, and the TLS
 * handshake then verifies the pin exactly as for a stored host.
 */
const val SERVICE_TYPE = "_switchtether._tcp"

data class DiscoveredDesktop(
    val serviceName: String,
    val serverId: String?,
    val name: String,
    /** Lowercase 64-hex SHA-256 of the desktop certificate. */
    val fingerprint: String,
    val host: String,
    val port: Int,
)

object DiscoveryTxt {
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    /**
     * Validates a resolved service. Returns null when it is not a usable SwitchTether desktop
     * (wrong/missing version, malformed fingerprint, bad port, no address).
     */
    fun parse(serviceName: String, host: String?, port: Int, txt: Map<String, ByteArray?>): DiscoveredDesktop? {
        fun value(k: String): String? = txt.entries.firstOrNull { it.key.equals(k, ignoreCase = true) }?.value?.toString(Charsets.UTF_8)?.trim()
        if (value("v") != "1") return null
        val fp = value("fp")?.replace(":", "")?.lowercase() ?: return null
        if (!HEX64.matches(fp)) return null
        if (host.isNullOrBlank() || port !in 1..65535) return null
        val name = io.github.tianli0.switchtether.remote.pairing.PairingLink.sanitizeName(value("n").orEmpty()).ifEmpty { serviceName }
        return DiscoveredDesktop(serviceName, value("id")?.takeIf { it.isNotBlank() }, name, fp, host.removePrefix("/"), port)
    }

    /** Discovered entries that belong to the paired desktop (pin match), best addresses first. */
    fun matching(found: Collection<DiscoveredDesktop>, pairedFingerprint: String): List<DiscoveredDesktop> {
        val fp = pairedFingerprint.lowercase()
        return found.filter { it.fingerprint == fp }
            // IPv4 before IPv6 link-local (which needs a scope id the stored host list cannot keep).
            .sortedBy { if (it.host.contains(':')) 1 else 0 }
            .distinctBy { it.host to it.port }
    }

    /** New stored host list: the discovered host first, the old ones after it (bounded). */
    fun mergeHosts(stored: List<String>, discoveredHost: String, max: Int = 8): List<String> =
        (listOf(discoveredHost) + stored.filter { it != discoveredHost }).take(max)
}
