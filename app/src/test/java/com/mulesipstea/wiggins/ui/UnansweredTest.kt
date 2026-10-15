package com.mulesipstea.wiggins.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mulesipstea.wiggins.ui.theme.WigginsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A question whose answer a dropped connection lost says so, and can be asked again (SPEC "Conversation screen"). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class UnansweredTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aLostAnswerOffersToAskAgain() {
        val resent = mutableListOf<Long>()
        compose.setContent {
            WigginsTheme {
                ConversationList(
                    listOf(TranscriptEntry(7, Who.USER, "what is on my calendar today", Delivery.UNANSWERED)),
                    thinking = false, onResend = { resent += it },
                ) {}
            }
        }
        compose.onNodeWithText("No answer: the connection changed · Ask again").performClick()
        assertEquals(listOf(7L), resent)
    }
}
