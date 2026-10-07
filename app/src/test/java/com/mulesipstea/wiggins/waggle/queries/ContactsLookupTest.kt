package com.mulesipstea.wiggins.waggle.queries

import android.Manifest
import android.app.Application
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import com.mulesipstea.wiggins.waggle.ErrorCode
import com.mulesipstea.wiggins.waggle.WaggleException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContactsLookupTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var provider: FakeProvider

    private fun contact(id: Long, name: String?) = mapOf(Contacts._ID to id, Contacts.DISPLAY_NAME_PRIMARY to name)
    private fun nickname(id: Long, name: String) = mapOf(Data.CONTACT_ID to id, Data.MIMETYPE to Nickname.CONTENT_ITEM_TYPE, Nickname.NAME to name)
    private fun phone(id: Long, number: String, normalized: String?, type: Int) =
        mapOf(Phone.CONTACT_ID to id, Phone.NUMBER to number, Phone.NORMALIZED_NUMBER to normalized, Phone.TYPE to type)

    private fun run(json: String): JsonObject = runBlocking {
        ContactsLookup.run(app, Json.parseToJsonElement(json).jsonObject)
    }

    private fun names(result: JsonObject) = result.getValue("contacts").jsonArray.map { it.jsonObject.getValue("fn").jsonPrimitive.content }

    @Before fun setUp() {
        provider = Robolectric.setupContentProvider(FakeProvider::class.java, ContactsContract.AUTHORITY)
        provider.tables["contacts"] = listOf(
            contact(1, "Tom Momsen"),
            contact(2, "Mom"),
            contact(3, "Margaret Smith"),
            contact(4, "Kimomo"),
            contact(5, "José Álvarez"),
            contact(6, "Dad"),
            contact(7, null),
        )
        provider.tables["data"] = listOf(
            nickname(3, "Mommy"),
            nickname(6, "Pops"),
            nickname(7, "Nana"),
            // A row of another kind, which a real provider wouldn't return for this selection.
            mapOf(Data.CONTACT_ID to 6L, Data.MIMETYPE to "vnd.android.cursor.item/email_v2", Nickname.NAME to "mom@example.com"),
        )
        provider.tables["data/phones"] = listOf(
            phone(2, "+1 555-123-4567", "+15551234567", Phone.TYPE_MOBILE),
            phone(2, "(555) 123-4567", "+15551234567", Phone.TYPE_HOME), // the same number, from a linked raw contact
            phone(2, "555 765 4321", null, Phone.TYPE_HOME),
            phone(2, "555-000-1111", null, Phone.TYPE_COMPANY_MAIN),
            phone(6, "555-222-3333", null, Phone.TYPE_MOBILE),
        )
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
    }

    @Test fun matchesDisplayNameAndNicknameBestFirst() {
        // Exact, then prefix (nickname "Mommy"), then word prefix, then anywhere.
        assertEquals(listOf("Mom", "Margaret Smith", "Tom Momsen", "Kimomo"), names(run("""{"name": "MOM"}""")))
    }

    @Test fun ignoresAccentsAndCase() {
        assertEquals(listOf("José Álvarez"), names(run("""{"name": "jose alvarez"}""")))
        assertEquals(listOf("José Álvarez"), names(run("""{"name": "álv"}""")))
    }

    @Test fun nicknameOnlyContactUsesTheNickname() {
        assertEquals(listOf("Nana"), names(run("""{"name": "nan"}""")))
    }

    @Test fun limit() {
        assertEquals(listOf("Mom", "Margaret Smith"), names(run("""{"name": "mom", "limit": 2}""")))
        assertEquals(5, names(run("""{"name": "o"}""")).size) // the default; six match
    }

    @Test fun noMatches() {
        assertEquals(Json.parseToJsonElement("""{"contacts": []}"""), run("""{"name": "Zebedee"}"""))
        assertEquals(1, provider.requests.count { it.uri == Contacts.CONTENT_URI })
        assertTrue(provider.requests.none { it.uri == Phone.CONTENT_URI }) // no phones read without matches
    }

    @Test fun phonesAreDedupedAndTyped() {
        val contact = run("""{"name": "Mom", "limit": 1}""").getValue("contacts").jsonArray.single()
        assertEquals(
            Json.parseToJsonElement("""{"fn": "Mom", "tel": [
                {"value": "+15551234567", "type": "cell"},
                {"value": "5557654321", "type": "home"},
                {"value": "5550001111", "type": "work"}
            ]}"""),
            contact,
        )
        val phones = provider.requests.single { it.uri == Phone.CONTENT_URI }
        assertEquals(listOf("2"), phones.args)
        assertEquals("${Phone.CONTACT_ID} IN (?)", phones.selection)
    }

    @Test fun nicknamesAreNeverSent() {
        val contact = run("""{"name": "pops"}""").getValue("contacts").jsonArray.single().jsonObject
        assertEquals(setOf("fn", "tel"), contact.keys)
        assertEquals("Dad", contact.getValue("fn").jsonPrimitive.content)
    }

    @Test fun otherDataKindsDontMatch() {
        assertTrue(names(run("""{"name": "example.com"}""")).isEmpty())
    }

    @Test fun phoneTypes() {
        assertEquals("cell", ContactsLookup.phoneType(Phone.TYPE_MOBILE))
        assertEquals("cell", ContactsLookup.phoneType(Phone.TYPE_WORK_MOBILE))
        assertEquals("home", ContactsLookup.phoneType(Phone.TYPE_HOME))
        assertEquals("work", ContactsLookup.phoneType(Phone.TYPE_WORK))
        assertEquals("other", ContactsLookup.phoneType(Phone.TYPE_OTHER))
        assertEquals("other", ContactsLookup.phoneType(Phone.TYPE_CUSTOM))
        assertEquals("other", ContactsLookup.phoneType(Phone.TYPE_MAIN))
    }

    @Test fun rank() {
        assertEquals(0, ContactsLookup.rank("mom", "mom"))
        assertEquals(1, ContactsLookup.rank("mom", "mommy"))
        assertEquals(2, ContactsLookup.rank("mom", "tom momsen"))
        assertEquals(2, ContactsLookup.rank("mom", "kimomo-mom"))
        assertEquals(3, ContactsLookup.rank("mom", "kimomo"))
        assertNull(ContactsLookup.rank("mom", "dad"))
    }

    @Test fun badParams() {
        for (bad in listOf("{}", """{"name": null}""", """{"name": ""}""", """{"name": "  "}""", """{"name": 7}""",
            """{"name": "Mom", "limit": 0}""", """{"name": "Mom", "limit": "5"}""")) {
            val e = assertThrows(bad, WaggleException::class.java) { run(bad) }
            assertEquals(bad, ErrorCode.BAD_REQUEST, e.code)
        }
    }

    @Test fun permissionDenied() {
        shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS)
        val e = assertThrows(WaggleException::class.java) { run("""{"name": "Mom"}""") }
        assertEquals(ErrorCode.PERMISSION_DENIED, e.code)
        assertTrue(provider.requests.isEmpty())
    }
}
