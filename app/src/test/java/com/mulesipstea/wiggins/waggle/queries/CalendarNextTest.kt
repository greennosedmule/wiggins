package com.mulesipstea.wiggins.waggle.queries

import android.Manifest
import android.app.Application
import android.provider.CalendarContract
import android.provider.CalendarContract.Instances
import com.mulesipstea.wiggins.waggle.ErrorCode
import com.mulesipstea.wiggins.waggle.WaggleException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
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
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CalendarNextTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var provider: FakeProvider

    // Monday 2026-10-05 07:00 in Chicago (CDT, UTC-5).
    private val now = ms("2026-10-05T12:00:00Z")
    private val zone = ZoneId.of("America/Chicago")
    private val day = 86_400_000L

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun row(title: String?, begin: String, end: String?, allDay: Boolean = false, location: String? = null, calendar: String? = "Personal") =
        mapOf(
            Instances.TITLE to title, Instances.BEGIN to ms(begin), Instances.END to end?.let(::ms),
            Instances.ALL_DAY to if (allDay) 1 else 0, Instances.EVENT_LOCATION to location,
            Instances.CALENDAR_DISPLAY_NAME to calendar,
        )

    private fun instance(title: String, begin: String, end: String, allDay: Boolean = false) =
        CalendarNext.Instance(title, ms(begin), ms(end), allDay, null, null)

    private fun run(json: String): JsonObject = runBlocking {
        CalendarNext.run(app, Json.parseToJsonElement(json).jsonObject, now, zone)
    }

    private fun summaries(result: JsonObject) = result.getValue("events").jsonArray.map { it.jsonObject.getValue("summary").jsonPrimitive.content }

    @Before fun setUp() {
        provider = Robolectric.setupContentProvider(FakeProvider::class.java, CalendarContract.AUTHORITY)
        provider.tables["instances/when"] = listOf(
            row("Dentist", "2026-10-05T19:00:00Z", "2026-10-05T20:00:00Z", location = "123 Main St"),
            row("Coffee", "2026-10-05T08:00:00Z", "2026-10-05T09:00:00Z"), // already over
            row("Standup", "2026-10-05T11:30:00Z", "2026-10-05T12:30:00Z", location = "  ", calendar = "Work"), // in progress
            row("Holiday", "2026-10-06T00:00:00Z", "2026-10-07T00:00:00Z", allDay = true, calendar = "Holidays"),
            row("Today", "2026-10-05T00:00:00Z", "2026-10-06T00:00:00Z", allDay = true), // all day, in progress
            row("Next week", "2026-10-12T15:00:00Z", "2026-10-12T16:00:00Z"),
        )
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
    }

    @Test fun nextEventByDefaultIncludesOneInProgress() {
        // The all-day "Today" began at local midnight, before the timed in-progress "Standup".
        assertEquals(listOf("Today"), summaries(run("{}")))
    }

    @Test fun eventsInStartOrderWithinTheWindow() {
        val result = run("""{"count": 10, "within_days": 1}""")
        assertEquals(listOf("Today", "Standup", "Dentist", "Holiday"), summaries(result))
        assertEquals(listOf("Today", "Standup", "Dentist", "Holiday", "Next week"), summaries(run("""{"count": 10, "within_days": 8}""")))
        assertEquals(listOf("Today", "Standup"), summaries(run("""{"count": 2, "within_days": 7}""")))
    }

    @Test fun eventShapes() {
        val events = run("""{"count": 10, "within_days": 1}""").getValue("events").jsonArray
        assertEquals(
            Json.parseToJsonElement("""{"summary": "Dentist", "dtstart": "2026-10-05T19:00:00Z", "dtend": "2026-10-05T20:00:00Z",
                "all_day": false, "location": "123 Main St", "calendar": "Personal"}"""),
            events[2],
        )
        // Blank location is left out.
        assertEquals(
            Json.parseToJsonElement("""{"summary": "Standup", "dtstart": "2026-10-05T11:30:00Z", "dtend": "2026-10-05T12:30:00Z",
                "all_day": false, "calendar": "Work"}"""),
            events[1],
        )
        // All-day: date-only, dtend exclusive.
        assertEquals(
            Json.parseToJsonElement("""{"summary": "Holiday", "dtstart": "2026-10-06", "dtend": "2026-10-07",
                "all_day": true, "calendar": "Holidays"}"""),
            events[3],
        )
    }

    @Test fun asksForVisibleCalendarsOverAWidenedWindow() {
        run("""{"within_days": 2}""")
        val request = provider.requests.single()
        val segments = request.uri.pathSegments
        assertEquals(listOf("instances", "when"), segments.take(2))
        assertEquals(now - day, segments[2].toLong())
        assertEquals(now + 3 * day, segments[3].toLong())
        assertTrue(request.selection!!.contains("${Instances.VISIBLE} = 1"))
    }

    @Test fun largeWindowIsAcceptedAndCapped() {
        run("""{"within_days": 100000}""")
        val segments = provider.requests.single().uri.pathSegments
        assertEquals(now + (CalendarNext.MAX_WINDOW_DAYS + 1) * day, segments[3].toLong())
    }

    @Test fun noEvents() {
        provider.tables.clear()
        assertEquals(Json.parseToJsonElement("""{"events": []}"""), run("""{"count": 3}"""))
    }

    @Test fun badParams() {
        for (bad in listOf("""{"count": 0}""", """{"count": 11}""", """{"count": "2"}""", """{"count": 1.5}""",
            """{"within_days": 0}""", """{"within_days": -3}""", """{"within_days": true}""", """{"within_days": null}""")) {
            val e = assertThrows(bad, WaggleException::class.java) { run(bad) }
            assertEquals(bad, ErrorCode.BAD_REQUEST, e.code)
        }
    }

    @Test fun permissionDenied() {
        shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR)
        val e = assertThrows(WaggleException::class.java) { run("{}") }
        assertEquals(ErrorCode.PERMISSION_DENIED, e.code)
        assertTrue(provider.requests.isEmpty())
    }

    @Test fun selectTreatsAllDayAsLocalMidnight() {
        // 22:00 Chicago on Oct 5 is 03:00Z on Oct 6. The all-day event is stored at 00:00Z
        // but starts at local midnight, 05:00Z, so the late-evening timed event comes first.
        val late = instance("Late", "2026-10-06T03:00:00Z", "2026-10-06T04:00:00Z")
        val allDay = instance("Oct 6", "2026-10-06T00:00:00Z", "2026-10-07T00:00:00Z", allDay = true)
        val picked = CalendarNext.select(listOf(allDay, late), now, now + 2 * day, 10, zone)
        assertEquals(listOf("Late", "Oct 6"), picked.map { it.title })
        // Oct 4 (all day) ended at Oct 5's local midnight, 05:00Z, before now.
        val yesterday = instance("Oct 4", "2026-10-04T00:00:00Z", "2026-10-05T00:00:00Z", allDay = true)
        assertEquals(emptyList<String>(), CalendarNext.select(listOf(yesterday), now, now + day, 10, zone).map { it.title })
    }

    @Test fun allDayWithoutAProperEndLastsOneDay() {
        val json = CalendarNext.eventJson(CalendarNext.Instance(null, ms("2026-10-06T00:00:00Z"), null, true, null, null))
        assertEquals(Json.parseToJsonElement("""{"summary": "", "dtstart": "2026-10-06", "dtend": "2026-10-07", "all_day": true}"""), json)
    }
}
