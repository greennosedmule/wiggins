package com.mulesipstea.wiggins.hivemind

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Connects [HiveProtocol] to a real hub over a websocket; skipped unless
 * WIGGINS_LIVE_HUB is set. docs/hivemind-protocol.md §16.6 has a local setup.
 *
 * - WIGGINS_LIVE_HUB: ws://host:port of the hub
 * - WIGGINS_LIVE_KEY, WIGGINS_LIVE_PASSWORD: a client's credentials
 * - WIGGINS_LIVE_FORCE_NOISE: "true" to force Noise on a hub that offers both
 * - WIGGINS_LIVE_EXPECT_VERSION: the protocol version the handshake should end on
 * - WIGGINS_LIVE_BUS: optional ws:// URL of the hub's OVOS messagebus, to send
 *   messages down to the phone (a small speak and a large one, which a Noise
 *   session has to chunk)
 * - WIGGINS_LIVE_SMALL_ONLY: "true" to skip the large one, which hivemind-core 4.15
 *   can't send over Noise (it fails on the hub: over 65535 bytes)
 */
class LiveHubTest {
    private val context = SessionContext(lang = "en-US", timezone = "America/New_York", systemUnit = "imperial", timeFormat = "half", dateFormat = "MDY")

    @Test fun handshakesAndExchangesMessages() {
        val hub = System.getenv("WIGGINS_LIVE_HUB")
        assumeTrue("WIGGINS_LIVE_HUB not set", !hub.isNullOrBlank())
        val forceNoise = System.getenv("WIGGINS_LIVE_FORCE_NOISE") == "true"
        val password = System.getenv("WIGGINS_LIVE_PASSWORD")
        // The hub pins the first static key it sees for a client, so every run uses the same one.
        val staticKey = MessageDigest.getInstance("SHA-256").digest("wiggins-live-${System.getenv("WIGGINS_LIVE_KEY")}".toByteArray())
        val keys = object : NoiseKeys {
            override val staticKey = staticKey
            // This test drives HiveProtocol without HiveMindClient, so it derives up front
            // rather than handling Output.DerivePsk.
            override fun cachedPsk(nodeId: String) = Noise.derivePsk(password, nodeId)
            override fun derivePsk(nodeId: String) = Noise.derivePsk(password, nodeId)
        }
        val proto = HiveProtocol("Wiggins", password, noiseKeys = keys, forceNoise = forceNoise).also { it.setSession(context) }
        val http = OkHttpClient()
        val outputs = LinkedBlockingQueue<HiveProtocol.Output>()
        val closed = LinkedBlockingQueue<String>()

        fun WebSocket.sendAll(frames: List<HiveProtocol.Frame>) = frames.forEach {
            when (it) {
                is HiveProtocol.Frame.Text -> send(it.text)
                is HiveProtocol.Frame.Binary -> send(it.bytes.toByteString())
            }
        }

        val url = hub.replaceFirst("ws", "http").toHttpUrl()
        val request = Request.Builder().url(HiveMindClient.authorizedUrl(url, "Wiggins", System.getenv("WIGGINS_LIVE_KEY"))).build()
        val socket = http.newWebSocket(
            request,
            object : WebSocketListener() {
                fun handle(ws: WebSocket, outs: List<HiveProtocol.Output>) = synchronized(proto) {
                    outs.forEach { if (it is HiveProtocol.Output.Send) ws.sendAll(listOf(it.frame)) else outputs += it }
                }
                override fun onMessage(webSocket: WebSocket, text: String) = handle(webSocket, synchronized(proto) { proto.onFrame(text) })
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(webSocket, synchronized(proto) { proto.onBinary(bytes.toByteArray()) })
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed += "$code $reason" }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { closed += "$code $reason" }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed += "failure $t" }
            },
        )

        fun next(): HiveProtocol.Output {
            val out = outputs.poll(20, TimeUnit.SECONDS) ?: throw AssertionError("nothing from the hub; closed: ${closed.poll()}")
            if (out is HiveProtocol.Output.Fail) throw AssertionError("protocol failed: ${out.reason}")
            return out
        }

        var connected: HiveProtocol.Output.Connected? = null
        while (connected == null) connected = next() as? HiveProtocol.Output.Connected
        println("connected: $connected")
        System.getenv("WIGGINS_LIVE_EXPECT_VERSION")?.let { assertEquals(it.toInt(), connected.protocolVersion) }

        // Up: an utterance (the hub forwards it to its bus; nothing has to answer).
        socket.sendAll(checkNotNull(synchronized(proto) { proto.utterance("what time is it", context) }))

        val busUrl = System.getenv("WIGGINS_LIVE_BUS")
        if (!busUrl.isNullOrBlank()) {
            val peer = checkNotNull(connected.peer)
            // Connected comes as our HELLO goes out; the hub routes to the peer only once it has read it.
            Thread.sleep(1_000)
            val bus = http.newWebSocket(Request.Builder().url(busUrl.replaceFirst("ws", "http")).build(), object : WebSocketListener() {})
            fun emit(type: String, data: kotlinx.serialization.json.JsonObject) = bus.send(
                buildJsonObject {
                    put("type", type)
                    put("data", data)
                    putJsonObject("context") {
                        putJsonArray("destination") { add(JsonPrimitive(peer)) }
                        put("source", "skills")
                        putJsonObject("session") { put("session_id", proto.sessionId) }
                    }
                }.toString(),
            )
            val smallOnly = System.getenv("WIGGINS_LIVE_SMALL_ONLY") == "true"
            emit("speak", buildJsonObject { put("utterance", "Hello from the live hub.") })
            // About 300 KB: several Noise transport messages on a v3 session.
            if (!smallOnly) emit("speak:b64_audio.response", buildJsonObject {
                put("utterance", "Big.")
                put("wiggins_id", "live-1")
                put("audio", "UklGR" + "A".repeat(300_000))
            })
            val events = mutableListOf<HubEvent>()
            while (events.none { if (smallOnly) it is HubEvent.Speak else it is HubEvent.SpeechAudio }) {
                (next() as? HiveProtocol.Output.Event)?.event?.let { events += it }
            }
            assertEquals("Hello from the live hub.", events.filterIsInstance<HubEvent.Speak>().single().utterance)
            if (!smallOnly) assertEquals(300_005, events.filterIsInstance<HubEvent.SpeechAudio>().single().wavBase64.length)
            bus.close(1000, null)
        }
        assertNotNull(connected.peer)
        socket.close(1000, null)
    }
}
