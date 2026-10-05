package com.mulesipstea.wiggins.hivemind

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Loads the Python-generated vectors in src/test/resources/hivemind-vectors (see tools/vectors). */
object Vectors {
    fun load(name: String): JsonObject {
        val stream = checkNotNull(javaClass.classLoader!!.getResourceAsStream("hivemind-vectors/$name")) { "missing vector $name" }
        return Json.parseToJsonElement(stream.reader().readText()).jsonObject
    }

    fun cases(name: String, key: String): List<JsonObject> = load(name).getValue(key).jsonArray.map { it.jsonObject }
}

fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
fun JsonObject.hex(key: String): ByteArray = str(key).hexToByteArray()
fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.content.toBoolean()
fun JsonObject.arr(key: String): JsonArray = getValue(key).jsonArray
fun String.json(): JsonElement = Json.parseToJsonElement(this)
