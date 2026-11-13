//! API error type with stable machine-readable codes (see docs/PROTOCOL.md §2).
//!
//! Messages returned to clients are sanitized: internal errors never leak
//! paths, secrets or backtraces — those only go to the local log.

use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ErrorCode {
    BadRequest,
    ValidationFailed,
    Unpaired,
    DeviceRevoked,
    StepUpRequired,
    Forbidden,
    NotFound,
    Conflict,
    CommandInProgress,
    PairingExpired,
    PayloadTooLarge,
    CapabilityUnavailable,
    RateLimited,
    Internal,
    BackendUnavailable,
}

impl ErrorCode {
    pub fn status(self) -> StatusCode {
        match self {
            ErrorCode::BadRequest | ErrorCode::ValidationFailed => StatusCode::BAD_REQUEST,
            ErrorCode::Unpaired => StatusCode::UNAUTHORIZED,
            ErrorCode::DeviceRevoked | ErrorCode::StepUpRequired | ErrorCode::Forbidden => {
                StatusCode::FORBIDDEN
            }
            ErrorCode::NotFound => StatusCode::NOT_FOUND,
            ErrorCode::Conflict | ErrorCode::CommandInProgress => StatusCode::CONFLICT,
            ErrorCode::PairingExpired => StatusCode::GONE,
            ErrorCode::PayloadTooLarge => StatusCode::PAYLOAD_TOO_LARGE,
            ErrorCode::CapabilityUnavailable => StatusCode::UNPROCESSABLE_ENTITY,
            ErrorCode::RateLimited => StatusCode::TOO_MANY_REQUESTS,
            ErrorCode::Internal => StatusCode::INTERNAL_SERVER_ERROR,
            ErrorCode::BackendUnavailable => StatusCode::SERVICE_UNAVAILABLE,
        }
    }

    pub fn retryable(self) -> bool {
        matches!(
            self,
            ErrorCode::RateLimited | ErrorCode::BackendUnavailable | ErrorCode::CommandInProgress
        )
    }
}

#[derive(Debug, Clone)]
pub struct ApiError {
    pub code: ErrorCode,
    pub message: String,
    pub details: Option<Value>,
}

pub type ApiResult<T> = Result<T, ApiError>;

impl ApiError {
    pub fn new(code: ErrorCode, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
            details: None,
        }
    }
    pub fn with_details(mut self, details: Value) -> Self {
        self.details = Some(details);
        self
    }
    pub fn bad_request(m: impl Into<String>) -> Self {
        Self::new(ErrorCode::BadRequest, m)
    }
    pub fn validation(m: impl Into<String>) -> Self {
        Self::new(ErrorCode::ValidationFailed, m)
    }
    pub fn not_found(m: impl Into<String>) -> Self {
        Self::new(ErrorCode::NotFound, m)
    }
    pub fn forbidden(m: impl Into<String>) -> Self {
        Self::new(ErrorCode::Forbidden, m)
    }
    pub fn conflict(m: impl Into<String>) -> Self {
        Self::new(ErrorCode::Conflict, m)
    }
    pub fn unavailable_capability(status: &str, reason: impl Into<String>) -> Self {
        let reason = reason.into();
        Self::new(ErrorCode::CapabilityUnavailable, reason.clone())
            .with_details(serde_json::json!({ "status": status, "reason": reason }))
    }
    /// Internal error: the detailed cause is logged locally, the client only
    /// sees a generic message.
    pub fn internal(context: &str, err: impl std::fmt::Display) -> Self {
        log::error!("[remote] internal error in {context}: {err}");
        Self::new(ErrorCode::Internal, format!("internal error ({context})"))
    }
    pub fn backend(err: impl std::fmt::Display) -> Self {
        // Backend (CC Switch service) errors are user-facing validation-style
        // messages in upstream, but may embed paths/keys. Redact before returning.
        let msg = crate::redact::redact_text(&err.to_string());
        Self::new(ErrorCode::BadRequest, msg)
    }
}

impl std::fmt::Display for ApiError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{:?}: {}", self.code, self.message)
    }
}

impl std::error::Error for ApiError {}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ErrorInner<'a> {
    code: ErrorCode,
    message: &'a str,
    retryable: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    details: Option<&'a Value>,
}

#[derive(Serialize)]
struct ErrorBody<'a> {
    error: ErrorInner<'a>,
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let body = ErrorBody {
            error: ErrorInner {
                code: self.code,
                message: &self.message,
                retryable: self.code.retryable(),
                details: self.details.as_ref(),
            },
        };
        (self.code.status(), axum::Json(body)).into_response()
    }
}

impl From<rusqlite::Error> for ApiError {
    fn from(e: rusqlite::Error) -> Self {
        ApiError::internal("database", e)
    }
}

impl From<tokio::task::JoinError> for ApiError {
    fn from(e: tokio::task::JoinError) -> Self {
        ApiError::internal("task", e)
    }
}
