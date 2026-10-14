package com.mulesipstea.wiggins.settings

import com.mulesipstea.wiggins.settings.PhoneContext.LOCKED
import com.mulesipstea.wiggins.settings.PhoneContext.TRUSTED_DEVICE
import com.mulesipstea.wiggins.settings.PhoneContext.UNLOCKED
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hub's tool catalog and the user's choices from it (SPEC "Permissions"). */
class ToolCatalogTest {
    private val catalog = SampleCatalog.catalog
    private val notes = catalog.servers.first { it.server == "notes" }
    private val calendar = catalog.servers.first { it.server == "calendar" }
    private val none = JsonObject(emptyMap())
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test fun theCatalogParsesAndSkipsWhatsBroken() {
        assertEquals("0123456789abcdef", catalog.version)
        assertEquals("Ada", catalog.persona)
        assertEquals(listOf("notes", "calendar", "homeassistant"), catalog.servers.map { it.server }) // "empty" left out
        val ha = catalog.servers.first { it.server == "homeassistant" }
        assertEquals(listOf("intent__HassTurnOn", "broken"), ha.tools.map { it.name }) // a tool with no name is dropped
        assertEquals("broken", ha.tools[1].label)
        assertFalse(ha.tools[1].allowedIn(UNLOCKED)) // no max: never allowed
        assertNull(ToolCatalog.parse(json("""{"personas":[{"persona_id":"Ada","name":"Ada"}]}""")))
        assertNull(ToolCatalog.parse(json("""{}""")))
    }

    @Test fun defaultsFollowTheHubButDeletesStayOff() {
        assertTrue(ToolChoices.isOn(none, "notes", notes.tools[0], TRUSTED_DEVICE))
        assertFalse(ToolChoices.isOn(none, "notes", notes.tools[0], LOCKED)) // the hub's locked default is off
        val delete = calendar.tools[1]
        assertTrue(delete.destructive)
        assertFalse(ToolChoices.isOn(none, "calendar", delete, UNLOCKED))
        assertFalse(delete.allowedIn(LOCKED))
    }

    @Test fun aServerReadsAllNoneOrMixed() {
        assertEquals(ToolChoices.State.ALL, ToolChoices.state(none, notes, UNLOCKED))
        assertEquals(ToolChoices.State.NONE, ToolChoices.state(none, notes, LOCKED))
        assertEquals(ToolChoices.State.MIXED, ToolChoices.state(none, calendar, UNLOCKED)) // the delete is off
    }

    @Test fun turningAServerOnLeavesDeletesOffAndNeverSendsAll() {
        val on = ToolChoices.setServer(none, calendar, true, UNLOCKED)
        assertEquals(json("""{"calendar":["list_events"]}"""), on)
        val withDelete = ToolChoices.setTool(on, calendar, "delete_event", true, UNLOCKED)
        assertEquals(json("""{"calendar":["delete_event","list_events"]}"""), withDelete)
        // Turning the server on again keeps a delete the user chose.
        assertEquals(withDelete, ToolChoices.setServer(withDelete, calendar, true, UNLOCKED))
        assertEquals(json("""{"calendar":"none"}"""), ToolChoices.setServer(withDelete, calendar, false, UNLOCKED))
    }

    @Test fun oneToolOffKeepsTheRestAsTheyWere() {
        val off = ToolChoices.setTool(none, notes, "write_note", false, TRUSTED_DEVICE)
        assertEquals(json("""{"notes":["search_notes"]}"""), off)
        assertEquals(json("""{"notes":"none"}"""), ToolChoices.setTool(off, notes, "search_notes", false, TRUSTED_DEVICE))
    }

    @Test fun aChoiceTheHubForbidsStaysOff() {
        val choices = json("""{"calendar":["delete_event"]}""")
        assertFalse(ToolChoices.isOn(choices, "calendar", calendar.tools[1], LOCKED))
        assertTrue(ToolChoices.isOn(choices, "calendar", calendar.tools[1], UNLOCKED))
        // "all" from an older choice is still read.
        assertTrue(ToolChoices.isOn(json("""{"notes":"all"}"""), "notes", notes.tools[1], LOCKED))
    }
}
