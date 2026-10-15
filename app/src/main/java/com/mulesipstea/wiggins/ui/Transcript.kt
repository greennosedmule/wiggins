package com.mulesipstea.wiggins.ui

import com.mulesipstea.wiggins.turns.TurnDetails
import kotlinx.serialization.Serializable

@Serializable
enum class Who { USER, HUB }

/**
 * Whether a user's question reached the hub. TRANSCRIBING: spoken, and the hub is still
 * transcribing it. UNANSWERED: it reached the hub, but the connection went before any of
 * the answer came back, so the answer is lost.
 */
@Serializable
enum class Delivery { SENT, PENDING, NOT_SENT, TRANSCRIBING, UNANSWERED }

/**
 * One line of the conversation: what was said (SPEC "Conversation screen"), and for a
 * question, what's known about how it was answered.
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
    /** The entry whose [details] cover this one: a reply points at its question. */
    val turn: Long? = null,
    /** What's known about the turn this entry started (SPEC "Reply details"). */
    val details: TurnDetails? = null,
)

/** The details covering entry [id] in this list: its own, or those of the question it answers. */
fun List<TranscriptEntry>.detailsFor(id: Long): TurnDetails? {
    val entry = firstOrNull { it.id == id } ?: return null
    return entry.details ?: entry.turn?.let { owner -> firstOrNull { it.id == owner }?.details }
}

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
