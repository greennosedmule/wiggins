package com.mulesipstea.wiggins.waggle.queries

import android.content.Context
import android.content.pm.PackageManager
import com.mulesipstea.wiggins.waggle.ErrorCode
import com.mulesipstea.wiggins.waggle.WaggleException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.text.Normalizer
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** A named read the hub can ask for (WAGGLE.md "`waggle.query`"). */
interface Query {
    /** The wire name: `calendar.next`, `contacts.lookup` or `apps.list`. */
    val name: String

    /** The runtime permission the query needs, or null for none. */
    val permission: String?

    /**
     * Runs the query off the main thread and returns the response `data`. Throws
     * [WaggleException] with [ErrorCode.BAD_REQUEST] for bad params (wrong types, out of
     * range, missing required) and [ErrorCode.PERMISSION_DENIED] if [permission] isn't
     * granted. Params are checked first, so a malformed request is reported as such even
     * without the permission.
     */
    suspend fun run(context: Context, params: JsonObject): JsonObject
}

/** The queries Wiggins implements. */
object Queries {
    val all: List<Query> = listOf(CalendarNext, ContactsLookup, AppsList)

    fun named(name: String): Query? = all.firstOrNull { it.name == name }
}

// --- params (the rules of ovos-skill-waggle's waggle/messages.py _validate_params) ---

internal fun badRequest(message: String) = WaggleException(ErrorCode.BAD_REQUEST, message)

/**
 * The integer param [key], or [default] if it's absent. A JSON number with no fraction
 * (not a string, boolean or null) from [min] to [max]. Values past [Int.MAX_VALUE] are
 * accepted when there's no [max] and clamped, since they mean "no limit" anyway.
 */
internal fun JsonObject.intParam(key: String, default: Int, min: Int, max: Int? = null): Int {
    val element = this[key] ?: return default
    val value = (element as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    if (value == null || value < min || (max != null && value > max)) {
        val bounds = if (max != null) "$min–$max" else ">= $min"
        throw badRequest("param '$key' must be an integer $bounds, got $element")
    }
    return value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/** The string param [key]; absent or null is null, or a bad request if [required] (as is ""). */
internal fun JsonObject.stringParam(key: String, required: Boolean = false): String? {
    val element = this[key]
    if (element == null || element is JsonNull) {
        if (required) throw badRequest("missing param '$key'")
        return null
    }
    val value = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (value == null || (required && value.isEmpty())) {
        throw badRequest(if (required) "param '$key' must be a non-empty string" else "param '$key' must be a string")
    }
    return value
}

internal fun requirePermission(context: Context, permission: String?) {
    if (permission != null && context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
        throw WaggleException(ErrorCode.PERMISSION_DENIED, "$permission isn't granted")
    }
}

// --- matching and formatting ---

private val COMBINING_MARKS = Regex("\\p{Mn}+")

/** [text] folded for name matching: case-insensitive and accent-insensitive ("José" matches "jose"). */
internal fun foldForMatch(text: String): String =
    Normalizer.normalize(text.trim(), Normalizer.Form.NFKD).replace(COMBINING_MARKS, "").lowercase(Locale.ROOT)

/** Epoch millis as a Waggle timestamp: ISO 8601 UTC, whole seconds, with `Z`. */
internal fun wireInstant(millis: Long): String =
    DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(millis).truncatedTo(ChronoUnit.SECONDS))
