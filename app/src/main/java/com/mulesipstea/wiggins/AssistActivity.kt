package com.mulesipstea.wiggins

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
import com.mulesipstea.wiggins.ui.theme.WigginsTheme
import kotlinx.coroutines.launch

/**
 * The assist gesture's target: the assistant panel over the current app, in its
 * own task. Its conversation lives only while it's on screen, so leaving it
 * closes it, except while the user's recognizer is in front of it.
 */
class AssistActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val assistant get() = viewModel.assistant
    private var awaitingRecognizer = false

    private val recognize = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        awaitingRecognizer = false
        assistant.onRecognized(result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull())
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
                val prerequisites by assistant.prerequisites.collectAsStateWithLifecycle()
                val thinking by assistant.thinking.collectAsStateWithLifecycle()
                val speaking by assistant.speaking.collectAsStateWithLifecycle()
                val speakReplies by assistant.speakReplies.collectAsStateWithLifecycle()
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
                        }
                    },
                    canListen = prerequisites.recognizer != null,
                    onSend = assistant::send,
                    onResend = assistant::resend,
                    onListen = assistant::listen,
                    onOpenApp = ::openApp,
                    onClosed = ::finish,
                )
            }
        }
        lifecycleScope.launch {
            // RESUMED, not STARTED: whichever Wiggins screen is in front starts the recognizer.
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                assistant.listenRequests.collect { startRecognizer() }
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
        if (!awaitingRecognizer && !isChangingConfigurations) finish()
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
            awaitingRecognizer = true
            recognize.launch(Prerequisites.recognizeIntent())
            assistant.onRecognizerStarted()
        } catch (e: ActivityNotFoundException) {
            awaitingRecognizer = false
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
