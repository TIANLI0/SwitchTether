//! Wake-on-LAN targets: the hardware (MAC) address and IPv4 broadcast address of each
//! LAN interface, reported to paired phones in `GET /v1/status` (`wake`). A phone on the
//! same network keeps them and can send a magic packet when the computer is asleep.
//!
//! SwitchTether never changes the computer's power, adapter or firmware settings: whether
//! a magic packet actually wakes the machine depends on the user's own configuration.
//! The MAC address is identifying but not a secret; it goes only to authenticated phones.

use std::net::{IpAddr, Ipv4Addr};

use serde::Serialize;

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WakeTarget {
    /// `aa:bb:cc:dd:ee:ff`, lowercase.
    pub mac: String,
    /// Directed broadcast address of the interface's IPv4 subnet (e.g. `192.168.1.255`).
    pub broadcast: String,
    /// The interface's own IPv4 address (lets the phone pick the subnet it is on).
    pub address: String,
}

/// Formats a 6-byte hardware address; anything else (tunnels, empty, all-zero) is not wakeable.
pub fn format_mac(bytes: &[u8]) -> Option<String> {
    if bytes.len() != 6 || bytes.iter().all(|b| *b == 0) || bytes.iter().all(|b| *b == 0xff) {
        return None;
    }
    Some(
        bytes
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect::<Vec<_>>()
            .join(":"),
    )
}

fn broadcast_of(ip: Ipv4Addr, netmask: Ipv4Addr) -> Ipv4Addr {
    Ipv4Addr::from(u32::from(ip) | !u32::from(netmask))
}

/// Wake targets of the LAN interfaces that are up. Tailscale, loopback and public addresses
/// are skipped (a magic packet is link-local by nature).
pub fn wake_targets() -> Vec<WakeTarget> {
    let Ok(ifaces) = if_addrs::get_if_addrs() else {
        return Vec::new();
    };
    let macs = platform::macs();
    let mut out: Vec<WakeTarget> = Vec::new();
    for iface in ifaces {
        if !iface.is_oper_up() || iface.is_loopback() {
            continue;
        }
        let if_addrs::IfAddr::V4(v4) = &iface.addr else {
            continue;
        };
        let ip = v4.ip;
        if !crate::net::is_private(IpAddr::V4(ip))
            || crate::net::is_tailscale(IpAddr::V4(ip))
            || ip.is_link_local()
            || ip.is_loopback()
        {
            continue;
        }
        let Some(mac) = platform::lookup(&macs, &iface) else {
            continue;
        };
        let broadcast = v4.broadcast.unwrap_or_else(|| broadcast_of(ip, v4.netmask));
        let t = WakeTarget {
            mac,
            broadcast: broadcast.to_string(),
            address: ip.to_string(),
        };
        if !out.contains(&t) {
            out.push(t);
        }
    }
    out
}

#[cfg(windows)]
mod platform {
    use std::collections::HashMap;

    use windows_sys::Win32::NetworkManagement::IpHelper::{
        GetAdaptersAddresses, GAA_FLAG_SKIP_ANYCAST, GAA_FLAG_SKIP_DNS_SERVER,
        GAA_FLAG_SKIP_MULTICAST, GAA_FLAG_SKIP_UNICAST, IP_ADAPTER_ADDRESSES_LH,
    };
    use windows_sys::Win32::Networking::WinSock::AF_UNSPEC;

    /// IF_TYPE_ETHERNET_CSMACD and IF_TYPE_IEEE80211: the only adapter types a magic packet
    /// can reach (VPN/tunnel adapters report synthetic addresses).
    const WAKEABLE_TYPES: [u32; 2] = [6, 71];

    /// IPv4 interface index → MAC.
    pub fn macs() -> HashMap<u32, String> {
        let mut out = HashMap::new();
        let flags = GAA_FLAG_SKIP_UNICAST
            | GAA_FLAG_SKIP_ANYCAST
            | GAA_FLAG_SKIP_MULTICAST
            | GAA_FLAG_SKIP_DNS_SERVER;
        let mut len: u32 = 16 * 1024;
        for _ in 0..3 {
            // u64 storage keeps the buffer aligned for IP_ADAPTER_ADDRESSES_LH.
            let mut buf = vec![0u64; (len as usize).div_ceil(8)];
            let head = buf.as_mut_ptr() as *mut IP_ADAPTER_ADDRESSES_LH;
            // SAFETY: `buf` is writable for `len` bytes and outlives every pointer read below.
            let rc = unsafe {
                GetAdaptersAddresses(AF_UNSPEC as u32, flags, std::ptr::null(), head, &mut len)
            };
            if rc == 111 {
                continue; // ERROR_BUFFER_OVERFLOW: `len` now holds the required size.
            }
            if rc != 0 {
                return out;
            }
            let mut cur = head as *const IP_ADAPTER_ADDRESSES_LH;
            while !cur.is_null() {
                // SAFETY: the list was filled by GetAdaptersAddresses inside `buf`.
                let a = unsafe { &*cur };
                let index = unsafe { a.Anonymous1.Anonymous.IfIndex };
                let n = (a.PhysicalAddressLength as usize).min(a.PhysicalAddress.len());
                if index != 0 && WAKEABLE_TYPES.contains(&a.IfType) {
                    if let Some(mac) = super::format_mac(&a.PhysicalAddress[..n]) {
                        out.insert(index, mac);
                    }
                }
                cur = a.Next;
            }
            return out;
        }
        out
    }

