package com.mulesipstea.wiggins.hivemind

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
                    is HiveProtocol.Output.Send -> sent += (out.frame as HiveProtocol.Frame.Text).text
                    is HiveProtocol.Output.Event -> events += out.event
                    is HiveProtocol.Output.Connected -> connected = true
                    is HiveProtocol.Output.Fail -> throw AssertionError("$name: ${out.reason}")
                    // The recorded sessions use the password handshake, which needs no PSK.
                    is HiveProtocol.Output.DerivePsk -> throw AssertionError("$name: asked for a PSK")
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
            val utterance = hubCipher.open(proto.utterance("what time is it", context).text()).json().jsonObject
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

        val sent = hub.open(proto.utterance("6:30", context).text()).json().jsonObject
        val sentSession = sent.getValue("payload").jsonObject.getValue("context").jsonObject.getValue("session").jsonObject
        assertEquals("response", sentSession.getValue("utterance_states").jsonObject.str("ovos-skill-alerts.openvoiceos"))
        assertTrue("active_skills" in sentSession)
        assertEquals("en-US", sentSession.str("lang"))
        assertEquals(proto.sessionId, sentSession.str("session_id"))
        assertEquals("imperial", sentSession.str("system_unit"))
    }

    /**
     * The phone's timezone goes inside the hub's location, so its city and coordinates
     * stay (the weather skill needs them); before the hub has handed a session back, no
     * location is sent and the hub fills its own.
     */
    @Test fun phoneTimezoneMergesIntoTheHubsLocation() {
        val session = Vectors.cases("full_session.json", "sessions")[0]
        val inputs = session.getValue("inputs").jsonObject
        val negotiated = session.getValue("negotiated").jsonObject
        val proto = HiveProtocol(
            useragent = "Wiggins",
            password = inputs.str("password"),
            handshake = PasswordHandshake(inputs.str("password"), inputs.hex("client_iv_hex")),
        ).also { it.setSession(context) }
        val sent = session.arr("frames").map { it.jsonObject }.filter { it.str("dir") == "hub->client" && !it.bool("encrypted") }
            .flatMap { proto.onFrame(it.str("wire")) }.filterIsInstance<HiveProtocol.Output.Send>()
        val hub = FrameCipher(negotiated.hex("key_hex"), HiveCipher.CHACHA20_POLY1305, HiveEncoding.JSON_B64)
        val hello = hub.open((sent.last().frame as HiveProtocol.Frame.Text).text).json().jsonObject.getValue("payload").jsonObject.getValue("session").jsonObject
        assertTrue("no location before the hub's session", "location" !in hello)

        proto.onFrame(hub.seal("""{"msg_type":"bus","payload":{"type":"speak","data":{"utterance":"Hi"},
            "context":{"session":{"session_id":"${proto.sessionId}","location":{"city":{"name":"Johnson City"},
            "coordinate":{"latitude":36.3,"longitude":-82.4},"timezone":{"code":"America/Chicago","name":"Central"}}}}}}"""))
        val utterance = hub.open(proto.utterance("weather", context).text()).json().jsonObject
        val location = utterance.getValue("payload").jsonObject.getValue("context").jsonObject
            .getValue("session").jsonObject.getValue("location").jsonObject
        assertEquals("36.3", location.getValue("coordinate").jsonObject.getValue("latitude").jsonPrimitive.content)
        assertEquals("Johnson City", location.getValue("city").jsonObject.str("name"))
        assertEquals("America/New_York", location.getValue("timezone").jsonObject.str("code"))
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
        val hello = hub.open((sent.last().frame as HiveProtocol.Frame.Text).text).json().jsonObject.getValue("payload").jsonObject.getValue("session").jsonObject
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

        val transcribe = hub.open(proto.transcribe("t1", byteArrayOf(1, 2, 3), context).text()).json().jsonObject.getValue("payload").jsonObject
        assertEquals("recognizer_loop:b64_transcribe", transcribe.str("type"))
        val tData = transcribe.getValue("data").jsonObject
        assertEquals("AQID", tData.str("audio"))
        assertEquals("en-US", tData.str("lang"))
        assertEquals("16000", tData.getValue("sample_rate").jsonPrimitive.content)
        assertEquals("2", tData.getValue("sample_width").jsonPrimitive.content)
        assertEquals("t1", transcribe.getValue("context").jsonObject.str("wiggins_id"))

        val synthesize = hub.open(proto.synthesize("s1", "Hello.", context).text()).json().jsonObject.getValue("payload").jsonObject
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

    /**
     * Every uplink says whether the phone is locked (SPEC "Permissions") and whether
     * the user is watching the screen, as message-context fields rather than session ones,
     * which OVOS would drop.
     */
    @Test fun everyUplinkSaysWhetherThePhoneIsLockedAndWatched() {
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
        fun contextOf(frames: List<HiveProtocol.Frame>?) =
            hub.open(frames.text()).json().jsonObject.getValue("payload").jsonObject.getValue("context").jsonObject
        val locked = context.copy(locked = true)
        val watching = context.copy(watching = true)
        val normalized = context.copy(rawUtterance = false)
        val trusted = context.copy(locked = true, phoneContext = "trusted_device", canAsk = false, phoneActions = true)
        val nightstand = context.copy(locked = true, phoneContext = "locked", canAsk = false, phoneActions = false, personaHistory = "since_lock")
        val narrowed = context.copy(personaTools = JsonObject(mapOf("notes" to JsonPrimitive("none"))))

        for (ctx in listOf(context, locked, watching, normalized, trusted, nightstand, narrowed)) {
            for (frames in listOf(
                proto.utterance("what's next", ctx),
                proto.transcribe("t1", byteArrayOf(1), ctx),
                proto.synthesize("s1", "Hi.", ctx),
                proto.bus("recognizer_loop:record_begin", JsonObject(emptyMap()), ctx),
            )) {
                val sent = contextOf(frames)
                assertEquals(ctx.locked.toString(), sent.getValue("device_locked").jsonPrimitive.content)
                assertEquals(ctx.watching.toString(), sent.getValue("user_watching").jsonPrimitive.content)
                assertEquals(ctx.rawUtterance.toString(), sent.getValue("persona_raw_utterance").jsonPrimitive.content)
                assertEquals(ctx.phoneContext, sent.getValue("phone_context").jsonPrimitive.content)
                assertEquals(ctx.canAsk.toString(), sent.getValue("can_ask").jsonPrimitive.content)
                assertEquals(ctx.phoneActions.toString(), sent.getValue("phone_actions").jsonPrimitive.content)
                assertEquals(ctx.personaHistory, sent.getValue("persona_history").jsonPrimitive.content)
                // Always a dict: servers left out follow the hub's defaults.
                assertEquals(ctx.personaTools, sent.getValue("persona_tools"))
                val session = sent.getValue("session").jsonObject
                assertNull(session["device_locked"])
                assertNull(session["user_watching"])
                assertNull(session["persona_raw_utterance"])
                assertNull(session["phone_context"])
            }
        }
    }

    /**
     * ovos-core 3 speaks as `ovos.utterance.speak`, and during the rename a bus client can
     * also send a `speak` twin with the same data and context; each sentence is said once.
     */
    @Test fun speaksBothNamesOnce() {
        val session = Vectors.cases("full_session.json", "sessions")[0]
        val inputs = session.getValue("inputs").jsonObject
        val negotiated = session.getValue("negotiated").jsonObject
        var now = 0L
        val proto = HiveProtocol(
            useragent = "Wiggins",
            password = inputs.str("password"),
            handshake = PasswordHandshake(inputs.str("password"), inputs.hex("client_iv_hex")),
            clock = { now },
        ).also { it.setSession(context) }
        session.arr("frames").map { it.jsonObject }.filter { it.str("dir") == "hub->client" && !it.bool("encrypted") }
            .forEach { proto.onFrame(it.str("wire")) }
        val hub = FrameCipher(negotiated.hex("key_hex"), HiveCipher.CHACHA20_POLY1305, HiveEncoding.JSON_B64)

        fun bus(type: String, utterance: String, twin: Boolean = false) = proto.onFrame(hub.seal(
            """{"msg_type":"bus","payload":{"type":"$type","data":{"utterance":"$utterance","expect_response":false},
            "context":{${if (twin) "\"_namespace_compat_twin\":true," else ""}"session":{"session_id":"${proto.sessionId}"}}}}""",
        )).filterIsInstance<HiveProtocol.Output.Event>().map { it.event }.filterIsInstance<HubEvent.Speak>().map { it.utterance }

        // A persona reply: only the new name.
        assertEquals(listOf("Paris."), bus("ovos.utterance.speak", "Paris."))
        // A skill's speech with its legacy twin, either order.
        assertEquals(listOf("Timer set."), bus("ovos.utterance.speak", "Timer set."))
        assertEquals(emptyList<String>(), bus("speak", "Timer set.", twin = true))
        assertEquals(listOf("Done."), bus("speak", "Done."))
        assertEquals(emptyList<String>(), bus("ovos.utterance.speak", "Done."))
        // A real repeat under one name is said again.
        assertEquals(listOf("Done."), bus("speak", "Done."))
        assertEquals(listOf("Done."), bus("speak", "Done."))
        // The other name long after is a new sentence, not a twin.
        now += 5_000
        assertEquals(listOf("Done."), bus("ovos.utterance.speak", "Done."))
        // Different words under the other name are never dropped.
        assertEquals(listOf("Anything else?"), bus("speak", "Anything else?"))
    }

    @Test fun listensOnBothNames() {
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
        for (type in listOf("mycroft.mic.listen", "ovos.mic.listen")) {
            val events = proto.onFrame(hub.seal("""{"msg_type":"bus","payload":{"type":"$type","data":{},"context":{}}}"""))
                .filterIsInstance<HiveProtocol.Output.Event>().map { it.event }
            assertTrue(type, HubEvent.Listen in events)
        }
    }

    private val noise = Vectors.load("noise.json")
    private fun noiseCase(name: String) = noise.arr("cases").map { it.jsonObject }.first { it.str("name") == name }

    /**
     * Keys from the vectors: the client's static key and its PSK, recording what's rejected.
     * With [cached] false the PSK isn't stored yet, so the protocol asks for it to be derived.
     */
    private class FixedKeys(override val staticKey: ByteArray, private val psk: ByteArray, private val cached: Boolean = true) : NoiseKeys {
        val rejected = mutableListOf<String>()
        val derived = mutableListOf<String>()
        override fun cachedPsk(nodeId: String) = psk.takeIf { cached }
        override fun derivePsk(nodeId: String) = psk.also { derived += nodeId }
        override fun rejected(nodeId: String) {
            rejected += nodeId
        }
    }

    private fun noiseProtocol(case: JsonObject, forceNoise: Boolean = false, keys: FixedKeys = FixedKeys(case.hex("client_static_hex"), case.hex("psk_hex"))) =
        HiveProtocol(
            useragent = "Wiggins",
            password = case.str("password"),
            noiseKeys = keys,
            forceNoise = forceNoise,
            noiseEphemeral = { case.hex("client_ephemeral_hex") },
        ).also { it.setSession(context) }

    private fun noiseShake(msgHex: String) = """{"msg_type":"shake","payload":{"noise":{"msg":"$msgHex"}}}"""

    private fun List<HiveProtocol.Output>.sentText() = filterIsInstance<HiveProtocol.Output.Send>().map { (it.frame as HiveProtocol.Frame.Text).text }

    /**
     * A hivemind-core 5 hub offers only Noise: Wiggins' handshake messages match the
     * Python client's byte for byte, HELLO is the first Noise transport message, and
     * the session then runs over binary frames, chunked or not.
     */
    @Test fun noiseOnlyHubHandshakesWithNoise() {
        val case = noiseCase("hub-5x")
        val proto = noiseProtocol(case)
        proto.onFrame(case.str("hello_wire"))
        val msg1 = proto.onFrame(case.str("shake_wire")).sentText().single().json().jsonObject
        val offer = msg1.getValue("payload").jsonObject.getValue("noise").jsonObject
        assertEquals("XXpsk2", offer.str("pattern"))
        assertEquals("25519_ChaChaPoly_SHA256", offer.str("suite"))
        assertEquals(case.str("msg1_hex"), offer.str("msg"))
        assertTrue(!proto.isReady)

        val outs = proto.onFrame(noiseShake(case.str("msg2_hex")))
        val msg3 = (outs[0] as HiveProtocol.Output.Send).frame as HiveProtocol.Frame.Text
        assertEquals(case.str("msg3_hex"), msg3.text.json().jsonObject.getValue("payload").jsonObject.getValue("noise").jsonObject.str("msg"))
        val helloFrames = outs.filterIsInstance<HiveProtocol.Output.Send>().drop(1).map { it.frame as HiveProtocol.Frame.Binary }
        val connected = outs.filterIsInstance<HiveProtocol.Output.Connected>().single()
        assertEquals(3, connected.protocolVersion)
        assertEquals(case.str("node_id"), connected.noiseNodeId)
        assertTrue(proto.isReady)

        // The hub's side of the transport, from the vectors' split keys.
        val hub = NoiseTransport(NoiseCipher(case.hex("s2c_key_hex")), NoiseCipher(case.hex("c2s_key_hex")))
        val hello = hub.open(helloFrames.single().bytes)!!.json().jsonObject
        assertEquals("hello", hello.str("msg_type"))
        assertEquals(proto.sessionId, hello.getValue("payload").jsonObject.getValue("session").jsonObject.str("session_id"))

        // The hub's frames, in the order it sent them: a speak, then chunked hub speech audio.
        val events = case.arr("hub_to_client").map { it.jsonObject }.flatMap { m -> m.arr("frames") }
            .flatMap { proto.onBinary(it.jsonPrimitive.content.hexToByteArray()) }
            .filterIsInstance<HiveProtocol.Output.Event>().map { it.event }
        assertEquals("It's 6:30 — café time.", events.filterIsInstance<HubEvent.Speak>().single().utterance)
        assertEquals("s1", events.filterIsInstance<HubEvent.SpeechAudio>().single().id)

        // Our messages go out as Noise frames the hub can read.
        val utterance = proto.utterance("what time is it", context)!!.map { hub.open((it as HiveProtocol.Frame.Binary).bytes) }.last()!!
        assertEquals("recognizer_loop:utterance", utterance.json().jsonObject.getValue("payload").jsonObject.str("type"))
        // Text frames have no place on a Noise session.
        assertEquals(emptyList<HiveProtocol.Output>(), proto.onFrame("""{"msg_type":"bus","payload":{"type":"speak"}}"""))
    }

    /** hivemind-core 4.7–4.15 offer both: the password handshake, unless Noise is forced. */
    /**
     * With no stored PSK the protocol doesn't stretch the password itself (argon2id would
     * run under the client's lock): it asks for the PSK, sends nothing until it has it, and
     * then sends the same message 1 as with a stored key.
     */
    @Test fun anUnstoredPskIsAskedForThenTheHandshakeContinues() {
        val case = noiseCase("hub-5x")
        val keys = FixedKeys(case.hex("client_static_hex"), case.hex("psk_hex"), cached = false)
        val proto = noiseProtocol(case, keys = keys)
        proto.onFrame(case.str("hello_wire"))
        val outs = proto.onFrame(case.str("shake_wire"))
        assertEquals(listOf(HiveProtocol.Output.DerivePsk(case.str("node_id"))), outs)
        assertTrue(keys.derived.isEmpty()) // Deriving is the client's job, off its lock.
        // Sends during the stretch find the handshake unfinished, as at any other point of it.
        assertNull(proto.utterance("hello", context))
        assertTrue(!proto.isReady)

        val psk = proto.derivePsk(case.str("node_id"))
        assertEquals(listOf(case.str("node_id")), keys.derived)
        // A PSK for another hub, or one arriving twice, is ignored.
        assertTrue(proto.onPsk("someone-else", psk).isEmpty())
        val msg1 = proto.onPsk(case.str("node_id"), psk).sentText().single().json().jsonObject
        assertEquals(case.str("msg1_hex"), msg1.getValue("payload").jsonObject.getValue("noise").jsonObject.str("msg"))
        assertTrue(proto.onPsk(case.str("node_id"), psk).isEmpty())

        proto.onFrame(noiseShake(case.str("msg2_hex")))
        assertTrue(proto.isReady)
    }

    /** A stored PSK is used at once: no request to derive one. */
    @Test fun aStoredPskSkipsTheStretch() {
        val case = noiseCase("hub-5x")
        val keys = FixedKeys(case.hex("client_static_hex"), case.hex("psk_hex"), cached = true)
        val proto = noiseProtocol(case, keys = keys)
        proto.onFrame(case.str("hello_wire"))
        val outs = proto.onFrame(case.str("shake_wire"))
        assertTrue(outs.none { it is HiveProtocol.Output.DerivePsk })
        assertEquals(case.str("msg1_hex"), outs.sentText().single().json().jsonObject.getValue("payload").jsonObject.getValue("noise").jsonObject.str("msg"))
        assertTrue(keys.derived.isEmpty())
    }

    @Test fun aFailedStretchFailsTheHandshake() {
        val case = noiseCase("hub-5x")
        val proto = noiseProtocol(case, keys = FixedKeys(case.hex("client_static_hex"), case.hex("psk_hex"), cached = false))
        proto.onFrame(case.str("hello_wire"))
        proto.onFrame(case.str("shake_wire"))
        val outs = proto.onPsk(case.str("node_id"), null)
        assertTrue(outs.single() is HiveProtocol.Output.Fail)
    }

    @Test fun hubOfferingBothUsesThePasswordUnlessNoiseIsForced() {
        val case = noiseCase("hub-415")
        val password = noiseProtocol(case)
        password.onFrame(case.str("hello_wire"))
        val shake = password.onFrame(case.str("shake_wire")).sentText().single().json().jsonObject.getValue("payload").jsonObject
        assertTrue("envelope" in shake && "noise" !in shake)

        val forced = noiseProtocol(case, forceNoise = true)
        forced.onFrame(case.str("hello_wire"))
        val offer = forced.onFrame(case.str("shake_wire")).sentText().single().json().jsonObject.getValue("payload").jsonObject
        assertEquals(case.str("msg1_hex"), offer.getValue("noise").jsonObject.str("msg"))
        val outs = forced.onFrame(noiseShake(case.str("msg2_hex")))
        assertEquals(3, outs.filterIsInstance<HiveProtocol.Output.Connected>().single().protocolVersion)
    }

    @Test fun wrongPasswordOverNoiseFailsAndForgetsThePsk() {
        val case = noise.getValue("wrong_password").jsonObject
        val keys = FixedKeys(case.hex("client_static_hex"), Noise.derivePsk(case.str("password"), case.str("node_id")))
        val proto = noiseProtocol(case, keys = keys)
        proto.onFrame(case.str("hello_wire"))
        proto.onFrame(case.str("shake_wire"))
        val fail = proto.onFrame(noiseShake(case.str("msg2_hex"))).filterIsInstance<HiveProtocol.Output.Fail>().single()
        assertTrue(fail.reason, fail.reason.startsWith("Wrong password"))
        assertEquals(listOf(case.str("node_id")), keys.rejected)
    }

    @Test fun forcedNoiseFailsOnAHubWithoutIt() {
        val session = Vectors.cases("full_session.json", "sessions")[0]
        val proto = HiveProtocol("Wiggins", "pw", forceNoise = true).also { it.setSession(context) }
        val outs = session.arr("frames").map { it.jsonObject }.filter { it.str("dir") == "hub->client" && !it.bool("encrypted") }
            .flatMap { proto.onFrame(it.str("wire")) }
        val fail = outs.filterIsInstance<HiveProtocol.Output.Fail>().single()
        assertTrue(fail.reason, "Noise" in fail.reason)
    }

    @Test fun envelopeHasOnlyKnownKeys() {
        val text = HiveProtocol.hiveMessage("bus", JsonObject(emptyMap()))
        assertEquals(setOf("msg_type", "payload"), text.json().jsonObject.keys)
    }
}

private fun List<HiveProtocol.Frame>?.text() = (checkNotNull(this).single() as HiveProtocol.Frame.Text).text
