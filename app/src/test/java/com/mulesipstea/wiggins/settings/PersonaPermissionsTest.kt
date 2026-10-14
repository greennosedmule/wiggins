package com.mulesipstea.wiggins.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The persona permissions are kept and read back (SPEC "Permissions"). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class PersonaPermissionsTest {
    private val settings = SettingsRepository(ApplicationProvider.getApplicationContext<Application>())

    @Test fun choicesCatalogAndHistoryRoundTrip() = runBlocking {
        settings.saveToolCatalog(SampleCatalog.json)
        val choices = Json.parseToJsonElement("""{"notes":["search_notes"],"calendar":"none"}""").jsonObject
        settings.setTools(PhoneContext.TRUSTED_DEVICE, choices)
        settings.setTrustedHistory("since_lock")
        val p = settings.persona.first()
        assertEquals("Ada", p.catalog?.persona)
        assertEquals(choices, p.toolsIn(PhoneContext.TRUSTED_DEVICE))
        assertEquals(JsonObject(emptyMap()), p.toolsIn(PhoneContext.UNLOCKED))
        assertEquals(listOf("full", "since_lock", "since_lock"), PhoneContext.entries.map(p::historyIn))

        settings.setTools(PhoneContext.TRUSTED_DEVICE, null)
        settings.saveToolCatalog(null)
        val reset = settings.persona.first()
        assertEquals(JsonObject(emptyMap()), reset.toolsIn(PhoneContext.TRUSTED_DEVICE))
        assertNull(reset.catalog)
    }
}
