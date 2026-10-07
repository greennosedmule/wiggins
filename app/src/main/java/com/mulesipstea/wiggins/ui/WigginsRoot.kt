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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mulesipstea.wiggins.MainViewModel
import com.mulesipstea.wiggins.ui.theme.WigginsTheme

enum class Screen { CHAT, SETTINGS, MESSAGES, SETUP, ABOUT, ACTIONS, ACTION_LOG }

@Composable
fun WigginsRoot(viewModel: MainViewModel) {
    WigginsTheme {
        var screen by rememberSaveable { mutableStateOf(Screen.CHAT) }
        BackHandler(enabled = screen != Screen.CHAT) { screen = if (screen == Screen.ACTION_LOG) Screen.ACTIONS else Screen.CHAT }

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
        val actions = assistant.phoneActions
        val asks by actions.asks.collectAsStateWithLifecycle()
        val rules by actions.rules.collectAsStateWithLifecycle()
        val actionSettings by actions.actionSettings.collectAsStateWithLifecycle()
        val actionLog by actions.log.collectAsStateWithLifecycle()
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

        Box(Modifier.fillMaxSize()) {
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
                    ask = asks.firstOrNull(),
                    onAnswerAsk = actions::answer,
                    onOpenActions = { screen = Screen.ACTIONS },
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
                Screen.ACTIONS -> PhoneActionsScreen(
                    rules = rules,
                    settings = actionSettings,
                    onSaveRule = { rule, id -> actions.saveRule(rule, id) },
                    onDeleteRule = { actions.deleteRule(it) },
                    onResetRules = { actions.resetRules() },
                    onUnmatchedChange = { actions.setUnmatched(it) },
                    onQueryEnabledChange = { name, on -> actions.setQueryEnabled(name, on) },
                    onOpenLog = { screen = Screen.ACTION_LOG },
                    onBack = { screen = Screen.CHAT },
                )
                Screen.ACTION_LOG -> ActionLogScreen(
                    entries = actionLog,
                    onClear = { actions.clearLog() },
                    onBack = { screen = Screen.ACTIONS },
                )
                Screen.MESSAGES -> MessagesScreen(
                    messages = log,
                    onClear = assistant::clearLog,
                    onBack = { screen = Screen.CHAT },
                )
            }
            // The hub is waiting for an answer, whatever screen is open (the chat shows it inline).
            if (screen != Screen.CHAT) {
                asks.firstOrNull()?.let { a ->
                    AskCard(
                        a,
                        onAnswer = { allow, always -> actions.answer(a.key, allow, always) },
                        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 8.dp),
                    )
                }
            }
        }
    }
}


/** The app's page in system settings, where a denied permission can be allowed. */
internal fun Context.openAppSettings() {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
