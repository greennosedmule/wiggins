package com.mulesipstea.wiggins.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.mulesipstea.wiggins.ui.theme.WigginsTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.PI
import kotlin.math.sin

/**
 * Frames of Wiggins blowing bubbles, for reviewing the animation:
 * ./gradlew :app:recordRoborazziDebug --tests '*AvatarAnimationTest*'
 * then join the PNGs in app/build/outputs/roborazzi/avatar-frames into a GIF.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w240dp-h240dp-xxhdpi")
class AvatarAnimationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun blowingFrames() {
        // 144 images: only when recording screenshots, not on every test run.
        assumeTrue(System.getProperty("roborazzi.test.record") == "true")
        compose.mainClock.autoAdvance = false
        compose.setContent { WigginsTheme { WigginsAvatar(Modifier.size(200.dp), blowing = true) } }
        // One full cycle (4 bubbles x 2400 ms) at 15 frames per second.
        repeat(144) { frame ->
            compose.mainClock.advanceTimeBy(66)
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/avatar-frames/%03d.png".format(frame))
        }
    }

    @Test fun listeningFrames() {
        assumeTrue(System.getProperty("roborazzi.test.record") == "true")
        compose.mainClock.autoAdvance = false
        var level by mutableFloatStateOf(0f)
        compose.setContent { WigginsTheme { ListeningWiggins(listening = true, starting = false, connecting = false, level = level, modifier = Modifier.size(220.dp)) } }
        // Two breaths (2 x 1200 ms) at 15 frames per second: silence, then a voice rising and falling.
        repeat(36) { frame ->
            level = if (frame < 12) 0f else (0.9f * sin((frame - 12) / 24.0 * PI)).toFloat()
            compose.mainClock.advanceTimeBy(66)
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/listening-frames/%03d.png".format(frame))
        }
    }
}
