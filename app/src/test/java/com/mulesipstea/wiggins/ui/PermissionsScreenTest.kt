package com.mulesipstea.wiggins.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mulesipstea.wiggins.settings.PersonaPermissions
import com.mulesipstea.wiggins.settings.PhoneContext
import com.mulesipstea.wiggins.settings.SampleCatalog
import com.mulesipstea.wiggins.ui.theme.WigginsTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The permissions screen narrows the hub's tools per context (SPEC "Permissions"). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h1600dp")
class PermissionsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val changes = mutableListOf<Pair<PhoneContext, JsonObject?>>()

    private fun show(persona: PersonaPermissions) = compose.setContent {
        WigginsTheme {
            PermissionsScreen(
                persona = persona, actionContexts = setOf(PhoneContext.UNLOCKED),
                onActionsChange = { _, _ -> }, onTrustedHistoryChange = {}, onToolsChange = { w, c -> changes += w to c },
                onOpenActions = {}, onBack = {},
            )
        }
    }

    @Test fun turningOffAServiceInAContextSendsNone() {
        show(PersonaPermissions(catalog = SampleCatalog.catalog))
        compose.onNodeWithText("Trusted device").performClick()
        compose.onNodeWithText("Notes").performClick() // opens it
        compose.onNodeWithText("Write note").performClick()
        assertEquals(PhoneContext.TRUSTED_DEVICE to Json.parseToJsonElement("""{"notes":["search_notes"]}""").jsonObject, changes.last())
    }

    @Test fun withoutACatalogTheHubDecides() {
        show(PersonaPermissions())
        compose.onNodeWithText("This hub hasn't listed its persona's tools", substring = true).assertExists()
    }

    @Test fun resetGoesBackToTheHubsDefaults() {
        show(PersonaPermissions(catalog = SampleCatalog.catalog, tools = mapOf(PhoneContext.UNLOCKED to JsonObject(emptyMap()))))
        compose.onNodeWithText("Reset to defaults").performClick()
        assertEquals(PhoneContext.UNLOCKED to null, changes.last())
    }
}
