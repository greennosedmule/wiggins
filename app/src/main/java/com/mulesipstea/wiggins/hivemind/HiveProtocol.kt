package com.mulesipstea.wiggins.hivemind

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/**
 * The HiveMind client protocol for one connection, independent of the
 * transport: feed it each text frame, and it returns what to send and what
 * happened. See docs/hivemind-protocol.md §4.
 *
 * Not thread-safe; the caller serializes access.
 */
class HiveProtocol(
    private val useragent: String,
    password: String,
    private val siteId: String = DEFAULT_SITE_ID,
    val sessionId: String = UUID.randomUUID().toString(),
    private val handshake: PasswordHandshake = PasswordHandshake(password),
    private val nonces: ((Int) -> ByteArray)? = null,
    /** The session OVOS last handed back, carried over from an earlier connection in the same session. */
    initialSession: JsonObject? = null,
) {
    sealed interface Output {
        data class Send(val text: String) : Output
        data class Connected(val peer: String?) : Output
        data class Event(val event: HubEvent) : Output
        data class Fail(val reason: String) : Output
    }

    private enum class Stage { AWAIT_SHAKE, AWAIT_SHAKE_RESPONSE, READY, FAILED }

    private var stage = Stage.AWAIT_SHAKE
    private var cipher: FrameCipher? = null
    private var session: SessionContext? = null
    private var hubPeer: String? = null

    /** The session as OVOS last sent it back for our session_id; see [sessionJson]. */
    private var hubSession: JsonObject? = initialSession

    /** The session OVOS last handed back, to carry into the next connection for the same [sessionId]. */
    val handedBackSession: JsonObject? get() = hubSession

    val isReady get() = stage == Stage.READY

    /** Session settings sent in HELLO; set before the handshake completes. */
    fun setSession(context: SessionContext) {
        session = context
    }

    fun onFrame(text: String): List<Output> {
        if (stage == Stage.FAILED) return emptyList()
        val json = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrElse {
            return fail("Hub sent a frame that isn't a JSON object")
        }
        val message = if ("ciphertext" in json) {
            val c = cipher ?: return fail("Hub sent an encrypted frame before the handshake")
            val plain = try {
                c.open(json)
            } catch (e: DecryptionException) {
                return fail(e.message ?: "Decryption failed")
            }
            runCatching { Json.parseToJsonElement(plain) as JsonObject }.getOrElse {
                return fail("Hub sent an encrypted frame that isn't a HiveMessage")
            }
        } else {
            json
        }
        return onMessage(message)
    }

    /** Builds an encrypted utterance frame, or null if the handshake isn't done. */
    fun utterance(text: String, context: SessionContext): String? = bus(
        "recognizer_loop:utterance",
        buildJsonObject {
            putJsonArray("utterances") { add(JsonPrimitive(text)) }
            put("lang", context.lang)
        },
        context,
    )

    /**
     * Builds an encrypted BUS message of [type] (e.g. `recognizer_loop:record_begin`),
     * or null if the handshake isn't done. The hub drops types the client isn't allowed to send.
     */
    fun bus(type: String, data: JsonObject, context: SessionContext): String? {
        if (stage != Stage.READY) return null
        session = context
        val payload = buildJsonObject {
            put("type", type)
            put("data", data)
            putJsonObject("context") {
                put("source", useragent)
                put("destination", "HiveMind")
                put("platform", useragent)
                put("session", sessionJson(context))
            }
        }
        return encrypted(hiveMessage("bus", payload))
    }

    /**
     * The session to send: the hub's latest copy with the phone's own settings on top.
     * OVOS keeps per-session state (a skill waiting in get_response, active skills)
     * only in the session the client hands back; neither the hub nor ovos-core keeps
     * a copy, so dropping it breaks follow-up answers (docs/hivemind-protocol.md §11.1).
     */
    private fun sessionJson(context: SessionContext): JsonObject {
        val ours = context.toSessionJson(sessionId, siteId)
        val theirs = hubSession ?: return ours
        return JsonObject(theirs + ours)
    }

    private fun onMessage(message: JsonObject): List<Output> {
        val type = message["msg_type"]?.jsonPrimitive?.contentOrNull ?: "?"
        val payload = message["payload"] as? JsonObject ?: JsonObject(emptyMap())
        return when (type) {
            "hello" -> {
                hubPeer = payload["peer"]?.jsonPrimitive?.contentOrNull
                listOf(Output.Event(HubEvent.Downlink(type, null, message)))
            }
            "shake" -> onShake(payload)
            "bus" -> onBus(message, payload)
            else -> listOf(Output.Event(HubEvent.Downlink(type, null, message)))
        }
    }

    private fun onShake(payload: JsonObject): List<Output> = when (stage) {
        Stage.AWAIT_SHAKE -> {
            // The hub's "handshake" flag is false for add-client records (they also carry a
            // legacy key); handshake whenever it offers the password path (§4.2).
            if (payload["password"]?.jsonPrimitive?.booleanOrNull != true) {
                fail("Hub doesn't offer a password handshake for this client")
            } else {
                stage = Stage.AWAIT_SHAKE_RESPONSE
                val shake = buildJsonObject {
                    put("binarize", false)
                    putJsonArray("encodings") { add(JsonPrimitive(OFFERED_ENCODING.wireName)) }
                    putJsonArray("ciphers") { add(JsonPrimitive(OFFERED_CIPHER.wireName)) }
                    put("envelope", handshake.envelope)
                }
                listOf(Output.Send(hiveMessage("shake", shake)))
            }
        }
        Stage.AWAIT_SHAKE_RESPONSE -> completeHandshake(payload)
        else -> emptyList()
    }

    private fun completeHandshake(payload: JsonObject): List<Output> {
        val envelope = payload["envelope"]?.jsonPrimitive?.contentOrNull
            ?: return fail("Hub's handshake response has no envelope")
        if (!handshake.verify(envelope)) return fail("Wrong password (the hub's handshake doesn't match it)")
        val cipherName = payload["cipher"]?.jsonPrimitive?.contentOrNull
        val encodingName = payload["encoding"]?.jsonPrimitive?.contentOrNull
        val hiveCipher = HiveCipher.fromWire(cipherName) ?: return fail("Hub chose an unsupported cipher: $cipherName")
        val encoding = HiveEncoding.fromWire(encodingName) ?: return fail("Hub chose an unsupported encoding: $encodingName")
        val key = handshake.deriveKey(envelope)
        cipher = if (nonces != null) FrameCipher(key, hiveCipher, encoding, nonces) else FrameCipher(key, hiveCipher, encoding)
        stage = Stage.READY

        val context = session ?: return fail("No session context set")
        val hello = buildJsonObject {
            put("session", sessionJson(context))
            put("site_id", siteId)
        }
        // The hub's HELLO named us with a "default" session; ours replaces it (§11.2).
        val peer = hubPeer?.substringBeforeLast("::")?.let { "$it::$sessionId" }
        return listOf(Output.Send(encrypted(hiveMessage("hello", hello))), Output.Connected(peer))
    }

    private fun onBus(message: JsonObject, payload: JsonObject): List<Output> {
        val busType = payload["type"]?.jsonPrimitive?.contentOrNull
        val downSession = (payload["context"] as? JsonObject)?.get("session") as? JsonObject
        if (downSession?.get("session_id")?.jsonPrimitive?.contentOrNull == sessionId) hubSession = downSession
        val data = payload["data"] as? JsonObject
        val out = mutableListOf<Output>(Output.Event(HubEvent.Downlink("bus", busType, message)))
        when (busType) {
            "speak" -> data?.get("utterance")?.jsonPrimitive?.contentOrNull?.let {
                val expect = data["expect_response"]?.jsonPrimitive?.booleanOrNull ?: false
                out += Output.Event(HubEvent.Speak(it, expect))
            }
            "mycroft.mic.listen" -> out += Output.Event(HubEvent.Listen)
        }
        return out
    }

    private fun encrypted(plaintext: String) = checkNotNull(cipher).seal(plaintext)

    private fun fail(reason: String): List<Output> {
        stage = Stage.FAILED
        return listOf(Output.Fail(reason))
    }

    companion object {
        const val DEFAULT_SITE_ID = "phone"
        val OFFERED_CIPHER = HiveCipher.CHACHA20_POLY1305
        val OFFERED_ENCODING = HiveEncoding.JSON_B64

        /** Only msg_type and payload: the hub rejects unknown envelope keys (§3.3). */
        fun hiveMessage(type: String, payload: JsonElement): String =
            JsonObject(mapOf("msg_type" to JsonPrimitive(type), "payload" to payload)).toString()
    }
}
