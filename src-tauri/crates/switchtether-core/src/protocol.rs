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
    /// QR / link pairing: session id + one-time secret.
    #[serde(default)]
    pub pairing_id: String,
    #[serde(default)]
    pub secret: String,
    /// Code pairing: the 6-digit code shown on the desktop (instead of id + secret).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub code: Option<String>,
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
        assert_eq!(pair.code, None);
        let by_code: PairRequest = serde_json::from_str(
            &std::fs::read_to_string(root.join("pair_request_code.json")).unwrap(),
        )
        .unwrap();
        assert_eq!(by_code.code.as_deref(), Some("042917"));
        assert!(by_code.pairing_id.is_empty() && by_code.secret.is_empty());
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
        // Attachment upload state: the server's serialization matches the fixture exactly.
        let view: serde_json::Value = serde_json::from_str(
            &std::fs::read_to_string(root.join("attachment_view.json")).unwrap(),
        )
        .unwrap();
        let ours = serde_json::to_value(crate::agents::attachments::AttachmentView {
            attachment_id: "0d5c3f8e-2b1a-4c11-9e2f-6a7b8c9d0e1f".into(),
            name: "build.log".into(),
            size: 5_242_880,
            received: 2_097_152,
            complete: false,
            media_type: "text/plain".into(),
        })
        .unwrap();
        assert_eq!(view, ours);
        // Usage per project: the server's serialization matches the fixture exactly.
        let projects: serde_json::Value = serde_json::from_str(
            &std::fs::read_to_string(root.join("usage_projects.json")).unwrap(),
        )
        .unwrap();
        use crate::usage_projects::{ProjectUsage, ProjectUsageView, SessionUsage, UsageTotals};
        let t = |requests, cost_usd, i, o, cr, cc, last| UsageTotals {
            requests,
            cost_usd,
            input_tokens: i,
            output_tokens: o,
            cache_read_tokens: cr,
            cache_creation_tokens: cc,
            last_ms: Some(last),
        };
        let cwd = Some("D:/develop/SwitchTether".to_string());
        let ours = serde_json::to_value(ProjectUsageView {
            projects: vec![ProjectUsage {
                project: "SwitchTether".into(),
                cwd: cwd.clone(),
                session_count: 2,
                totals: t(
                    41,
                    3.125,
                    52_000,
                    18_000,
                    2_400_000,
                    96_000,
                    1_764_000_000_000,
                ),
                sessions: vec![
                    SessionUsage {
                        id: "claude:0f8e5a2c-7d1b-4c3e-9a6f-1b2c3d4e5f60".into(),
                        app: "claude".into(),
                        title: Some("重构设置页的按钮层级".into()),
                        cwd: cwd.clone(),
                        totals: t(
                            30,
                            2.5,
                            40_000,
                            15_000,
                            2_000_000,
                            80_000,
                            1_764_000_000_000,
                        ),
                    },
                    SessionUsage {
                        id: "codex:019a1b2c-3d4e-7f60-8a9b-0c1d2e3f4a5b".into(),
                        app: "codex".into(),
                        title: None,
                        cwd,
                        totals: t(11, 0.625, 12_000, 3_000, 400_000, 16_000, 1_763_990_000_000),
                    },
                ],
            }],
            other_projects: None,
            unattributed: Some(t(4, 0.05, 900, 120, 0, 0, 1_763_900_000_000)),
        })
        .unwrap();
        assert_eq!(projects, ours);
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
