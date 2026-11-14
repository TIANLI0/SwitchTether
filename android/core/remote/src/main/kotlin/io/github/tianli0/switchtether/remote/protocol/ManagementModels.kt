package io.github.tianli0.switchtether.remote.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Views of CC Switch management data (§7.2–7.4). The desktop passes CC Switch's own JSON through
 * (secrets redacted), so these are parsed leniently from JsonObject instead of strict serializers:
 * missing fields degrade to null, unknown fields are ignored.
 */

// ---- Providers ----------------------------------------------------------------------------------

/** A redacted secret field: the server only says whether it is set plus an optional last-4 hint. */
data class SecretFieldStatus(val path: String, val configured: Boolean, val hint: String?, val name: String = path.substringAfterLast('.'))

data class ProviderView(
    val id: String,
    val name: String,
    val category: String?,
    val websiteUrl: String?,
    val notes: String?,
    val iconColor: String?,
    val secretFields: List<SecretFieldStatus>,
    val raw: JsonObject,
) {
    companion object {
        fun parse(o: JsonObject): ProviderView = ProviderView(
            id = o.str("id") ?: "",
            name = o.str("name") ?: o.str("id") ?: "",
            category = o.str("category"),
            websiteUrl = o.str("websiteUrl"),
            notes = o.str("notes"),
            iconColor = o.str("iconColor"),
            // Preferred: the server's explicit list `secretFields: [{field, name, configured, hint}]`.
            secretFields = (o["secretFields"] as? JsonArray)?.mapNotNull { it.obj() }?.map {
                SecretFieldStatus(it.str("field") ?: "", it.bool("configured") ?: false, it.str("hint"), it.str("name") ?: it.str("field") ?: "")
            }?.filter { it.path.isNotEmpty() } ?: collectSecrets(o, ""),
            raw = o,
        )

        /** Walks the JSON tree and lists every `{ configured, hint }` redaction marker. */
        fun collectSecrets(e: JsonElement, path: String): List<SecretFieldStatus> = when (e) {
            is JsonObject -> {
                val configured = (e["configured"] as? JsonPrimitive)?.contentOrNull
                if (configured == "true" || configured == "false") {
                    listOf(SecretFieldStatus(path.ifEmpty { "secret" }, configured == "true", e.str("hint")))
                } else {
                    e.entries.flatMap { (k, v) -> collectSecrets(v, if (path.isEmpty()) k else "$path.$k") }
                }
            }
            is JsonArray -> e.flatMapIndexed { i, v -> collectSecrets(v, "$path[$i]") }
            else -> emptyList()
        }
    }
}

data class ProvidersView(val app: String, val currentId: String?, val providers: List<ProviderView>) {
    companion object {
        fun parse(e: JsonElement, requestedApp: String): ProvidersView {
            val o = e.obj()
            return ProvidersView(
                app = o?.str("app") ?: requestedApp,
                currentId = o?.str("currentId", "current"),
                providers = (o?.get("providers") ?: e).listOfObjects().map(ProviderView::parse),
            )
        }
    }
}

/** Outcome of a provider switch/test, shown as a generic result (shape owned by CC Switch). */
data class OperationResult(val ok: Boolean, val message: String?, val latencyMs: Long?, val raw: JsonElement) {
    companion object {
        fun parse(e: JsonElement): OperationResult {
            val o = e.obj()
            val ok = o?.bool("success", "ok", "healthy", "available") ?: (o?.get("error") == null)
            return OperationResult(
                ok = ok,
                message = o?.str("message", "error", "detail", "status"),
                latencyMs = o?.long("latencyMs", "responseTimeMs", "durationMs", "latency"),
                raw = e,
            )
        }
    }
}

// ---- Usage --------------------------------------------------------------------------------------

data class UsageSummary(
    val totalRequests: Long,
    val totalCostUsd: Double?,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheCreationTokens: Long,
    val totalTokens: Long,
    val successRate: Double?,
    val cacheHitRate: Double?,
) {
    companion object {
        fun parse(e: JsonElement): UsageSummary {
            val o = e.obj()?.let { it["summary"].obj() ?: it } ?: JsonObject(emptyMap())
            val input = o.long("totalInputTokens", "inputTokens") ?: 0
            val output = o.long("totalOutputTokens", "outputTokens") ?: 0
            val cacheRead = o.long("totalCacheReadTokens", "cacheReadTokens") ?: 0
            val cacheWrite = o.long("totalCacheCreationTokens", "cacheCreationTokens", "cacheWriteTokens") ?: 0
            return UsageSummary(
                totalRequests = o.long("totalRequests", "requestCount") ?: 0,
                totalCostUsd = o.double("totalCost", "totalCostUsd", "costUsd"),
                inputTokens = input,
                outputTokens = output,
                cacheReadTokens = cacheRead,
                cacheCreationTokens = cacheWrite,
                totalTokens = o.long("realTotalTokens", "totalTokens") ?: (input + output + cacheRead + cacheWrite),
                successRate = o.double("successRate"),
                cacheHitRate = o.double("cacheHitRate"),
            )
        }
    }
}

