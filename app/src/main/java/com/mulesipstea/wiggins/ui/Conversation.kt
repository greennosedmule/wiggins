package com.mulesipstea.wiggins.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mulesipstea.wiggins.R
import com.mulesipstea.wiggins.hivemind.ConnectionState
import com.mulesipstea.wiggins.ui.theme.StatusColors.connected
import com.mulesipstea.wiggins.ui.theme.StatusColors.connecting
import com.mulesipstea.wiggins.ui.theme.StatusColors.disconnected

/** Things to try in an empty conversation; tapping one asks it. */
private val SUGGESTIONS = listOf("What time is it?", "What's the weather like?", "Tell me about honeybees")

/** The conversation with the hub's "thinking" indicator; [empty] shows when there's nothing yet. */
@Composable
internal fun ConversationList(
    transcript: List<TranscriptEntry>,
    thinking: Boolean,
    onResend: (Long) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    /** A short conversation sits at the bottom, next to the text box, rather than the top. */
    anchorBottom: Boolean = false,
    empty: @Composable () -> Unit,
) {
    // The first bubble fades in as the empty state fades out.
    Crossfade(transcript.isEmpty() && !thinking, label = "empty") { isEmpty ->
        if (isEmpty) {
            Box(modifier, contentAlignment = Alignment.Center) { empty() }
        } else {
            Transcript(transcript, thinking, onResend, modifier, contentPadding, anchorBottom)
        }
    }
}

@Composable
private fun Transcript(
    transcript: List<TranscriptEntry>,
    thinking: Boolean,
    onResend: (Long) -> Unit,
    modifier: Modifier,
    contentPadding: PaddingValues,
    anchorBottom: Boolean,
) {
    val listState = rememberLazyListState()
    val count = transcript.size + if (thinking) 1 else 0
    LaunchedEffect(count) { listState.animateScrollToItem(count - 1) }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp, if (anchorBottom) Alignment.Bottom else Alignment.Top),
    ) {
        items(transcript, key = { it.id }) { Bubble(it, onResend) }
        if (thinking) item(key = "thinking") { ThinkingBubble() }
    }
}

@Composable
internal fun Bubble(entry: TranscriptEntry, onResend: (Long) -> Unit) {
    val user = entry.who == Who.USER
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
            if (user) Spacer(Modifier.weight(0.15f))
            Surface(
                color = if (user) colors.primaryContainer else colors.surfaceContainerHigh,
                contentColor = if (user) colors.onPrimaryContainer else colors.onSurface,
                // The small corner points at the speaker.
                shape = if (user) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp),
                modifier = Modifier.weight(0.85f, fill = false)
                    .alpha(if (entry.delivery == Delivery.PENDING || entry.delivery == Delivery.TRANSCRIBING) 0.6f else 1f),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    if (entry.delivery == Delivery.TRANSCRIBING) {
                        Text("Transcribing…", style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic)
                    } else {
                        Text(entry.text, style = MaterialTheme.typography.bodyLarge)
                    }
                    if (entry.streaming) MoreToCome(Modifier.padding(top = 6.dp))
                }
            }
            if (!user) Spacer(Modifier.weight(0.15f))
        }
        when (entry.delivery) {
            Delivery.SENT, Delivery.TRANSCRIBING -> Unit
            Delivery.PENDING -> Text(
                "Sending…",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, end = 8.dp),
            )
            Delivery.NOT_SENT -> TextButton(onClick = { onResend(entry.id) }) {
                Text("Not sent · Retry", color = colors.error, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * Three small bubbles that swell and fade in turn: more of this reply is on its
 * way (the hub hasn't finished handling the question).
 */
@Composable
private fun MoreToCome(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "more")
    val color = MaterialTheme.colorScheme.primary
    Row(modifier.semantics { contentDescription = "More to come" }, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val phase by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(durationMillis = 700, delayMillis = i * 200), RepeatMode.Reverse),
                label = "bubble$i",
            )
            Box(
                Modifier.size(11.dp).scale(0.65f + 0.35f * phase).alpha(0.45f + 0.55f * phase)
                    .border(2.dp, color, CircleShape),
            )
        }
    }
}

