package com.mulesipstea.wiggins.turns

import java.util.Locale
import kotlin.math.max

/**
 * A turn as a waterfall (SPEC "Reply details"), like a browser's network panel: one row
 * per phase, each a bar from its start to its end on a shared time axis, so overlaps and
 * gaps show at a glance. Plain data and maths; the sheet draws it.
 */
object Timeline {
    /** Who did the work, which sets a row's colour. */
    enum class Component { PHONE, HUB, PERSONA, TOOL, SPEECH }

    /** [light] marks waiting (for the first text, the audio, an answer) rather than work. */
    data class Bar(val startMs: Long, val endMs: Long, val light: Boolean = false)

    /**
     * A row: its [bars], none if [missing] says why, and a [marker] for an instant (the
     * end of the turn). [approximate] rows are placed by the hub's clock. [detail] is
     * shown when the row is tapped.
     */
    data class Row(
        val label: String,
        val component: Component,
        val bars: List<Bar> = emptyList(),
        val marker: Long? = null,
        val approximate: Boolean = false,
        val missing: String? = null,
        val detail: String = "",
    ) {
        val startMs get() = bars.minOfOrNull { it.startMs } ?: marker
        val endMs get() = bars.maxOfOrNull { it.endMs } ?: marker
        val durationMs get() = if (bars.isEmpty()) null else endMs!! - startMs!!
    }

    const val NOT_REPORTED = "Not reported by the hub"

    fun rows(d: TurnDetails): List<Row> {
        val timed = mutableListOf<Row>()
        d.listen?.let { s ->
            val device = d.origin == TurnDetails.Origin.DEVICE_STT
            timed += span(if (device) "Recognizer" else "Listening", Component.PHONE, s,
                if (device) "Your recognizer app, from opening to its answer" else "Recording on the phone")
        }
        d.transcribe?.let { timed += span("Transcription", Component.HUB, it, "Hub speech-to-text, from the end of the recording to the words") }
        d.connect?.let { timed += span("Connecting", Component.PHONE, it, "The question waited for the connection to the hub") }
        d.sentMs?.let { sent ->
            timed += d.matchedMs?.let { Row("Matching", Component.HUB, listOf(Bar(sent, it)), detail = "From sending the question to the hub's match (ovos.intent.matched)") }
                ?: Row("Matching", Component.HUB, missing = if (d.handledMs != null) NOT_REPORTED else null, marker = sent.takeIf { d.handledMs == null }, detail = "The hub didn't say what matched")
        }
        d.hub?.let { hub ->
            val persona = personaName(d)
            hub.requests.forEachIndexed { i, r ->
                val start = hub.anchorMs + r.startMs
                val end = hub.anchorMs + (r.endMs ?: r.firstMs ?: r.startMs)
                val first = r.firstMs?.let { hub.anchorMs + it }?.coerceIn(start, end)
                val bars = if (first != null && first > start) listOf(Bar(start, first, light = true), Bar(first, end)) else listOf(Bar(start, end))
                timed += Row("$persona ${i + 1}", Component.PERSONA, bars, approximate = true, detail = requestDetail(r, first?.minus(start), hub.currency))
            }
            hub.tools.forEach { t ->
                val start = hub.anchorMs + t.startMs
                val end = hub.anchorMs + (t.endMs ?: t.startMs)
                val outcome = when (t.ok) { true -> "ok"; false -> "failed"; null -> null }
                timed += Row(t.name, Component.TOOL, listOf(Bar(start, end)), approximate = true,
                    detail = listOfNotNull(listOfNotNull("Tool call", t.server?.let { "on $it" }).joinToString(" "), outcome, "no end reported".takeIf { t.endMs == null })
                        .joinToString(" · "))
            }
        }
        d.actions.forEach { a -> timed += action(a) }
        d.sentences.forEachIndexed { i, s -> timed += sentence(i, s) }
        val rows = timed.sortedBy { it.startMs ?: Long.MAX_VALUE }.toMutableList()
        rows += d.handledMs?.let { Row("Done", Component.HUB, marker = it, detail = "The hub finished the turn (ovos.utterance.handled)") }
            ?: Row("Done", Component.HUB, missing = if (d.origin == TurnDetails.Origin.HUB) "Spoken by the hub on its own" else NOT_REPORTED,
                detail = "The hub didn't report the end of this turn")
        return rows
    }

    /**
     * The persona's name as the hub gives it (its report, or the match's `persona` slot),
     * so nothing here assumes which model a hub runs.
     */
    fun personaName(d: TurnDetails): String = d.hub?.persona ?: d.match?.slots?.get("persona") ?: "Persona"

