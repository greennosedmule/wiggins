package com.mulesipstea.wiggins.speech

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.Locale
import java.util.UUID

/**
 * Speaks hub replies through the system TTS engine. Speech queues; nothing is
 * dropped. [onIdle] fires when the last queued utterance finishes, which is
 * when a follow-up question can start listening.
 */
class Speaker(
    context: Context,
    /** The attributes to speak with now; they change while the Bluetooth hands-free link is up. */
    private val attributes: () -> AudioAttributes = { DEFAULT_ATTRIBUTES },
) {
    private enum class Engine { STARTING, READY, UNAVAILABLE }

    private val lock = Any()
    private var engine = Engine.STARTING
    private val pending = mutableListOf<Pair<String, String?>>()
    private var active = 0

    private val _speaking = MutableStateFlow(false)

    /** True while queued speech is playing or waiting for the engine. */
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private fun updateSpeaking() {
        _speaking.value = active > 0 || pending.isNotEmpty()
    }

    /** Called when queued speech has finished, or at once if there's no engine. Any thread. */
    var onIdle: (() -> Unit)? = null

    /** Progress of speech given a tag, by tag. Any thread. */
    var onProgress: ((tag: String, SpeechProgress) -> Unit)? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        synchronized(lock) {
            engine = if (status == TextToSpeech.SUCCESS) Engine.READY else Engine.UNAVAILABLE
            if (engine == Engine.UNAVAILABLE) Log.w(TAG, "TTS engine unavailable (status $status)")
            val queued = pending.toList()
            pending.clear()
            queued.forEach { (text, tag) -> enqueue(text, tag) }
            updateSpeaking()
        }
        if (!isSpeaking) onIdle?.invoke()
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                onProgress?.invoke(utteranceId, SpeechProgress.START)
            }

            override fun onDone(utteranceId: String) = finished(utteranceId)
            override fun onStop(utteranceId: String, interrupted: Boolean) = finished(utteranceId)
            override fun onError(utteranceId: String, errorCode: Int) = finished(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = finished(utteranceId)
        })
    }

    val isAvailable get() = synchronized(lock) { engine != Engine.UNAVAILABLE }

    val isSpeaking get() = synchronized(lock) { active > 0 || pending.isNotEmpty() }

    /** Queues [text]; a [tag] has its progress reported to [onProgress]. */
    fun speak(text: String, tag: String? = null) {
        val idleNow = synchronized(lock) {
            when (engine) {
                Engine.READY -> enqueue(text, tag)
                Engine.STARTING -> pending += text to tag
                Engine.UNAVAILABLE -> Unit
            }
            updateSpeaking()
            // No engine, or it refused the text with nothing else queued: no onDone is coming.
            engine == Engine.UNAVAILABLE || (engine == Engine.READY && active == 0 && pending.isEmpty())
        }
        if (idleNow) onIdle?.invoke()
    }

    /** Speaks [text] and returns once it (and anything queued before it) has been spoken. */
    suspend fun speakAndWait(text: String) {
        if (!isAvailable) return
        speak(text)
        speaking.first { !it }
    }

    /** Stops speech without firing [onIdle]: the user interrupted. */
    fun stop() {
        synchronized(lock) {
            pending.clear()
            active = 0
            updateSpeaking()
        }
        tts.stop()
    }

    fun shutdown() = tts.shutdown()

    private var applied: AudioAttributes? = null

    private fun enqueue(text: String, tag: String?) {
        if (engine != Engine.READY) return
        tts.language = Locale.getDefault()
        val wanted = attributes()
        if (wanted != applied) {
            tts.setAudioAttributes(wanted)
            applied = wanted
        }
        active++
        if (tts.speak(text, TextToSpeech.QUEUE_ADD, null, tag ?: UUID.randomUUID().toString()) != TextToSpeech.SUCCESS) active--
    }

    private fun finished(utteranceId: String) {
        onProgress?.invoke(utteranceId, SpeechProgress.END)
        val nowIdle = synchronized(lock) {
            if (active == 0) return
            active--
            updateSpeaking()
            active == 0 && pending.isEmpty()
        }
        if (nowIdle) onIdle?.invoke()
    }

    companion object {
        /** What the engine uses unless told otherwise: media usage, for speech. */
        val DEFAULT_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        private const val TAG = "Speaker"
    }
}
