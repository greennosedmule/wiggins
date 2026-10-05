package com.mulesipstea.wiggins.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.mulesipstea.wiggins.hivemind.ConnectionState

/**
 * The assistant panel (SPEC "Conversation screen"): a sheet from the bottom of
 * the screen over the current app, with a conversation that lasts only while
 * it's open. Tapping outside it or Back slides it away, then [onClosed] runs.
 */
@Composable
fun AssistPanel(
    connection: ConnectionState,
    transcript: List<TranscriptEntry>,
    thinking: Boolean,
    banner: Banner?,
    canListen: Boolean,
    onSend: (String) -> Unit,
    onResend: (Long) -> Unit,
    onListen: () -> Unit,
    onOpenApp: () -> Unit,
    onClosed: () -> Unit,
    startVisible: Boolean = false,
) {
    val shown = remember { MutableTransitionState(startVisible).apply { targetState = true } }
    val dismiss = { shown.targetState = false }
    LaunchedEffect(shown.isIdle, shown.currentState) {
        if (shown.isIdle && !shown.currentState && !shown.targetState) onClosed()
    }
    BackHandler(onBack = dismiss)

    val maxListHeight = (LocalConfiguration.current.screenHeightDp * 0.45f).dp
    AnimatedVisibility(visibleState = shown, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = dismiss),
        )
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visibleState = shown,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    // Taps inside the sheet must not reach the scrim.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                Column(Modifier.navigationBarsPadding().imePadding()) {
                    Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.size(width = 32.dp, height = 4.dp)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
                        )
                    }
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Wiggins", style = MaterialTheme.typography.titleMedium)
                            StatusLine(connection)
                        }
                        TextButton(onClick = onOpenApp) { Text("Open app") }
                    }
                    banner?.let { BannerRow(it) }
                    ConversationList(
                        transcript = transcript,
                        thinking = thinking,
                        onResend = onResend,
                        modifier = Modifier.fillMaxWidth().heightIn(max = maxListHeight),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        EmptyConversation(canListen, onAsk = onSend, compact = true)
                    }
                    InputRow(onSend, canListen, onListen)
                }
            }
        }
    }
}
