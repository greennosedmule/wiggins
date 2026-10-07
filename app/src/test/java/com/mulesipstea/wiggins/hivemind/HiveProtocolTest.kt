package com.mulesipstea.wiggins.hivemind

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replays the hub side of full_session.json through [HiveProtocol]. */
class HiveProtocolTest {
    private val context = SessionContext(lang = "en-US", timezone = "America/New_York", systemUnit = "imperial", timeFormat = "half", dateFormat = "MDY")

    @Test fun replaysRecordedSessions() {
        for (session in Vectors.cases("full_session.json", "sessions")) {
            val name = session.str("name")
            val negotiated = session.getValue("negotiated").jsonObject
            if (HiveEncoding.fromWire(negotiated.str("encoding")) == null) continue
            val inputs = session.getValue("inputs").jsonObject
            val proto = HiveProtocol(
                useragent = inputs.str("useragent"),
                password = inputs.str("password"),
                handshake = PasswordHandshake(inputs.str("password"), inputs.hex("client_iv_hex")),
            ).also { it.setSession(context) }
            val hubCipher = FrameCipher(
                negotiated.hex("key_hex"),
                checkNotNull(HiveCipher.fromWire(negotiated.str("cipher"))),
                checkNotNull(HiveEncoding.fromWire(negotiated.str("encoding"))),
            )

            val sent = mutableListOf<String>()
            val events = mutableListOf<HubEvent>()
            var connected = false
            for (frame in session.arr("frames").map { it.jsonObject }.filter { it.str("dir") == "hub->client" }) {
                for (out in proto.onFrame(frame.str("wire"))) when (out) {
                    is HiveProtocol.Output.Send -> sent += out.text
                    is HiveProtocol.Output.Event -> events += out.event
                    is HiveProtocol.Output.Connected -> connected = true
                    is HiveProtocol.Output.Fail -> throw AssertionError("$name: ${out.reason}")
                }
            }
            assertTrue(name, connected)

            // Our plaintext HANDSHAKE equals the client's recorded one when we offer the same thing.
            val recordedShake = session.arr("frames").map { it.jsonObject }
                .first { it.str("dir") == "client->hub" && it.str("hive_msg_type") == "shake" }
            val ourShake = sent[0].json().jsonObject
            assertEquals(name, recordedShake.str("plaintext").json().jsonObject["payload"]!!.jsonObject["envelope"], ourShake["payload"]!!.jsonObject["envelope"])

            // HELLO is encrypted under the derived key and carries a real session id.
            val hello = hubCipher.open(sent[1]).json().jsonObject
            assertEquals("hello", hello.str("msg_type"))
            assertEquals(setOf("msg_type", "payload"), hello.keys)
            val sessionJson = hello.getValue("payload").jsonObject.getValue("session").jsonObject
            assertEquals(proto.sessionId, sessionJson.str("session_id"))

            // Utterances are encrypted BUS messages the hub can read.
            val utterance = hubCipher.open(checkNotNull(proto.utterance("what time is it", context))).json().jsonObject
            val payload = utterance.getValue("payload").jsonObject
            assertEquals("recognizer_loop:utterance", payload.str("type"))
            assertEquals("what time is it", payload.getValue("data").jsonObject.arr("utterances")[0].jsonPrimitive.content)

            // Every downlink BUS message is logged, and speak is surfaced.
            val busTypes = events.filterIsInstance<HubEvent.Downlink>().filter { it.hiveType == "bus" }.map { it.busType }
            assertTrue(name, "speak" in busTypes && "ovos.utterance.handled" in busTypes)
            assertNotNull(name, events.filterIsInstance<HubEvent.Speak>().firstOrNull()?.utterance)
        }
    }

