package com.mulesipstea.wiggins.speech

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Gets one utterance transcribed by the hub (SPEC "Hub speech-to-text"). With [early]
 * on, the audio so far is sent at each pause, and if the pause turns out to be the end,
 * that request's answer is the result; speech resuming makes it stale. Otherwise the
 * audio is sent once the utterance has ended. Each request has its own id, and answers
 * to requests no longer wanted are ignored.
 *
 * Confined to [scope]'s thread, like its caller.
 */
class Transcription(
    private val scope: CoroutineScope,
    private val early: Boolean,
    /** Sends audio to the hub under an id; the caller reports a failed send with [onSendFailed]. */
    private val send: (id: String, wav: ByteArray) -> Unit,
    private val onResult: (Result) -> Unit,
    private val newId: () -> String,
    private val timeoutMs: Long = 15_000,
) {
    sealed interface Result {
        data class Heard(val text: String) : Result

        /** The hub answered with no words. */
        data object NothingHeard : Result

        /** No answer in time. */
        data object NoAnswer : Result

        /** The audio couldn't be sent (no connection). */
        data object NotSent : Result
    }

    /** The request sent at the current pause, while the utterance may still go on. */
    private var earlyId: String? = null
    private var earlyAnswer: String? = null
    private var earlyAnswered = false

    /** The request whose answer is the result, once the utterance has ended. */
    private var awaiting: String? = null
    private var timeout: Job? = null
    var finished = false
        private set

    /** Speech paused: with early transcription on, send what we have so far. Replaces any earlier pause's request. */
    fun onPause(wav: () -> ByteArray) {
        if (!early || finished || awaiting != null) return
        val id = newId()
        earlyId = id
        earlyAnswer = null
        earlyAnswered = false
        send(id, wav())
    }

    /** Speech resumed after a pause: that pause's request is stale. */
    fun onResume() {
        earlyId = null
    }

    /** The utterance ended: the pending early request's answer is the result, else [wav] is sent now. */
    fun onEnd(wav: () -> ByteArray) {
        if (finished || awaiting != null) return
        val pending = earlyId
        if (pending != null) {
            awaiting = pending
            if (earlyAnswered) return finish(earlyAnswer)
        } else {
            val id = newId()
            awaiting = id
            send(id, wav())
        }
        if (finished) return
        timeout = scope.launch {
            delay(timeoutMs)
            complete(Result.NoAnswer)
        }
    }

    /** The hub answered request [id]; [text] is null or blank if it heard nothing. */
    fun onAnswer(id: String, text: String?) {
        if (finished) return
        when (id) {
            awaiting -> finish(text)
            earlyId -> {
                earlyAnswer = text
                earlyAnswered = true
            }
        }
    }

    /** Request [id] never left the phone. */
    fun onSendFailed(id: String) {
        if (id == awaiting) complete(Result.NotSent)
        if (id == earlyId) earlyId = null // Try again at the end.
    }

    /** The utterance was discarded: ignore any answer. */
    fun cancel() {
        finished = true
        timeout?.cancel()
    }

    private fun finish(text: String?) {
        val words = text?.trim().orEmpty()
        complete(if (words.isEmpty()) Result.NothingHeard else Result.Heard(words))
    }

    private fun complete(result: Result) {
        if (finished) return
        finished = true
        timeout?.cancel()
        onResult(result)
    }
}
