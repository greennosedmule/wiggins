package com.mulesipstea.wiggins.turns

import com.mulesipstea.wiggins.speech.SpeechProgress
import com.mulesipstea.wiggins.turns.TurnDetails.Origin
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Collects [TurnDetails] as a turn goes by (SPEC "Reply details"): the listening and
 * transcription before the question is sent, then the hub's messages, the phone actions
 * and each sentence's speech until the last one has played. Each change goes to the
 * turn's [Sink], which keeps it with the conversation.
 *
 * Questions are known by a [key] the caller picks (the bubble), so listening and
 * transcription can be timed before the turn starts. Main-thread confined.
 */
class TurnRecorder(private val clock: () -> Long = System::currentTimeMillis) {
    /** Where a turn's details go: the bubble that owns them. */
    fun interface Sink {
        fun update(details: TurnDetails)
    }

    private class Turn(val sink: Sink, var details: TurnDetails) {
        fun change(transform: (TurnDetails) -> TurnDetails) {
            details = transform(details)
            sink.update(details)
        }

        fun at(epoch: Long) = epoch - details.startedAt
    }

    /** What happened to a question before it was sent. */
    private class Asked(val origin: Origin, val startedAt: Long, val listen: Span?, val transcribe: Span?, val askedAt: Long)

    private var listenStartedAt: Long? = null
    private var listenDevice = false
    private val asked = mutableMapOf<Any, Asked>()
    private var current: Turn? = null

    /** Sentences and actions by their tags, so speech that plays after the turn ends still lands. */
    private val sentences = LinkedHashMap<String, Pair<Turn, Int>>()
    private val actions = LinkedHashMap<String, Pair<Turn, Int>>()
    private var openAsks = emptySet<String>()
    private var nextTag = 0L

    /** The microphone opened ([device]: the user's recognizer instead of Wiggins' recording). */
    fun listeningStarted(device: Boolean = false) {
        listenStartedAt = clock()
        listenDevice = device
    }

    /** Listening ended with nothing to send. */
    fun listeningCancelled() {
        listenStartedAt = null
    }

    /** The recording for the question [key] ended; the hub transcribes it now. */
    fun recorded(key: Any) {
        val now = clock()
        val start = listenStartedAt ?: now
        listenStartedAt = null
        asked[key] = Asked(Origin.HUB_STT, start, Span(0, now - start), Span(now - start), now)
    }

    /** The hub's transcription for [key] arrived. */
    fun transcribed(key: Any) {
        val a = asked[key] ?: return
        asked[key] = Asked(a.origin, a.startedAt, a.listen, a.transcribe?.copy(endMs = clock() - a.startedAt), clock())
    }

    /** The question [key] was asked: typed, or what the user's recognizer heard. Ignored if it was recorded. */
    fun asked(key: Any) {
        if (key in asked) return
        val now = clock()
        val start = listenStartedAt?.takeIf { listenDevice }
        listenStartedAt = null
        asked[key] = if (start != null) {
            Asked(Origin.DEVICE_STT, start, Span(0, now - start), null, now)
        } else {
            Asked(Origin.TYPED, now, null, null, now)
        }
    }

    /** A question that will never be sent (dropped with its bubble). */
    fun forget(key: Any) {
        asked.remove(key)
    }

    /** The question [key] reached the hub: a turn starts, kept by [sink]. */
    fun sent(key: Any, sink: Sink) {
        endTurn()
        val now = clock()
        val a = asked.remove(key) ?: Asked(Origin.TYPED, now, null, null, now)
        val waited = now - a.askedAt
        val details = TurnDetails(
            startedAt = a.startedAt,
            origin = a.origin,
            listen = a.listen,
            transcribe = a.transcribe,
            connect = if (waited >= CONNECT_SHOWN_MS) Span(a.askedAt - a.startedAt, now - a.startedAt) else null,
            sentMs = now - a.startedAt,
        )
        current = Turn(sink, details).also { it.sink.update(details) }
    }

    /** Whether a turn is collecting the hub's messages. */
    val inTurn get() = current != null