    private fun span(label: String, component: Component, s: Span, detail: String) =
        Row(label, component, listOf(Bar(s.startMs, s.endMs ?: s.startMs)), detail = detail)

    private fun action(a: TurnDetails.PhoneAction): Row {
        val end = a.endMs ?: a.askEndMs ?: a.askStartMs ?: a.startMs
        val bars = if (a.askStartMs != null) {
            val askEnd = a.askEndMs ?: end
            listOfNotNull(
                Bar(a.startMs, a.askStartMs).takeIf { a.askStartMs > a.startMs },
                Bar(a.askStartMs, askEnd, light = true),
                Bar(askEnd, end).takeIf { end > askEnd },
            )
        } else {
            listOf(Bar(a.startMs, end))
        }
        val waited = if (a.askStartMs != null) "waited ${duration((a.askEndMs ?: end) - a.askStartMs)} for your answer" else null
        return Row(shortAction(a.name), Component.PHONE, bars,
            detail = listOfNotNull("Waggle ${a.kind} ${a.name}", a.outcome ?: "no answer sent", waited).joinToString(" · "))
    }

    private fun sentence(i: Int, s: TurnDetails.Sentence): Row {
        val label = "Speech ${i + 1}"
        val said = "“${s.text}”"
        if (!s.spoken || s.playStartMs == null) {
            return Row(label, Component.SPEECH, marker = s.arrivedMs,
                detail = listOf(said, if (s.spoken) "not played" else "shown, not read aloud").joinToString(" · "))
        }
        val end = s.playEndMs ?: s.playStartMs
        val bars = listOfNotNull(Bar(s.arrivedMs, s.playStartMs, light = true).takeIf { s.playStartMs > s.arrivedMs }, Bar(s.playStartMs, end))
        val audio = s.audioMs?.let { "audio ready after ${duration(it - s.arrivedMs)}" }
        return Row(label, Component.SPEECH, bars, detail = listOfNotNull(said, audio, "played for ${duration(end - s.playStartMs)}").joinToString(" · "))
    }

    /** A turn's token counts summed over its requests, by kind, with only the kinds the hub reported. */
    data class Usage(val tokens: List<Pair<String, Long>>, val cost: Double?, val currency: String?, val pricing: String?)

    fun usage(hub: TurnDetails.HubReport): Usage? {
        val kinds = listOf<Pair<String, (TurnDetails.ModelRequest) -> Long?>>(
            "input" to { it.inputTokens },
            "cache read" to { it.cacheReadTokens },
            "cache write" to { it.cacheWriteTokens },
            "output" to { it.outputTokens },
            "thinking" to { it.thinkingTokens },
        )
        val tokens = kinds.mapNotNull { (name, of) -> hub.requests.mapNotNull(of).takeIf { it.isNotEmpty() }?.let { name to it.sum() } }
        val cost = hub.cost ?: hub.requests.mapNotNull { it.cost }.takeIf { it.isNotEmpty() }?.sum()
        if (tokens.isEmpty() && cost == null) return null
        return Usage(tokens, cost, hub.currency, hub.pricing)
    }

    /** An estimated cost: "$0.0123", or "0.0123 EUR" for another currency. */
    fun cost(amount: Double, currency: String?): String {
        val digits = if (amount >= 1) 2 else if (amount >= 0.1) 3 else 4
        val number = String.format(Locale.ROOT, "%.${digits}f", amount)
        return when (currency?.uppercase()) {
            null, "USD" -> "$$number"
            else -> "$number ${currency.uppercase()}"
        }
    }

    /** 12345 → "12,345". */
    fun count(n: Long): String = String.format(Locale.US, "%,d", n)

    private fun requestDetail(r: TurnDetails.ModelRequest, firstAfter: Long?, currency: String?): String = listOfNotNull(
        firstAfter?.let { "first text after ${duration(it)}" },
        r.stop?.let { "stop: $it" },
        r.inputTokens?.let { "$it in" + (r.cacheReadTokens?.let { c -> " ($c cached)" } ?: "") },
        r.cacheWriteTokens?.let { "$it cache written" },
        r.outputTokens?.let { "$it out" },
        r.thinkingTokens?.let { "$it thinking" },
        r.cost?.let { "about ${cost(it, currency)}" },
    ).joinToString(" · ")

    /** `android.intent.action.SET_ALARM` → `SET_ALARM`; a query name stays as it is. */
    fun shortAction(name: String) = name.substringAfterLast('.').takeIf { name.contains("intent.action") } ?: name

