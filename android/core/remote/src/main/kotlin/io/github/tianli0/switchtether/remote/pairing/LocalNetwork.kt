package io.github.tianli0.switchtether.remote.pairing

/**
 * Classifies desktop host candidates for Android 17's local network protection
 * (`ACCESS_LOCAL_NETWORK`): TCP to a LAN address needs the permission, Internet / VPN overlay
 * addresses do not. Used to skip hosts that cannot work while the permission is denied (they
 * would only time out) and to decide when to ask for the permission.
 *
 * Local: RFC 1918 (10/8, 172.16/12, 192.168/16), IPv4 link-local 169.254/16, IPv6 link-local
 * fe80::/10, IPv6 ULA fc00::/7 and `.local` mDNS names. Not local: loopback, public addresses,
 * CGNAT 100.64/10 (Tailscale) and other DNS names (e.g. MagicDNS `*.ts.net`).
 */
object LocalNetwork {
    fun isLocal(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]").substringBefore('%').lowercase()
        if (h.endsWith(".local")) return true
        val v4 = h.split('.').takeIf { it.size == 4 }?.map { it.toIntOrNull() ?: return false }
        if (v4 != null) {
            if (v4.any { it !in 0..255 }) return false
            val (a, b) = v4[0] to v4[1]
            return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
        }
        if (':' in h) {
            val first = h.substringBefore(':').ifEmpty { "0" }.toIntOrNull(16) ?: return false
            return (first and 0xffc0) == 0xfe80 || (first and 0xfe00) == 0xfc00
        }
        return false
    }

    /** Hosts usable without the local-network permission (VPN/Tailscale, public, DNS names). */
    fun withoutLocal(hosts: List<String>): List<String> = hosts.filterNot(::isLocal)
}
