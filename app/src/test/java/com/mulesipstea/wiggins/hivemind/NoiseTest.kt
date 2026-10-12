package com.mulesipstea.wiggins.hivemind

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks the Noise layer against noise.json, made by the Python client and hub code (tools/vectors/noise_vectors.py). */
class NoiseTest {
    private val vectors = Vectors.load("noise.json")
    private val chunkSize = vectors.getValue("chunk_size").jsonPrimitive.content.toInt()
    private val cases = vectors.arr("cases").map { it.jsonObject }

    private fun payload(wire: String) = wire.json().jsonObject.getValue("payload").jsonObject

    @Test fun derivesThePskWithArgon2id() {
        for (case in cases) {
            assertArrayEquals(case.str("name"), case.hex("psk_hex"), Noise.derivePsk(case.str("password"), case.str("node_id")))
        }
    }

    @Test fun buildsTheHubsPrologue() {
        for (case in cases) {
            val prologue = Noise.prologue(payload(case.str("hello_wire")), payload(case.str("shake_wire")))
            assertEquals(case.str("name"), case.str("prologue_hex"), prologue.toHexString())
        }
    }

    @Test fun handshakesLikeThePythonClient() {
        for (case in cases) {
            val name = case.str("name")
            val hs = NoiseHandshake(case.hex("client_static_hex"), case.hex("psk_hex"), case.hex("prologue_hex"), case.hex("client_ephemeral_hex"))
            assertEquals(name, case.str("msg1_hex"), hs.writeMessage1(case.str("node_payload").toByteArray()).toHexString())
            assertEquals(name, case.str("hub_payload"), hs.readMessage2(case.hex("msg2_hex")).toString(Charsets.UTF_8))
            assertEquals(name, case.str("hub_static_public_hex"), hs.remoteStatic!!.toHexString())
            assertEquals(name, case.str("msg3_hex"), hs.writeMessage3(ByteArray(0)).toHexString())

            val transport = hs.split(chunkSize)
            for (message in case.arr("client_to_hub").map { it.jsonObject }) {
                val frames = transport.seal(message.str("plaintext")).map { it.toHexString() }
                assertEquals(name, message.arr("frames").map { it.jsonPrimitive.content }, frames)
            }
            for (message in case.arr("hub_to_client").map { it.jsonObject }) {
                val opened = message.arr("frames").map { transport.open(it.jsonPrimitive.content.hexToByteArray()) }
                assertEquals(name, message.str("plaintext"), opened.last())
                opened.dropLast(1).forEach { assertNull(name, it) }
            }
        }
    }

    @Test fun wrongPasswordFailsMessage2() {
        val case = vectors.getValue("wrong_password").jsonObject
        val prologue = Noise.prologue(payload(case.str("hello_wire")), payload(case.str("shake_wire")))
        val psk = Noise.derivePsk(case.str("password"), case.str("node_id"))
        val hs = NoiseHandshake(case.hex("client_static_hex"), psk, prologue, case.hex("client_ephemeral_hex"))
        hs.writeMessage1("""{"binarize":false,"encodings":["JSON-B64"]}""".toByteArray())
        assertThrows(NoiseException::class.java) { hs.readMessage2(case.hex("msg2_hex")) }
    }

    /** Real-size chunks: every frame fits a Noise message, and they reassemble. */
    @Test fun chunksLargeMessages() {
        val key = ByteArray(32) { it.toByte() }
        val sender = NoiseTransport(NoiseCipher(key), NoiseCipher(ByteArray(32)))
        val receiver = NoiseTransport(NoiseCipher(ByteArray(32)), NoiseCipher(key))
        val big = """{"msg_type":"bus","payload":{"audio":"${"x".repeat(200_000)}"}}"""
        val frames = sender.seal(big)
        assertEquals(4, frames.size)
        assertTrue(frames.all { it.size <= Noise.MAX_MESSAGE })
        val opened = frames.map { receiver.open(it) }
        assertEquals(big, opened.last())
        // A replayed frame fails: the receive counter has moved on.
        assertThrows(NoiseException::class.java) { receiver.open(frames[0]) }
    }

    @Test fun canonicalJsonMatchesPython() {
        val json = """{"b":[1,true,null,"x"],"a":{"z":"tab\tquote\"back\\slash\u0001 é ✓","y":1.5}}""".json()
        assertEquals(
            """{"a":{"y":1.5,"z":"tab\tquote\"back\\slash\u0001 é ✓"},"b":[1,true,null,"x"]}""",
            Noise.canonicalJson(json),
        )
        assertEquals("{}", Noise.canonicalJson(JsonObject(emptyMap())))
    }
}
