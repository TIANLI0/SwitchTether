//! Wire types shared by REST handlers (docs/PROTOCOL.md). The Kotlin client
//! mirrors these; `protocol/fixtures/*.json` are checked by tests on both sides.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Hello {
    pub protocol_version: u32,
    pub min_protocol_version: u32,
    pub server_id: String,
    pub server_name: String,
    pub server_version: String,
    pub paired: bool,
    pub device_id: Option<String>,
    pub epoch: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct PairRequest {
    pub pairing_id: String,
    pub secret: String,
    pub device_name: String,
    pub platform: String,
    #[serde(default)]
    pub os_version: Option<String>,
    #[serde(default)]
    pub app_version: Option<String>,
    pub protocol_version: u32,
    /// Base64 (standard) SubjectPublicKeyInfo DER of the Keystore step-up key.
    #[serde(default)]
    pub step_up_public_key: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct PairResponse {
    pub device_id: String,
    pub server_id: String,
    pub server_name: String,
    pub server_fingerprint: String,
    pub step_up_enabled: bool,
    /// Six-digit code shown on both screens for optional visual comparison.
    pub verification_code: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct CommandEnvelope {
    #[serde(default)]
    pub command_id: Option<String>,
}

/// Compatibility status of a capability (docs/AGENT_COMPATIBILITY.md).
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum CapStatus {
    Verified,
    Partial,
    Blocked,
    Untested,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fixtures_parse() {
        let root =
            std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../protocol/fixtures");
        let pair: PairRequest =
            serde_json::from_str(&std::fs::read_to_string(root.join("pair_request.json")).unwrap())
                .unwrap();
        assert_eq!(pair.platform, "android");
        let hello: Hello =
            serde_json::from_str(&std::fs::read_to_string(root.join("hello.json")).unwrap())
                .unwrap();
        assert_eq!(hello.protocol_version, 1);
        let resp: PairResponse = serde_json::from_str(
            &std::fs::read_to_string(root.join("pair_response.json")).unwrap(),
        )
        .unwrap();
        assert_eq!(resp.verification_code.len(), 6);
        let question: crate::agents::model::AgentEvent = serde_json::from_str(
            &std::fs::read_to_string(root.join("agent_question.json")).unwrap(),
        )
        .unwrap();
        assert_eq!(question.kind, "question");
        assert_eq!(question.data["questions"][1]["multiSelect"], true);
        assert_eq!(question.data["questions"][1]["required"], false);
    }

    #[test]
    fn cap_status_wire_format() {
        assert_eq!(
            serde_json::to_string(&CapStatus::Verified).unwrap(),
            "\"VERIFIED\""
        );
        assert_eq!(
            serde_json::to_string(&CapStatus::Blocked).unwrap(),
            "\"BLOCKED\""
        );
    }
}
