package com.mulesipstea.wiggins.waggle

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test

class MessagesTest {
    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun intent(fields: String = ""): JsonObject =
        obj("""{"id":"r1","action":"android.intent.action.VIEW"${if (fields.isEmpty()) "" else ", $fields"}}""")

    private fun extra(type: String, value: String) =
        intent(""""extras": {"x": {"type": "$type", "value": $value}}""")

    private fun assertBadIntent(data: JsonObject) {
        val e = assertThrows("$data", WaggleException::class.java) { IntentRequest.parse(data) }
        assertEquals("$data", ErrorCode.BAD_REQUEST, e.code)
    }

    private fun assertBadQuery(data: JsonObject) {
        val e = assertThrows("$data", WaggleException::class.java) { QueryRequest.parse(data) }
        assertEquals("$data", ErrorCode.BAD_REQUEST, e.code)
    }

    // --- waggle.intent -----------------------------------------------------------

    @Test fun parsesTheWaggleMdExample() {
        val request = IntentRequest.parse(obj("""
            {"id": "5f0c1e9a-3b7d-4c4e-9a51-2f8e6b0d7c11",
             "description": "Set an alarm for 6:30 AM",
             "action": "android.intent.action.SET_ALARM",
             "extras": {
               "android.intent.extra.alarm.HOUR": {"type": "int", "value": 6},
               "android.intent.extra.alarm.MINUTES": {"type": "int", "value": 30},
               "android.intent.extra.alarm.SKIP_UI": {"type": "bool", "value": true}
             },
             "some_future_field": {"ignored": true}}
        """))
        assertEquals("5f0c1e9a-3b7d-4c4e-9a51-2f8e6b0d7c11", request.id)
        assertEquals("android.intent.action.SET_ALARM", request.action)
        assertEquals("Set an alarm for 6:30 AM", request.description)
        assertNull(request.data)
        assertNull(request.scheme)
        assertEquals(emptyList<String>(), request.categories)
        assertEquals(Extra("int", JsonPrimitive(6)), request.extras["android.intent.extra.alarm.HOUR"])
        assertEquals(Extra("bool", JsonPrimitive(true)), request.extras["android.intent.extra.alarm.SKIP_UI"])
    }

    @Test fun parsesAllFields() {
        val request = IntentRequest.parse(intent("""
            "data": "HTTPS://example.com/", "mime_type": "text/html", "description": null,
            "categories": ["android.intent.category.BROWSABLE"], "package": "org.mozilla.firefox"
        """))
        assertEquals("HTTPS://example.com/", request.data)
        assertEquals("https", request.scheme)
        assertEquals("text/html", request.mimeType)
        assertNull(request.description)
        assertEquals(listOf("android.intent.category.BROWSABLE"), request.categories)
        assertEquals("org.mozilla.firefox", request.packageName)
    }

    @Test fun acceptsEveryExtraType() {
        val request = IntentRequest.parse(intent(""""extras": {
            "i": {"type": "int", "value": -2147483648},
            "l": {"type": "long", "value": 9223372036854775807},
            "f": {"type": "float", "value": 1.5},
            "fi": {"type": "float", "value": 2},
            "d": {"type": "double", "value": -0.25},
            "b": {"type": "bool", "value": false},
            "s": {"type": "string", "value": ""},
            "sa": {"type": "string[]", "value": ["a", "b"]},
            "se": {"type": "string[]", "value": []},
            "u": {"type": "uri", "value": "geo:0,0?q=coffee"}
        }"""))
        assertEquals(10, request.extras.size)
        assertEquals("string[]", request.extras.getValue("sa").type)
    }

    @Test fun rejectsMissingOrBadIdAndAction() {
        assertBadIntent(obj("""{"action":"a"}"""))
        assertBadIntent(obj("""{"id":null,"action":"a"}"""))
        assertBadIntent(obj("""{"id":"","action":"a"}"""))
        assertBadIntent(obj("""{"id":7,"action":"a"}"""))
        assertBadIntent(obj("""{"id":"r1"}"""))
        assertBadIntent(obj("""{"id":"r1","action":""}"""))
        assertBadIntent(obj("""{"id":"r1","action":["a"]}"""))
    }

