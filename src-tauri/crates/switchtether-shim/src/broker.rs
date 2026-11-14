//! Connection to the SwitchTether desktop "attach broker".
//!
//! The desktop writes `~/.switchtether/remote/attach.json` (owner-only) with a
//! loopback port and a random token. Frames are newline-delimited JSON.
//! Any failure → `None` / disabled, and the wrapped session keeps running.

use std::io::{BufRead, BufReader, Write};
use std::net::{SocketAddr, TcpStream};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde_json::{json, Value};

pub fn attach_file() -> Option<PathBuf> {
    if let Some(p) = std::env::var_os("SWITCHTETHER_ATTACH_FILE") {
        return Some(PathBuf::from(p));
    }
    Some(
        dirs::home_dir()?
            .join(".switchtether")
            .join("remote")
            .join("attach.json"),
    )
}

#[derive(Clone)]
pub struct Broker {
    writer: Arc<Mutex<Option<TcpStream>>>,
}

impl Broker {
    /// Connect and send `hello`. Returns the broker and a reader for frames
    /// coming from the desktop.
    pub fn connect(hello: Value) -> Option<(Broker, BufReader<TcpStream>)> {
        let text = std::fs::read_to_string(attach_file()?).ok()?;
        let info: Value = serde_json::from_str(&text).ok()?;
        let port = info.get("port")?.as_u64()? as u16;
        let token = info.get("token")?.as_str()?.to_string();
        let addr: SocketAddr = ([127, 0, 0, 1], port).into();
        let stream = TcpStream::connect_timeout(&addr, Duration::from_millis(400)).ok()?;
        let _ = stream.set_nodelay(true);
        let mut hello = hello;
        hello["t"] = json!("hello");
        hello["v"] = json!(1);
        hello["token"] = json!(token);
        let mut w = stream.try_clone().ok()?;
        let mut line = hello.to_string();
        line.push('\n');
        w.write_all(line.as_bytes()).ok()?;
        // Expect an ack quickly; otherwise give up (old desktop, wrong token…).
        stream.set_read_timeout(Some(Duration::from_secs(3))).ok()?;
        let mut reader = BufReader::new(stream.try_clone().ok()?);
        let mut ack = String::new();
        reader.read_line(&mut ack).ok()?;
        let ack: Value = serde_json::from_str(ack.trim()).ok()?;
        if ack.get("t").and_then(|t| t.as_str()) != Some("welcome") {
            return None;
        }
        // Clear the handshake timeout on every handle (on Windows a cloned
        // socket handle keeps its own receive timeout).
        stream.set_read_timeout(None).ok()?;
        reader.get_ref().set_read_timeout(None).ok()?;
        Some((
            Broker {
                writer: Arc::new(Mutex::new(Some(w))),
            },
            reader,
        ))
    }

    /// Best effort; disables itself on the first error.
    pub fn send(&self, frame: &Value) {
        let mut g = match self.writer.lock() {
            Ok(g) => g,
            Err(p) => p.into_inner(),
        };
        if let Some(w) = g.as_mut() {
            let mut line = frame.to_string();
            line.push('\n');
            if w.write_all(line.as_bytes()).is_err() {
                *g = None;
            }
        }
    }
}