    /** The turn ended without the hub saying so (cleared, the panel closed); late speech still lands. */
    fun endTurn() {
        current = null
    }

    /**
     * A downlink bus message ([type], with its [payload]'s data and context): the match,
     * the end of the turn, a follow-up and the persona's report are kept.
     */
    fun onDownlink(type: String?, payload: JsonObject) {
        val turn = current ?: return
        val context = payload["context"] as? JsonObject
        val data = payload["data"] as? JsonObject ?: JsonObject(emptyMap())
        val utteranceId = context?.string("utterance_id")
        val known = turn.details.utteranceId
        // Another utterance's messages, such as a late end of the turn before.
        if (known != null && utteranceId != null && utteranceId != known) return
        val now = turn.at(clock())
        when (type) {
            "ovos.intent.matched" -> turn.change {
                it.copy(
                    utteranceId = utteranceId ?: it.utteranceId,
                    matchedMs = it.matchedMs ?: now,
                    match = TurnDetails.Match(
                        skillId = data.string("skill_id"),
                        intentName = data.string("intent_name"),
                        pipelineId = data.string("pipeline_id") ?: context?.string("pipeline_id"),
                        slots = (data["slots"] as? JsonObject)?.flatten().orEmpty(),
                    ),
                )
            }
            "ovos.mic.listen", "mycroft.mic.listen" -> if (!turn.details.followUp) turn.change { it.copy(followUp = true) }
            PERSONA_REPORT -> report(data, now)?.let { hub -> turn.change { it.copy(hub = hub) } }
            "ovos.utterance.handled" -> {
                if (known == null && utteranceId != null) turn.change { it.copy(utteranceId = utteranceId) }
                turn.change { it.copy(handledMs = now) }
                current = null
            }
        }
    }

    /**
     * A sentence arrived, shown in [sink]'s bubble when no turn is collecting (speech the
     * hub started by itself). Returns the tag to report its speech with ([onSpeech]).
     */
    fun onSpeak(text: String, meta: JsonObject?, expectResponse: Boolean, spoken: Boolean, sink: () -> Sink): String {
        val now = clock()
        val turn = current ?: Turn(sink(), TurnDetails(startedAt = now, origin = Origin.HUB))
        val sentence = TurnDetails.Sentence(
            text = text.take(SENTENCE_TEXT),
            skill = meta?.string("skill"),
            dialog = meta?.string("dialog"),
            data = (meta?.get("data") as? JsonObject)?.flatten().orEmpty(),
            arrivedMs = turn.at(now),
            spoken = spoken,
        )
        turn.change { it.copy(sentences = it.sentences + sentence, followUp = it.followUp || expectResponse) }
        val tag = "s${nextTag++}"
        sentences[tag] = turn to turn.details.sentences.lastIndex
        trim(sentences)
        return tag
    }

    /** Speech progress for the sentence [tag]. */
    fun onSpeech(tag: String, event: SpeechProgress) {
        val (turn, index) = sentences[tag] ?: return
        val now = turn.at(clock())
        turn.change { d ->
            d.copy(sentences = d.sentences.mapIndexed { i, s ->
                if (i != index) s else when (event) {
                    SpeechProgress.AUDIO -> s.copy(audioMs = s.audioMs ?: now)
                    SpeechProgress.START -> s.copy(audioMs = s.audioMs ?: now, playStartMs = s.playStartMs ?: now)
                    SpeechProgress.END -> s.copy(playEndMs = now)
                }
            })
        }
    }

    /** A Waggle request [id] arrived: an intent's [name] is its action, a query's its name. */
    fun actionStarted(id: String, kind: String, name: String) {
        val turn = current ?: return
        turn.change { it.copy(actions = it.actions + TurnDetails.PhoneAction(id, kind, name, turn.at(clock()))) }
        actions[id] = turn to turn.details.actions.lastIndex
        trim(actions)
    }

    /** The phone answered the Waggle request [id] with [outcome] (`ok` or the error code). */
    fun actionDone(id: String, outcome: String) = changeAction(id) { a, now -> a.copy(endMs = now, outcome = outcome) }

