package com.mulesipstea.wiggins.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
        val hubConfigured = settings?.isComplete == true

        // At most one banner: what's broken now, else what's left to set up.
        val banner = problem?.let { p ->
            when (p.fix) {
                Problem.Fix.SETTINGS -> Banner(p.message, "Settings", isProblem = true) { screen = Screen.SETTINGS }
                Problem.Fix.RETRY -> Banner(p.message, "Retry", isProblem = true, assistant::retry)
            }
        } ?: if (settings != null && (!prerequisites.speechReady || !prerequisites.isAssistant)) {
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
                canListen = prerequisites.recognizer != null,
                onListen = assistant::listen,
                onOpenSetup = { screen = Screen.SETUP },
                onOpenSettings = { screen = Screen.SETTINGS },
                onOpenMessages = { screen = Screen.MESSAGES },
                onOpenAbout = { screen = Screen.ABOUT },
            )
            Screen.SETTINGS -> SettingsScreen(
                initial = settings,
                onSave = { assistant.saveSettings(it); screen = Screen.CHAT },
                onBack = { screen = Screen.CHAT },
            )
            Screen.SETUP -> SetupScreen(
                prerequisites = prerequisites,
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