/** Wiggins blowing bubbles where the hub's answer will appear. */
@Composable
internal fun ThinkingBubble() {
    WigginsAvatar(Modifier.size(48.dp), blowing = true)
}

/** What the app's empty conversation shows: the mark, a prompt, and questions to try. */
@Composable
internal fun EmptyConversation(canListen: Boolean, onAsk: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Slow bubbles: he's waiting, not working.
        WigginsAvatar(Modifier.size(132.dp), blowing = true, periodMillis = 3600)
        Text("Ask Wiggins", style = MaterialTheme.typography.headlineSmall)
        Prompt(canListen)
        @Suppress("OPT_IN_USAGE")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            SUGGESTIONS.forEach { SuggestionChip(onClick = { onAsk(it) }, label = { Text(it) }) }
        }
    }
}

/**
 * What the assistant panel shows until its conversation has a bubble: Wiggins as a big
 * listening indicator (SPEC "Assistant panel"), since an invocation listens straight
 * away, and under him what he's doing or how to ask.
 */
@Composable
internal fun PanelGreeting(canListen: Boolean, listening: Boolean, starting: Boolean, connecting: Boolean, level: Float) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ListeningWiggins(listening, starting, connecting, level, Modifier.size(PANEL_WIGGINS))
        when {
            listening -> Text(
                "Listening…",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            starting -> Text(
                "Getting ready…",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            else -> Prompt(canListen)
        }
    }
}

/** The empty panel's height: Wiggins with room for his halo, and a line of text. */
internal val PANEL_GREETING_HEIGHT = 200.dp
private val PANEL_WIGGINS = 154.dp

@Composable
private fun Prompt(canListen: Boolean) {
    Text(
        if (canListen) "Tap the mic and speak, or type a question." else "Type a question below.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/**
 * The text box and its button: send when there's text; otherwise, while listening, the
 * mic showing the input level, which ends the utterance; stop while a reply is being read
 * aloud; else the mic. [hint] replaces the placeholder for a moment ("Didn't catch that").
 */
@Composable
internal fun InputRow(
    onSend: (String) -> Unit,
    canListen: Boolean,
    onListen: () -> Unit,
    speaking: Boolean,
    onStopSpeaking: () -> Unit,
    listening: Boolean = false,
    level: Float = 0f,
    onStopListening: () -> Unit = {},
    hint: String? = null,
    /** Listening was asked for and is getting ready (the hands-free link, the tone). */
    starting: Boolean = false,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val view = LocalView.current
    // A tick when the microphone actually opens, for a user who isn't looking.
    LaunchedEffect(listening) { if (listening) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }
    val submit = {
        if (text.isNotBlank()) {
            onSend(text)
            text = ""
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f).testTag("utterance"),
            placeholder = {
                when {
                    hint != null -> Text(hint)
                    listening -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        LevelMeter(level)
                        Text(
                            "Listening…",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    starting -> Text("Getting ready…", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    else -> Text("Ask something")
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedBorderColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
        )
        when {
            text.isNotBlank() -> FilledIconButton(onClick = submit, modifier = Modifier.size(52.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send")
            }
            listening -> ListeningButton(level, onStopListening)
            starting -> StartingButton()
            speaking -> FilledIconButton(onClick = onStopSpeaking, modifier = Modifier.size(52.dp)) {
                Icon(painterResource(R.drawable.ic_stop), "Stop speaking")
            }
            canListen -> FilledIconButton(onClick = onListen, modifier = Modifier.size(52.dp)) {
                Icon(painterResource(R.drawable.ic_mic), "Speak")
            }
            else -> FilledIconButton(onClick = submit, enabled = false, modifier = Modifier.size(52.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send")
            }
        }
    }
}

/**
 * The button while recording: a filled stop square in the recording colour, so it reads
 * differently from the mic at a glance. A ring breathes out from it all the time, even in
 * silence, and a halo grows with the input level. Tapping it ends the utterance.
 */
@Composable
private fun ListeningButton(level: Float, onStop: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val halo by animateFloatAsState(level, tween(durationMillis = 90), label = "level")
    val breath by rememberInfiniteTransition(label = "breathing").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(BREATH_MS, easing = LinearEasing)),
        label = "breath",
    )
    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(52.dp).scale(1f + 0.32f * breath)
                .border(2.dp, colors.error.copy(alpha = 0.7f * (1f - breath)), CircleShape),
        )
        Box(
            Modifier.size(52.dp).scale(1.1f + 0.22f * halo)
                .background(colors.error.copy(alpha = 0.14f + 0.22f * halo), CircleShape),
        )
        FilledIconButton(
            onClick = onStop,
            modifier = Modifier.size(52.dp).testTag("listening"),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.error, contentColor = colors.onError),
        ) {
            Icon(painterResource(R.drawable.ic_stop), "Listening; tap when you've finished")
        }
    }
}

/** The mic while listening gets ready (the hands-free link, the tone): a spinner around it, no action. */
@Composable
private fun StartingButton() {
    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(52.dp), strokeWidth = 2.dp)
        FilledIconButton(onClick = {}, enabled = false, modifier = Modifier.size(44.dp).testTag("starting")) {
            Icon(painterResource(R.drawable.ic_mic), "Getting ready to listen")
        }
    }
}

