package com.mulesipstea.wiggins.ui

import kotlinx.serialization.Serializable

@Serializable
enum class Who { USER, HUB }

/** Whether a user's question reached the hub. */
@Serializable
enum class Delivery { SENT, PENDING, NOT_SENT }

/** One line of the conversation: only what was said (SPEC "Conversation screen"). */
@Serializable
data class TranscriptEntry(val id: Long, val who: Who, val text: String, val delivery: Delivery = Delivery.SENT)

data class LoggedMessage(val id: Long, val timeMillis: Long, val hiveType: String, val busType: String?, val json: String)

/** What's stopping Wiggins from working right now, shown as status rather than conversation. */
data class Problem(val message: String, val fix: Fix) {
    enum class Fix { SETTINGS, RETRY }
}
