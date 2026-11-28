//! Phone activity statistics (docs/PROTOCOL.md §7.15).

use std::collections::HashSet;
use std::sync::Arc;

use axum::extract::{Query, State};
use axum::response::Response;
use axum::routing::{get, post};
use axum::{Json, Router};
use serde::Deserialize;
use serde_json::{json, Value};

use super::{idempotent, ApiContext, AuthDevice};
use crate::backend::UsageQuery;
use crate::error::{ApiError, ApiResult};
use crate::stats::MAX_FOREGROUND_INTERVALS;

pub fn routes() -> Router<Arc<ApiContext>> {
    Router::new()
        .route("/v1/stats/remote", get(remote))
        .route("/v1/stats/foreground", post(foreground))
}

#[derive(Deserialize)]
struct RangeParams {
    /// Epoch seconds (inclusive); the day it falls on is the first day.
    from: Option<i64>,
    /// Epoch seconds (inclusive); the day it falls on is the last day.
    to: Option<i64>,
}

/// `GET /v1/stats/remote?from&to` — what all paired phones did, per desktop-local day.
async fn remote(
    State(ctx): State<Arc<ApiContext>>,
    _d: AuthDevice,
    Query(p): Query<RangeParams>,
) -> ApiResult<Json<Value>> {
    let reg = ctx.registry.clone();
    let (mut view, ids, (from_ms, to_ms)) = tokio::task::spawn_blocking(move || {
        let since = reg.stats_first_day();
        let (from, to) =
            crate::stats::resolve_range(p.from, p.to, since.as_deref(), crate::util::now_ms());
        let fmt = |d: chrono::NaiveDate| d.format("%Y-%m-%d").to_string();
        let rows = reg.stats_daily(&fmt(from), &fmt(to))?;
        let devices = reg.list_devices()?;
        let range = crate::stats::range_ms(from, to);
        let ids: HashSet<String> = reg
            .stats_session_ids(range.0, range.1)?
            .into_iter()
            .collect();
        let view = crate::stats::build_view(&rows, &devices, from, to, since);
        anyhow::Ok((view, ids, range))
    })
    .await?
    .map_err(|e| ApiError::internal("stats", format!("{e:#}")))?;
    view.sessions = ids.len();
    if !ids.is_empty() && ctx.backend.available() {
        let q = UsageQuery {
            from: Some(from_ms / 1000),
            to: Some(to_ms / 1000),
            app: None,
            provider: None,
            model: None,
        };
        match ctx.backend.usage_sessions(q).await {
            Ok(raw) => {
                let rows: Vec<crate::usage_projects::SessionUsageRow> =
                    serde_json::from_value(raw).unwrap_or_default();
                view.cost = Some(crate::stats::phone_cost(&rows, &ids));
            }
            Err(e) => log::warn!("[remote] stats: usage unavailable: {e}"),
        }
    } else if ids.is_empty() {
        view.cost = Some(Default::default());
    }
    Ok(Json(serde_json::to_value(view).unwrap_or(Value::Null)))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Interval {
    start_ms: i64,
    end_ms: i64,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ForegroundBody {
    command_id: Option<String>,
    #[serde(default)]
    intervals: Vec<Interval>,
}

/// `POST /v1/stats/foreground` `{commandId, intervals: [{startMs, endMs}]}` — the calling phone's
/// time in the foreground. Idempotent per `commandId`, so a retried report counts once.
async fn foreground(
    State(ctx): State<Arc<ApiContext>>,
    dev: AuthDevice,
    Json(body): Json<ForegroundBody>,
) -> Response {
    if body.intervals.len() > MAX_FOREGROUND_INTERVALS {
        return axum::response::IntoResponse::into_response(ApiError::validation(format!(
            "at most {MAX_FOREGROUND_INTERVALS} intervals per report"
        )));
    }
    let c = ctx.clone();
    idempotent(
        &ctx,
        dev.id().to_string(),
        body.command_id,
        "stats.foreground",
        || async move {
            let intervals: Vec<(i64, i64)> = body
                .intervals
                .iter()
                .map(|i| (i.start_ms, i.end_ms))
                .collect();
            let stats = c.stats.clone();
            let id = dev.id().to_string();
            let counted = tokio::task::spawn_blocking(move || {
                stats.foreground(&id, &intervals, crate::util::now_ms())
            })
            .await?;
            Ok(json!({ "countedMs": counted }))
        },
    )
    .await
}
