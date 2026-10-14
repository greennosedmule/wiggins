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
    /**
     * The phone is locked: a voice session over a car's or headset's hands-free link that
     * runs without the unlock (SPEC "Locked, in the car"). Sent in every uplink's message
     * context as [LOCKED_KEY], true or false, so the hub can hold back what a locked
     * phone mustn't reach. Not a session field: OVOS's Session drops fields it doesn't know.
     */
    val locked: Boolean = false,
    /**
     * The user is looking at a Wiggins screen: the question comes from the assistant panel
     * or the app, on an unlocked phone, and not over a car's or headset's hands-free link.
     * Sent as [WATCHING_KEY], true or false, on every uplink; the hub then doesn't speak a
     * confirmation whose result is on screen (WAGGLE.md "Watching").
     */
    val watching: Boolean = false,
    /**
     * A persona on the hub gets the user's words exactly as sent, not the normalized text
     * skills match on. Sent as [RAW_UTTERANCE_KEY], true or false, on every uplink.
     */
    val rawUtterance: Boolean = true,
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

    companion object {
        /** The message-context key for [locked]; see docs/hivemind-protocol.md §11.1. */
        const val LOCKED_KEY = "device_locked"

        /** The message-context key for [watching]; see docs/hivemind-protocol.md §11.1. */
        const val WATCHING_KEY = "user_watching"

        /** The message-context key for [rawUtterance]; see docs/hivemind-protocol.md §11.1. */
        const val RAW_UTTERANCE_KEY = "persona_raw_utterance"
    }
}
