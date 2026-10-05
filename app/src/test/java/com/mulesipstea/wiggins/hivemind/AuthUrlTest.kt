package com.mulesipstea.wiggins.hivemind

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthUrlTest {
    @Test fun matchesPythonClient() {
        val cases = Vectors.cases("auth.json", "cases")
        assertTrue("vectors should include + or /", cases.any { it.bool("has_plus_or_slash") })
        for (c in cases) {
            val scheme = if (c.bool("tls")) "https" else "http"
            val base = "$scheme://${c.str("host")}:${c.str("port")}/some/path?x=1".toHttpUrl()
            val url = HiveMindClient.authorizedUrl(base, c.str("useragent"), c.str("access_key"))
            // OkHttp writes encodedPath + "?" + encodedQuery as the request target.
            assertEquals(c.str("request_target"), url.encodedPath + "?" + url.encodedQuery)
        }
    }
}
