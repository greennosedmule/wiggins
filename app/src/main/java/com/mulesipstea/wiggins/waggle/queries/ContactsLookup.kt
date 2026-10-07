package com.mulesipstea.wiggins.waggle.queries

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import android.telephony.PhoneNumberUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.Collator

/**
 * `contacts.lookup`: contacts whose display name or a nickname contains `name`
 * (required), up to `limit` (default 5), each with its deduplicated phone numbers.
 *
 * Matching ignores case and accents and finds `name` anywhere in a name, as the
 * reference fake phone does, then ranks the matches so the likeliest come first within
 * `limit`: an exact name, then a name starting with `name`, then a word in it starting
 * with `name`, then any other match; ties go alphabetically. So "mom" puts "Mom" before
 * "Tom Momsen". Only matches are returned, never the whole address book.
 */
object ContactsLookup : Query {
    override val name = "contacts.lookup"
    override val permission: String = Manifest.permission.READ_CONTACTS

    /** A contact's names, as read from the provider. */
    internal data class Candidate(val id: Long, val displayName: String?, val nicknames: List<String> = emptyList())

    /** A phone data row. [normalized] is the provider's E.164 form, when it has one. */
    internal data class PhoneRow(val number: String?, val normalized: String?, val type: Int)

    override suspend fun run(context: Context, params: JsonObject): JsonObject {
        val query = params.stringParam("name", required = true)!!
        if (query.isBlank()) throw badRequest("param 'name' must not be blank")
        val limit = params.intParam("limit", default = 5, min = 1)
        requirePermission(context, permission)
        return withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val matches = match(query, readCandidates(resolver), limit)
            val phones = readPhones(resolver, matches.map { it.first.id })
            buildJsonObject {
                put("contacts", buildJsonArray {
                    matches.forEach { (candidate, fn) -> add(contactJson(fn, phones[candidate.id].orEmpty())) }
                })
            }
        }
    }

    private fun readCandidates(resolver: ContentResolver): List<Candidate> {
        val names = linkedMapOf<Long, String?>()
        resolver.query(Contacts.CONTENT_URI, arrayOf(Contacts._ID, Contacts.DISPLAY_NAME_PRIMARY), null, null, null)
            ?.use { c -> while (c.moveToNext()) names[c.getLong(0)] = c.getString(1) }
        val nicknames = mutableMapOf<Long, MutableList<String>>()
        resolver.query(
            Data.CONTENT_URI,
            arrayOf(Data.CONTACT_ID, Data.MIMETYPE, Nickname.NAME),
            "${Data.MIMETYPE} = ?",
            arrayOf(Nickname.CONTENT_ITEM_TYPE),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val nickname = c.getString(2)
                if (c.getString(1) != Nickname.CONTENT_ITEM_TYPE || nickname.isNullOrBlank()) continue
                nicknames.getOrPut(c.getLong(0)) { mutableListOf() } += nickname
            }
        }
        return names.map { (id, name) -> Candidate(id, name, nicknames[id].orEmpty()) }
    }

    /** Phone rows of the contacts [ids], by contact, primary numbers first. */
    private fun readPhones(resolver: ContentResolver, ids: List<Long>): Map<Long, List<PhoneRow>> {
        if (ids.isEmpty()) return emptyMap()
        val wanted = ids.toSet()
        val rows = mutableMapOf<Long, MutableList<PhoneRow>>()
        resolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.NUMBER, Phone.NORMALIZED_NUMBER, Phone.TYPE),
            "${Phone.CONTACT_ID} IN (${ids.joinToString(",") { "?" }})",
            ids.map { it.toString() }.toTypedArray(),
            "${Phone.IS_SUPER_PRIMARY} DESC, ${Phone.IS_PRIMARY} DESC, ${Phone._ID} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (id !in wanted) continue
                rows.getOrPut(id) { mutableListOf() } += PhoneRow(c.getString(1), c.getString(2), if (c.isNull(3)) 0 else c.getInt(3))
            }
        }
        return rows
    }

    /**
     * The candidates matching [query], best first, at most [limit], each paired with
     * the name to report: its display name, or the matching nickname if it has none.
     */
    internal fun match(query: String, candidates: List<Candidate>, limit: Int): List<Pair<Candidate, String>> {
        val needle = foldForMatch(query)
        if (needle.isEmpty()) return emptyList()
        val collator = Collator.getInstance()
        return candidates.mapNotNull { candidate ->
            val names = listOfNotNull(candidate.displayName?.takeIf { it.isNotBlank() }) + candidate.nicknames
            val ranked = names.mapNotNull { n -> rank(needle, foldForMatch(n))?.let { it to n } }
            val best = ranked.minByOrNull { it.first } ?: return@mapNotNull null
            val fn = candidate.displayName?.takeIf { it.isNotBlank() } ?: best.second
            Triple(candidate, fn, best.first)
        }
            .sortedWith(compareBy<Triple<Candidate, String, Int>> { it.third }.thenBy(collator) { it.second }.thenBy { it.first.id })
            .take(limit)
            .map { it.first to it.second }
    }

    /** How well [name] matches [needle] (both folded): 0 exact, 1 prefix, 2 word prefix, 3 contains, null none. */
    internal fun rank(needle: String, name: String): Int? {
        if (name == needle) return 0
        if (name.startsWith(needle)) return 1
        var at = name.indexOf(needle)
        if (at < 0) return null
        while (at >= 0) {
            if (!name[at - 1].isLetterOrDigit()) return 2
            at = name.indexOf(needle, at + 1)
        }
        return 3
    }

    /** Waggle's phone type for an Android [Phone] type. */
    internal fun phoneType(type: Int): String = when (type) {
        Phone.TYPE_MOBILE, Phone.TYPE_WORK_MOBILE -> "cell"
        Phone.TYPE_HOME, Phone.TYPE_FAX_HOME -> "home"
        Phone.TYPE_WORK, Phone.TYPE_FAX_WORK, Phone.TYPE_WORK_PAGER, Phone.TYPE_COMPANY_MAIN -> "work"
        else -> "other"
    }

    /**
     * A contact's JSON. Numbers are given in E.164 when the provider has that form, or
     * else stripped of formatting, and each appears once (linked raw contacts often
     * repeat them); the first row's type wins.
     */
    internal fun contactJson(fn: String, phones: List<PhoneRow>): JsonObject = buildJsonObject {
        put("fn", fn)
        put("tel", buildJsonArray {
            val seen = mutableSetOf<String>()
            for (row in phones) {
                val value = phoneValue(row) ?: continue
                if (!seen.add(value)) continue
                add(buildJsonObject {
                    put("value", value)
                    put("type", phoneType(row.type))
                })
            }
        })
    }

    private fun phoneValue(row: PhoneRow): String? {
        row.normalized?.takeIf { it.isNotBlank() }?.let { return it }
        val number = row.number?.takeIf { it.isNotBlank() } ?: return null
        return PhoneNumberUtils.normalizeNumber(number).ifEmpty { number.trim() }
    }
}
