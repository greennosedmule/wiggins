package com.mulesipstea.wiggins.turns

import kotlinx.serialization.Serializable

/**
 * What Wiggins knows about one turn, for the reply details sheet (SPEC "Reply details"):
 * what answered and how, and when each part happened. Kept with the conversation.
 *
 * Phone times are milliseconds after [startedAt], by the phone's clock. The hub's own
 * timings, in [hub], are by its clock and placed on the phone's by [HubReport.anchorMs].
 * A null time means it didn't happen or wasn't reported.
 */
@Serializable
data class TurnDetails(
    /** The turn's zero, epoch millis: when listening started, or the question was asked. */
    val startedAt: Long,
    val origin: Origin,
    /** OVOS's id for the utterance, which every message of the turn carries. */
    val utteranceId: String? = null,
    /** Listening: the recording, or the user's recognizer while it was open. */
    val listen: Span? = null,
    /** Hub speech-to-text: from the end of the recording to the transcription. */
    val transcribe: Span? = null,
    /** The question waited for the connection before it could be sent. */
    val connect: Span? = null,
    val sentMs: Long? = null,
    /** When `ovos.intent.matched` arrived. */
    val matchedMs: Long? = null,
    /** When `ovos.utterance.handled` arrived: the hub finished the turn. */
    val handledMs: Long? = null,
    val match: Match? = null,
    val sentences: List<Sentence> = emptyList(),
    val actions: List<PhoneAction> = emptyList(),
    /** The hub asked a question and wants an answer (`ovos.mic.listen` or `expect_response`). */
    val followUp: Boolean = false,
    val hub: HubReport? = null,
) {
    enum class Origin {
        /** Typed in the text box. */
        TYPED,

        /** Spoken, transcribed by the hub. */
        HUB_STT,

        /** Spoken, transcribed by the user's recognizer app. */
        DEVICE_STT,

        /** The hub spoke by itself, outside any question. */
        HUB,
    }

    /** The stage, skill and intent that answered (`ovos.intent.matched`). */
    @Serializable
    data class Match(
        val skillId: String? = null,
        val intentName: String? = null,
        val pipelineId: String? = null,
        val slots: Map<String, String> = emptyMap(),
    )

    /** One speak message: when it arrived, and when its audio was ready, started and ended. */
    @Serializable
    data class Sentence(
        /** The first words, to tell sentences apart. */
        val text: String,
        val skill: String? = null,
        val dialog: String? = null,
        /** The dialog's filled-in values. */
        val data: Map<String, String> = emptyMap(),
        val arrivedMs: Long,
        val audioMs: Long? = null,
        val playStartMs: Long? = null,
        val playEndMs: Long? = null,
        /** Whether it was read aloud at all (spoken replies on). */
        val spoken: Boolean = true,
    )

    /** A Waggle request the phone handled (WAGGLE.md), with the time an ask card waited for the user. */
    @Serializable
    data class PhoneAction(
        val id: String,
        /** `intent` or `query`. */
        val kind: String,
        /** The intent's action, or the query's name. */
        val name: String,
        val startMs: Long,
        val endMs: Long? = null,
        val askStartMs: Long? = null,
        val askEndMs: Long? = null,
        /** `ok`, or the error code. */
        val outcome: String? = null,
    )

    /**
     * The persona's own report of the turn (`persona.turn.debug`): its model requests and
     * the tools it called, with whatever the hub chose to include. Their times are milliseconds after the hub's zero, which is at
     * [anchorMs] on the phone's timeline: approximate, to within the network delay.
     */
    @Serializable
    data class HubReport(
        val anchorMs: Long,
        /** The persona's name, if the hub gives it. */
        val persona: String? = null,
        val model: String? = null,
        val effort: String? = null,
        val requests: List<ModelRequest> = emptyList(),
        val tools: List<ToolCall> = emptyList(),
        val truncated: Boolean = false,
        /** The hub's estimate of the turn's cost, in [currency]; null if it didn't give one. */
        val cost: Double? = null,
        val currency: String? = null,
        /** What the estimate assumes, in the hub's words ("list price"). */
        val pricing: String? = null,
    )

    @Serializable
    data class ModelRequest(
        val startMs: Long,
        /** When its first text arrived. */
        val firstMs: Long? = null,
        val endMs: Long? = null,
        val stop: String? = null,
        val inputTokens: Long? = null,
        val cacheReadTokens: Long? = null,
        val cacheWriteTokens: Long? = null,
        val outputTokens: Long? = null,
        val thinkingTokens: Long? = null,
        /** The hub's estimate of this request's cost, in the report's currency. */
        val cost: Double? = null,
    )

    @Serializable
    data class ToolCall(
        val server: String? = null,
        val name: String,
        val startMs: Long,
        val endMs: Long? = null,
        val ok: Boolean? = null,
    )
}

/** A stretch of time on a turn's timeline; [endMs] is null if it never ended. */
@Serializable
data class Span(val startMs: Long, val endMs: Long? = null)
