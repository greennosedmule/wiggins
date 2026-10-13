package com.mulesipstea.wiggins.speech

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Decides when the hands-free link closes (SPEC "In the car"). It stays up while anything
 * in the turn is going on (listening, waiting for the hub, speaking) and for [graceMs]
 * after, because a follow-up question starts listening again just after the speech that
 * asked it ends, and closing in between would make the car drop the session. Main thread.
 */
class LinkKeeper(
    private val scope: CoroutineScope,
    private val graceMs: Long,
    private val close: () -> Unit,
) {
    private var pending: Job? = null
    private var open = false
    private var lastBusy = false

    /** The link has just opened; unless the turn is busy, it closes after [graceMs] if it doesn't get busy. */
    fun opened() {
        open = true
        busy(lastBusy)
    }

    /** Whether anything in the turn is going on now. */
    fun busy(busy: Boolean) {
        lastBusy = busy
        if (!open) return
        pending?.cancel()
        if (!busy) pending = scope.launch {
            delay(graceMs)
            closeNow()
        }
    }

    /** Closes at once, e.g. when the last Wiggins screen goes away. */
    fun closeNow() {
        pending?.cancel()
        pending = null
        if (!open) return
        open = false
        close()
    }
}
