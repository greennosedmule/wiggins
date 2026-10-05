package com.mulesipstea.wiggins.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
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
    empty: @Composable () -> Unit,
) {
    if (transcript.isEmpty() && !thinking) {
        Box(modifier, contentAlignment = Alignment.Center) { empty() }
        return
    }
    val listState = rememberLazyListState()
    val count = transcript.size + if (thinking) 1 else 0
    LaunchedEffect(count) { listState.animateScrollToItem(count - 1) }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
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
                modifier = Modifier.weight(0.85f, fill = false).alpha(if (entry.delivery == Delivery.PENDING) 0.6f else 1f),
            ) {
                Text(entry.text, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
            }
            if (!user) Spacer(Modifier.weight(0.15f))
        }
        when (entry.delivery) {
            Delivery.SENT -> Unit
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

/** Three pulsing dots in a hub bubble while an answer is on its way. */
@Composable
internal fun ThinkingBubble() {
    val transition = rememberInfiniteTransition(label = "thinking")
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { i ->
                val alpha by transition.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(durationMillis = 600, delayMillis = i * 150), RepeatMode.Reverse),
                    label = "dot$i",
                )
                Box(Modifier.size(8.dp).alpha(alpha).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
            }
        }
    }
}

/** What an empty conversation shows: the mark, a prompt, and questions to try. */
@Composable
internal fun EmptyConversation(canListen: Boolean, onAsk: (String) -> Unit, compact: Boolean = false) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = if (compact) 8.dp else 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!compact) {
            Box(Modifier.size(88.dp).background(colorResource(R.color.ic_launcher_background), CircleShape), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(120.dp))
            }
            Text("Ask Wiggins", style = MaterialTheme.typography.headlineSmall)
        }
        Text(
            if (canListen) "Tap the mic and speak, or type a question." else "Type a question below.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        @Suppress("OPT_IN_USAGE")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            SUGGESTIONS.forEach { SuggestionChip(onClick = { onAsk(it) }, label = { Text(it) }) }
        }
    }
}

/** The text box with a mic button when it's empty and a send button when it isn't. */
@Composable
internal fun InputRow(onSend: (String) -> Unit, canListen: Boolean, onListen: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
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
            placeholder = { Text("Ask something") },
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
        val listening = text.isBlank() && canListen
        FilledIconButton(onClick = if (listening) onListen else submit, modifier = Modifier.size(52.dp)) {
            if (listening) Icon(painterResource(R.drawable.ic_mic), "Speak") else Icon(Icons.AutoMirrored.Filled.Send, "Send")
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

/** One short line; why a connection failed is the banner's job. */
internal fun ConnectionState.label() = when (this) {
    ConnectionState.Connecting, ConnectionState.Handshaking -> "Connecting…"
    is ConnectionState.Connected -> "Connected"
    ConnectionState.Disconnected, is ConnectionState.Failed -> "Not connected"
}
