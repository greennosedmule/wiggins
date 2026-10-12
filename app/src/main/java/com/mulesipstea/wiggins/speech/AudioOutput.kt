package com.mulesipstea.wiggins.speech

/**
 * Tells the hub when Wiggins reads a reply aloud, as a voice satellite's audio
 * service does: `recognizer_loop:audio_output_start` when speech starts, and
 * `recognizer_loop:audio_output_end` once it has all played or been stopped.
 *
 * A skill that speaks with `wait=True` blocks until the hub sees `audio_output_end`
 * for its session, or 15 s pass, and the turn only ends after that. So every reply
 * gets an end: one per stretch of speech, or, when nothing plays (replies muted, no
 * TTS engine), one straight away for each speak, without a start.
 *
 * Confined to one thread, like [HubSpeaker].
 */
class AudioOutput(
    /** Sends a data-less BUS message of this type with the current session. */
    private val send: (type: String) -> Unit,
) {
    /** A start was sent and its end hasn't been. */
    var isOpen = false
        private set

    /** A speak was handed to a voice; [playing] says whether anything is now playing. */
    fun onSpeak(playing: Boolean) {
        if (playing) {
            if (!isOpen) {
                isOpen = true
                send(START)
            }
        } else {
            // Nothing to wait for: a waiting skill can go on now.
            isOpen = false
            send(END)
        }
    }

    /** Every voice is quiet: the queue played to the end, or the user stopped it. */
    fun onIdle() {
        if (!isOpen) return
        isOpen = false
        send(END)
    }

    companion object {
        const val START = "recognizer_loop:audio_output_start"
        const val END = "recognizer_loop:audio_output_end"
    }
}
