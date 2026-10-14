package com.mulesipstea.wiggins.speech

/** How far a tagged sentence has got, for the reply details' timeline (SPEC "Reply details"). */
enum class SpeechProgress {
    /** Its audio is ready: the hub's TTS answered, or the fallback took over. */
    AUDIO,

    /** It started playing. */
    START,

    /** It finished playing, or was stopped. */
    END,
}
