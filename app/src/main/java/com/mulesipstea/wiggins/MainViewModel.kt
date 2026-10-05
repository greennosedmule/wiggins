package com.mulesipstea.wiggins

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel

/**
 * A screen's view of [Assistant], which the application owns. Only UI concerns
 * live here: handling assist invocations.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val assistant: Assistant = (app as WigginsApp).assistant

    private var lastAssistMillis = 0L

    /**
     * Wiggins was opened by the assist gesture: listen straight away when a recognizer
     * exists, unless it was invoked from a keyboard, where typing is the natural answer.
     */
    fun onAssistInvoked(fromKeyboard: Boolean) {
        // Android can deliver the assist intent twice in a row (seen by Dicio).
        val now = SystemClock.elapsedRealtime()
        if (now - lastAssistMillis < ASSIST_DEBOUNCE_MS) return
        lastAssistMillis = now
        assistant.refreshPrerequisites()
        if (!fromKeyboard && assistant.prerequisites.value.recognizer != null) assistant.listen()
    }

    private companion object {
        const val ASSIST_DEBOUNCE_MS = 500L
    }
}
