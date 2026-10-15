package com.mulesipstea.wiggins.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** phone.status starts on, once, even for installs that predate it; after that the user's choice stands. */
class QueryDefaultsTest {
    @Test fun phoneStatusIsOnUntilTheUserChooses() {
        assertEquals(setOf("phone.status"), SettingsRepository.queriesFrom(null, null))
        assertEquals(setOf("apps.list", "phone.status"), SettingsRepository.queriesFrom(setOf("apps.list"), null))
        // Once the user has changed any query, what's stored is the whole truth.
        assertEquals(setOf("apps.list"), SettingsRepository.queriesFrom(setOf("apps.list"), true))
    }
}
