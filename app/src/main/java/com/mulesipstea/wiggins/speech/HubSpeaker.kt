package com.mulesipstea.wiggins.speech

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.BreakIterator
import java.util.Locale

/**
 * Reads replies aloud with the hub's TTS (SPEC "Hub text-to-speech"). A reply is split
 * into sentences, which play in order; each one's audio is requested when it reaches
 * the front of the queue or is next after it, so the next sentence is ready when the
 * current one ends. A sentence whose audio doesn't come in time (see [timeoutFor])
 * goes to [fallback] (the device's engine, if any).
 *
 * Confined to [scope]'s thread.
 */
class HubSpeaker(
    private val scope: CoroutineScope,
    /** Asks the hub for a sentence's audio; false if it couldn't be sent. */
    private val request: (id: String, text: String) -> Boolean,
    /** Plays a base64 WAV file, returning when it has finished. */
    private val play: suspend (wavBase64: String) -> Unit,
    /** Speaks a sentence another way, returning when it has finished. */
    private val fallback: suspend (text: String) -> Unit,
    /** Whether the hub answered a request in time; for the status banner. */
    private val onHubAnswered: (Boolean) -> Unit,
    private val newId: () -> String,
    private val locale: () -> Locale = Locale::getDefault,
) {
    private class Sentence(val id: String, val text: String) {
        var requested = false
        var timeout: Job? = null

        /** The audio, or null if it isn't coming. */
        val audio = CompletableDeferred<String?>()
    }

    private val queue = ArrayDeque<Sentence>()
    private var runner: Job? = null

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()
    val isSpeaking get() = _speaking.value

    /** Called when the queue has been spoken to the end; not after [stop]. */
    var onIdle: (() -> Unit)? = null

    fun speak(text: String) {
        // One speak can be a whole paragraph, which the hub's engine takes many seconds
        // to synthesize; a sentence at a time starts sooner and keeps each request short.
        sentences(text, locale()).forEach { queue.addLast(Sentence(newId(), it)) }
        if (queue.isEmpty()) return
        _speaking.value = true
        requestAhead()
        if (runner?.isActive != true) runner = scope.launch { run() }
    }

    /** The hub's audio for request [id]. */
    fun onAudio(id: String, wavBase64: String) {
        val sentence = queue.firstOrNull { it.id == id } ?: return
        sentence.timeout?.cancel()
        onHubAnswered(true)
        sentence.audio.complete(wavBase64)
    }

    /** Stops speaking and forgets the queue, without [onIdle]: the user interrupted. */
    fun stop() {
        runner?.cancel()
        runner = null
        queue.forEach { it.timeout?.cancel() }
        queue.clear()
        _speaking.value = false
    }

    private suspend fun run() {
        while (true) {
            val sentence = queue.firstOrNull() ?: break
            val audio = sentence.audio.await()
            if (audio != null) {
                try {
                    play(audio)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "can't play the hub's audio", e)
                    fallback(sentence.text)
                }
            } else {
                fallback(sentence.text)
            }
            queue.removeFirstOrNull()
            requestAhead()
        }
        runner = null
        _speaking.value = false
        onIdle?.invoke()
    }

    private fun requestAhead() {
        queue.take(AHEAD).filterNot { it.requested }.forEach { sentence ->
            sentence.requested = true
            if (!request(sentence.id, sentence.text)) {
                sentence.audio.complete(null)
                return@forEach
            }
            sentence.timeout = scope.launch {
                delay(timeoutFor(sentence.text))
                onHubAnswered(false)
                sentence.audio.complete(null)
            }
        }
    }

    companion object {
        /** The sentence playing and the next one. */
        private const val AHEAD = 2
        private const val TAG = "HubSpeaker"
        private const val TIMEOUT_MS = 10_000L

        /** Allowance per character past the first 100, for a long sentence: a CPU engine takes ~25 ms each. */
        private const val TIMEOUT_PER_CHAR_MS = 40L

        /** How long to wait for a sentence's audio: 10 s (SPEC), longer for a long sentence. */
        fun timeoutFor(text: String) = TIMEOUT_MS + TIMEOUT_PER_CHAR_MS * (text.length - 100).coerceAtLeast(0)

        /** [text] split into sentences, trimmed, with empty ones dropped. */
        fun sentences(text: String, locale: Locale): List<String> {
            val breaks = BreakIterator.getSentenceInstance(locale).apply { setText(text) }
            val out = mutableListOf<String>()
            var start = breaks.first()
            var end = breaks.next()
            while (end != BreakIterator.DONE) {
                text.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let(out::add)
                start = end
                end = breaks.next()
            }
            return out
        }
    }
}
