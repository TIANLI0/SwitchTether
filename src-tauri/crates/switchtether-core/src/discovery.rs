//! LAN discovery: the desktop advertises `_switchtether._tcp` over mDNS/DNS-SD
//! while the Remote server listens on the LAN, so a paired phone finds it again
//! after its IP address changed. The advertisement carries only public data
//! (the certificate fingerprint the phone already pins, the server id and name);
//! pairing still needs the one-time secret from the QR code.

use mdns_sd::{ServiceDaemon, ServiceInfo};

pub const SERVICE_TYPE: &str = "_switchtether._tcp.local.";

pub struct Advertiser {
    daemon: ServiceDaemon,
    fullname: String,
}

/// DNS-SD instance names are at most 63 bytes; keep it readable and unique.
fn instance_name(server_name: &str, server_id: &str) -> String {
    let clean: String = server_name
        .chars()
        .filter(|c| !c.is_control() && *c != '.')
        .collect();
    let suffix: String = server_id
        .chars()
        .filter(|c| c.is_ascii_alphanumeric())
        .take(6)
        .collect();
    let mut base = clean.trim().to_string();
    if base.is_empty() {
        base = "SwitchTether".into();
    }
    let max = 63 - suffix.len() - 1;
    while base.len() > max {
        base.pop();
    }
    format!("{base}-{suffix}")
}

impl Advertiser {
    pub fn start(
        server_name: &str,
        server_id: &str,
        fingerprint: &str,
        port: u16,
    ) -> anyhow::Result<Self> {
        let daemon = ServiceDaemon::new()?;
        let instance = instance_name(server_name, server_id);
        let host: String = server_id
            .chars()
            .filter(|c| c.is_ascii_alphanumeric())
            .take(12)
            .collect();
        let host = format!("switchtether-{host}.local.");
        let txt = [
            ("v", "1"),
            ("fp", fingerprint),
            ("id", server_id),
            ("n", server_name),
        ];
        let info = ServiceInfo::new(SERVICE_TYPE, &instance, &host, "", port, &txt[..])?
            .enable_addr_auto();
        let fullname = info.get_fullname().to_string();
        daemon.register(info)?;
        log::info!("[discovery] advertising {fullname} on port {port}");
        Ok(Self { daemon, fullname })
    }
}

impl Drop for Advertiser {
    fn drop(&mut self) {
        let _ = self.daemon.unregister(&self.fullname);
        let _ = self.daemon.shutdown();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn instance_names_are_short_and_unique() {
        let n = instance_name("My.PC", "0a1b2c3d-xx");
        assert_eq!(n, "MyPC-0a1b2c");
        let long = "x".repeat(200);
        assert!(instance_name(&long, "abcdef").len() <= 63);
        assert_eq!(instance_name("  ", "abcdef"), "SwitchTether-abcdef");
    }
}
