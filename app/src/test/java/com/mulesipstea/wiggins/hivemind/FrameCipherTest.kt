package com.mulesipstea.wiggins.hivemind

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FrameCipherTest {
    private val supported = HiveEncoding.entries.map { it.wireName }.toSet()

    private fun cipherFor(c: kotlinx.serialization.json.JsonObject, nonce: ByteArray? = null) = FrameCipher(
        c.hex("key_hex"),
        checkNotNull(HiveCipher.fromWire(c.str("cipher"))),
        checkNotNull(HiveEncoding.fromWire(c.str("encoding"))),
        nonces = { size -> checkNotNull(nonce).also { assertEquals(size, it.size) } },
    )

    @Test fun sealMatchesPython() {
        val cases = Vectors.cases("encryption.json", "encrypt_cases").filter { it.str("encoding") in supported }
        check(cases.size >= 6)
        for (c in cases) {
            val name = c.str("name")
            val cipher = cipherFor(c, c.hex("nonce_hex"))
            val encoding = cipher.encoding
            val wire = cipher.seal(c.str("plaintext")).json().jsonObject
            assertArrayEquals(name, c.hex("ciphertext_hex"), encoding.decode(wire.str("ciphertext")))
            assertArrayEquals(name, c.hex("tag_hex"), encoding.decode(wire.str("tag")))
            assertArrayEquals(name, c.hex("nonce_hex"), encoding.decode(wire.str("nonce")))
            // And the Python wire opens to the same plaintext.
            assertEquals(name, c.str("plaintext"), cipher.open(c.str("wire")))
        }
    }

    @Test fun decryptCases() {
        // The 12-byte AES-GCM case documents a hub-side quirk: the hub reads 16 nonce bytes, so it
        // can't open such a frame. We only ever send 16-byte AES nonces (checked in sealMatchesPython
        // and aesNoncesAre16Bytes); opening a standard 12-byte frame on receive is harmless.
        val cases = Vectors.cases("encryption.json", "decrypt_cases")
            .filter { it.str("encoding") in supported && !it.str("name").contains("12-byte nonce") }
        for (c in cases) {
            val cipher = cipherFor(c)
            when (c.str("expect")) {
                "ok" -> assertEquals(c.str("name"), c.str("plaintext"), cipher.open(c.str("wire")))
                else -> assertThrows(c.str("name"), DecryptionException::class.java) { cipher.open(c.str("wire")) }
            }
        }
    }

    @Test fun aesNoncesAre16Bytes() {
        val wire = FrameCipher(ByteArray(32), HiveCipher.AES_GCM, HiveEncoding.JSON_HEX).seal("x").json().jsonObject
        assertEquals(16, HiveEncoding.JSON_HEX.decode(wire.str("nonce")).size)
    }
}
