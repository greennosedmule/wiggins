package com.mulesipstea.wiggins.waggle

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Says in words what a Clock request will do, for the ask card (SPEC "Asking"). The hub's
 * description is its own claim; this is read from the intent's extras, the way the stock
 * Clock (AOSP DeskClock's HandleApiCalls) reads them, so a request can't say "the 6:30
 * alarm" and dismiss every alarm.
 */
object ClockRequests {
    private const val A = "android.intent.action."
    private const val X = "android.intent.extra.alarm."

    /**
     * A sentence for a Clock [request], or null if it isn't one this describes. [time]
     * formats a 24-hour clock time in the phone's style ("6:30 AM" or "06:30").
     */
    fun summary(request: IntentRequest, time: (hour: Int, minute: Int) -> String): String? {
        val extras = request.extras
        fun int(name: String) = (extras[X + name]?.value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        fun bool(name: String) = (extras[X + name]?.value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        fun string(name: String) = (extras[X + name]?.value as? JsonPrimitive)?.takeIf { it.isString }?.content

        return when (request.action) {
            "${A}SHOW_TIMERS" -> "Opens the Clock's timers."
            "${A}DISMISS_TIMER" -> "Stops any timer that's ringing. Running timers are left alone."
            "${A}SNOOZE_ALARM" -> "Snoozes any alarm that's ringing" +
                (int("SNOOZE_DURATION")?.let { " for $it min, if the Clock allows it." } ?: ".")
            "${A}DISMISS_ALARM" -> dismissAlarm(string("SEARCH_MODE"), int("HOUR"), int("MINUTES"), bool("IS_PM"), string("MESSAGE"), time) +
                " A repeating alarm skips only its next ring."
            else -> null
        }
    }

    private fun dismissAlarm(
        mode: String?,
        hour: Int?,
        minutes: Int?,
        isPm: Boolean?,
        label: String?,
        time: (Int, Int) -> String,
    ): String = when (mode) {
        "android.next" -> "Dismisses the next alarm, or the one ringing now."
        "android.all" -> "Dismisses ALL your alarms."
        "android.label" -> if (label == null) "Asks for an alarm by label but gives none; the Clock will refuse." else
            "Dismisses the alarm labelled “$label” (any label containing it, case matters)."
        "android.time" -> {
            // As DeskClock reads it: minutes default to 0, IS_PM moves 1–11 into the afternoon.
            val h = hour ?: -1
            val m = minutes ?: 0
            if (h !in 0..23 || m !in 0..59 || (isPm == true && h > 12)) {
                "Asks for an invalid time; the Clock will refuse."
            } else {
                "Dismisses the alarm at ${time(if (isPm == true && h < 12) h + 12 else h, m)}."
            }
        }
        null -> "Dismisses your only alarm, or opens the Clock to choose one."
        else -> "Uses an unknown search mode ($mode)."
    }
}
