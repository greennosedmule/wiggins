package com.mulesipstea.wiggins.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.mulesipstea.wiggins.actions.ActionDatabase
import com.mulesipstea.wiggins.actions.RuleEntity
import com.mulesipstea.wiggins.settings.ActionSettings
import com.mulesipstea.wiggins.settings.HubSettings
import com.mulesipstea.wiggins.settings.OtherHeadsets
import com.mulesipstea.wiggins.settings.SpeechMode
import com.mulesipstea.wiggins.settings.SpeechSettings
import com.mulesipstea.wiggins.ui.theme.WigginsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The permissions settings (SPEC "Permissions"), tall enough to show them whole:
 * ./gradlew :app:recordRoborazziDebug --tests '*PermissionsScreenshotTest*'
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h2400dp-xxhdpi")
class PermissionsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent { WigginsTheme(darkTheme = dark) { content() } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name-${if (dark) "dark" else "light"}.png")
    }

    @Test fun lockedSettingsLight() = shoot("settings-locked", false) {
        SettingsScreen(
            initial = HubSettings(hubUrl = "wss://hivemind.example.com", accessKey = "0123456789abcdef", password = "secret"),
            initialSpeech = SpeechSettings(
                stt = SpeechMode.HUB, tts = SpeechMode.HUB, hubSpeechAvailable = true, handsFree = true,
                otherHeadsets = OtherHeadsets.TALK_ONLY,
            ),
            onSave = { _, _ -> }, onBack = {},
        )
    }

    @Test fun whereActionsRunLight() = shoot("phone-actions-contexts", false) {
        PhoneActionsScreen(
            rules = ActionDatabase.DEFAULT_RULES.mapIndexed { i, r -> RuleEntity.of(r, i + 1L) },
            settings = ActionSettings(queries = setOf("apps.list")),
            onSaveRule = { _, _ -> }, onDeleteRule = {}, onResetRules = {}, onUnmatchedChange = {},
            onQueryEnabledChange = { _, _ -> }, onOpenLog = {}, onBack = {},
        )
    }

    @Test fun whereActionsRunDark() = shoot("phone-actions-contexts", true) {
        PhoneActionsScreen(
            rules = ActionDatabase.DEFAULT_RULES.mapIndexed { i, r -> RuleEntity.of(r, i + 1L) },
            settings = ActionSettings(),
            onSaveRule = { _, _ -> }, onDeleteRule = {}, onResetRules = {}, onUnmatchedChange = {},
            onQueryEnabledChange = { _, _ -> }, onOpenLog = {}, onBack = {},
        )
    }
}
