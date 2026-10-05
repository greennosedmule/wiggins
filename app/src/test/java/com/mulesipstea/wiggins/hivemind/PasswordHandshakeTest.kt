package com.mulesipstea.wiggins.hivemind

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PasswordHandshakeTest {
    @Test fun envelopesAndKeysMatchPython() {
        for (c in Vectors.cases("password_handshake.json", "cases")) {
            val name = c.str("name")
            val client = PasswordHandshake(c.str("password"), c.hex("client_iv_hex"))
            assertEquals(name, c.str("client_envelope"), client.envelope)
            assertEquals(name, c.bool("client_verifies_server_envelope"), client.verify(c.str("server_envelope")))
            if (c.bool("client_verifies_server_envelope")) {
                assertArrayEquals(name, c.hex("client_key_hex"), client.deriveKey(c.str("server_envelope")))
            }
        }
    }

    @Test fun envelopeMatchingRules() {
        for (c in Vectors.cases("password_handshake.json", "hsub_match_checks")) {
            assertEquals(c.str("hsub"), c.bool("match_hsub"), PasswordHandshake.envelopeMatches(c.str("hsub"), c.str("password")))
        }
    }

    @Test fun pbkdf2MatchesRfc7914Vector() {
        // RFC 7914 §11: PBKDF2-HMAC-SHA256("passwd", "salt", c=1, dkLen=64)
        val dk = PasswordHandshake.pbkdf2HmacSha256("passwd".toByteArray(), "salt".toByteArray(), 1, 64)
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            dk.toHexString(),
        )
    }
}
