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
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * A conversation and the hub session it belongs to. With a [file] it's kept in
 * app-private storage so it survives Android killing the app (writes are debounced
 * and atomic), and an app restart continues the same hub session; without one it
 * lives only in memory, like the assistant panel's. Holds the most recent [limit]
 * entries.
 */
class ConversationStore(
    private val file: File?,
    private val scope: CoroutineScope,
    private val limit: Int = 200,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val initial = load()
    private val _entries = MutableStateFlow(initial.entries)
    val entries: StateFlow<List<TranscriptEntry>> = _entries.asStateFlow()

    /** The OVOS session this conversation is with; the hub's memory of it is keyed by this. */
    @Volatile var sessionId: String = initial.sessionId
        private set

    /** When the conversation last had a question or reply (epoch millis). */
    @Volatile var lastActivity: Long = initial.lastActivity
        private set

    private val ids = AtomicLong(_entries.value.maxOfOrNull { it.id } ?: 0)

    // Each change schedules a save of the latest state; a newer change replaces a save
    // that hasn't started, and the lock keeps writes in order. No flow subscription,
    // so no change can slip past before a collector starts.
    private val saveLock = Mutex()
    private var pendingSave: Job? = null

    fun add(who: Who, text: String, delivery: Delivery = Delivery.SENT, streaming: Boolean = false): Long {
        val id = ids.incrementAndGet()
        _entries.update { (it + TranscriptEntry(id, who, text, delivery, streaming)).takeLast(limit) }
        changed()
        return id
    }

    fun get(id: Long): TranscriptEntry? = _entries.value.firstOrNull { it.id == id }

    fun setDelivery(id: Long, delivery: Delivery) {
        _entries.update { list -> list.map { if (it.id == id) it.copy(delivery = delivery) else it } }
        changed()
    }

    /** Adds the next sentence of a streaming reply. */
    fun append(id: Long, text: String) {
        _entries.update { list -> list.map { if (it.id == id) it.copy(text = it.text + " " + text) else it } }
        changed()
    }

    /** The reply is complete. */
    fun finish(id: Long) {
        _entries.update { list -> list.map { if (it.id == id) it.copy(streaming = false) else it } }
        changed()
    }

    /** A question or reply passed: the session is in use. */
    fun touch() {
        lastActivity = clock()
        changed()
    }

    /** Whether nothing has happened in the conversation for longer than [idleMillis]. */
    fun idleFor(idleMillis: Long) = clock() - lastActivity > idleMillis

    /** Ends the conversation: no entries, and a new hub session that knows nothing of the old one. */
    fun newSession() {
        _entries.update { emptyList() }
        sessionId = newSessionId()
        lastActivity = clock()
        changed()
    }

    private fun changed() {
        val target = file ?: return
        synchronized(this) {
            pendingSave?.cancel()
            pendingSave = scope.launch(Dispatchers.IO) {
                delay(SAVE_DEBOUNCE_MS)
                saveLock.withLock { save(target, Saved(sessionId, lastActivity, _entries.value)) }
            }
        }
    }

    private fun load(): Saved {
        val fresh = Saved(newSessionId(), clock(), emptyList())
        val source = file?.takeIf { it.exists() } ?: return fresh
        val text = runCatching { source.readText() }.getOrElse { return fresh }
        val saved = runCatching { json.decodeFromString(Saved.serializer(), text) }.getOrNull()
            // The first format was a bare list of entries, with no session.
            ?: runCatching { Saved(newSessionId(), source.lastModified(), json.decodeFromString(listSerializer, text)) }.getOrNull()
            ?: return fresh
        // A question still pending when the app died never reached the hub, and no
        // more of a reply that was still arriving will come.
        return saved.copy(
            entries = saved.entries.map {
                it.copy(delivery = if (it.delivery == Delivery.PENDING) Delivery.NOT_SENT else it.delivery, streaming = false)
            },
        )
    }

    private fun save(file: File, saved: Saved) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(Saved.serializer(), saved))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    @Serializable
    private data class Saved(val sessionId: String, val lastActivity: Long, val entries: List<TranscriptEntry>)

    private companion object {
        const val SAVE_DEBOUNCE_MS = 300L
        val json = Json { ignoreUnknownKeys = true }
        val listSerializer = ListSerializer(TranscriptEntry.serializer())

        fun newSessionId() = UUID.randomUUID().toString()
    }
}