    /** How long [ms] took: "340 ms", "1.2 s", "1:05". */
    fun duration(ms: Long): String = when {
        ms < 1_000 -> "$ms ms"
        ms < 60_000 -> String.format(Locale.ROOT, "%.1f s", ms / 1000.0)
        else -> "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)
    }

    /** The axis's end: the last thing on the timeline, at least [MIN_TOTAL_MS]. */
    fun totalMs(rows: List<Row>): Long = max(MIN_TOTAL_MS, rows.maxOfOrNull { it.endMs ?: 0 } ?: 0)

    /**
     * Times for the scale's ticks, from 0 to [totalMs], spaced by the smallest round step
     * (1, 2 or 5 times a power of ten milliseconds) that keeps them [maxTicks] or fewer
     * after zero.
     */
    fun ticks(totalMs: Long, maxTicks: Int): List<Long> {
        val count = maxTicks.coerceAtLeast(1)
        var step = 1L
        while (true) {
            for (m in longArrayOf(1, 2, 5)) {
                if (totalMs / (step * m) <= count) return (0..totalMs step step * m).toList()
            }
            step *= 10
        }
    }

    /** A tick's label: "0", "500 ms", "1 s", "1.5 s". */
    fun tickLabel(ms: Long): String = when {
        ms == 0L -> "0"
        ms < 1_000 -> "$ms ms"
        ms % 1_000 == 0L -> "${ms / 1_000} s"
        else -> String.format(Locale.ROOT, "%.1f s", ms / 1000.0)
    }

    /** A bar placed on screen: [x] and [width] in pixels. */
    data class Placed(val x: Float, val width: Float, val light: Boolean)

    /** Where a row's duration label goes: [x] is its left edge. */
    data class LabelAt(val x: Float, val inside: Boolean)

    /**
     * Places [row]'s bars in [widthPx] for an axis of [totalMs], each at least
     * [minBarPx] wide so a short phase stays visible.
     */
    fun place(row: Row, totalMs: Long, widthPx: Float, minBarPx: Float): List<Placed> {
        val scale = widthPx / totalMs
        return row.bars.map { b ->
            val x = (b.startMs * scale).coerceIn(0f, widthPx - minBarPx)
            Placed(x, max((b.endMs - b.startMs) * scale, minBarPx).coerceAtMost(widthPx - x), b.light)
        }
    }

    /**
     * Where the duration label of a row spanning [startPx] to [endPx] goes: after the
     * bar if it fits, else before it, else inside the end of the axis.
     */
    fun label(startPx: Float, endPx: Float, textPx: Float, widthPx: Float, gapPx: Float): LabelAt = when {
        endPx + gapPx + textPx <= widthPx -> LabelAt(endPx + gapPx, inside = false)
        startPx - gapPx - textPx >= 0 -> LabelAt(startPx - gapPx - textPx, inside = false)
        else -> LabelAt(max(0f, widthPx - textPx), inside = true)
    }

    /** A plain-text account of the turn, for copying. */
    fun describe(d: TurnDetails, rows: List<Row> = rows(d)): String = buildString {
        d.match?.let { m ->
            appendLine("Answered by: ${m.skillId ?: "?"}")
            appendLine("Stage: ${m.pipelineId ?: "?"}")
            appendLine("Intent: ${m.intentName ?: "?"}")
            if (m.slots.isNotEmpty()) appendLine("Slots: " + m.slots.entries.joinToString { "${it.key}=${it.value}" })
        }
        d.hub?.let { h ->
            appendLine(listOfNotNull(personaName(d), h.model, h.effort?.let { "effort $it" }).joinToString(" · "))
            usage(h)?.let { u ->
                if (u.tokens.isNotEmpty()) appendLine("Tokens: " + u.tokens.joinToString(", ") { (k, n) -> "${count(n)} $k" })
                u.cost?.let { appendLine("Estimated cost: ${cost(it, u.currency)}" + (u.pricing?.let { p -> " ($p)" } ?: "")) }
            }
        }
        rows.forEach { r ->
            val time = r.durationMs?.let { "${tickTime(r.startMs!!)} +${duration(it)}" } ?: r.marker?.let { "at ${tickTime(it)}" } ?: r.missing.orEmpty()
            appendLine("${r.label}: $time${if (r.approximate) " (hub clock)" else ""}${if (r.detail.isNotEmpty()) " · ${r.detail}" else ""}")
        }
    }.trimEnd()

    private fun tickTime(ms: Long) = String.format(Locale.ROOT, "%.2f s", ms / 1000.0)

    private const val MIN_TOTAL_MS = 100L
}
