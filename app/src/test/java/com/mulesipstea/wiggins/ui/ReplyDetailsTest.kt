package com.mulesipstea.wiggins.ui

import android.app.Application
import android.content.ClipboardManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mulesipstea.wiggins.hivemind.ConnectionState
import com.mulesipstea.wiggins.turns.SampleTurns
import com.mulesipstea.wiggins.turns.TurnDetails
import com.mulesipstea.wiggins.ui.theme.WigginsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The reply details sheet, opened by long-pressing a bubble (SPEC "Reply details"). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ReplyDetailsTest {
    @get:Rule val compose = createComposeRule()

    private fun chat(transcript: List<TranscriptEntry>) = compose.setContent {
        WigginsTheme {
            ChatScreen(
                connection = ConnectionState.Connected("hub"), transcript = transcript,
                thinking = false, speaking = false, speakReplies = true, onStopSpeaking = {}, onSpeakRepliesChange = {},
                banner = null, onSend = {}, onResend = {}, onClearConversation = {}, canListen = true, onListen = {},
                onOpenSetup = {}, onOpenSettings = {}, onOpenMessages = {}, onOpenAbout = {},
            )
        }
    }

    private fun details(d: TurnDetails?) = compose.setContent { WigginsTheme { ReplyDetails(d) } }

    @Test fun longPressingAReplyShowsItsQuestionsDetails() {
        chat(listOf(
            TranscriptEntry(1, Who.USER, "what time is it", details = SampleTurns.skill),
            TranscriptEntry(2, Who.HUB, "It's nine forty six.", turn = 1),
        ))
        compose.onNodeWithTag("bubble-2", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithText("Reply details").assertExists()
        compose.onNodeWithText("date time").assertExists()
        compose.onNodeWithText("Intent (Padatious)").assertExists()
    }

    @Test fun copyTextCopiesThePressedBubble() {
        chat(listOf(
            TranscriptEntry(1, Who.USER, "what time is it", details = SampleTurns.skill),
            TranscriptEntry(2, Who.HUB, "It's nine forty six.", turn = 1),
        ))
        compose.onNodeWithTag("bubble-2", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithText("Copy text").performSemanticsAction(SemanticsActions.OnClick)
        val clip = ApplicationProvider.getApplicationContext<Application>().getSystemService(ClipboardManager::class.java).primaryClip
        assertEquals("It's nine forty six.", clip?.getItemAt(0)?.text?.toString())
    }

    @Test fun anOldBubbleCanStillBeCopied() {
        chat(listOf(TranscriptEntry(1, Who.HUB, "Hello.")))
        compose.onNodeWithTag("bubble-1", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithText("Copy text").assertIsEnabled()
        compose.onAllNodesWithText("Copy details").assertCountEquals(0)
    }

    @Test fun aBubbleFromBeforeSaysNothingWasKept() {
        chat(listOf(TranscriptEntry(1, Who.HUB, "Hello.")))
        compose.onNodeWithTag("bubble-1", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithText("No details were kept", substring = true).assertExists()
    }

    @Test fun aQuestionNotSentSaysSo() {
        chat(listOf(TranscriptEntry(1, Who.USER, "what time is it", Delivery.NOT_SENT)))
        compose.onNodeWithTag("bubble-1", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithText("hasn't reached the hub", substring = true).assertExists()
    }

    @Test fun aQuietTurnShowsTheActionAndNoReply() {
        details(SampleTurns.quietWaggle)
        compose.onNodeWithText("None was spoken").assertExists()
        compose.onNodeWithText("SHOW_ALARMS: ok").assertExists()
    }

    @Test fun aPersonaTurnWithoutTheHubsReportSaysSo() {
        details(SampleTurns.personaNoReport)
        compose.onNodeWithText("Ada").assertExists()
        compose.onNodeWithText("Not reported by the hub").assertExists()
    }

    @Test fun tappingARowShowsItsNumbers() {
        details(SampleTurns.persona)
        // Below the test screen's fold, so by its action rather than a touch.
        compose.onNodeWithContentDescription("Ada 1: 1.5 s").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("2.8 s → 4.2 s (1.5 s)", substring = true).assertExists()
    }
}
