package com.mulesipstea.wiggins.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.mulesipstea.wiggins.R
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.mulesipstea.wiggins.hivemind.ConnectionState
import com.mulesipstea.wiggins.settings.AuthMode
import com.mulesipstea.wiggins.settings.HubSettings
import com.mulesipstea.wiggins.settings.SpeechMode
import com.mulesipstea.wiggins.settings.SpeechSettings
import com.mulesipstea.wiggins.speech.Prerequisites
import com.mulesipstea.wiggins.ui.theme.WigginsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the main screens to PNGs for design review, in light and dark:
 * ./gradlew :app:recordRoborazziDebug --tests '*ScreenshotTest*'
 * (images in app/build/outputs/roborazzi).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val conversation = listOf(
        TranscriptEntry(1, Who.USER, "What time is it?"),
        TranscriptEntry(2, Who.HUB, "It's nine forty six."),
        TranscriptEntry(3, Who.USER, "Set an alarm"),
        TranscriptEntry(4, Who.HUB, "For what time?"),
        TranscriptEntry(5, Who.USER, "Six thirty in the morning"),
        TranscriptEntry(6, Who.HUB, "Your alarm is set for 6:30 AM tomorrow."),
        TranscriptEntry(7, Who.USER, "Tell me about Sherlock Holmes", Delivery.NOT_SENT),
    )

    private fun shoot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent { WigginsTheme(darkTheme = dark) { content() } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name-${if (dark) "dark" else "light"}.png")
    }

    private fun chat(name: String, dark: Boolean, transcript: List<TranscriptEntry>, thinking: Boolean = false, banner: Banner? = null, connection: ConnectionState = ConnectionState.Connected("hub")) =
        shoot(name, dark) {
            ChatScreen(
                connection = connection, transcript = transcript, thinking = thinking, banner = banner,
                speaking = false, speakReplies = true, onStopSpeaking = {}, onSpeakRepliesChange = {},
                onSend = {}, onResend = {}, onClearConversation = {}, canListen = true, onListen = {},
                onOpenSetup = {}, onOpenSettings = {}, onOpenMessages = {}, onOpenAbout = {},
            )
        }

    @Test fun chatLight() = chat("chat", false, conversation, thinking = true)
    @Test fun chatDark() = chat("chat", true, conversation, thinking = true)
    @Test fun streamingLight() = chat(
        "streaming", false,
        listOf(
            TranscriptEntry(1, Who.USER, "Tell me about honeybees"),
            TranscriptEntry(2, Who.HUB, "Honeybees live in colonies of up to sixty thousand. Each colony has a single queen.", streaming = true),
        ),
    )

    @Test fun emptyLight() = chat("empty", false, emptyList())
    @Test fun emptyDark() = chat("empty", true, emptyList())

    @Test fun problemLight() = chat(
        "problem", false, conversation,
        banner = Banner("Can't reach the hub. Check your network, or your VPN if the hub is only reachable through it.", "Retry", isProblem = true) {},
        connection = ConnectionState.Failed("x", retryable = true),
    )

    @Test fun setupBannerDark() = chat("setup-banner", true, conversation.take(2), banner = Banner("Setup isn't complete.", "See what's missing", isProblem = false) {})

    private fun panel(dark: Boolean, transcript: List<TranscriptEntry>, thinking: Boolean) = shoot("panel${if (transcript.isEmpty()) "-empty" else ""}", dark) {
        // A stand-in for the app underneath.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF3A6EA5), Color(0xFF9BC4E2))))) {
            AssistPanel(
                connection = ConnectionState.Connected("hub"), transcript = transcript, thinking = thinking, banner = null,
                speaking = !thinking && transcript.isNotEmpty(), speakReplies = !thinking, onStopSpeaking = {}, onSpeakRepliesChange = {},
                canListen = true, onSend = {}, onResend = {}, onListen = {}, onOpenApp = {}, onClosed = {}, startVisible = true,
            )
        }
    }

    @Test fun panelLight() = panel(false, conversation.take(4), thinking = false)
    @Test fun panelDark() = panel(true, conversation.take(3), thinking = true)
    @Test fun panelEmptyLight() = panel(false, emptyList(), thinking = false)

    /** The launcher icon as a circular mask shows it: the 72dp middle of the 108dp layers. */
    @Test fun launcherIcon() = shoot("launcher-icon", false) {
        Box(Modifier.fillMaxSize().background(Color(0xFFEEEEEE)), contentAlignment = Alignment.Center) {
            Box(Modifier.size(216.dp).clip(CircleShape).background(colorResource(R.color.ic_launcher_background)), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.requiredSize(324.dp))
            }
        }
    }

    @Test fun setupLight() = shoot("setup", false) {
        SetupScreen(
            prerequisites = Prerequisites(recognizer = "FUTO Voice Input", ttsEngines = listOf("RHVoice"), isAssistant = false),
            speech = SpeechSettings(stt = SpeechMode.HUB, tts = SpeechMode.DEVICE, hubSpeechAvailable = true),
            checkingHubSpeech = false, onCheckHubSpeech = {},
            hubConfigured = true, connection = ConnectionState.Connected("hub"), onOpenHubSettings = {}, onBack = {},
        )
    }

    @Test fun settingsDark() = shoot("settings", true) {
        SettingsScreen(
            initial = HubSettings(
                hubUrl = "wss://hivemind.example.com", accessKey = "0123456789abcdef", password = "secret",
                authMode = AuthMode.SIGN_IN, signInIssuer = "https://login.microsoftonline.com/tenant/v2.0",
                signInClientId = "00000000-0000-0000-0000-000000000000", signInScope = "api://example/hivemind",
            ),
            initialSpeech = SpeechSettings(stt = SpeechMode.HUB, tts = SpeechMode.HUB, hubSpeechAvailable = true),
            onSave = { _, _ -> }, onBack = {},
        )
    }

    @Test fun listeningLight() = shoot("listening", false) {
        ChatScreen(
            connection = ConnectionState.Connected("hub"),
            transcript = conversation.take(2) + TranscriptEntry(8, Who.USER, "", Delivery.TRANSCRIBING),
            thinking = false, speaking = false, speakReplies = true, onStopSpeaking = {}, onSpeakRepliesChange = {},
            banner = Banner("Hub speech isn't answering.", "Use device speech", isProblem = true) {},
            onSend = {}, onResend = {}, onClearConversation = {}, canListen = true, onListen = {},
            onOpenSetup = {}, onOpenSettings = {}, onOpenMessages = {}, onOpenAbout = {},
            listening = true, level = 0.7f,
        )
    }
}