data class DailyUsage(val date: String, val requests: Long, val costUsd: Double?, val tokens: Long) {
    companion object {
        fun parseList(e: JsonElement): List<DailyUsage> = e.listOfObjects("trends", "days", "stats").map { o ->
            DailyUsage(
                date = o.str("date", "day", "bucket") ?: "",
                requests = o.long("requestCount", "requests", "totalRequests") ?: 0,
                costUsd = o.double("totalCost", "costUsd", "cost"),
                tokens = o.long("totalTokens", "realTotalTokens", "tokens") ?: 0,
            )
        }
    }
}

data class UsageBreakdownRow(
    val key: String,
    val label: String,
    val requests: Long,
    val tokens: Long,
    val costUsd: Double?,
    val successRate: Double?,
) {
    companion object {
        fun parseModels(e: JsonElement): List<UsageBreakdownRow> = e.listOfObjects("models", "stats").map { o ->
            val model = o.str("model", "modelId", "name") ?: "?"
            UsageBreakdownRow(model, model, o.long("requestCount", "requests") ?: 0, o.long("totalTokens") ?: 0,
                o.double("totalCost", "costUsd"), o.double("successRate"))
        }

        fun parseProviders(e: JsonElement): List<UsageBreakdownRow> = e.listOfObjects("providers", "stats").map { o ->
            val id = o.str("providerId", "id") ?: "?"
            val app = o.str("appType", "app")
            val name = o.str("providerName", "name") ?: id
            UsageBreakdownRow(if (app != null) "$app/$id" else id, if (app != null) "$name · $app" else name,
                o.long("requestCount", "requests") ?: 0, o.long("totalTokens") ?: 0,
                o.double("totalCost", "costUsd"), o.double("successRate"))
        }
    }
}

data class QuotaTier(val name: String, val utilizationPercent: Double?, val resetsAt: String?)

/** Subscription quota. `available == false` carries the server's honest reason (§7.3). */
data class QuotaView(
    val tool: String,
    val available: Boolean,
    val reason: String?,
    val planName: String?,
    val tiers: List<QuotaTier>,
) {
    companion object {
        fun parse(e: JsonElement, tool: String): QuotaView {
            val o = e.obj() ?: return QuotaView(tool, false, null, null, emptyList())
            val explicit = o.bool("available")
            val success = o.bool("success")
            val tiers = o["tiers"].listOfObjects().map {
                QuotaTier(it.str("name") ?: "", it.double("utilization"), it.str("resetsAt"))
            }
            val available = explicit ?: (success ?: tiers.isNotEmpty())
            return QuotaView(
                tool = o.str("tool") ?: tool,
                available = available && tiers.isNotEmpty(),
                reason = o.str("reason", "error", "credentialMessage")
                    ?: o.str("credentialStatus")?.let { "credential status: $it" } // CC Switch SubscriptionQuota
                    ?: if (available && tiers.isEmpty()) "no quota tiers reported" else null,
                planName = o.str("planName"),
                tiers = tiers,
            )
        }
    }
}

// ---- MCP / skills / prompts -------------------------------------------------------------------------

/** Something that can be enabled per app (MCP servers and skills both use an `apps` map). */
data class AppToggleItem(
    val id: String,
    val name: String,
    val description: String?,
    val apps: Map<String, Boolean>,
) {
    companion object {
        fun parseList(e: JsonElement, vararg wrapperKeys: String): List<AppToggleItem> =
            e.listOfObjects(*wrapperKeys).map { o ->
                val apps = o["apps"].obj()?.mapNotNull { (k, v) ->
                    (v as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()?.let { k to it }
                }?.toMap().orEmpty()
                AppToggleItem(
                    id = o.str("id", "key", "directory") ?: "",
                    name = o.str("name", "id") ?: "",
                    description = o.str("description"),
                    apps = apps,
                )
            }.filter { it.id.isNotEmpty() }
    }
}

data class PromptView(val id: String, val name: String, val description: String?, val enabled: Boolean) {
    companion object {
        fun parseList(e: JsonElement): List<PromptView> = e.listOfObjects("prompts").map { o ->
            PromptView(
                id = o.str("id") ?: "",
                name = o.str("name", "id") ?: "",
                description = o.str("description"),
                enabled = o.bool("enabled") ?: false,
            )
        }.filter { it.id.isNotEmpty() }
    }
}

/** `GET /v1/providers/apps`: either `["claude", …]` or `[{ id, name }, …]`. */
fun parseAppTypes(e: JsonElement): List<String> {
    val arr = e.arr() ?: e.obj()?.get("apps").arr() ?: return emptyList()
    return arr.mapNotNull { item ->
        when (item) {
            is JsonPrimitive -> item.contentOrNull
            is JsonObject -> item.str("id", "app", "name")
            else -> null
        }
    }
}
