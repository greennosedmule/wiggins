package com.mulesipstea.wiggins.hivemind

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import java.util.TimeZone

/**
 * The phone's locale settings, sent as the OVOS session so ordinary skills
 * answer for the phone rather than for the hub container's defaults
 * (docs/hivemind-protocol.md §11.1).
 */
data class SessionContext(
    val lang: String,
    val timezone: String,
    val systemUnit: String = "metric",
    val timeFormat: String = "full",
    val dateFormat: String = "DMY",
) {
    /**
     * The phone's settings as OVOS session fields. No `location`: the hub replaces a
     * session's whole location with whatever is sent, and the phone knows only its
     * timezone, so sending that alone would drop the hub's city and coordinates
     * (the weather skill then fails). See [timezoneJson] and HiveProtocol.sessionJson.
     */
    fun toSessionJson(sessionId: String, siteId: String): JsonObject = buildJsonObject {
        put("session_id", sessionId)
        put("lang", lang)
        put("site_id", siteId)
        put("system_unit", systemUnit)
        put("time_format", timeFormat)
        put("date_format", dateFormat)
    }

    /** The phone's timezone in OVOS's `location.timezone` shape. */
    fun timezoneJson(): JsonObject {
        val tz = TimeZone.getTimeZone(timezone)
        return buildJsonObject {
            put("code", tz.id)
            put("name", tz.getDisplayName(false, TimeZone.LONG, Locale.ENGLISH))
            put("offset", tz.rawOffset)
            put("dstOffset", tz.dstSavings)
        }
    }
}
