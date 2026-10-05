package com.mulesipstea.wiggins

import com.mulesipstea.wiggins.ui.Delivery
import com.mulesipstea.wiggins.ui.TranscriptEntry
import com.mulesipstea.wiggins.ui.Who
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong

/**
 * A conversation. With a [file] it's kept in app-private storage so it survives
 * Android killing the app (writes are debounced and atomic); without one it lives
 * only in memory, like the assistant panel's. Holds the most recent [limit] entries.
 */
class ConversationStore(private val file: File?, private val scope: CoroutineScope, private val limit: Int = 200) {
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<TranscriptEntry>> = _entries.asStateFlow()

    private val ids = AtomicLong(_entries.value.maxOfOrNull { it.id } ?: 0)

    // Each change schedules a save of the latest list; a newer change replaces a save
    // that hasn't started, and the lock keeps writes in order. No flow subscription,
    // so no change can slip past before a collector starts.
    private val saveLock = Mutex()
    private var pendingSave: Job? = null

    fun add(who: Who, text: String, delivery: Delivery = Delivery.SENT): Long {
        val id = ids.incrementAndGet()
        _entries.update { (it + TranscriptEntry(id, who, text, delivery)).takeLast(limit) }
        changed()
        return id
    }

    fun get(id: Long): TranscriptEntry? = _entries.value.firstOrNull { it.id == id }

    fun setDelivery(id: Long, delivery: Delivery) {
        _entries.update { list -> list.map { if (it.id == id) it.copy(delivery = delivery) else it } }
        changed()
    }

    fun clear() {
        _entries.update { emptyList() }
        changed()
    }

    private fun changed() {
        val target = file ?: return
        synchronized(this) {
            pendingSave?.cancel()
            pendingSave = scope.launch(Dispatchers.IO) {
                delay(SAVE_DEBOUNCE_MS)
                saveLock.withLock { save(target, _entries.value) }
            }
        }
    }

    private fun load(): List<TranscriptEntry> = runCatching {
        if (file == null || !file.exists()) return emptyList()
        // A question still pending when the app died never reached the hub.
        json.decodeFromString(serializer, file.readText())
            .map { if (it.delivery == Delivery.PENDING) it.copy(delivery = Delivery.NOT_SENT) else it }
    }.getOrDefault(emptyList())

    private fun save(file: File, entries: List<TranscriptEntry>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, entries))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private companion object {
        const val SAVE_DEBOUNCE_MS = 300L
        val json = Json { ignoreUnknownKeys = true }
        val serializer = ListSerializer(TranscriptEntry.serializer())
    }
}
