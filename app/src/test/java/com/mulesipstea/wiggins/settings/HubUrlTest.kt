package com.mulesipstea.wiggins.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HubUrlTest {
    private fun ok(raw: String, mode: AuthMode = AuthMode.NONE) =
        (HubUrl.parse(raw, mode) as HubUrl.Result.Ok).url

    @Test fun acceptsWsInNoneMode() {
        val url = ok("ws://192.0.2.10:5678")
        assertEquals("http", url.scheme)
        assertEquals(5678, url.port)
    }

    @Test fun acceptsWss() {
        val url = ok(" wss://hivemind.example.com/ ")
        assertEquals("https", url.scheme)
        assertEquals(443, url.port)
    }

    @Test fun rejectsOtherSchemes() {
        listOf("http://x", "https://x", "x:5678", "", "ftp://x").forEach {
            assertTrue(it, HubUrl.parse(it, AuthMode.NONE) is HubUrl.Result.Invalid)
        }
    }

    @Test fun rejectsGarbage() {
        assertTrue(HubUrl.parse("ws://", AuthMode.NONE) is HubUrl.Result.Invalid)
    }
}
