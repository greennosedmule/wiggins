package com.mulesipstea.wiggins.turns

import com.mulesipstea.wiggins.turns.TurnDetails.Origin

/** Turns as the sheet sees them, for tests and screenshots. Made-up names only: the repo is public. */
object SampleTurns {
    /** A spoken question the persona answered with a home tool and a phone action. */
    val persona = TurnDetails(
        startedAt = 0, origin = Origin.HUB_STT, utteranceId = "u1",
        listen = Span(0, 2_100), transcribe = Span(2_100, 2_480), sentMs = 2_480, matchedMs = 2_700, handledMs = 7_900,
        match = TurnDetails.Match("persona.openvoiceos", "persona:query", "ovos-persona-pipeline-plugin", mapOf("persona" to "Ada", "utterance" to "is the porch light on")),
        sentences = listOf(
            TurnDetails.Sentence("One moment.", "persona.openvoiceos", arrivedMs = 3_550, audioMs = 3_900, playStartMs = 3_900, playEndMs = 4_600),
            TurnDetails.Sentence("The porch light is off, and I've set your alarm.", "persona.openvoiceos", arrivedMs = 7_700, audioMs = 8_150, playStartMs = 8_150, playEndMs = 10_900),
        ),
        actions = listOf(TurnDetails.PhoneAction("w1", "intent", "android.intent.action.SET_ALARM", 6_250, 6_480, outcome = "ok")),
        hub = TurnDetails.HubReport(
            anchorMs = 2_740, persona = "Ada", model = "example-model-1", effort = "low",
            requests = listOf(
                TurnDetails.ModelRequest(10, 790, 1_480, "tool_use", 9_100, 8_800, null, 60, 24),
                TurnDetails.ModelRequest(3_840, 4_950, 5_300, "end_turn", 9_400, 9_100, null, 38, 10),
            ),
            tools = listOf(
                TurnDetails.ToolCall("homeassistant", "GetLiveContext", 1_490, 2_460, true),
                TurnDetails.ToolCall("waggle", "set_alarm", 3_420, 3_830, true),
            ),
        ),
    )

    /** A typed question a skill answered. */
    val skill = TurnDetails(
        startedAt = 0, origin = Origin.TYPED, utteranceId = "u2", sentMs = 0, matchedMs = 210, handledMs = 590,
        match = TurnDetails.Match("ovos-skill-date-time.openvoiceos", "ovos-skill-date-time.openvoiceos:what_time_is_it", "ovos-padatious-pipeline-plugin"),
        sentences = listOf(
            TurnDetails.Sentence("It's nine forty six.", "ovos-skill-date-time.openvoiceos", "time_current", mapOf("time" to "nine forty six"),
                arrivedMs = 480, audioMs = 820, playStartMs = 820, playEndMs = 2_050),
        ),
    )

    /** A watched question: Waggle showed the alarms and nothing was spoken. */
    val quietWaggle = TurnDetails(
        startedAt = 0, origin = Origin.TYPED, utteranceId = "u3", sentMs = 0, matchedMs = 180, handledMs = 1_020,
        match = TurnDetails.Match("ovos-skill-waggle.example", "ovos-skill-waggle.example:utterance", "ovos-waggle-pipeline-plugin",
            mapOf("request" to "show_alarms")),
        actions = listOf(TurnDetails.PhoneAction("w2", "intent", "android.intent.action.SHOW_ALARMS", 260, 880, outcome = "ok")),
    )

    /** The persona answered, but the hub sent no report of its own. */
    val personaNoReport = persona.copy(hub = null, actions = emptyList())
}