    @Test fun wrongPasswordFailsBeforeSendingEncrypted() {
        val session = Vectors.cases("full_session.json", "sessions")[0]
        val proto = HiveProtocol("Wiggins", "not-the-password").also { it.setSession(context) }
        val outs = session.arr("frames").map { it.jsonObject }.filter { it.str("dir") == "hub->client" && !it.bool("encrypted") }
            .flatMap { proto.onFrame(it.str("wire")) }
        val fail = outs.filterIsInstance<HiveProtocol.Output.Fail>().single()
        assertTrue(fail.reason, fail.reason.startsWith("Wrong password"))
        assertEquals(1, outs.filterIsInstance<HiveProtocol.Output.Send>().size) // only the plaintext HANDSHAKE
    }

    /** OVOS keeps get_response state only in the session the client sends back. */
    @Test fun echoesTheHubsSessionWithThePhonesSettings() {
        val session = Vectors.cases("full_session.json", "sessions")[0]
        val inputs = session.getValue("inputs").jsonObject
        val negotiated = session.getValue("negotiated").jsonObject
        val proto = HiveProtocol(
            useragent = "Wiggins",
            password = inputs.str("password"),
            handshake = PasswordHandshake(inputs.str("password"), inputs.hex("client_iv_hex")),
        ).also { it.setSession(context) }
        session.arr("frames").map { it.jsonObject }.filter { it.str("dir") == "hub->client" && !it.bool("encrypted") }
            .forEach { proto.onFrame(it.str("wire")) }
        assertTrue(proto.isReady)
        val hub = FrameCipher(negotiated.hex("key_hex"), HiveCipher.CHACHA20_POLY1305, HiveEncoding.JSON_B64)

        // The alerts skill asks a question; its session marks it as waiting for the answer.
        val speak = """{"msg_type":"bus","payload":{"type":"speak","data":{"utterance":"For what time?","expect_response":true},
            "context":{"session":{"session_id":"${proto.sessionId}","lang":"en-us","site_id":"phone",
            "utterance_states":{"ovos-skill-alerts.openvoiceos":"response"},"active_skills":[["ovos-skill-alerts.openvoiceos",1.0]]}}}}"""
        proto.onFrame(hub.seal(speak))
        // A session for someone else's session_id is ignored.
        proto.onFrame(hub.seal(speak.replace(proto.sessionId, "someone-else").replace("alerts", "other")))

        val sent = hub.open(checkNotNull(proto.utterance("6:30", context))).json().jsonObject
        val sentSession = sent.getValue("payload").jsonObject.getValue("context").jsonObject.getValue("session").jsonObject
        assertEquals("response", sentSession.getValue("utterance_states").jsonObject.str("ovos-skill-alerts.openvoiceos"))
        assertTrue("active_skills" in sentSession)
        assertEquals("en-US", sentSession.str("lang"))
        assertEquals(proto.sessionId, sentSession.str("session_id"))
        assertEquals("imperial", sentSession.str("system_unit"))
    }

    /** A reconnect in the same session hands OVOS's state back in the new connection's HELLO. */
    @Test fun carriedSessionGoesInHello() {
        val session = Vectors.cases("full_session.json", "sessions")[0]
        val inputs = session.getValue("inputs").jsonObject
        val negotiated = session.getValue("negotiated").jsonObject
        val carried = "{\"session_id\":\"s1\",\"utterance_states\":{\"ovos-skill-alerts.openvoiceos\":\"response\"}}".json().jsonObject
        val proto = HiveProtocol(
            useragent = "Wiggins",
            password = inputs.str("password"),
            sessionId = "s1",
            handshake = PasswordHandshake(inputs.str("password"), inputs.hex("client_iv_hex")),
            initialSession = carried,
        ).also { it.setSession(context) }
        val sent = session.arr("frames").map { it.jsonObject }.filter { it.str("dir") == "hub->client" && !it.bool("encrypted") }
            .flatMap { proto.onFrame(it.str("wire")) }.filterIsInstance<HiveProtocol.Output.Send>()
        val hub = FrameCipher(negotiated.hex("key_hex"), HiveCipher.CHACHA20_POLY1305, HiveEncoding.JSON_B64)
        val hello = hub.open(sent.last().text).json().jsonObject.getValue("payload").jsonObject.getValue("session").jsonObject
        assertEquals("response", hello.getValue("utterance_states").jsonObject.str("ovos-skill-alerts.openvoiceos"))
        assertEquals("en-US", hello.str("lang"))
        assertEquals(carried, proto.handedBackSession)
    }

