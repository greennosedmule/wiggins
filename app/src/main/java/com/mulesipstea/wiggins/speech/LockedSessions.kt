package com.mulesipstea.wiggins.speech

import com.mulesipstea.wiggins.settings.WhenLocked

/**
 * Decides whether a voice session may run on a locked phone (SPEC "Locked, in the car").
 *
 * With [WhenLocked.TRUSTED_CAR], a trusted hands-free device is armed when the phone is
 * unlocked while it's connected, and disarmed when it disconnects: unlock once per drive.
 * The armed set lives only in memory, so if Android stops Wiggins in between, the next
 * locked press asks for the unlock again. That errs towards asking, never towards
 * allowing. Main thread.
 */
class LockedSessions {
    private val armed = mutableSetOf<String>()

    /** The phone was unlocked while [connected] hands-free devices were connected. */
    fun onUnlocked(connected: Collection<String>) {
        armed += connected
    }

    /** The hands-free device [address] disconnected. */
    fun onDisconnected(address: String) {
        armed -= address
    }

    /**
     * Whether a session through the connected hands-free device [device] (null if none)
     * may run without the unlock, given the user's [mode] and [trusted] devices.
     */
    fun allows(mode: WhenLocked, trusted: Set<String>, device: String?): Boolean = when {
        device == null -> false
        mode == WhenLocked.ALWAYS -> true
        mode == WhenLocked.TRUSTED_CAR -> device in trusted && device in armed
        else -> false
    }
}
