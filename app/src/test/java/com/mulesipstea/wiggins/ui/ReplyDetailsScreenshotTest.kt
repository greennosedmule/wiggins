package com.mulesipstea.wiggins.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.mulesipstea.wiggins.turns.SampleTurns
import com.mulesipstea.wiggins.turns.TurnDetails
import com.mulesipstea.wiggins.ui.theme.WigginsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The reply details sheet's content, tall enough to show it whole, in light and dark:
 * ./gradlew :app:recordRoborazziDebug --tests '*ReplyDetailsScreenshotTest*'
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h1100dp-xxhdpi")
class ReplyDetailsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, dark: Boolean, details: TurnDetails?) {
        compose.setContent {
            WigginsTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) { ReplyDetails(details) }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/details-$name-${if (dark) "dark" else "light"}.png")
    }

    @Test fun personaLight() = shoot("persona", false, SampleTurns.persona)
    @Test fun personaDark() = shoot("persona", true, SampleTurns.persona)
    @Test fun personaCostLight() = shoot("persona-cost", false,
        SampleTurns.persona.copy(hub = SampleTurns.persona.hub!!.copy(cost = 0.0213, currency = "USD", pricing = "list price")))
    @Test fun skillLight() = shoot("skill", false, SampleTurns.skill)
    @Test fun skillDark() = shoot("skill", true, SampleTurns.skill)
    @Test fun quietWaggleLight() = shoot("quiet-waggle", false, SampleTurns.quietWaggle)
    @Test fun quietWaggleDark() = shoot("quiet-waggle", true, SampleTurns.quietWaggle)
    @Test fun noHubReportLight() = shoot("no-hub-report", false, SampleTurns.personaNoReport)
    @Test fun noHubReportDark() = shoot("no-hub-report", true, SampleTurns.personaNoReport)
    @Test fun notKeptLight() = shoot("not-kept", false, null)
}
