package com.mulesipstea.wiggins.waggle

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The Waggle protocol, v1 (`ovos-skill-waggle/WAGGLE.md`): the hub asks the phone to
 * launch Android intents and run named queries, within rules the phone's user sets.
 */
object Waggle {
    const val VERSION = 1

    const val CAPABILITIES = "waggle.capabilities"
    const val INTENT = "waggle.intent"
    const val INTENT_RESPONSE = "waggle.intent.response"
    const val QUERY = "waggle.query"
    const val QUERY_RESPONSE = "waggle.query.response"
}

/** What a rule, or the unmatched setting, does with an intent. Declared from least to most strict. */
enum class Mode(val wire: String) {
    RUN("run"),
    ASK("ask"),
    BLOCK("block"),
    ;

    companion object {
        fun fromWire(value: String?): Mode? = entries.firstOrNull { it.wire == value }
    }
}

/** WAGGLE.md "Error codes". */
enum class ErrorCode(val wire: String) {
    BLOCKED("blocked"),
    DECLINED("declined"),
    TIMEOUT("timeout"),
    NO_HANDLER("no_handler"),
    LAUNCH_FAILED("launch_failed"),
    PERMISSION_DENIED("permission_denied"),
    BAD_REQUEST("bad_request"),
    UNSUPPORTED_VERSION("unsupported_version"),
}

/** A request failed with [code]; [message] is detail for logs, never spoken. */
class WaggleException(val code: ErrorCode, message: String) : Exception(message)

/** The answer to a `waggle.intent` or `waggle.query` (WAGGLE.md "Common rules"). */
data class WaggleResponse(
    val id: String,
    val ok: Boolean,
    val error: ErrorCode? = null,
    val message: String? = null,
    val data: JsonObject? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("ok", ok)
        error?.let { put("error", it.wire) }
        message?.let { put("message", it) }
        data?.let { put("data", it) }
    }

    companion object {
        fun ok(id: String, data: JsonObject? = null) = WaggleResponse(id, ok = true, data = data)
        fun error(id: String, code: ErrorCode, message: String? = null) = WaggleResponse(id, ok = false, error = code, message = message)
    }
}
