package com.mulesipstea.wiggins.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mulesipstea.wiggins.ui.theme.WigginsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The conversation stays at its newest message, unless the user has scrolled up to read. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class ConversationScrollTest {
    @get:Rule val compose = createComposeRule()

    private var height by mutableStateOf(600.dp)
    private var transcript by mutableStateOf(messages(30))

    private fun messages(n: Int) = (1L..n).map { TranscriptEntry(it, if (it % 2 == 0L) Who.HUB else Who.USER, "Message $it") }

    private fun show() = compose.setContent {
        WigginsTheme {
            Box(Modifier.fillMaxWidth().height(height)) {
                ConversationList(transcript, thinking = false, onResend = {}, modifier = Modifier.fillMaxSize()) {}
            }
        }
    }

    @Test fun theNewestMessageStaysInViewWhenTheKeyboardOpensAndCloses() {
        show()
        compose.onNodeWithText("Message 30").assertIsDisplayed()
        height = 260.dp // the keyboard takes the rest
        compose.waitForIdle()
        compose.onNodeWithText("Message 30").assertIsDisplayed()
        height = 600.dp
        compose.waitForIdle()
        compose.onNodeWithText("Message 30").assertIsDisplayed()
    }

    @Test fun aNewMessageIsFollowedAtTheBottom() {
        show()
        transcript = messages(31)
        compose.waitForIdle()
        compose.onNodeWithText("Message 31").assertIsDisplayed()
    }

    @Test fun someoneReadingEarlierMessagesIsntPulledDown() {
        show()
        compose.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.onNodeWithText("Message 30").assertIsNotDisplayed()
        transcript = messages(31)
        compose.waitForIdle()
        compose.onNodeWithText("Message 31").assertIsNotDisplayed()
    }
}
