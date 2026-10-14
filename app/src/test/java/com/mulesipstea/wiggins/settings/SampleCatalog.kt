package com.mulesipstea.wiggins.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** A persona list response in the hub's shape (made-up servers and tools: the repo is public). */
object SampleCatalog {
    private fun tool(name: String, label: String, destructive: Boolean = false, locked: String = "allow", lockedDefault: Boolean = false) = """
        {"name":"$name","label":"$label","description":"$label, briefly.","destructive":$destructive,
         "max":{"unlocked":"allow","trusted_device":"allow","locked":"$locked"},
         "default":{"unlocked":${!destructive},"trusted_device":${!destructive},"locked":$lockedDefault}}"""

    val json: JsonObject = Json.parseToJsonElement("""
        {"pipeline_id":"persona.openvoiceos","personas":[{"persona_id":"Ada","name":"Ada","tools":{"version":"0123456789abcdef","servers":[
          {"server":"notes","label":"Notes","tools":[${tool("search_notes", "Search notes")},${tool("write_note", "Write note")}]},
          {"server":"calendar","label":"Calendar","tools":[${tool("list_events", "List events")},
             ${tool("delete_event", "Delete event", destructive = true, locked = "never")}]},
          {"server":"homeassistant","label":"Home Assistant","tools":[${tool("intent__HassTurnOn", "Turn on")},
             {"name":"broken"},{"label":"no name"}]},
          {"server":"empty","label":"Nothing","tools":[]}]}}]}""").jsonObject

    val catalog = ToolCatalog.parse(json)!!
}
