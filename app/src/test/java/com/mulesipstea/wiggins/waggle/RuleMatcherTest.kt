package com.mulesipstea.wiggins.waggle

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs every case in waggle/rule_cases.json, an unchanged copy of
 * ovos-skill-waggle/waggle/testdata/rule_cases.json, so Wiggins and the hub's Python
 * matcher agree. Refresh the copy when the upstream file changes.
 */
class RuleMatcherTest {
    private val doc: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.getResource("/waggle/rule_cases.json")).readText(),
    ).jsonObject
    private val cases = doc.getValue("cases").jsonArray.map { it.jsonObject }

    @Test fun fixtureIsTheV1File() {
        assertEquals(1, doc.getValue("version").jsonPrimitive.int)
        assertTrue(cases.size >= 40)
    }

    @Test fun allSharedCasesPass() {
        val failures = cases.mapNotNull { case ->
            val name = case.getValue("name").jsonPrimitive.content
            runCatching { check(case) }.exceptionOrNull()?.let { "$name: ${it.message}" }
        }
        assertTrue("${failures.size} of ${cases.size} cases failed:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun check(case: JsonObject) {
        val rules = case.getValue("rules").jsonArray.map { r ->
            assertNotNull("rule $r should parse", Rule.fromJson(r.jsonObject))
            Rule.fromJson(r.jsonObject)!!
        }
        val unmatched = case["unmatched"]?.let { Mode.fromWire(it.jsonPrimitive.content)!! } ?: Mode.BLOCK
        val intent = IntentRequest.parse(case.getValue("intent").jsonObject)
        val expect = case.getValue("expect").jsonObject
        val decision = RuleMatcher.decide(rules, unmatched, intent)
        assertEquals(Mode.fromWire(expect.getValue("mode").jsonPrimitive.content), decision.mode)
        val index = expect.getValue("rule")
        if (index is JsonNull) assertNull(decision.rule) else assertSame(rules[index.jsonPrimitive.int], decision.rule)
    }

    @Test fun ruleJsonRoundTrips() {
        for (case in cases) {
            for (r in case.getValue("rules").jsonArray) {
                val rule = Rule.fromJson(r.jsonObject)!!
                assertEquals(rule, Rule.fromJson(rule.toJson()))
            }
        }
    }

    @Test fun ruleSchemeIsLowercased() {
        val rule = Rule.fromJson(Json.parseToJsonElement("""{"action":"a","scheme":"TeL","mode":"run"}""").jsonObject)
        assertEquals("tel", rule?.scheme)
    }

    @Test fun malformedRulesAreNull() {
        listOf(
            """{"mode":"run"}""",
            """{"action":"","mode":"run"}""",
            """{"action":5,"mode":"run"}""",
            """{"action":"a"}""",
            """{"action":"a","mode":"RUN"}""",
            """{"action":"a","mode":"allow"}""",
            """{"action":"a","mode":"run","scheme":""}""",
            """{"action":"a","mode":"run","package":3}""",
            """{"action":"a","mode":"run","category":["c"]}""",
        ).forEach { assertNull(it, Rule.fromJson(Json.parseToJsonElement(it).jsonObject)) }
    }

    @Test fun ruleToJsonUsesPackageKey() {
        val json = Rule("a", "tel", "p", "c", Mode.ASK).toJson()
        assertEquals(
            Json.parseToJsonElement("""{"action":"a","scheme":"tel","package":"p","category":"c","mode":"ask"}"""),
            json,
        )
        assertEquals(listOf("action", "mode"), Rule("a", mode = Mode.RUN).toJson().keys.toList())
    }
}