    pub fn lookup(macs: &HashMap<u32, String>, iface: &if_addrs::Interface) -> Option<String> {
        iface.index.and_then(|i| macs.get(&i).cloned())
    }
}

#[cfg(target_os = "linux")]
mod platform {
    use std::collections::HashMap;

    pub fn macs() -> HashMap<String, String> {
        HashMap::new()
    }

    /// `/sys/class/net/<name>/address`; virtual devices (bridges, docker, VPN) have no
    /// `device` link and are skipped.
    pub fn lookup(_: &HashMap<String, String>, iface: &if_addrs::Interface) -> Option<String> {
        let base = std::path::Path::new("/sys/class/net").join(&iface.name);
        if !base.join("device").exists() {
            return None;
        }
        let text = std::fs::read_to_string(base.join("address")).ok()?;
        let bytes: Vec<u8> = text
            .trim()
            .split(':')
            .map(|p| u8::from_str_radix(p, 16))
            .collect::<Result<_, _>>()
            .ok()?;
        super::format_mac(&bytes)
    }
}

#[cfg(target_os = "macos")]
mod platform {
    use std::collections::HashMap;
    use std::ffi::CStr;

    /// Interface name → MAC from the AF_LINK entries of getifaddrs.
    pub fn macs() -> HashMap<String, String> {
        let mut out = HashMap::new();
        let mut head: *mut libc::ifaddrs = std::ptr::null_mut();
        // SAFETY: getifaddrs allocates a list that is released with freeifaddrs below.
        if unsafe { libc::getifaddrs(&mut head) } != 0 {
            return out;
        }
        let mut cur = head;
        while !cur.is_null() {
            // SAFETY: `cur` is a node of the list returned by getifaddrs.
            let ifa = unsafe { &*cur };
            if !ifa.ifa_addr.is_null()
                && unsafe { (*ifa.ifa_addr).sa_family } as i32 == libc::AF_LINK
            {
                let sdl = ifa.ifa_addr as *const libc::sockaddr_dl;
                // SAFETY: AF_LINK addresses are sockaddr_dl; the link-layer address follows
                // the name inside `sdl_data`.
                let (nlen, alen) = unsafe { ((*sdl).sdl_nlen as usize, (*sdl).sdl_alen as usize) };
                let data = unsafe { (*sdl).sdl_data.as_ptr() as *const u8 };
                let bytes = unsafe { std::slice::from_raw_parts(data.add(nlen), alen) };
                let name = unsafe { CStr::from_ptr(ifa.ifa_name) }
                    .to_string_lossy()
                    .into_owned();
                if name.starts_with("en") {
                    if let Some(mac) = super::format_mac(bytes) {
                        out.insert(name, mac);
                    }
                }
            }
            cur = ifa.ifa_next;
        }
        // SAFETY: `head` came from getifaddrs.
        unsafe { libc::freeifaddrs(head) };
        out
    }

    pub fn lookup(macs: &HashMap<String, String>, iface: &if_addrs::Interface) -> Option<String> {
        macs.get(&iface.name).cloned()
    }
}

#[cfg(not(any(windows, target_os = "linux", target_os = "macos")))]
mod platform {
    pub fn macs() {}
    pub fn lookup(_: &(), _: &if_addrs::Interface) -> Option<String> {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mac_formatting_rejects_non_ethernet_addresses() {
        assert_eq!(
            format_mac(&[0x00, 0x1A, 0x2b, 0x3c, 0x4d, 0x5e]).as_deref(),
            Some("00:1a:2b:3c:4d:5e")
        );
        assert_eq!(format_mac(&[0; 6]), None);
        assert_eq!(format_mac(&[0xff; 6]), None);
        assert_eq!(format_mac(&[1, 2, 3, 4]), None);
        assert_eq!(format_mac(&[1, 2, 3, 4, 5, 6, 7, 8]), None);
    }

    #[test]
    fn directed_broadcast() {
        let ip = Ipv4Addr::new(192, 168, 1, 23);
        assert_eq!(
            broadcast_of(ip, Ipv4Addr::new(255, 255, 255, 0)),
            Ipv4Addr::new(192, 168, 1, 255)
        );
        assert_eq!(
            broadcast_of(Ipv4Addr::new(10, 1, 2, 3), Ipv4Addr::new(255, 255, 0, 0)),
            Ipv4Addr::new(10, 1, 255, 255)
        );
    }

    #[test]
    fn targets_are_private_lan_ipv4_only() {
        // Real interfaces of the machine running the test: whatever is found must be a
        // private, non-Tailscale IPv4 with a well-formed MAC.
        for t in wake_targets() {
            eprintln!("wake target: {t:?}");
            let ip: Ipv4Addr = t.address.parse().unwrap();
            assert!(crate::net::is_private(IpAddr::V4(ip)));
            assert!(!crate::net::is_tailscale(IpAddr::V4(ip)));
            assert_eq!(t.mac.len(), 17);
            let _: Ipv4Addr = t.broadcast.parse().unwrap();
        }
    }
}
