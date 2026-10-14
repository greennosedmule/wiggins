package com.mulesipstea.wiggins.turns

import com.mulesipstea.wiggins.speech.SpeechProgress
import com.mulesipstea.wiggins.turns.TurnDetails.Origin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a turn's details are collected (SPEC "Reply details"). */
class TurnRecorderTest {
    private var now = 1_000_000L
    private val recorder = TurnRecorder { now }
    private val kept = mutableMapOf<String, TurnDetails>()

    private fun sink(name: String) = TurnRecorder.Sink { kept[name] = it }
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun after(ms: Long) { now += ms }

    private fun bus(type: String, data: String = "{}", utterance: String? = "u1") =
        recorder.onDownlink(type, json("""{"type":"$type","data":$data,"context":{${utterance?.let { "\"utterance_id\":\"$it\"" } ?: ""}}}"""))

    private val matched = """{"skill_id":"ovos-skill-date-time.openvoiceos","intent_name":"ovos-skill-date-time.openvoiceos:what_time_is_it",
        "pipeline_id":"ovos-padatious-pipeline-plugin","slots":{"place":"Paris","count":2}}"""

    @Test fun aTypedSkillTurnFromSendToLastWord() {
        recorder.asked("q")
        recorder.sent("q", sink("q"))
        after(200)
        bus("ovos.intent.matched", matched)
        after(300)
        val tag = recorder.onSpeak("It's nine forty six.", json("""{"skill":"ovos-skill-date-time.openvoiceos","dialog":"time_current","data":{"time":"nine forty six"}}"""),
            expectResponse = false, spoken = true) { error("in a turn") }
        after(100)
        bus("ovos.utterance.handled")
        after(150)
        recorder.onSpeech(tag, SpeechProgress.AUDIO)
        recorder.onSpeech(tag, SpeechProgress.START)
        after(1_200)
        recorder.onSpeech(tag, SpeechProgress.END)

        val d = kept.getValue("q")
        assertEquals(Origin.TYPED, d.origin)
        assertEquals("u1", d.utteranceId)
        assertEquals(0L, d.sentMs)
        assertEquals(200L, d.matchedMs)
        assertEquals(600L, d.handledMs)
        assertEquals("ovos-padatious-pipeline-plugin", d.match?.pipelineId)
        assertEquals(mapOf("place" to "Paris", "count" to "2"), d.match?.slots)
        val s = d.sentences.single()
        assertEquals("time_current", s.dialog)
        assertEquals(mapOf("time" to "nine forty six"), s.data)
        // Speech that plays after the hub finished still lands in the turn.
        assertEquals(listOf(500L, 750L, 750L, 1_950L), listOf(s.arrivedMs, s.audioMs, s.playStartMs, s.playEndMs))
        assertFalse(recorder.inTurn)
    }

    @Test fun hubSpeechTimesTheRecordingAndTranscription() {
        recorder.listeningStarted()
        after(2_000)
        recorder.recorded("q")
        after(400)
        recorder.transcribed("q")
        recorder.sent("q", sink("q"))
        val d = kept.getValue("q")
        assertEquals(Origin.HUB_STT, d.origin)
        assertEquals(Span(0, 2_000), d.listen)
        assertEquals(Span(2_000, 2_400), d.transcribe)
        assertEquals(2_400L, d.sentMs)
        assertNull(d.connect)
    }

    @Test fun theDevicesRecognizerIsOneSpan() {
        recorder.listeningStarted(device = true)
        after(3_000)
        recorder.asked("q")
        recorder.sent("q", sink("q"))
        val d = kept.getValue("q")
        assertEquals(Origin.DEVICE_STT, d.origin)
        assertEquals(Span(0, 3_000), d.listen)
        assertNull(d.transcribe)
    }

    @Test fun aQuestionThatWaitedForTheConnectionShowsTheWait() {
        recorder.asked("q")
        after(1_500)
        recorder.sent("q", sink("q"))
        assertEquals(Span(0, 1_500), kept.getValue("q").connect)
    }

    @Test fun aQuietWaggleTurnKeepsTheActionAndTheAskWait() {
        recorder.asked("q")
        recorder.sent("q", sink("q"))
        after(300)
        recorder.actionStarted("w1", "intent", "android.intent.action.DIAL")
        after(100)
        recorder.onAsks(setOf("w1"))
        after(2_000)
        recorder.onAsks(emptySet())
        after(50)
        recorder.actionDone("w1", "ok")
        after(100)
        bus("ovos.utterance.handled")
        val d = kept.getValue("q")
        assertEquals(TurnDetails.PhoneAction("w1", "intent", "android.intent.action.DIAL", 300, 2_450, 400, 2_400, "ok"), d.actions.single())
        assertTrue(d.sentences.isEmpty())
        assertEquals(2_550L, d.handledMs)
    }

    @Test fun anotherUtterancesMessagesAreIgnored() {
        recorder.asked("q")
        recorder.sent("q", sink("q"))
        bus("ovos.intent.matched", matched)
        bus("ovos.utterance.handled", utterance = "older")
        assertTrue(recorder.inTurn)
        assertNull(kept.getValue("q").handledMs)
    }

