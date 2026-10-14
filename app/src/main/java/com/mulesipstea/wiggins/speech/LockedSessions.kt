package com.mulesipstea.wiggins.speech

import com.mulesipstea.wiggins.settings.OtherHeadsets
import com.mulesipstea.wiggins.settings.PhoneContext

/**
 * Decides whether a voice session may run on a locked phone, and as what (SPEC
 * "Permissions"). Everything here fails closed: anything it can't be sure of asks for the
 * unlock, or runs as [PhoneContext.LOCKED].
 *
 * A [PhoneContext.TRUSTED_DEVICE] session needs a hands-free device's voice button, not
 * the assist gesture, and every connected hands-free device trusted: Android doesn't say
 * which device pressed the button, so one untrusted device among them makes it anyone's.
 * With "unlock once per drive", each of them must also have been connected when the phone
 * was last unlocked; that lasts until it disconnects. The armed set lives only in memory,
 * so if Android stops Wiggins in between, the next locked press asks again.
 *
 * While a trusted session runs, an untrusted device connecting turns it into a locked one.
 * Main thread.
 */
class LockedSessions {
    private val armed = mutableSetOf<String>()

    /** The context of the locked session running now, or null. */
    var current: PhoneContext? = null
        private set

    /** The devices trusted when the session started. */
    var trusted: Set<String> = emptySet()
        private set

    /** The phone was unlocked while [connected] hands-free devices were connected. */
    fun onUnlocked(connected: Collection<String>) {
        armed += connected
    }

    /** The hands-free device [address] disconnected. */
    fun onDisconnected(address: String) {
        armed -= address
    }

    /** The hands-free device [address] connected while the phone was locked. */
    fun onConnectedLocked(address: String) {
        if (current == PhoneContext.TRUSTED_DEVICE && address !in trusted) current = PhoneContext.LOCKED
    }

    /**
     * What a locked invocation may run as, or null to ask for the unlock. [voiceButton]: it
     * came from a hands-free device's button (ACTION_VOICE_COMMAND), not the assist gesture.
     * [connected]: the hands-free devices connected now.
     */
    fun contextFor(
        voiceButton: Boolean,
        connected: Collection<String>,
        trusted: Set<String>,
        unlockPerDrive: Boolean,
        otherHeadsets: OtherHeadsets,
    ): PhoneContext? = when {
        !voiceButton || connected.isEmpty() -> null
        connected.all { it in trusted } && (!unlockPerDrive || connected.all { it in armed }) -> PhoneContext.TRUSTED_DEVICE
        otherHeadsets == OtherHeadsets.TALK_ONLY -> PhoneContext.LOCKED
        else -> null
    }

    /** A locked session starts as [context], with [trusted] the devices trusted now. */
    fun start(context: PhoneContext, trusted: Set<String>) {
        current = context
        this.trusted = trusted
    }

    fun end() {
        current = null
    }
}
