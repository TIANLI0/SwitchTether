//! Network exposure policy.
//!
//! The server binds to all interfaces (so Wi-Fi changes and Tailscale coming up
//! later need no rebind), but the accept loop immediately drops TCP peers whose
//! source address is not allowed by the configured [`NetworkMode`]. By default
//! only private/link-local/loopback/CGNAT (Tailscale) peers may connect; the
//! Remote API is never reachable from public addresses unless the user opts in.
//! Application-level mutual-TLS authentication applies in every mode — being on
//! the LAN or the Tailnet is never sufficient on its own.

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum NetworkMode {
    /// Private LAN + link-local + loopback + Tailscale/CGNAT (default).
    #[default]
    Lan,
    /// Only Tailscale (100.64.0.0/10, fd7a:115c:a1e0::/48) and loopback.
    TailscaleOnly,
    /// Only loopback (for testing / SSH tunnels).
    LocalhostOnly,
    /// Any source address. Must be explicitly enabled by the user.
    Any,
}

fn canonical(ip: IpAddr) -> IpAddr {
    match ip {
        IpAddr::V6(v6) => match v6.to_ipv4_mapped() {
            Some(v4) => IpAddr::V4(v4),
            None => IpAddr::V6(v6),
        },
        v4 => v4,
    }
}

pub fn is_tailscale(ip: IpAddr) -> bool {
    match canonical(ip) {
        IpAddr::V4(v4) => is_cgnat(v4),
        IpAddr::V6(v6) => {
            let s = v6.segments();
            s[0] == 0xfd7a && s[1] == 0x115c && s[2] == 0xa1e0
        }
    }
}

fn is_cgnat(v4: Ipv4Addr) -> bool {
    let o = v4.octets();
    o[0] == 100 && (o[1] & 0xc0) == 64
}

fn is_ula(v6: Ipv6Addr) -> bool {
    (v6.segments()[0] & 0xfe00) == 0xfc00
}

fn is_v6_link_local(v6: Ipv6Addr) -> bool {
    (v6.segments()[0] & 0xffc0) == 0xfe80
}

/// Non-public address (never routed on the public Internet).
pub fn is_private(ip: IpAddr) -> bool {
    match canonical(ip) {
        IpAddr::V4(v4) => {
            v4.is_loopback() || v4.is_private() || v4.is_link_local() || is_cgnat(v4)
        }
        IpAddr::V6(v6) => v6.is_loopback() || is_ula(v6) || is_v6_link_local(v6),
    }
}

pub fn peer_allowed(mode: NetworkMode, ip: IpAddr) -> bool {
    let ip = canonical(ip);
    match mode {
        NetworkMode::Any => true,
        NetworkMode::Lan => is_private(ip),
        NetworkMode::TailscaleOnly => ip.is_loopback() || is_tailscale(ip),
        NetworkMode::LocalhostOnly => ip.is_loopback(),
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HostCandidate {
    pub address: String,
    pub interface: String,
    pub kind: &'static str,
}

/// Addresses the phone may try, best first (LAN IPv4, Tailscale, LAN IPv6).
pub fn candidate_hosts(mode: NetworkMode) -> Vec<HostCandidate> {
    let mut out: Vec<(u8, HostCandidate)> = Vec::new();
    let Ok(ifaces) = if_addrs::get_if_addrs() else {
        return Vec::new();
    };
    for iface in ifaces {
        let ip = iface.ip();
        if ip.is_unspecified() || ip.is_multicast() {
            continue;
        }
        let (rank, kind) = if ip.is_loopback() {
            (9, "loopback")
        } else if is_tailscale(ip) {
            (1, "tailscale")
        } else if let IpAddr::V6(v6) = ip {
            if is_v6_link_local(v6) {
                continue; // needs a scope id; not useful in a QR code
            }
            (2, if is_ula(v6) { "lan6" } else { "public6" })
        } else if is_private(ip) {
            (0, "lan")
        } else {
            (5, "public")
        };
        if !peer_allowed(mode, ip) && !(mode == NetworkMode::Any) {
            continue;
        }
        if kind == "loopback" && mode != NetworkMode::LocalhostOnly {
            continue;
        }
        let address = match ip {
            IpAddr::V6(v6) => format!("[{v6}]"),
            IpAddr::V4(v4) => v4.to_string(),
        };
        out.push((
            rank,
            HostCandidate {
                address,
                interface: iface.name.clone(),
                kind,
            },
        ));
    }
    out.sort_by_key(|(r, _)| *r);
    out.dedup_by(|a, b| a.1.address == b.1.address);
    out.into_iter().map(|(_, c)| c).take(6).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ip(s: &str) -> IpAddr {
        s.parse().unwrap()
    }

    #[test]
    fn private_detection() {
        for a in ["10.1.2.3", "172.16.0.1", "192.168.1.1", "127.0.0.1", "169.254.1.1", "100.100.1.1", "::1", "fd00::1", "fe80::1", "::ffff:192.168.1.5", "fd7a:115c:a1e0::1"] {
            assert!(is_private(ip(a)), "{a}");
        }
        for a in ["8.8.8.8", "1.1.1.1", "100.128.0.1", "2001:4860::8888", "::ffff:8.8.8.8", "172.32.0.1"] {
            assert!(!is_private(ip(a)), "{a}");
        }
    }

    #[test]
    fn modes() {
        assert!(peer_allowed(NetworkMode::Lan, ip("192.168.0.9")));
        assert!(!peer_allowed(NetworkMode::Lan, ip("8.8.8.8")));
        assert!(peer_allowed(NetworkMode::TailscaleOnly, ip("100.101.1.2")));
        assert!(peer_allowed(NetworkMode::TailscaleOnly, ip("fd7a:115c:a1e0::5")));
        assert!(!peer_allowed(NetworkMode::TailscaleOnly, ip("192.168.0.9")));
        assert!(peer_allowed(NetworkMode::LocalhostOnly, ip("127.0.0.1")));
        assert!(!peer_allowed(NetworkMode::LocalhostOnly, ip("192.168.0.9")));
        assert!(peer_allowed(NetworkMode::Any, ip("8.8.8.8")));
    }

    #[test]
    fn candidates_do_not_panic() {
        let _ = candidate_hosts(NetworkMode::Lan);
    }
}
