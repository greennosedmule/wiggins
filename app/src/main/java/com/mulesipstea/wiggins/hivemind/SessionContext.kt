package com.mulesipstea.wiggins.hivemind

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
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
    fun toSessionJson(sessionId: String, siteId: String): JsonObject {
        val tz = TimeZone.getTimeZone(timezone)
        return buildJsonObject {
            put("session_id", sessionId)
            put("lang", lang)
            put("site_id", siteId)
            putJsonObject("location") {
                putJsonObject("timezone") {
                    put("code", tz.id)
                    put("name", tz.getDisplayName(false, TimeZone.LONG, Locale.ENGLISH))
                    put("offset", tz.rawOffset)
                    put("dstOffset", tz.dstSavings)
                }
            }
            put("system_unit", systemUnit)
            put("time_format", timeFormat)
            put("date_format", dateFormat)
        }
    }
}
