package com.mulesipstea.wiggins.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.mulesipstea.wiggins.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Wiggins' own colors, the same in light and dark themes: he's the brand mark.
private val Navy = Color(0xFF1B2440)
private val Amber = Color(0xFFF9BC4D)

/** A pointy-top hexagon filling its box: the honeycomb frame around Wiggins. */
val HexagonShape = GenericShape { size, _ ->
    val w = size.width
    val h = size.height
    moveTo(w * 0.5f, h * 0.01f)
    lineTo(w * 0.933f, h * 0.255f)
    lineTo(w * 0.933f, h * 0.745f)
    lineTo(w * 0.5f, h * 0.99f)
    lineTo(w * 0.067f, h * 0.745f)
    lineTo(w * 0.067f, h * 0.255f)
    close()
}

/**
 * Wiggins in his honeycomb frame. While [blowing], bubbles rise from his pipe
 * (the hub is thinking, or connecting); otherwise they rest as on the app icon.
 * [periodMillis] is how long one bubble takes to rise; four are in the air at once.
 */
@Composable
fun WigginsAvatar(modifier: Modifier = Modifier, blowing: Boolean = false, periodMillis: Int = 2400) {
    Box(modifier.aspectRatio(1f).clip(HexagonShape).background(Navy)) {
        Image(painterResource(R.drawable.wiggins_figure), contentDescription = "Wiggins", modifier = Modifier.fillMaxSize())
        if (blowing) RisingBubbles(periodMillis) else RestingBubbles()
    }
}

@Composable
private fun RestingBubbles() {
    Canvas(Modifier.fillMaxSize()) {
        val u = size.width / 200f
        REST.forEachIndexed { i, (x, y, r) ->
            val center = Offset(x * u, y * u)
            if (i == REST.lastIndex) hexagon(center, r * u, 1f) else bubble(center, r * u, 1f)
        }
    }
}

@Composable
private fun RisingBubbles(periodMillis: Int) {
    val t by rememberInfiniteTransition(label = "bubbles").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMillis * BUBBLES, easing = LinearEasing)),
        label = "t",
    )
    Canvas(Modifier.fillMaxSize()) {
        val u = size.width / 200f
        repeat(BUBBLES) { i ->
            // Each bubble is a quarter of the cycle behind the one before it.
            val p = (t * BUBBLES + i) % BUBBLES / BUBBLES
            val (x, y) = alongPath(p)
            val wobble = 3f * sin((p * 4f * PI + i).toFloat())
            val radius = (1.5f + 5.5f * (p / 0.25f).coerceAtMost(1f)) * (1f + 0.25f * ((p - 0.9f) / 0.1f).coerceIn(0f, 1f))
            val alpha = (p / 0.08f).coerceAtMost(1f) * (1f - ((p - 0.85f) / 0.15f).coerceIn(0f, 1f))
            val center = Offset((x + wobble) * u, y * u)
            if (i == BUBBLES - 1) hexagon(center, (radius + 2f) * u, alpha) else bubble(center, radius * u, alpha)
        }
    }
}

private const val BUBBLES = 4

/** Resting bubbles (x, y, radius) in the 200x200 frame, as on the icon; the last is the honeycomb. */
private val REST = listOf(
    Triple(155.0f, 112.6f, 5.6f),
    Triple(165.2f, 92.1f, 7.4f),
    Triple(155.9f, 71.7f, 5.1f),
    Triple(144.8f, 43.8f, 9.3f),
)

/** The rising path: from the pipe bowl through the resting spots and up out of frame. */
private val PATH = listOf(149f to 120f, 155f to 112.6f, 165.2f to 92.1f, 155.9f to 71.7f, 144.8f to 43.8f, 138f to 8f)

private fun alongPath(p: Float): Pair<Float, Float> {
    val scaled = p * (PATH.size - 1)
    val i = scaled.toInt().coerceAtMost(PATH.size - 2)
    val f = scaled - i
    val (x0, y0) = PATH[i]
    val (x1, y1) = PATH[i + 1]
    return (x0 + (x1 - x0) * f) to (y0 + (y1 - y0) * f)
}

