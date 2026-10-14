package com.mulesipstea.wiggins.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mulesipstea.wiggins.hivemind.ConnectionState
import com.mulesipstea.wiggins.ui.theme.WigginsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The assistant panel's empty state: Wiggins as the listening indicator, and no suggestions (SPEC "Assistant panel"). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class PanelGreetingTest {
    @get:Rule val compose = createComposeRule()

    private fun panel(transcript: List<TranscriptEntry> = emptyList(), listening: Boolean = false, starting: Boolean = false) =
        compose.setContent {
            WigginsTheme {
                AssistPanel(
                    connection = ConnectionState.Connected("hub"), transcript = transcript, thinking = false,
                    speaking = false, speakReplies = true, onStopSpeaking = {}, onSpeakRepliesChange = {}, banner = null,
                    canListen = true, onSend = {}, onResend = {}, onListen = {}, onOpenApp = {}, onClosed = {},
                    startVisible = true, listening = listening, level = 0.5f, startingToListen = starting,
                )
            }
        }

    @Test fun idleShowsThePromptAndNoSuggestions() {
        panel()
        compose.onNodeWithText("Tap the mic and speak, or type a question.").assertExists()
        compose.onAllNodesWithText("What time is it?").assertCountEquals(0)
        compose.onAllNodesWithTag("listening-wiggins", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun listeningShowsTheRingsAndSaysSo() {
        panel(listening = true)
        compose.onAllNodesWithTag("listening-wiggins", useUnmergedTree = true).assertCountEquals(1)
        // Under Wiggins, and in the text box.
        compose.onAllNodesWithText("Listening…").assertCountEquals(2)
        compose.onAllNodesWithText("Tap the mic and speak, or type a question.").assertCountEquals(0)
    }

    @Test fun gettingReadySaysSo() {
        panel(starting = true)
        compose.onAllNodesWithText("Getting ready…").assertCountEquals(2)
        compose.onAllNodesWithTag("listening-wiggins", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun theFirstBubbleReplacesTheGreeting() {
        panel(transcript = listOf(TranscriptEntry(1, Who.USER, "", Delivery.TRANSCRIBING)))
        compose.onNodeWithText("Transcribing…").assertExists()
        compose.onAllNodesWithText("Tap the mic and speak, or type a question.").assertCountEquals(0)
    }

    @Test fun theAppKeepsItsSuggestions() {
        compose.setContent {
            WigginsTheme {
                ChatScreen(
                    connection = ConnectionState.Connected("hub"), transcript = emptyList(),
                    thinking = false, speaking = false, speakReplies = true, onStopSpeaking = {}, onSpeakRepliesChange = {},
                    banner = null, onSend = {}, onResend = {}, onClearConversation = {}, canListen = true, onListen = {},
                    onOpenSetup = {}, onOpenSettings = {}, onOpenMessages = {}, onOpenAbout = {},
                )
            }
        }
        compose.onNodeWithText("What time is it?").assertExists()
        compose.onNodeWithText("Ask Wiggins").assertExists()
    }
}
