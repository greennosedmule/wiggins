package com.mulesipstea.wiggins.hivemind

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The client against a hub that offers only Noise, when the PSK isn't stored yet (a fresh
 * install against hivemind-core 5). Stretching it takes a second or more; the app's sends
 * mustn't wait for it, as they did when argon2id ran under the client's lock.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class HiveMindClientNoiseTest {
    private val case = Vectors.load("noise.json").getValue("cases").let { cases ->
        (cases as kotlinx.serialization.json.JsonArray).map { it.jsonObject }.first { it.getValue("name").jsonPrimitive.content == "hub-5x" }
    }
    private fun str(key: String) = case.getValue(key).jsonPrimitive.content
    private val context = SessionContext(lang = "en-US", timezone = "America/New_York", systemUnit = "imperial", timeFormat = "half", dateFormat = "MDY")

    private val server = MockWebServer()
    private val fromClient = LinkedBlockingQueue<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun setUp() {
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    // A hivemind-core 5 hub: HELLO, then a handshake offer with Noise only.
                    webSocket.send(str("hello_wire"))
                    webSocket.send(str("shake_wire"))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    fromClient += text
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }
            }).build(),
        )
        server.start()
    }

    @After fun tearDown() {
        scope.cancel()
        server.close()
    }

    /** Keys with no stored PSK, whose stretch takes as long as the test says. */
    private class SlowKeys(override val staticKey: ByteArray, private val psk: ByteArray) : NoiseKeys {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var cached: ByteArray? = null
        override fun cachedPsk(nodeId: String) = cached
        override fun derivePsk(nodeId: String): ByteArray {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            return psk.also { cached = it }
        }
    }

    private fun hex(key: String) = str(key).hexToByteArray()

    @Test fun sendsDontWaitForTheStretchAndTheHandshakeGoesOnAfterIt() {
        val keys = SlowKeys(hex("client_static_hex"), hex("psk_hex"))
        val client = HiveMindClient(OkHttpClient(), scope)
        client.connect(server.url("/"), "key", str("password"), context, "s1", noiseKeys = keys)
        assertTrue("the stretch started", keys.started.await(5, TimeUnit.SECONDS))

        // Mid-stretch, as the main thread would: the send returns at once, unsent.
        val before = System.nanoTime()
        val sent = client.sendUtterance("hello", context)
        val tookMs = (System.nanoTime() - before) / 1_000_000
        assertFalse(sent)
        assertTrue("send took $tookMs ms", tookMs < 500)
        assertNull("nothing goes to the hub before message 1", fromClient.poll(200, TimeUnit.MILLISECONDS))

        keys.release.countDown()
        val shake = Json.parseToJsonElement(fromClient.poll(5, TimeUnit.SECONDS) ?: error("no message 1")).jsonObject
        assertEquals("shake", shake.getValue("msg_type").jsonPrimitive.content)
        val noise = shake.getValue("payload").jsonObject.getValue("noise").jsonObject
        assertEquals("XXpsk2", noise.getValue("pattern").jsonPrimitive.content)
        assertTrue(noise.getValue("msg").jsonPrimitive.content.isNotEmpty())
        client.disconnect()
    }

    @Test fun aStoredPskSendsMessage1WithoutStretching() {
        val keys = SlowKeys(hex("client_static_hex"), hex("psk_hex")).also { it.cached = hex("psk_hex") }
        val client = HiveMindClient(OkHttpClient(), scope)
        client.connect(server.url("/"), "key", str("password"), context, "s2", noiseKeys = keys)
        val shake = Json.parseToJsonElement(fromClient.poll(5, TimeUnit.SECONDS) ?: error("no message 1")).jsonObject
        assertEquals("XXpsk2", shake.getValue("payload").jsonObject.getValue("noise").jsonObject.getValue("pattern").jsonPrimitive.content)
        assertEquals("no stretch for a stored key", 1L, keys.started.count)
        client.disconnect()
    }
}
