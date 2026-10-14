package com.mulesipstea.wiggins.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mulesipstea.wiggins.turns.Stages
import com.mulesipstea.wiggins.turns.Timeline
import com.mulesipstea.wiggins.turns.Timeline.Component
import com.mulesipstea.wiggins.turns.TurnDetails

/** The reply details sheet (SPEC "Reply details"), opened by long-pressing a bubble. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReplyDetailsSheet(details: TurnDetails?, sent: Boolean, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            ReplyDetails(details, sent)
        }
    }
}

/** What's known about a turn: what answered it, then its timeline. [sent]: false for a question that hasn't reached the hub. */
@Composable
internal fun ReplyDetails(details: TurnDetails?, sent: Boolean = true) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Reply details", style = MaterialTheme.typography.titleLarge)
        if (details == null) {
            Text(
                if (sent) {
                    "No details were kept for this reply. Wiggins keeps them from version 0.6.0 on, for questions asked since."
                } else {
                    "This question hasn't reached the hub, so there's nothing to show yet."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Summary(details)
        HorizontalDivider()
        Text("Timeline", style = MaterialTheme.typography.titleMedium)
        val rows = remember(details) { Timeline.rows(details) }
        Legend(rows, details)
        Waterfall(rows)
        val zero = when {
            details.origin == TurnDetails.Origin.HUB -> "the hub spoke"
            details.listen != null -> "listening started"
            else -> "the question was asked"
        }
        val approximate = if (rows.any { it.approximate }) {
            "Rows marked ≈ come from the hub's own report and are placed by its clock, so they're approximate, to within the network delay. "
        } else {
            ""
        }
        Text(
            "Times are from the moment $zero, by the phone's clock. ${approximate}Tap a row for its numbers.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val context = LocalContext.current
        OutlinedButton(onClick = {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Reply details", Timeline.describe(details, rows)))
        }) { Text("Copy details") }
    }
}

@Composable
private fun Summary(d: TurnDetails) {
    val match = d.match
    val persona = Stages.isPersona(match)
    when {
        d.origin == TurnDetails.Origin.HUB -> Fact("Answered by", "The hub, on its own", d.sentences.firstOrNull()?.skill)
        match == null -> Fact("Answered by", null, missing = if (d.handledMs != null) Timeline.NOT_REPORTED else "Waiting for the hub")
        persona -> Fact("Answered by", Timeline.personaName(d), match.skillId)
        else -> Fact("Answered by", match.skillId?.let(Stages::skillName), match.skillId)
    }
    match?.pipelineId?.let { Fact("Stage", Stages.stageName(it), it) }
    match?.intentName?.let { Fact("Intent", it.substringAfter(':'), it.takeIf { n -> ':' in n }) }
    match?.slots?.takeIf { it.isNotEmpty() }?.let { slots ->
        Fact("Slots", slots.entries.joinToString("\n") { "${it.key}: ${it.value}" })
    }
    d.sentences.filter { it.dialog != null }.distinctBy { it.dialog to it.data }.takeIf { it.isNotEmpty() }?.let { dialogs ->
        Fact("Dialogs", dialogs.joinToString("\n") { s ->
            s.dialog + s.data.takeIf { it.isNotEmpty() }?.entries?.joinToString(", ", " (", ")") { "${it.key}: ${it.value}" }.orEmpty()
        })
    }
    if (d.actions.isNotEmpty()) {
        Fact("Phone actions", d.actions.joinToString("\n") { "${Timeline.shortAction(it.name)}: ${it.outcome ?: "no answer sent"}" })
    }
    val hub = d.hub
    when {
        hub != null -> {
            hub.model?.let { Fact("Model", it + (hub.effort?.let { e -> ", effort $e" } ?: "")) }
            Fact("Tools", hub.tools.takeIf { it.isNotEmpty() }?.joinToString("\n") { t ->
                listOfNotNull(t.server, t.name).joinToString(" · ") + when (t.ok) { false -> " (failed)"; else -> "" }
            } ?: "None")
            hub.requests.mapNotNull { it.thinkingTokens }.takeIf { it.isNotEmpty() }?.let { Fact("Thinking", "${it.sum()} tokens") }
        }
        persona && d.handledMs != null -> Fact("Tools", null, missing = Timeline.NOT_REPORTED)
    }
    if (d.sentences.isEmpty() && d.handledMs != null) Fact("Reply", "None was spoken", missing = null)
    if (d.followUp) Fact("Follow-up", "Asked a question")
}

/** One fact: its [label], its [value] (or why it's [missing]), and the raw [id] it came from. */
@Composable
private fun Fact(label: String, value: String?, id: String? = null, missing: String? = null) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.width(LABEL_WIDTH), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            if (value != null) {
                Text(value, style = MaterialTheme.typography.bodyMedium)
            } else if (missing != null) {
                Text(missing, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (id != null && id != value) {
                Text(id, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Legend(rows: List<Timeline.Row>, d: TurnDetails) {
    val present = Component.entries.filter { c -> rows.any { it.component == c } }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        present.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(10.dp).background(componentColor(c), RoundedCornerShape(2.dp)))
                Text(componentName(c, d), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * The waterfall: a scale, then a row per phase with its bars on the shared axis and its
 * duration beside them. Tapping a row shows its numbers.
 */
@Composable
internal fun Waterfall(rows: List<Timeline.Row>) {
    val total = Timeline.totalMs(rows)
    val measurer = rememberTextMeasurer()
    val small = MaterialTheme.typography.labelSmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val colors = Component.entries.associateWith { componentColor(it) }
    var selected by remember(rows) { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxWidth().testTag("waterfall")) {
        // The scale, with as many ticks as fit.
        Row(Modifier.fillMaxWidth().height(18.dp)) {
            Spacer(Modifier.width(ROW_LABEL_WIDTH))
            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                val ticks = Timeline.ticks(total, (size.width / TICK_SPACING.toPx()).toInt())
                ticks.forEach { t ->
                    val x = t * size.width / total
                    val text = measurer.measure(Timeline.tickLabel(t), small.copy(color = muted))
                    val left = (x - text.size.width / 2f).coerceIn(0f, size.width - text.size.width)
                    drawText(text, topLeft = Offset(left, 0f))
                }
            }
        }
        rows.forEachIndexed { i, row ->
            val description = row.label + ": " + (row.durationMs?.let { Timeline.duration(it) } ?: row.missing ?: row.marker?.let { "at " + Timeline.duration(it) } ?: "")
            Row(
                Modifier.fillMaxWidth().height(ROW_HEIGHT)
                    .clickable { selected = if (selected == i) null else i }
                    .semantics { contentDescription = description },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    row.label,
                    Modifier.width(ROW_LABEL_WIDTH).padding(end = 6.dp),
                    style = small,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Canvas(Modifier.weight(1f).fillMaxHeight()) {
                    val ticks = Timeline.ticks(total, (size.width / TICK_SPACING.toPx()).toInt())
                    ticks.forEach { t ->
                        val x = t * size.width / total
                        drawLine(grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                    }
                    val color = colors.getValue(row.component)
                    val barHeight = BAR_HEIGHT.toPx()
                    val top = (size.height - barHeight) / 2
                    val placed = Timeline.place(row, total, size.width, MIN_BAR.toPx())
                    placed.forEach { p ->
                        drawRect(if (p.light) color.copy(alpha = 0.35f) else color, Offset(p.x, top), Size(p.width, barHeight))
                        if (row.approximate) {
                            drawRect(color, Offset(p.x, top), Size(p.width, barHeight),
                                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f))))
                        }
                    }
                    val labelText: String?
                    val start: Float
                    val end: Float
                    if (placed.isNotEmpty()) {
                        start = placed.first().x
                        end = placed.maxOf { it.x + it.width }
                        labelText = (if (row.approximate) "≈" else "") + Timeline.duration(row.durationMs ?: 0)
                    } else if (row.marker != null) {
                        val x = (row.marker * size.width / total).coerceIn(1f, size.width - 1f)
                        drawLine(color, Offset(x, top - 3.dp.toPx()), Offset(x, top + barHeight + 3.dp.toPx()), strokeWidth = 2.dp.toPx())
                        start = x
                        end = x
                        labelText = Timeline.duration(row.marker)
                    } else {
                        start = 0f
                        end = 0f
                        labelText = null
                    }
                    if (row.missing != null && placed.isEmpty() && row.marker == null) {
                        val text = measurer.measure(row.missing, small.copy(color = muted, fontStyle = FontStyle.Italic))
                        drawText(text, topLeft = Offset(0f, (size.height - text.size.height) / 2))
                    } else if (labelText != null) {
                        val text = measurer.measure(labelText, small.copy(color = onSurface))
                        val at = Timeline.label(start, end, text.size.width.toFloat(), size.width, 4.dp.toPx())
                        drawText(text, topLeft = Offset(at.x, (size.height - text.size.height) / 2))
                    }
                }
            }
            if (selected == i) RowDetail(row)
        }
    }
}

@Composable
private fun RowDetail(row: Timeline.Row) {
    val numbers = when {
        row.durationMs != null -> "${Timeline.duration(row.startMs!!)} → ${Timeline.duration(row.endMs!!)} (${Timeline.duration(row.durationMs!!)})"
        row.marker != null -> "at ${Timeline.duration(row.marker)}"
        else -> row.missing.orEmpty()
    }
    val text = listOf(numbers, row.detail, if (row.approximate) "by the hub's clock" else "").filter { it.isNotEmpty() }.joinToString(" · ")
    Text(
        text,
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)).padding(8.dp),
        style = MaterialTheme.typography.bodySmall,
    )
}

/** A component's colour, deeper on a light surface and lighter on a dark one. */
@Composable
internal fun componentColor(c: Component): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when (c) {
        Component.PHONE -> if (dark) Color(0xFF8FB8E8) else Color(0xFF2F6AA8)
        Component.HUB -> if (dark) Color(0xFFC3A8EA) else Color(0xFF7550A8)
        Component.PERSONA -> if (dark) Color(0xFFEA9A7E) else Color(0xFFB8532F)
        Component.TOOL -> if (dark) Color(0xFF7CCFBE) else Color(0xFF237D6C)
        Component.SPEECH -> if (dark) Color(0xFFF2C062) else Color(0xFF9A6A00)
    }
}

private fun componentName(c: Component, d: TurnDetails) = when (c) {
    Component.PHONE -> "Phone"
    Component.HUB -> "Hub"
    Component.PERSONA -> Timeline.personaName(d)
    Component.TOOL -> "Tools"
    Component.SPEECH -> "Speech"
}

private val LABEL_WIDTH = 104.dp
private val ROW_LABEL_WIDTH = 96.dp
private val ROW_HEIGHT = 26.dp
private val BAR_HEIGHT = 12.dp
private val MIN_BAR = 2.dp
private val TICK_SPACING = 56.dp
