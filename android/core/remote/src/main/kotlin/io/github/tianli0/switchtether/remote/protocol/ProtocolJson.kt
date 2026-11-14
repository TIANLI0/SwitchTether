package io.github.tianli0.switchtether.remote.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * JSON configuration for the wire protocol (docs/PROTOCOL.md §9: clients must ignore unknown
 * fields; additive changes do not bump the protocol version).
 */
val ProtocolJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

/** Protocol version implemented by this client. */
const val CLIENT_PROTOCOL_VERSION: Int = 1

// ---- Lenient accessors for JSON views whose exact shape is owned by CC Switch upstream ----

fun JsonElement?.obj(): JsonObject? = this as? JsonObject

fun JsonElement?.arr(): JsonArray? = this as? JsonArray

fun JsonObject.str(vararg keys: String): String? {
    for (k in keys) {
        val v = this[k]
        if (v is JsonPrimitive && v !is JsonNull) return v.contentOrNull
    }
    return null
}

fun JsonObject.long(vararg keys: String): Long? {
    for (k in keys) {
        val v = this[k] as? JsonPrimitive ?: continue
        if (v is JsonNull) continue
        v.longOrNull?.let { return it }
        v.doubleOrNull?.let { return it.toLong() }
        v.contentOrNull?.trim()?.toDoubleOrNull()?.let { return it.toLong() }
    }
    return null
}

/** Numbers are sometimes encoded as strings upstream (e.g. `totalCost: "1.2345"`). */
fun JsonObject.double(vararg keys: String): Double? {
    for (k in keys) {
        val v = this[k] as? JsonPrimitive ?: continue
        if (v is JsonNull) continue
        v.doubleOrNull?.let { return it }
        v.contentOrNull?.trim()?.toDoubleOrNull()?.let { return it }
    }
    return null
}

fun JsonObject.bool(vararg keys: String): Boolean? {
    for (k in keys) {
        val v = this[k] as? JsonPrimitive ?: continue
        if (v is JsonNull) continue
        v.booleanOrNull?.let { return it }
    }
    return null
}

/**
 * A list-shaped view: accepts a JSON array, an object with an `items`/given key holding an array,
 * or an `id -> value` map (CC Switch uses maps for MCP servers and prompts). Map entries get their
 * key injected as `id` when absent.
 */
fun JsonElement?.listOfObjects(vararg wrapperKeys: String): List<JsonObject> {
    when (this) {
        is JsonArray -> return mapNotNull { it as? JsonObject }
        is JsonObject -> {
            for (k in wrapperKeys.toList() + listOf("items", "data")) {
                val inner = this[k]
                if (inner is JsonArray) return inner.mapNotNull { it as? JsonObject }
                if (inner is JsonObject && k in wrapperKeys) return inner.listOfObjects()
            }
            // id -> object map
            if (values.isNotEmpty() && values.all { it is JsonObject }) {
                return entries.map { (key, v) ->
                    val o = v as JsonObject
                    if (o.containsKey("id")) o else JsonObject(o + ("id" to JsonPrimitive(key)))
                }
            }
            return emptyList()
        }
        else -> return emptyList()
    }
}
