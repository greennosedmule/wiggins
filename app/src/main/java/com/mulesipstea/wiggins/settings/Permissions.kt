package com.mulesipstea.wiggins.settings

/**
 * Where the phone is when it talks to the hub (SPEC "Permissions"). The phone decides,
 * and tells the hub on every uplink as `phone_context`; what's allowed in each is the
 * user's choice, within what the hub allows.
 */
enum class PhoneContext(val wire: String, val label: String) {
    /** The keyguard isn't locked. */
    UNLOCKED("unlocked", "Unlocked"),

    /**
     * Locked, through a trusted car's or headset's voice button, with the audio on its link,
     * and no untrusted hands-free device connected.
     */
    TRUSTED_DEVICE("trusted_device", "Trusted device"),

    /** Locked otherwise: a talk-only headset now, the nightstand later. */
    LOCKED("locked", "Locked"),
    ;

    /** How much of the conversation the hub's persona sees here: everything, or only since the lock. */
    val history get() = if (this == LOCKED) "since_lock" else "full"
}

/** What another (untrusted) headset's voice button does on a locked phone. */
enum class OtherHeadsets {
    /** Ask for the unlock first, as the assist gesture does. */
    ASK_UNLOCK,

    /** Talk to the hub with the [PhoneContext.LOCKED] permissions. */
    TALK_ONLY,
}

/** The locked-phone settings from before 0.7.0's contexts, read once and carried over. */
internal object LegacyWhenLocked {
    /**
     * The new settings for the old `hands_free_when_locked` value [legacy] and its ticked
     * devices. Nothing becomes trusted that wasn't in use: devices ticked under "Ask to
     * unlock" or "Always" are dropped, and a trusted car keeps its unlock once per drive.
     */
    fun migrate(legacy: String?, trusted: Set<String>): Triple<Set<String>, Boolean, OtherHeadsets> = when (legacy) {
        "TRUSTED_CAR" -> Triple(trusted, true, OtherHeadsets.ASK_UNLOCK)
        "ALWAYS" -> Triple(emptySet(), false, OtherHeadsets.TALK_ONLY)
        else -> Triple(emptySet(), false, OtherHeadsets.ASK_UNLOCK)
    }
}
