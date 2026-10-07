package com.mulesipstea.wiggins.waggle

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/*
 * Waggle v1 message models (WAGGLE.md). Validation follows the reference implementation,
 * ovos-skill-waggle's waggle/messages.py, wherever WAGGLE.md is silent: unknown fields are
 * ignored, a JSON null counts as an absent optional string, and anything malformed is
 * `bad_request`.
 */

/** A phone rule (WAGGLE.md "waggle.capabilities"). JSON key for [packageName] is "package". */
data class Rule(
    val action: String,
    val scheme: String? = null,
    val packageName: String? = null,
    val category: String? = null,
    val mode: Mode,
) {
    /** How many fields the rule sets; the action always counts (WAGGLE.md "Rule matching"). */
    internal val specificity: Int
        get() = 1 + listOf(scheme, packageName, category).count { it != null }

    fun toJson(): JsonObject = buildJsonObject {
        put("action", action)
        scheme?.let { put("scheme", it) }
        packageName?.let { put("package", it) }
        category?.let { put("category", it) }
        put("mode", mode.wire)
    }

    companion object {
        /** Parses a rule object; null if malformed. The scheme is lowercased, as schemes are case-insensitive. */
        fun fromJson(json: JsonObject): Rule? {
            val mode = Mode.fromWire(json.stringOrNull("mode")) ?: return null
            val action = json.stringOrNull("action")?.takeIf { it.isNotEmpty() } ?: return null
            val optional = listOf("scheme", "package", "category").map { key ->
                when (val value = json[key]) {
                    null, JsonNull -> null
                    else -> (value as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content
                        ?: return null
                }
            }
            return Rule(action, optional[0]?.lowercase(), optional[1], optional[2], mode)
        }
    }
}

/** The `data` of `waggle.capabilities`: what this phone allows. */
data class Capabilities(
    val clientName: String,
    val clientVersion: String,
    val timezone: String,
    val lang: String,
    val askTimeoutS: Int,
    val unmatched: Mode,
    val rules: List<Rule>,
    val queries: List<String>,
    val version: Int = Waggle.VERSION,
) {
    init {
        require(unmatched != Mode.RUN) { "unmatched must be block or ask" }
        require(askTimeoutS >= 0) { "ask_timeout_s must not be negative" }
    }

    /** The message `data`, in WAGGLE.md's field order. */
    fun toJson(): JsonObject = buildJsonObject {
        put("version", version)
        put("client", buildJsonObject {
            put("name", clientName)
            put("version", clientVersion)
        })
        put("timezone", timezone)
        put("lang", lang)
        put("ask_timeout_s", askTimeoutS)
        put("unmatched", unmatched.wire)
        put("rules", JsonArray(rules.map { it.toJson() }))
        put("queries", buildJsonArray { queries.forEach { add(JsonPrimitive(it)) } })
    }
}

/** A typed intent extra: [type] is one of the WAGGLE.md extra types and [value] matches it. */
data class Extra(val type: String, val value: JsonElement)

/** The validated `data` of a `waggle.intent`. */
data class IntentRequest(
    val id: String,
    val action: String,
    val description: String?,
    val data: String?,
    val mimeType: String?,
    val categories: List<String>,
    val packageName: String?,
    val extras: Map<String, Extra>,
) {
    /** The data URI's scheme, lowercased, or null. */
    val scheme: String?
        get() = data?.let(::uriScheme)

    companion object {
        /**
         * Parses and validates a `waggle.intent` `data`. Throws [WaggleException] with
         * [ErrorCode.BAD_REQUEST] for a missing or empty id or action, a field of the wrong
         * type, an unknown extra type, an extra value that doesn't fit its type, or a `data`
         * or `uri` extra without a scheme or with a forbidden one (any case).
         */
        fun parse(data: JsonObject): IntentRequest {
            val id = data.requiredString("id")
            val action = data.requiredString("action")
            val uri = data.optionalString("data")?.also { checkUri(it, "'data'") }
            val categories = when (val value = data["categories"]) {
                null -> emptyList()
                is JsonArray -> value.map { c ->
                    (c as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content
                        ?: throw badRequest("'categories' must be non-empty strings")
                }
                else -> throw badRequest("'categories' must be a list")
            }
            val extras = when (val value = data["extras"]) {
                null -> emptyMap()
                is JsonObject -> value.mapValues { (name, extra) -> parseExtra(name, extra) }
                else -> throw badRequest("'extras' must be an object")
            }
            return IntentRequest(
                id = id,
                action = action,
                description = data.optionalString("description"),
                data = uri,
                mimeType = data.optionalString("mime_type"),
                categories = categories,
                packageName = data.optionalString("package"),
                extras = extras,
            )
        }

        private fun parseExtra(name: String, json: JsonElement): Extra {
            if (name.isEmpty()) throw badRequest("extra names must be non-empty strings")
            val obj = json as? JsonObject ?: throw badRequest("extra '$name' must be an object")
            val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (type == null || type !in EXTRA_TYPES) throw badRequest("extra '$name' has unknown type ${obj["type"]}")
            val value = obj["value"] ?: JsonNull
            val primitive = value as? JsonPrimitive
            val number = primitive?.takeIf { !it.isString && it !is JsonNull }
            // An integer literal only: Python's json reads 1e3 or 6.0 as a float, which int and long refuse.
            val integer = number?.takeIf { INTEGER.matches(it.content) }?.content?.toLongOrNull()
            val ok = when (type) {
                "int" -> integer != null && integer in Int.MIN_VALUE..Int.MAX_VALUE
                "long" -> integer != null
                // Python's reference takes an int or a float here, never a bool.
                "float", "double" -> number != null && number.booleanOrNull == null && number.doubleOrNull != null
                "bool" -> number?.booleanOrNull != null
                "string" -> primitive?.isString == true
                "string[]" -> value is JsonArray && value.all { (it as? JsonPrimitive)?.isString == true }
                "uri" -> primitive?.isString == true && primitive.content.isNotEmpty()
                else -> false
            }
            if (!ok) throw badRequest("extra '$name' value $value is not a valid $type")
            if (type == "uri") checkUri(primitive!!.content, "extra '$name'")
            return Extra(type, value)
        }
    }
}

/** The `data` of a `waggle.query`. Query names and their params are checked by the query that runs. */
data class QueryRequest(val id: String, val name: String, val params: JsonObject) {
    companion object {
        /** Throws [WaggleException] with [ErrorCode.BAD_REQUEST] if id or name is missing or params isn't an object. */
        fun parse(data: JsonObject): QueryRequest {
            val id = data.requiredString("id")
            val name = data.requiredString("name")
            val params = when (val value = data["params"]) {
                null, JsonNull -> JsonObject(emptyMap())
                is JsonObject -> value
                else -> throw badRequest("'params' must be an object")
            }
            return QueryRequest(id, name, params)
        }
    }
}

/**
 * The request id from a request's data, if any, so even a malformed request can be answered.
 * An empty id comes back as is (WAGGLE.md allows answering with it).
 */
fun requestId(data: JsonObject?): String? =
    (data?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content

/** WAGGLE.md extra types. */
internal val EXTRA_TYPES = setOf("int", "long", "float", "double", "bool", "string", "string[]", "uri")

/** URI schemes never accepted in `data` or `uri` extras, compared lowercased. */
internal val FORBIDDEN_SCHEMES = setOf("content", "file", "intent", "android-app")

private val INTEGER = Regex("-?(0|[1-9][0-9]*)")

// RFC 3986's scheme syntax, as Android's Uri reads it.
private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

/** The URI's scheme, lowercased, or null if it has none. */
internal fun uriScheme(uri: String): String? = SCHEME.find(uri)?.groupValues?.get(1)?.lowercase()

private fun checkUri(uri: String, what: String) {
    if (uri.isEmpty()) throw badRequest("$what must be a non-empty string")
    val scheme = uriScheme(uri) ?: throw badRequest("$what has no scheme: $uri")
    if (scheme in FORBIDDEN_SCHEMES) throw badRequest("$what uses forbidden scheme '$scheme'")
}

internal fun badRequest(message: String) = WaggleException(ErrorCode.BAD_REQUEST, message)

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A non-empty string, or bad_request. */
private fun JsonObject.requiredString(key: String): String {
    val value = this[key]
    if (value == null || value is JsonNull) throw badRequest("missing '$key'")
    return (value as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content
        ?: throw badRequest("'$key' must be a non-empty string")
}

/** A string, null if absent or JSON null, or bad_request if it's anything else. */
private fun JsonObject.optionalString(key: String): String? {
    val value = this[key]
    if (value == null || value is JsonNull) return null
    return (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw badRequest("'$key' must be a string")
}