    @Test fun rejectsOptionalFieldsOfTheWrongType() {
        assertBadIntent(intent(""""description": 5"""))
        assertBadIntent(intent(""""data": 5"""))
        assertBadIntent(intent(""""mime_type": true"""))
        assertBadIntent(intent(""""package": {}"""))
        assertBadIntent(intent(""""categories": "android.intent.category.LAUNCHER""""))
        assertBadIntent(intent(""""categories": null"""))
        assertBadIntent(intent(""""categories": [""]"""))
        assertBadIntent(intent(""""categories": [3]"""))
        assertBadIntent(intent(""""extras": []"""))
        assertBadIntent(intent(""""extras": null"""))
        assertBadIntent(intent(""""extras": {"x": "string"}"""))
        assertBadIntent(intent(""""extras": {"": {"type": "string", "value": "v"}}"""))
    }

    @Test fun rejectsForbiddenDataSchemesInAnyCase() {
        for (scheme in listOf("content", "file", "intent", "android-app")) {
            for (variant in listOf(scheme, scheme.uppercase(), scheme.replaceFirstChar { it.uppercase() })) {
                assertBadIntent(intent(""""data": "$variant://x/y""""))
                assertBadIntent(intent(""""data": "$variant:x""""))
            }
        }
        assertBadIntent(intent(""""data": "CoNtEnT://contacts/people/1""""))
        assertBadIntent(intent(""""data": "Android-App://com.example/https/x""""))
    }

    @Test fun rejectsDataWithoutAScheme() {
        assertBadIntent(intent(""""data": """""))
        assertBadIntent(intent(""""data": "/sdcard/x""""))
        assertBadIntent(intent(""""data": "example.com/x""""))
        assertBadIntent(intent(""""data": " content://x""""))
        assertBadIntent(intent(""""data": "1tel:5""""))
    }

    @Test fun allowsOrdinarySchemes() {
        for (uri in listOf("tel:+15551234567", "SMSTO:5", "https://example.com", "geo:0,0", "mailto:a@b.c", "contents:x", "files:x")) {
            IntentRequest.parse(intent(""""data": "$uri""""))
            IntentRequest.parse(extra("uri", "\"$uri\""))
        }
    }

    @Test fun rejectsForbiddenSchemesInUriExtras() {
        for (uri in listOf("content://x", "CONTENT://x", "File:///sdcard/x", "intent:#Intent;end", "INTENT:x", "android-app://p", "ANDROID-APP://p")) {
            assertBadIntent(extra("uri", "\"$uri\""))
        }
        assertBadIntent(extra("uri", "\"no-scheme\""))
        assertBadIntent(extra("uri", "\"\""))
        assertBadIntent(extra("uri", "5"))
        assertBadIntent(extra("uri", "null"))
    }

    @Test fun forbiddenSchemesInStringExtrasAreJustStrings() {
        IntentRequest.parse(extra("string", "\"content://x\""))
    }

    @Test fun rejectsUnknownExtraTypes() {
        assertBadIntent(extra("short", "1"))
        assertBadIntent(extra("INT", "1"))
        assertBadIntent(extra("intent", "\"x\""))
        assertBadIntent(intent(""""extras": {"x": {"value": 1}}"""))
        assertBadIntent(intent(""""extras": {"x": {"type": 1, "value": 1}}"""))
    }

    @Test fun rejectsValuesThatDontMatchTheirType() {
        val bad = mapOf(
            "int" to listOf("2147483648", "-2147483649", "1.5", "6.0", "1e3", "1E3", "-0.0", "\"6\"", "true", "null"),
            "long" to listOf("9223372036854775808", "1.5", "2e5", "\"6\"", "false", "null"),
            "float" to listOf("\"1.5\"", "true", "null", "[1]"),
            "double" to listOf("\"1.5\"", "false", "null", "{}"),
            "bool" to listOf("1", "0", "\"true\"", "null"),
            "string" to listOf("1", "true", "null", "[\"a\"]"),
            "string[]" to listOf("\"a\"", "[1]", "[\"a\", null]", "null", "{}"),
        )
        for ((type, values) in bad) for (value in values) assertBadIntent(extra(type, value))
        assertBadIntent(intent(""""extras": {"x": {"type": "int"}}"""))
    }

    // --- waggle.query --------------------------------------------------------------

    @Test fun parsesQueries() {
        val q = QueryRequest.parse(obj("""{"id":"c2a1","name":"contacts.lookup","params":{"name":"Mom"},"extra":1}"""))
        assertEquals(QueryRequest("c2a1", "contacts.lookup", obj("""{"name":"Mom"}""")), q)
        assertEquals(JsonObject(emptyMap()), QueryRequest.parse(obj("""{"id":"c","name":"apps.list"}""")).params)
        assertEquals(JsonObject(emptyMap()), QueryRequest.parse(obj("""{"id":"c","name":"apps.list","params":null}""")).params)
    }

    @Test fun rejectsBadQueries() {
        assertBadQuery(obj("""{"name":"apps.list"}"""))
        assertBadQuery(obj("""{"id":"","name":"apps.list"}"""))
        assertBadQuery(obj("""{"id":1,"name":"apps.list"}"""))
        assertBadQuery(obj("""{"id":"c"}"""))
        assertBadQuery(obj("""{"id":"c","name":""}"""))
        assertBadQuery(obj("""{"id":"c","name":null}"""))
        assertBadQuery(obj("""{"id":"c","name":"apps.list","params":[]}"""))
        assertBadQuery(obj("""{"id":"c","name":"apps.list","params":"x"}"""))
    }

    // --- request ids ---------------------------------------------------------------

    @Test fun requestIdFromAnyData() {
        assertEquals("r1", requestId(obj("""{"id":"r1","action":5}""")))
        assertEquals("", requestId(obj("""{"id":""}""")))
        assertNull(requestId(obj("""{"id":5}""")))
        assertNull(requestId(obj("""{}""")))
        assertNull(requestId(null))
    }

    // --- waggle.capabilities -------------------------------------------------------

    @Test fun capabilitiesMatchTheWaggleMdExample() {
        val example = obj("""{
          "version": 1,
          "client": {"name": "Wiggins", "version": "0.3.0"},
          "timezone": "America/Chicago",
          "lang": "en-US",
          "ask_timeout_s": 15,
          "unmatched": "block",
          "rules": [
            {"action": "android.intent.action.SET_ALARM", "mode": "run"},
            {"action": "android.intent.action.SET_TIMER", "mode": "run"},
            {"action": "android.intent.action.SHOW_ALARMS", "mode": "run"},
            {"action": "android.intent.action.MAIN", "category": "android.intent.category.LAUNCHER", "mode": "run"},
            {"action": "android.intent.action.DIAL", "scheme": "tel", "mode": "ask"},
            {"action": "android.intent.action.SENDTO", "scheme": "smsto", "mode": "ask"}
          ],
          "queries": ["calendar.next", "contacts.lookup", "apps.list"]
        }""")
        val rules = (example.getValue("rules") as JsonArray).map { Rule.fromJson(it.jsonObject)!! }
        val caps = Capabilities(
            clientName = "Wiggins", clientVersion = "0.3.0", timezone = "America/Chicago", lang = "en-US",
            askTimeoutS = 15, unmatched = Mode.BLOCK, rules = rules,
            queries = listOf("calendar.next", "contacts.lookup", "apps.list"),
        )
        val json = caps.toJson()
        assertEquals(example, json)
        assertEquals(example.keys.toList(), json.keys.toList())
        assertEquals(
            Json.encodeToString(JsonObject.serializer(), example),
            Json.encodeToString(JsonObject.serializer(), json),
        )
    }

    @Test fun capabilitiesWithAskAndNoQueries() {
        val json = Capabilities("Wiggins", "1", "UTC", "en", 30, Mode.ASK, emptyList(), emptyList()).toJson()
        assertEquals(JsonPrimitive("ask"), json["unmatched"])
        assertEquals(JsonArray(emptyList()), json["rules"])
        assertEquals(JsonArray(emptyList()), json["queries"])
        assertEquals(JsonPrimitive(Waggle.VERSION), json["version"])
    }

    @Test fun capabilitiesRefuseUnmatchedRun() {
        try {
            Capabilities("Wiggins", "1", "UTC", "en", 15, Mode.RUN, emptyList(), emptyList())
            fail("unmatched run must be refused")
        } catch (_: IllegalArgumentException) {
        }
    }
}
