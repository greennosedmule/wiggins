package com.mulesipstea.wiggins.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mulesipstea.wiggins.ui.theme.WigginsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The setup banner can be dismissed; problem banners can't. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class BannerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dismissIsOffered() {
        var dismissed = 0
        compose.setContent {
            WigginsTheme { BannerRow(Banner("Setup isn't complete.", "See what's missing", isProblem = false, onDismiss = { dismissed++ }) {}) }
        }
        compose.onNodeWithContentDescription("Dismiss").performClick()
        assertEquals(1, dismissed)
    }

    @Test fun problemsHaveNoDismiss() {
        compose.setContent {
            WigginsTheme { BannerRow(Banner("Can't reach the hub.", "Retry", isProblem = true) {}) }
        }
        compose.onNodeWithContentDescription("Dismiss").assertDoesNotExist()
    }
}