private fun DrawScope.bubble(center: Offset, radius: Float, alpha: Float) {
    drawCircle(Amber.copy(alpha = alpha), radius, center, style = Stroke(width = radius * 0.4f + size.width / 200f))
    // A glint on the upper left.
    drawArc(
        Amber.copy(alpha = alpha),
        startAngle = 200f, sweepAngle = 55f, useCenter = false,
        topLeft = Offset(center.x - radius * 0.55f, center.y - radius * 0.55f),
        size = androidx.compose.ui.geometry.Size(radius * 1.1f, radius * 1.1f),
        style = Stroke(width = radius * 0.22f, cap = StrokeCap.Round),
    )
}

private fun DrawScope.hexagon(center: Offset, radius: Float, alpha: Float) {
    drawPath(hexagonPath(center, radius), Amber.copy(alpha = alpha), style = Stroke(width = radius * 0.32f))
}

/** A regular pointy-top hexagon around [center], [radius] to each corner, starting at the top. */
private fun hexagonPath(center: Offset, radius: Float) = Path().apply {
    for (k in 0 until 6) {
        val angle = (PI / 3 * k - PI / 2).toFloat()
        val point = Offset(center.x + radius * cos(angle), center.y + radius * sin(angle))
        if (k == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
    }
    close()
}

/**
 * Wiggins as the panel's listening indicator (SPEC "Assistant panel"), the mic button's
 * treatment moved onto the mark: while [listening], a honeycomb ring breathes out from
 * him every [BREATH_MS] even in silence, and a honeycomb halo grows with the input
 * [level] (0 to 1). While [starting], a stroke runs round his frame, as the spinner does
 * round the mic. While [connecting], he blows bubbles, as in the header. The box is
 * [HALO_ROOM] times his size, so the ring and halo have room to grow.
 */
@Composable
fun ListeningWiggins(
    listening: Boolean,
    starting: Boolean,
    connecting: Boolean,
    level: Float,
    modifier: Modifier = Modifier,
) {
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        if (listening) ListeningRings(level)
        if (starting && !listening) GettingReadyStroke()
        WigginsAvatar(Modifier.fillMaxSize(1f / HALO_ROOM), blowing = connecting && !listening && !starting)
    }
}

@Composable
private fun ListeningRings(level: Float) {
    val halo by animateFloatAsState(level, tween(durationMillis = 90), label = "level")
    val breath by rememberInfiniteTransition(label = "breathing").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(BREATH_MS, easing = LinearEasing)),
        label = "breath",
    )
    Canvas(Modifier.fillMaxSize().testTag("listening-wiggins")) {
        // His frame's corners sit just inside the avatar's box, half of it from the centre.
        val frame = size.minDimension / HALO_ROOM * 0.49f
        drawPath(hexagonPath(center, frame * (1.08f + 0.25f * halo)), Amber.copy(alpha = 0.18f + 0.3f * halo))
        drawPath(
            hexagonPath(center, frame * (1f + 0.32f * breath)),
            Amber.copy(alpha = 0.8f * (1f - breath)),
            style = Stroke(width = 2.5.dp.toPx(), join = StrokeJoin.Round),
        )
    }
}

@Composable
private fun GettingReadyStroke() {
    val t by rememberInfiniteTransition(label = "ready").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(READY_MS, easing = LinearEasing)),
        label = "t",
    )
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize()) {
        val measure = PathMeasure().apply { setPath(hexagonPath(center, size.minDimension / HALO_ROOM * 0.49f * 1.1f), true) }
        val length = measure.length
        val start = t * length
        val end = start + length * 0.3f
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        // A third of the way round, wrapping past the top corner.
        val segment = Path().apply {
            measure.getSegment(start, minOf(end, length), this, true)
            if (end > length) measure.getSegment(0f, end - length, this, true)
        }
        drawPath(segment, color, style = stroke)
    }
}

/** How much bigger than Wiggins the listening indicator's box is. */
private const val HALO_ROOM = 1.4f
private const val BREATH_MS = 1_200
private const val READY_MS = 1_400
