package com.mulesipstea.wiggins.ui

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mulesipstea.wiggins.hivemind.ConnectionState
import com.mulesipstea.wiggins.ui.theme.WigginsTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The app's text box stays at the bottom however long the conversation is. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class ChatLayoutTest {
    @get:Rule val compose = createComposeRule()

    private fun assertInputAtBottom(transcript: List<TranscriptEntry>) {
        compose.setContent {
            WigginsTheme {
                ChatScreen(
                    connection = ConnectionState.Connected("hub"), transcript = transcript,
                    thinking = false, speaking = false, speakReplies = true, onStopSpeaking = {}, onSpeakRepliesChange = {},
                    banner = null, onSend = {}, onResend = {}, onClearConversation = {}, canListen = true, onListen = {},
                    onOpenSetup = {}, onOpenSettings = {}, onOpenMessages = {}, onOpenAbout = {},
                )
            }
        }
        val screen = compose.onRoot().getBoundsInRoot().bottom
        val input = compose.onNodeWithTag("utterance").getBoundsInRoot().bottom
        assertTrue("text box ends at $input, screen at $screen", screen - input < 40.dp)
    }

    @Test fun emptyConversation() = assertInputAtBottom(emptyList())

    @Test fun oneBubble() = assertInputAtBottom(listOf(TranscriptEntry(1, Who.USER, "What time is it?")))

    @Test fun longConversation() = assertInputAtBottom((1L..30L).map { TranscriptEntry(it, if (it % 2 == 0L) Who.HUB else Who.USER, "Message $it") })
}
