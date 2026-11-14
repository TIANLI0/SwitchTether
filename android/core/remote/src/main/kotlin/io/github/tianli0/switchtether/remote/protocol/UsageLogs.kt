package io.github.tianli0.switchtether.remote.protocol

import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One row of `GET /v1/usage/logs` (PROTOCOL.md §7.3): metadata of one model request, no bodies.
 * Costs arrive as decimal strings (like the desktop's `RequestLog`).
 */
data class RequestLog(
    val requestId: String,
    val providerName: String?,
    val appType: String?,
    val model: String?,
    val requestModel: String?,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheCreationTokens: Long,
    val inputCostUsd: Double?,
    val outputCostUsd: Double?,
    val cacheReadCostUsd: Double?,
    val cacheCreationCostUsd: Double?,
    val totalCostUsd: Double?,
    val isStreaming: Boolean,
    val latencyMs: Long?,
    val firstTokenMs: Long?,
    val durationMs: Long?,
    val statusCode: Int?,
    val errorMessage: String?,
    val createdAtMs: Long,
    val dataSource: String?,
) {
    val ok: Boolean get() = statusCode == null || statusCode in 200..299
    val totalTokens: Long get() = inputTokens + outputTokens + cacheReadTokens + cacheCreationTokens

    companion object {
        fun parse(o: JsonObject) = RequestLog(
            requestId = o.str("requestId", "id") ?: "",
            providerName = o.str("providerName"),
            appType = o.str("appType"),
            model = o.str("model"),
            requestModel = o.str("requestModel"),
            inputTokens = o.long("inputTokens") ?: 0,
            outputTokens = o.long("outputTokens") ?: 0,
            cacheReadTokens = o.long("cacheReadTokens") ?: 0,
            cacheCreationTokens = o.long("cacheCreationTokens") ?: 0,
            inputCostUsd = o.double("inputCostUsd"),
            outputCostUsd = o.double("outputCostUsd"),
            cacheReadCostUsd = o.double("cacheReadCostUsd"),
            cacheCreationCostUsd = o.double("cacheCreationCostUsd"),
            totalCostUsd = o.double("totalCostUsd"),
            isStreaming = o.bool("isStreaming") ?: false,
            latencyMs = o.long("latencyMs"),
            firstTokenMs = o.long("firstTokenMs"),
            durationMs = o.long("durationMs"),
            statusCode = o.long("statusCode")?.toInt(),
            errorMessage = o.str("errorMessage")?.takeIf { it.isNotBlank() },
            // `createdAt` is epoch seconds in CC Switch's request log; accept milliseconds too.
            createdAtMs = (o.long("createdAt") ?: 0).let { if (it in 1 until 100_000_000_000L) it * 1000 else it },
            dataSource = o.str("dataSource"),
        )
    }
}

/** `GET /v1/usage/logs` page (page 0-based). */
data class RequestLogPage(val data: List<RequestLog>, val total: Long, val page: Int, val pageSize: Int) {
    val hasMore: Boolean get() = (page + 1).toLong() * pageSize < total

    companion object {
        fun parse(e: JsonElement): RequestLogPage {
            val o = e as? JsonObject ?: JsonObject(emptyMap())
            val rows = (o["data"] ?: e).listOfObjects("data", "items").map(RequestLog::parse)
            return RequestLogPage(rows, o.long("total") ?: rows.size.toLong(), o.long("page")?.toInt() ?: 0, o.long("pageSize")?.toInt() ?: rows.size)
        }
    }
}

/**
 * The desktop's time zone (`GET /v1/status` → `timeZone`, `utcOffsetMinutes`): usage ranges and
 * buckets are computed there so "今天" means the same day as on the computer. Falls back to the
 * fixed offset, then to [fallback] (older desktops send neither).
 */
fun desktopZone(timeZone: String?, utcOffsetMinutes: Int?, fallback: ZoneId): ZoneId =
    timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        ?: utcOffsetMinutes?.let { runCatching { ZoneOffset.ofTotalSeconds(it * 60) }.getOrNull() }
        ?: fallback
