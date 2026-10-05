package com.mulesipstea.wiggins.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

/**
 * Speaks hub replies through the system TTS engine. Speech queues; nothing is
 * dropped. [onIdle] fires when the last queued utterance finishes, which is
 * when a follow-up question can start listening.
 */
class Speaker(context: Context) {
    private enum class Engine { STARTING, READY, UNAVAILABLE }

    private val lock = Any()
    private var engine = Engine.STARTING
    private val pending = mutableListOf<String>()
    private var active = 0

    /** Called when queued speech has finished, or at once if there's no engine. Any thread. */
    var onIdle: (() -> Unit)? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        synchronized(lock) {
            engine = if (status == TextToSpeech.SUCCESS) Engine.READY else Engine.UNAVAILABLE
            if (engine == Engine.UNAVAILABLE) Log.w(TAG, "TTS engine unavailable (status $status)")
            val queued = pending.toList()
            pending.clear()
            queued.forEach(::enqueue)
        }
        if (!isSpeaking) onIdle?.invoke()
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = finished()
            override fun onStop(utteranceId: String, interrupted: Boolean) = finished()
            override fun onError(utteranceId: String, errorCode: Int) = finished()

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = finished()
        })
    }

    val isAvailable get() = synchronized(lock) { engine != Engine.UNAVAILABLE }

    val isSpeaking get() = synchronized(lock) { active > 0 || pending.isNotEmpty() }

    fun speak(text: String) {
        val idleNow = synchronized(lock) {
            when (engine) {
                Engine.READY -> enqueue(text)
                Engine.STARTING -> pending += text
                Engine.UNAVAILABLE -> Unit
            }
            engine == Engine.UNAVAILABLE
        }
        if (idleNow) onIdle?.invoke()
    }

    /** Stops speech without firing [onIdle]: the user interrupted. */
    fun stop() {
        synchronized(lock) {
            pending.clear()
            active = 0
        }
        tts.stop()
    }

    fun shutdown() = tts.shutdown()

    private fun enqueue(text: String) {
        if (engine != Engine.READY) return
        tts.language = Locale.getDefault()
        active++
        if (tts.speak(text, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString()) != TextToSpeech.SUCCESS) active--
    }

    private fun finished() {
        val nowIdle = synchronized(lock) {
            if (active == 0) return
            active--
            active == 0 && pending.isEmpty()
        }
        if (nowIdle) onIdle?.invoke()
    }

    private companion object {
        const val TAG = "Speaker"
    }
}