    /** The ask cards on screen now, by request id: one appearing starts its wait, one going ends it. */
    fun onAsks(ids: Set<String>) {
        (ids - openAsks).forEach { id -> changeAction(id) { a, now -> a.copy(askStartMs = now) } }
        (openAsks - ids).forEach { id -> changeAction(id) { a, now -> a.copy(askEndMs = now) } }
        openAsks = ids
    }

    private fun changeAction(id: String, transform: (TurnDetails.PhoneAction, Long) -> TurnDetails.PhoneAction) {
        val (turn, index) = actions[id] ?: return
        val now = turn.at(clock())
        turn.change { d -> d.copy(actions = d.actions.mapIndexed { i, a -> if (i == index) transform(a, now) else a }) }
    }

    private fun <V> trim(map: LinkedHashMap<String, V>) {
        while (map.size > TRACKED) map.remove(map.keys.first())
    }

    companion object {
        /** The persona's report of a turn (its model requests and tools), sent just before the turn ends. */
        const val PERSONA_REPORT = "persona.turn.debug"

        /** A question that waited this long for the connection shows the wait. */
        private const val CONNECT_SHOWN_MS = 100L
        private const val SENTENCE_TEXT = 60
        private const val TRACKED = 64
        private const val MAX_REQUESTS = 20
        private const val MAX_TOOLS = 50

        /**
         * The persona's report (version 1), placed so that its `sent_ms` is when it arrived
         * ([arrivedMs]): the hub-to-phone delay then falls on its bars as on the speech's.
         */
        internal fun report(data: JsonObject, arrivedMs: Long): TurnDetails.HubReport? {
            if (data.long("v") != 1L) return null
            val requests = (data["requests"] as? JsonArray).orEmpty().mapNotNull { r ->
                val o = r as? JsonObject ?: return@mapNotNull null
                TurnDetails.ModelRequest(
                    startMs = o.long("start_ms") ?: return@mapNotNull null,
                    firstMs = o.long("first_ms"),
                    endMs = o.long("end_ms"),
                    stop = o.string("stop"),
                    inputTokens = o.long("input_tokens"),
                    cacheReadTokens = o.long("cache_read_tokens"),
                    cacheWriteTokens = o.long("cache_write_tokens"),
                    outputTokens = o.long("output_tokens"),
                    thinkingTokens = o.long("thinking_tokens"),
                    cost = o.number("cost_usd") ?: o.number("cost"),
                )
            }
            val tools = (data["tools"] as? JsonArray).orEmpty().mapNotNull { t ->
                val o = t as? JsonObject ?: return@mapNotNull null
                TurnDetails.ToolCall(
                    server = o.string("server"),
                    name = o.string("name") ?: return@mapNotNull null,
                    startMs = o.long("start_ms") ?: return@mapNotNull null,
                    endMs = o.long("end_ms"),
                    ok = (o["ok"] as? JsonPrimitive)?.booleanOrNull,
                )
            }
            val sent = data.long("sent_ms") ?: (requests.mapNotNull { it.endMs } + tools.mapNotNull { it.endMs }).maxOrNull() ?: 0
            return TurnDetails.HubReport(
                anchorMs = arrivedMs - sent,
                persona = data.string("persona"),
                model = data.string("model"),
                effort = data.string("effort"),
                requests = requests.take(MAX_REQUESTS),
                tools = tools.take(MAX_TOOLS),
                truncated = (data["truncated"] as? JsonPrimitive)?.booleanOrNull == true ||
                    requests.size > MAX_REQUESTS || tools.size > MAX_TOOLS,
                // Either `cost_usd`, or `cost` with `cost_currency`.
                cost = data.number("cost_usd") ?: data.number("cost"),
                currency = if (data.number("cost_usd") != null) "USD" else data.string("cost_currency"),
                pricing = data.string("pricing"),
            )
        }

        private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

        /** Values as text: strings as they are, anything else as JSON. */
        private fun JsonObject.flatten(): Map<String, String> = mapValues { (_, v) -> v.text() }

        private fun JsonElement.text(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: toString()
    }
}
