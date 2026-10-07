package com.mulesipstea.wiggins

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mulesipstea.wiggins.speech.Prerequisites
import com.mulesipstea.wiggins.ui.AssistPanel
import com.mulesipstea.wiggins.ui.Banner
import com.mulesipstea.wiggins.ui.Problem
import com.mulesipstea.wiggins.ui.openAppSettings
import com.mulesipstea.wiggins.ui.theme.WigginsTheme
import kotlinx.coroutines.launch

/**
 * The assist gesture's target: the assistant panel over the current app, in its
 * own task. Its conversation lives only while it's on screen, so leaving it
 * closes it, except while the user's recognizer or a permission request is in front of it.
 */
class AssistActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val assistant get() = viewModel.assistant
    private var awaitingResult = false

    private val recognize = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        awaitingResult = false
        assistant.onRecognized(result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull())
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        awaitingResult = false
        assistant.onMicPermission(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The volume keys adjust Wiggins' voice, even between sentences.
        volumeControlStream = AudioManager.STREAM_MUSIC
        assistant.openPanel()
        setContent {
            WigginsTheme {
                val connection by assistant.connection.collectAsStateWithLifecycle()
                val panel by assistant.panelTranscript.collectAsStateWithLifecycle()
                val transcript = panel?.collectAsStateWithLifecycle()?.value.orEmpty()
                val problem by assistant.problem.collectAsStateWithLifecycle()
                val thinking by assistant.thinking.collectAsStateWithLifecycle()
                val speaking by assistant.speaking.collectAsStateWithLifecycle()
                val speakReplies by assistant.speakReplies.collectAsStateWithLifecycle()
                val canListen by assistant.canListen.collectAsStateWithLifecycle()
                val listening by assistant.listening.collectAsStateWithLifecycle()
                val level by assistant.inputLevel.collectAsStateWithLifecycle()
                val hint by assistant.hint.collectAsStateWithLifecycle()
                AssistPanel(
                    connection = connection,
                    transcript = transcript,
                    thinking = thinking,
                    speaking = speaking,
                    speakReplies = speakReplies,
                    onStopSpeaking = assistant::stopSpeaking,
                    onSpeakRepliesChange = assistant::setSpeakReplies,
                    banner = problem?.let { p ->
                        when (p.fix) {
                            Problem.Fix.SETTINGS -> Banner(p.message, "Open app", isProblem = true, ::openApp)
                            Problem.Fix.RETRY -> Banner(p.message, "Retry", isProblem = true, assistant::retry)
                            Problem.Fix.DEVICE_SPEECH -> Banner(p.message, "Use device speech", isProblem = true, assistant::useDeviceSpeech)
                            Problem.Fix.MIC_PERMISSION -> Banner(p.message, "Allow", isProblem = true) { openAppSettings() }
                        }
                    },
                    canListen = canListen,
                    onSend = assistant::send,
                    onResend = assistant::resend,
                    onListen = assistant::listen,
                    onOpenApp = ::openApp,
                    onClosed = ::finish,
                    listening = listening,
                    level = level,
                    onStopListening = assistant::finishListening,
                    hint = hint,
                )
            }
        }
        lifecycleScope.launch {
            // RESUMED, not STARTED: whichever Wiggins screen is in front starts the recognizer.
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch { assistant.listenRequests.collect { startRecognizer() } }
                assistant.micPermissionRequests.collect {
                    awaitingResult = true
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
        if (savedInstanceState == null) handleLaunch(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunch(intent)
    }

    override fun onStart() {
        super.onStart()
        assistant.onForeground()
    }

    override fun onStop() {
        assistant.onBackground()
        super.onStop()
        // The panel's conversation lasts only while it's on screen.
        if (!awaitingResult && !isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        if (isFinishing) assistant.closePanel()
        super.onDestroy()
    }

    private fun handleLaunch(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND ->
                viewModel.onAssistInvoked(fromKeyboard = intent.getBooleanExtra(Intent.EXTRA_ASSIST_INPUT_HINT_KEYBOARD, false))
        }
    }

    private fun startRecognizer() {
        try {
            awaitingResult = true
            recognize.launch(Prerequisites.recognizeIntent())
            assistant.onRecognizerStarted()
        } catch (e: ActivityNotFoundException) {
            awaitingResult = false
            Log.w(TAG, "no speech recognizer", e)
            assistant.refreshPrerequisites()
        }
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private companion object {
        const val TAG = "AssistActivity"
    }
}
