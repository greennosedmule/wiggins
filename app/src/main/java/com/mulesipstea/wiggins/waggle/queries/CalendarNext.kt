package com.mulesipstea.wiggins.waggle.queries

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract.Instances
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * `calendar.next`: the next `count` (1–10, default 1) event instances on visible calendars
 * that aren't over yet and start within `within_days` (default 7), in start order.
 *
 * "Starting from now" includes events already in progress, as the reference fake phone
 * does: an event is listed while it hasn't ended. All-day events count from midnight in
 * the phone's time zone, and are reported as the provider stores them, as UTC dates.
 */
object CalendarNext : Query {
    override val name = "calendar.next"
    override val permission: String = Manifest.permission.READ_CALENDAR

    /**
     * The longest window actually searched. The protocol sets no maximum for
     * `within_days`, so larger values are accepted and searched as this many days.
     */
    const val MAX_WINDOW_DAYS = 366

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** One row of [Instances]. [begin] and [end] are epoch millis, UTC midnights for all-day events. */
    internal data class Instance(
        val title: String?,
        val begin: Long,
        val end: Long?,
        val allDay: Boolean,
        val location: String?,
        val calendar: String?,
    )

    override suspend fun run(context: Context, params: JsonObject): JsonObject =
        run(context, params, System.currentTimeMillis(), ZoneId.systemDefault())

    internal suspend fun run(context: Context, params: JsonObject, now: Long, zone: ZoneId): JsonObject {
        val count = params.intParam("count", default = 1, min = 1, max = 10)
        val withinDays = params.intParam("within_days", default = 7, min = 1)
        requirePermission(context, permission)
        val horizon = now + minOf(withinDays, MAX_WINDOW_DAYS) * DAY_MS
        val instances = withContext(Dispatchers.IO) { read(context.contentResolver, now, horizon) }
        return eventsData(select(instances, now, horizon, count, zone))
    }

    /**
     * Instances overlapping [from, to], widened by a day each side: all-day instances are
     * stored at UTC midnight, which can be up to a day off the phone's own midnight.
     * [select] does the exact filtering.
     */
    private fun read(resolver: ContentResolver, from: Long, to: Long): List<Instance> {
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from - DAY_MS)
            ContentUris.appendId(it, to + DAY_MS)
        }.build()
        val projection = arrayOf(
            Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY,
            Instances.EVENT_LOCATION, Instances.CALENDAR_DISPLAY_NAME,
        )
        val selection = "${Instances.VISIBLE} = 1 AND " +
            "(${Instances.STATUS} IS NULL OR ${Instances.STATUS} != ${Instances.STATUS_CANCELED})"
        val instances = mutableListOf<Instance>()
        resolver.query(uri, projection, selection, null, "${Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                if (c.isNull(1)) continue
                instances += Instance(
                    title = c.getString(0),
                    begin = c.getLong(1),
                    end = if (c.isNull(2)) null else c.getLong(2),
                    allDay = !c.isNull(3) && c.getInt(3) != 0,
                    location = c.getString(4),
                    calendar = c.getString(5),
                )
            }
        }
        return instances
    }

    /** The first [count] of [instances] not over by [now] and starting before [horizon], by start. */
    internal fun select(instances: List<Instance>, now: Long, horizon: Long, count: Int, zone: ZoneId): List<Instance> =
        instances
            .map { it to bounds(it, zone) }
            .filter { (_, b) -> (b.second > now || b.first >= now) && b.first < horizon }
            .sortedBy { (_, b) -> b.first }
            .take(count)
            .map { it.first }

    /** When [instance] starts and ends on the phone, in epoch millis. */
    private fun bounds(instance: Instance, zone: ZoneId): Pair<Long, Long> {
        if (!instance.allDay) return instance.begin to maxOf(instance.end ?: instance.begin, instance.begin)
        val (start, end) = allDayDates(instance)
        fun local(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()
        return local(start) to local(end)
    }

    /** An all-day instance's first day and exclusive end day, at least a day apart. */
    private fun allDayDates(instance: Instance): Pair<LocalDate, LocalDate> {
        fun date(millis: Long) = Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate()
        val start = date(instance.begin)
        val end = instance.end?.let(::date)?.takeIf { it > start } ?: start.plusDays(1)
        return start to end
    }

    internal fun eventJson(instance: Instance): JsonObject = buildJsonObject {
        put("summary", instance.title.orEmpty())
        if (instance.allDay) {
            val (start, end) = allDayDates(instance)
            put("dtstart", start.toString())
            put("dtend", end.toString())
        } else {
            put("dtstart", wireInstant(instance.begin))
            put("dtend", wireInstant(maxOf(instance.end ?: instance.begin, instance.begin)))
        }
        put("all_day", instance.allDay)
        instance.location?.takeIf { it.isNotBlank() }?.let { put("location", it.trim()) }
        instance.calendar?.takeIf { it.isNotBlank() }?.let { put("calendar", it) }
    }

    internal fun eventsData(instances: List<Instance>): JsonObject = buildJsonObject {
        put("events", buildJsonArray { instances.forEach { add(eventJson(it)) } })
    }
}