    /** Hub speech (SPEC "Hub speech-to-text", "Hub text-to-speech"): requests carry an id the responses are matched by. */
    @Test fun speechRequestsAndResponses() {
        val session = Vectors.cases("full_session.json", "sessions")[0]
        val inputs = session.getValue("inputs").jsonObject
        val negotiated = session.getValue("negotiated").jsonObject
        val proto = HiveProtocol(
            useragent = "Wiggins",
            password = inputs.str("password"),
            handshake = PasswordHandshake(inputs.str("password"), inputs.hex("client_iv_hex")),
        ).also { it.setSession(context) }
        session.arr("frames").map { it.jsonObject }.filter { it.str("dir") == "hub->client" && !it.bool("encrypted") }
            .forEach { proto.onFrame(it.str("wire")) }
        val hub = FrameCipher(negotiated.hex("key_hex"), HiveCipher.CHACHA20_POLY1305, HiveEncoding.JSON_B64)

        val transcribe = hub.open(checkNotNull(proto.transcribe("t1", byteArrayOf(1, 2, 3), context))).json().jsonObject.getValue("payload").jsonObject
        assertEquals("recognizer_loop:b64_transcribe", transcribe.str("type"))
        val tData = transcribe.getValue("data").jsonObject
        assertEquals("AQID", tData.str("audio"))
        assertEquals("en-US", tData.str("lang"))
        assertEquals("16000", tData.getValue("sample_rate").jsonPrimitive.content)
        assertEquals("2", tData.getValue("sample_width").jsonPrimitive.content)
        assertEquals("t1", transcribe.getValue("context").jsonObject.str("wiggins_id"))

        val synthesize = hub.open(checkNotNull(proto.synthesize("s1", "Hello.", context))).json().jsonObject.getValue("payload").jsonObject
        assertEquals("speak:b64_audio", synthesize.str("type"))
        val sData = synthesize.getValue("data").jsonObject
        assertEquals("Hello.", sData.str("utterance"))
        assertEquals("en-US", sData.str("lang"))
        assertEquals("s1", sData.str("wiggins_id"))

        // The plugin answers with Message.reply, which copies the request's context.
        fun events(json: String) = proto.onFrame(hub.seal(json)).filterIsInstance<HiveProtocol.Output.Event>().map { it.event }
        val heard = events("""{"msg_type":"bus","payload":{"type":"recognizer_loop:b64_transcribe.response",
            "data":{"transcriptions":[["what time is it",0.92],["what time is", 0.5]]},
            "context":{"wiggins_id":"t1","session":{"session_id":"${proto.sessionId}"}}}}""")
        assertEquals(HubEvent.Transcription("t1", "what time is it"), heard.filterIsInstance<HubEvent.Transcription>().single())
        // A failed STT answers [[null, 1.0]].
        val failed = events("""{"msg_type":"bus","payload":{"type":"recognizer_loop:b64_transcribe.response",
            "data":{"transcriptions":[[null,1.0]]},"context":{"wiggins_id":"t2"}}}""")
        assertEquals(HubEvent.Transcription("t2", null), failed.filterIsInstance<HubEvent.Transcription>().single())
        val audio = events("""{"msg_type":"bus","payload":{"type":"speak:b64_audio.response",
            "data":{"utterance":"Hello.","lang":"en-US","wiggins_id":"s1","audio":"UklGRg=="},"context":{}}}""")
        assertEquals(HubEvent.SpeechAudio("s1", "UklGRg=="), audio.filterIsInstance<HubEvent.SpeechAudio>().single())
    }

    @Test fun envelopeHasOnlyKnownKeys() {
        val text = HiveProtocol.hiveMessage("bus", JsonObject(emptyMap()))
        assertEquals(setOf("msg_type", "payload"), text.json().jsonObject.keys)
    }
}
