package com.mulesipstea.wiggins.waggle

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The ask card's sentence for Clock requests, read from the extras as DeskClock reads them. */
class ClockRequestsTest {
    private val x = "android.intent.extra.alarm."

    private fun summary(action: String, extras: String = ""): String? {
        val request = IntentRequest.parse(
            Json.parseToJsonElement("""{"id":"r","action":"android.intent.action.$action","extras":{$extras}}""").jsonObject,
        )
        return ClockRequests.summary(request) { h, m -> "%02d:%02d".format(h, m) }
    }

    private fun mode(value: String) = """"${x}SEARCH_MODE":{"type":"string","value":"$value"}"""
    private fun int(name: String, value: Int) = """"$x$name":{"type":"int","value":$value}"""

    @Test fun dismissByTime() {
        assertEquals(
            "Dismisses the alarm at 06:30. A repeating alarm skips only its next ring.",
            summary("DISMISS_ALARM", "${mode("android.time")},${int("HOUR", 6)},${int("MINUTES", 30)}"),
        )
        // Minutes default to 0; IS_PM moves a 12-hour hour into the afternoon, as DeskClock does.
        assertEquals(
            "Dismisses the alarm at 19:00. A repeating alarm skips only its next ring.",
            summary("DISMISS_ALARM", """${mode("android.time")},${int("HOUR", 7)},"${x}IS_PM":{"type":"bool","value":true}"""),
        )
        assertEquals(
            "Asks for an invalid time; the Clock will refuse. A repeating alarm skips only its next ring.",
            summary("DISMISS_ALARM", "${mode("android.time")},${int("HOUR", 24)}"),
        )
    }

    @Test fun dismissByOtherModes() {
        assertEquals("Dismisses the next alarm, or the one ringing now. A repeating alarm skips only its next ring.",
            summary("DISMISS_ALARM", mode("android.next")))
        assertEquals("Dismisses ALL your alarms. A repeating alarm skips only its next ring.",
            summary("DISMISS_ALARM", mode("android.all")))
        assertEquals(
            "Dismisses the alarm labelled “work” (any label containing it, case matters). " +
                "A repeating alarm skips only its next ring.",
            summary("DISMISS_ALARM", """${mode("android.label")},"${x}MESSAGE":{"type":"string","value":"work"}"""),
        )
        assertEquals("Dismisses your only alarm, or opens the Clock to choose one. A repeating alarm skips only its next ring.",
            summary("DISMISS_ALARM"))
    }

    @Test fun timersAndSnooze() {
        assertEquals("Stops any timer that's ringing. Running timers are left alone.", summary("DISMISS_TIMER"))
        assertEquals("Opens the Clock's timers.", summary("SHOW_TIMERS"))
        assertEquals("Snoozes any alarm that's ringing.", summary("SNOOZE_ALARM"))
        assertEquals("Snoozes any alarm that's ringing for 5 min, if the Clock allows it.",
            summary("SNOOZE_ALARM", int("SNOOZE_DURATION", 5)))
    }

    @Test fun otherIntentsHaveNoSummary() {
        assertNull(summary("SET_ALARM", "${int("HOUR", 6)}"))
        assertNull(summary("DIAL"))
    }
}
