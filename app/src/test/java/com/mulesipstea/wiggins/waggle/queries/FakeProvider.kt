package com.mulesipstea.wiggins.waggle.queries

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * A stand-in for the calendar or contacts provider: serves fixed rows per path and
 * records each query. It ignores selection and sort order, so the code under test must
 * not rely on them for correctness (tests check the selection was asked for).
 */
class FakeProvider : ContentProvider() {
    data class Request(val uri: Uri, val projection: List<String>?, val selection: String?, val args: List<String>?)

    /** Rows by path prefix (no leading slash); the longest matching prefix serves a query. */
    val tables = mutableMapOf<String, List<Map<String, Any?>>>()
    val requests = mutableListOf<Request>()

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        requests += Request(uri, projection?.toList(), selection, selectionArgs?.toList())
        val path = uri.path.orEmpty().trimStart('/')
        val rows = tables.keys.filter { path.startsWith(it) }.maxByOrNull { it.length }?.let { tables.getValue(it) }.orEmpty()
        val columns = projection ?: rows.flatMap { it.keys }.distinct().toTypedArray()
        return MatrixCursor(columns).apply { rows.forEach { row -> addRow(columns.map { row[it] }) } }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
