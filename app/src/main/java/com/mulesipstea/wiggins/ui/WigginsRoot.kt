package com.mulesipstea.wiggins.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mulesipstea.wiggins.MainViewModel
import com.mulesipstea.wiggins.ui.theme.WigginsTheme

enum class Screen { CHAT, SETTINGS, MESSAGES, SETUP, ABOUT }

@Composable
fun WigginsRoot(viewModel: MainViewModel) {
    WigginsTheme {
        var screen by rememberSaveable { mutableStateOf(Screen.CHAT) }
        BackHandler(enabled = screen != Screen.CHAT) { screen = Screen.CHAT }

        val assistant = viewModel.assistant
        val connection by assistant.connection.collectAsStateWithLifecycle()
        val transcript by assistant.transcript.collectAsStateWithLifecycle()
        val settings by assistant.settings.collectAsStateWithLifecycle()
        val log by assistant.log.collectAsStateWithLifecycle()
        val prerequisites by assistant.prerequisites.collectAsStateWithLifecycle()
        val problem by assistant.problem.collectAsStateWithLifecycle()
        val thinking by assistant.thinking.collectAsStateWithLifecycle()
        val speaking by assistant.speaking.collectAsStateWithLifecycle()
        val speakReplies by assistant.speakReplies.collectAsStateWithLifecycle()
        val speech by assistant.speech.collectAsStateWithLifecycle()
        val canListen by assistant.canListen.collectAsStateWithLifecycle()
        val listening by assistant.listening.collectAsStateWithLifecycle()
        val level by assistant.inputLevel.collectAsStateWithLifecycle()
        val hint by assistant.hint.collectAsStateWithLifecycle()
        val checkingHubSpeech by assistant.checkingHubSpeech.collectAsStateWithLifecycle()
        val context = LocalContext.current
        val hubConfigured = settings?.isComplete == true

        // At most one banner: what's broken now, else what's left to set up.
        val banner = problem?.let { p ->
            when (p.fix) {
                Problem.Fix.SETTINGS -> Banner(p.message, "Settings", isProblem = true) { screen = Screen.SETTINGS }
                Problem.Fix.RETRY -> Banner(p.message, "Retry", isProblem = true, assistant::retry)
                Problem.Fix.DEVICE_SPEECH -> Banner(p.message, "Use device speech", isProblem = true, assistant::useDeviceSpeech)
                Problem.Fix.MIC_PERMISSION -> Banner(p.message, "Allow", isProblem = true) { context.openAppSettings() }
            }
        } ?: if (settings != null && (!prerequisites.speechReady(speech) || !prerequisites.isAssistant)) {
            Banner("Setup isn't complete.", "See what's missing", isProblem = false) { screen = Screen.SETUP }
        } else {
            null
        }

        when (screen) {
            Screen.CHAT -> ChatScreen(
                connection = connection,
                transcript = transcript,
                thinking = thinking,
                speaking = speaking,
                speakReplies = speakReplies,
                onStopSpeaking = assistant::stopSpeaking,
                onSpeakRepliesChange = assistant::setSpeakReplies,
                banner = banner,
                onSend = assistant::send,
                onResend = assistant::resend,
                onClearConversation = assistant::clearConversation,
                canListen = canListen,
                onListen = assistant::listen,
                listening = listening,
                level = level,
                onStopListening = assistant::finishListening,
                onCancelListening = assistant::cancelListening,
                hint = hint,
                onOpenSetup = { screen = Screen.SETUP },
                onOpenSettings = { screen = Screen.SETTINGS },
                onOpenMessages = { screen = Screen.MESSAGES },
                onOpenAbout = { screen = Screen.ABOUT },
            )
            Screen.SETTINGS -> SettingsScreen(
                initial = settings,
                initialSpeech = speech,
                onSave = { hub, newSpeech -> assistant.saveSettings(hub, newSpeech); screen = Screen.CHAT },
                onBack = { screen = Screen.CHAT },
            )
            Screen.SETUP -> SetupScreen(
                prerequisites = prerequisites,
                speech = speech,
                checkingHubSpeech = checkingHubSpeech,
                onCheckHubSpeech = assistant::checkHubSpeech,
                hubConfigured = hubConfigured,
                connection = connection,
                onOpenHubSettings = { screen = Screen.SETTINGS },
                onBack = { screen = Screen.CHAT },
            )
            Screen.ABOUT -> AboutScreen(onBack = { screen = Screen.CHAT })
            Screen.MESSAGES -> MessagesScreen(
                messages = log,
                onClear = assistant::clearLog,
                onBack = { screen = Screen.CHAT },
            )
        }
    }
}


/** The app's page in system settings, where a denied permission can be allowed. */
internal fun Context.openAppSettings() {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
