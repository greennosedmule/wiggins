package com.mulesipstea.wiggins.ui

import kotlinx.serialization.Serializable

@Serializable
enum class Who { USER, HUB }

/** Whether a user's question reached the hub. TRANSCRIBING: spoken, and the hub is still transcribing it. */
@Serializable
enum class Delivery { SENT, PENDING, NOT_SENT, TRANSCRIBING }

/**
 * One line of the conversation: only what was said (SPEC "Conversation screen").
 * A hub reply is [streaming] while more of it may still arrive: OVOS can speak an
 * answer a sentence at a time, and the sentences join into one entry.
 */
@Serializable
data class TranscriptEntry(
    val id: Long,
    val who: Who,
    val text: String,
    val delivery: Delivery = Delivery.SENT,
    val streaming: Boolean = false,
)

data class LoggedMessage(val id: Long, val timeMillis: Long, val hiveType: String, val busType: String?, val json: String)

/** What's stopping Wiggins from working right now, shown as status rather than conversation. */
data class Problem(val message: String, val fix: Fix) {
    enum class Fix {
        SETTINGS,
        RETRY,

        /** Switch speech-to-text and text-to-speech to the device. */
        DEVICE_SPEECH,

        /** Allow the microphone in the app's system settings. */
        MIC_PERMISSION,
    }
}