    @Test fun speechOutsideATurnKeepsItsOwnDetails() {
        recorder.onSpeak("Your timer is done.", json("""{"skill":"ovos-skill-waggle.example"}"""), expectResponse = false, spoken = false) { sink("timer") }
        val d = kept.getValue("timer")
        assertEquals(Origin.HUB, d.origin)
        assertEquals("ovos-skill-waggle.example", d.sentences.single().skill)
        assertFalse(d.sentences.single().spoken)
    }

    @Test fun aFollowUpQuestionIsNoted() {
        recorder.asked("q")
        recorder.sent("q", sink("q"))
        bus("ovos.mic.listen")
        assertTrue(kept.getValue("q").followUp)
    }

    @Test fun thePersonasReportIsPlacedByItsSendTime() {
        recorder.asked("q")
        recorder.sent("q", sink("q"))
        after(4_000)
        bus(TurnRecorder.PERSONA_REPORT, """{"v":1,"sent_ms":3500,"persona":"Ada","model":"example-model","effort":"low",
            "requests":[{"start_ms":20,"first_ms":900,"end_ms":1400,"stop":"tool_use","input_tokens":900,"cache_read_tokens":800,"cache_write_tokens":50,"output_tokens":40,"thinking_tokens":12},
                        {"start_ms":2100,"end_ms":3400,"stop":"end_turn"},{"no_start":1}],
            "tools":[{"server":"homeassistant","name":"GetLiveContext","start_ms":1410,"end_ms":2090,"ok":true}],
            "speaks":[{"at_ms":3400}],"future_field":true}""")
        val hub = kept.getValue("q").hub!!
        assertEquals(500L, hub.anchorMs) // arrived at 4000, sent 3500 ms after the hub's zero
        assertEquals("Ada", hub.persona)
        assertEquals(2, hub.requests.size)
        assertEquals(TurnDetails.ModelRequest(20, 900, 1400, "tool_use", 900, 800, 50, 40, 12), hub.requests.first())
        assertEquals(TurnDetails.ToolCall("homeassistant", "GetLiveContext", 1410, 2090, true), hub.tools.single())
        assertFalse(hub.truncated)
    }

    /** The hub's own output for a turn with one tool call, as its persona sends it (mocked model, so the tokens are made up). */
    @Test fun aReportAsTheHubSendsIt() {
        val report = TurnRecorder.report(json("""{"v": 1, "persona": "Claude", "model": "claude-opus-5-5", "effort": "low",
            "requests": [{"start_ms": 28, "first_ms": 35, "end_ms": 37, "stop": "tool_use", "input_tokens": 0, "output_tokens": 20},
                         {"start_ms": 59, "first_ms": 63, "end_ms": 68, "stop": "end_turn", "input_tokens": 0, "output_tokens": 20}],
            "tools": [{"server": "waggle", "name": "show_alarms", "start_ms": 47, "end_ms": 59, "ok": true}],
            "speaks": [{"at_ms": 37}, {"at_ms": 63}, {"at_ms": 68}], "sent_ms": 71}"""), arrivedMs = 1_000)!!
        assertEquals(929L, report.anchorMs)
        assertEquals("Claude", report.persona)
        assertEquals(listOf("tool_use", "end_turn"), report.requests.map { it.stop })
        assertEquals(TurnDetails.ToolCall("waggle", "show_alarms", 47, 59, true), report.tools.single())
    }

    @Test fun costsAreReadInEitherSpelling() {
        val usd = TurnRecorder.report(json("""{"v":1,"sent_ms":10,"cost_usd":0.042,"pricing":"list price","requests":[{"start_ms":0,"cost_usd":0.04}]}"""), 10)!!
        assertEquals(0.042, usd.cost!!, 1e-9)
        assertEquals("USD", usd.currency)
        assertEquals("list price", usd.pricing)
        assertEquals(0.04, usd.requests.single().cost!!, 1e-9)
        val eur = TurnRecorder.report(json("""{"v":1,"sent_ms":10,"cost":0.03,"cost_currency":"EUR"}"""), 10)!!
        assertEquals("EUR", eur.currency)
        assertNull(TurnRecorder.report(json("""{"v":1,"sent_ms":10,"cost":"lots"}"""), 10)!!.cost)
    }

    @Test fun anUnknownReportVersionIsIgnored() {
        assertNull(TurnRecorder.report(json("""{"v":2,"sent_ms":1}"""), 10))
        assertNull(TurnRecorder.report(json("""{"sent_ms":1}"""), 10))
    }

    @Test fun aLongReportIsCapped() {
        val tools = (1..60).joinToString(",") { """{"name":"t$it","start_ms":$it}""" }
        val report = TurnRecorder.report(json("""{"v":1,"sent_ms":100,"tools":[$tools]}"""), 100)!!
        assertEquals(50, report.tools.size)
        assertTrue(report.truncated)
    }

    @Test fun aNewQuestionEndsTheTurnBefore() {
        recorder.asked("a")
        recorder.sent("a", sink("a"))
        recorder.asked("b")
        recorder.sent("b", sink("b"))
        bus("ovos.intent.matched", matched)
        assertNull(kept.getValue("a").match)
        assertEquals("ovos-skill-date-time.openvoiceos", kept.getValue("b").match?.skillId)
    }

}