/**
 * Five bars that follow the input [level] (0 to 1), louder in the middle, with a slight
 * idle wobble so the meter visibly lives even in silence.
 */
@Composable
internal fun LevelMeter(level: Float, modifier: Modifier = Modifier) {
    val shown by animateFloatAsState(level, tween(durationMillis = 90), label = "meter")
    val wobble by rememberInfiniteTransition(label = "wobble").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(BREATH_MS, easing = LinearEasing), RepeatMode.Reverse),
        label = "wobble",
    )
    Row(modifier.height(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        METER_WEIGHTS.forEachIndexed { i, weight ->
            val idle = 0.2f + 0.1f * (if (i % 2 == 0) wobble else 1f - wobble)
            val fraction = (idle + shown * weight).coerceIn(0f, 1f)
            Box(
                Modifier.width(3.dp).height(18.dp * fraction)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
            )
        }
    }
}

private const val BREATH_MS = 1_200
private val METER_WEIGHTS = floatArrayOf(0.55f, 0.85f, 1f, 0.85f, 0.55f)

/** Turns spoken replies on or off; with them off, replies are text only. */
@Composable
internal fun SpeechToggle(speakReplies: Boolean, onChange: (Boolean) -> Unit) {
    IconButton(onClick = { onChange(!speakReplies) }) {
        if (speakReplies) {
            Icon(painterResource(R.drawable.ic_volume_up), "Replies are spoken; tap for text only")
        } else {
            Icon(painterResource(R.drawable.ic_volume_off), "Replies are text only; tap to speak them")
        }
    }
}

/** A problem or missing setup, with the action that fixes it. */
data class Banner(val message: String, val action: String, val isProblem: Boolean, val onAction: () -> Unit)

@Composable
internal fun BannerRow(banner: Banner, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (banner.isProblem) colors.errorContainer else colors.secondaryContainer,
        contentColor = if (banner.isProblem) colors.onErrorContainer else colors.onSecondaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (banner.isProblem) Icons.Default.Warning else Icons.Default.Info, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(banner.message, Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = banner.onAction) { Text(banner.action) }
        }
    }
}

/** The connection as a colored dot and one short word or two. */
@Composable
internal fun StatusLine(connection: ConnectionState) {
    val colors = MaterialTheme.colorScheme
    val dot = when (connection) {
        is ConnectionState.Connected -> colors.connected
        ConnectionState.Connecting, ConnectionState.Handshaking -> colors.connecting
        ConnectionState.Disconnected, is ConnectionState.Failed -> colors.disconnected
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Text(connection.label(), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1)
    }
}

internal fun ConnectionState.isConnecting() = this == ConnectionState.Connecting || this == ConnectionState.Handshaking

/** One short line; why a connection failed is the banner's job. */
internal fun ConnectionState.label() = when (this) {
    ConnectionState.Connecting, ConnectionState.Handshaking -> "Connecting…"
    is ConnectionState.Connected -> "Connected"
    ConnectionState.Disconnected, is ConnectionState.Failed -> "Not connected"
}
