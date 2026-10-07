package com.mulesipstea.wiggins.speech

/**
 * Decides where an utterance starts and ends from a voice detector's verdict on each
 * frame (SPEC "Hub speech-to-text"): speech starts after [minSpeechMs] of speech, and
 * ends after [endSilenceMs] of silence. A [pauseMs] silence on the way is reported, so
 * early transcription can start. Pure logic: feed it frames in order.
 */
class Endpointer(
    private val frameMs: Int = 20,
    private val minSpeechMs: Int = 200,
    private val pauseMs: Int = 300,
    private val endSilenceMs: Int = 800,
    private val noSpeechMs: Int = 8_000,
    private val maxUtteranceMs: Int = 30_000,
    /** Speech must last this long to end a silence, so a click doesn't. */
    private val resumeMs: Int = 60,
) {
    enum class Event {
        /** Speech has started: the utterance begins [LEAD_IN_MS] before [speechStartMs]. */
        STARTED,

        /** A pause long enough to transcribe the audio so far. */
        PAUSED,

        /** Speech resumed after [PAUSED]; that transcription is stale. */
        RESUMED,

        /** The utterance ended after a silence. */
        ENDED,

        /** The utterance reached its length cap. */
        CAPPED,

        /** No speech started in time; send nothing. */
        NO_SPEECH,
    }

    private var elapsedMs = 0
    private var speechMs = 0
    private var silenceMs = 0
    private var resumingMs = 0
    private var firstSpeechAt = -1

    var started = false
        private set
    private var paused = false
    private var done = false

    /** When the speech that started the utterance began, in ms from the first frame. */
    var speechStartMs = 0
        private set

    /** Any speech at all, even too short to start the utterance. */
    val heardSpeech get() = started || firstSpeechAt >= 0

    /** Feeds one frame; returns what it changed, if anything. Nothing after the utterance is over. */
    fun onFrame(speech: Boolean): Event? {
        if (done) return null
        elapsedMs += frameMs
        val event = if (started) during(speech) else before(speech)
        if (event == null && started && elapsedMs - (speechStartMs - LEAD_IN_MS).coerceAtLeast(0) >= maxUtteranceMs) {
            done = true
            return Event.CAPPED
        }
        if (event == Event.ENDED || event == Event.NO_SPEECH) done = true
        return event
    }

    private fun before(speech: Boolean): Event? {
        if (speech) {
            if (firstSpeechAt < 0) firstSpeechAt = elapsedMs - frameMs
            speechMs += frameMs
            silenceMs = 0
            if (speechMs >= minSpeechMs) {
                started = true
                speechStartMs = firstSpeechAt
                return Event.STARTED
            }
        } else {
            silenceMs += frameMs
            // Speech too short and too far apart to be one utterance: start counting again.
            if (silenceMs >= endSilenceMs) {
                speechMs = 0
                firstSpeechAt = -1
            }
        }
        return if (elapsedMs >= noSpeechMs) Event.NO_SPEECH else null
    }

    private fun during(speech: Boolean): Event? {
        if (speech) {
            resumingMs += frameMs
            if (resumingMs < resumeMs) {
                silenceMs += frameMs
            } else {
                silenceMs = 0
                if (paused) {
                    paused = false
                    return Event.RESUMED
                }
            }
        } else {
            resumingMs = 0
            silenceMs += frameMs
        }
        return when {
            silenceMs >= endSilenceMs -> Event.ENDED
            !paused && silenceMs >= pauseMs -> {
                paused = true
                Event.PAUSED
            }
            else -> null
        }
    }

    companion object {
        /** Audio kept from before speech was detected, for a soft first syllable. */
        const val LEAD_IN_MS = 300
    }
}
