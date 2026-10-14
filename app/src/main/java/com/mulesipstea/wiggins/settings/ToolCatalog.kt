package com.mulesipstea.wiggins.settings

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The persona's tools as the hub offers them to this phone (SPEC "Permissions"), from
 * `ovos.persona.list.response`: per server, each tool with what the hub allows per
 * context at most ([Tool.max]) and its default there. The user narrows it per context.
 */
data class ToolCatalog(val version: String, val persona: String?, val servers: List<Server>) {
    data class Server(val server: String, val label: String, val tools: List<Tool>)

    data class Tool(
        val name: String,
        val label: String,
        val description: String,
        /** Deletes or changes things that can't be undone: off by default everywhere. */
        val destructive: Boolean,
        private val max: Map<String, String>,
        private val default: Map<String, Boolean>,
    ) {
        /** The hub allows it in [where] at all. */
        fun allowedIn(where: PhoneContext) = max[where.wire] == "allow"

        /** On in [where] when the user hasn't chosen: the hub's default, and never a destructive tool. */
        fun defaultIn(where: PhoneContext) = allowedIn(where) && !destructive && default[where.wire] == true
    }

    companion object {
        /**
         * The catalog in a persona list response's [data], or null if the hub lists no
         * tools. The first persona with tools counts; malformed entries are skipped.
         */
        fun parse(data: JsonObject): ToolCatalog? {
            val persona = (data["personas"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                .firstOrNull { it["tools"] is JsonObject } ?: return null
            val tools = persona["tools"] as JsonObject
            val servers = (tools["servers"] as? JsonArray).orEmpty().mapNotNull { s ->
                val o = s as? JsonObject ?: return@mapNotNull null
                val id = o.string("server") ?: return@mapNotNull null
                val list = (o["tools"] as? JsonArray).orEmpty().mapNotNull { t ->
                    val tool = t as? JsonObject ?: return@mapNotNull null
                    val name = tool.string("name") ?: return@mapNotNull null
                    Tool(
                        name = name,
                        label = tool.string("label") ?: name,
                        description = tool.string("description").orEmpty(),
                        destructive = (tool["destructive"] as? JsonPrimitive)?.booleanOrNull ?: false,
                        max = (tool["max"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap(),
                        default = (tool["default"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.booleanOrNull?.let { k to it } }.toMap(),
                    )
                }
                Server(id, o.string("label") ?: id, list).takeIf { list.isNotEmpty() }
            }
            if (servers.isEmpty()) return null
            return ToolCatalog(tools.string("version").orEmpty(), persona.string("name") ?: persona.string("persona_id"), servers)
        }

        private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }
}

/**
 * The user's tool choices for one context, in `persona_tools` form: per server `"none"`
 * or a list of tool names. A server that isn't named follows the hub's defaults. Wiggins
 * never writes `"all"`, which would let tools the hub adds later in, deletes included:
 * in a server the user has changed, new tools start off.
 */
object ToolChoices {
    /** Whether [tool] of [server] is on in [where], given the user's [choices] there. */
    fun isOn(choices: JsonObject, server: String, tool: ToolCatalog.Tool, where: PhoneContext): Boolean {
        if (!tool.allowedIn(where)) return false
        return when (val choice = choices[server]) {
            null -> tool.defaultIn(where)
            is JsonArray -> choice.any { (it as? JsonPrimitive)?.contentOrNull == tool.name }
            is JsonPrimitive -> choice.contentOrNull == "all"
            else -> false
        }
    }

    enum class State { ALL, NONE, MIXED }

    /** How a server reads at a glance in [where]: all its allowed tools on, none, or some. */
    fun state(choices: JsonObject, server: ToolCatalog.Server, where: PhoneContext): State {
        val allowed = server.tools.filter { it.allowedIn(where) }
        val on = allowed.count { isOn(choices, server.server, it, where) }
        return when (on) {
            0 -> State.NONE
            allowed.size -> State.ALL
            else -> State.MIXED
        }
    }

    /** [choices] with [tool] of [server] turned [on]. */
    fun setTool(choices: JsonObject, server: ToolCatalog.Server, tool: String, on: Boolean, where: PhoneContext): JsonObject {
        val now = server.tools.filter { isOn(choices, server.server, it, where) }.map { it.name }.toSet()
        return with(choices, server.server, if (on) now + tool else now - tool)
    }

    /**
     * [choices] with every allowed tool of [server] on, except destructive ones the user
     * hasn't turned on themselves; or every tool off.
     */
    fun setServer(choices: JsonObject, server: ToolCatalog.Server, on: Boolean, where: PhoneContext): JsonObject {
        if (!on) return with(choices, server.server, emptySet())
        val keep = server.tools.filter { it.destructive && isOn(choices, server.server, it, where) }.map { it.name }
        val safe = server.tools.filter { it.allowedIn(where) && !it.destructive }.map { it.name }
        return with(choices, server.server, (safe + keep).toSet())
    }

    private fun with(choices: JsonObject, server: String, names: Set<String>): JsonObject {
        val value: JsonElement = if (names.isEmpty()) JsonPrimitive("none") else JsonArray(names.sorted().map(::JsonPrimitive))
        return JsonObject(choices + (server to value))
    }
}
