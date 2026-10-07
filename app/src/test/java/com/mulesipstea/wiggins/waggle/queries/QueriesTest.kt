package com.mulesipstea.wiggins.waggle.queries

import android.Manifest
import com.mulesipstea.wiggins.waggle.ErrorCode
import com.mulesipstea.wiggins.waggle.WaggleException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class QueriesTest {
    private fun params(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun assertBadRequest(block: () -> Unit) {
        val e = assertThrows(WaggleException::class.java) { block() }
        assertEquals(ErrorCode.BAD_REQUEST, e.code)
    }

    @Test fun registryHasTheThreeQueries() {
        assertEquals(listOf("calendar.next", "contacts.lookup", "apps.list"), Queries.all.map { it.name })
        assertSame(CalendarNext, Queries.named("calendar.next"))
        assertSame(ContactsLookup, Queries.named("contacts.lookup"))
        assertSame(AppsList, Queries.named("apps.list"))
        assertNull(Queries.named("sms.read"))
    }

    @Test fun permissions() {
        assertEquals(Manifest.permission.READ_CALENDAR, CalendarNext.permission)
        assertEquals(Manifest.permission.READ_CONTACTS, ContactsLookup.permission)
        assertNull(AppsList.permission)
    }

    @Test fun intParamDefaultsAndBounds() {
        assertEquals(1, params("{}").intParam("count", 1, 1, 10))
        assertEquals(10, params("""{"count": 10}""").intParam("count", 1, 1, 10))
        assertEquals(Int.MAX_VALUE, params("""{"limit": 99999999999}""").intParam("limit", 5, 1))
        for (bad in listOf("0", "11", "-1", "\"3\"", "2.5", "true", "null", "[1]", "{}")) {
            assertBadRequest { params("""{"count": $bad}""").intParam("count", 1, 1, 10) }
        }
    }

    @Test fun stringParam() {
        assertEquals("Mom", params("""{"name": "Mom"}""").stringParam("name", required = true))
        assertNull(params("{}").stringParam("name"))
        assertNull(params("""{"name": null}""").stringParam("name"))
        assertEquals("", params("""{"name": ""}""").stringParam("name"))
        assertBadRequest { params("{}").stringParam("name", required = true) }
        assertBadRequest { params("""{"name": null}""").stringParam("name", required = true) }
        assertBadRequest { params("""{"name": ""}""").stringParam("name", required = true) }
        assertBadRequest { params("""{"name": 3}""").stringParam("name") }
        assertBadRequest { params("""{"name": ["a"]}""").stringParam("name") }
    }

    @Test fun folding() {
        assertEquals("jose alvarez", foldForMatch("  José ÁLVAREZ "))
    }

    @Test fun wireInstantIsUtcSecondsWithZ() {
        assertEquals("2026-10-05T19:00:00Z", wireInstant(1_791_226_800_123))
    }
}
