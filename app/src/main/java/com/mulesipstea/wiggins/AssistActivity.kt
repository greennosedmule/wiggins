package com.mulesipstea.wiggins

import android.Manifest
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mulesipstea.wiggins.speech.Prerequisites
import com.mulesipstea.wiggins.ui.AssistPanel
import com.mulesipstea.wiggins.ui.Banner
import com.mulesipstea.wiggins.ui.LockedHint
import com.mulesipstea.wiggins.ui.LockedSessionPanel
import com.mulesipstea.wiggins.ui.Problem
import com.mulesipstea.wiggins.ui.openAppSettings
import com.mulesipstea.wiggins.ui.theme.WigginsTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The assist gesture's target: the assistant panel over the current app, in its
 * own task. Its conversation lives only while it's on screen, so leaving it
 * closes it, except while the user's recognizer or a permission request is in front of it.
 *
 * On a locked phone the panel would start hidden behind the lock screen, where it can't
 * listen. So an invocation on a locked phone asks for the unlock first, and listens once
 * the phone is unlocked (SPEC "Lock screen"). If the user backs out, a short hint shows
 * over the lock screen, holding nothing from the conversation, then the panel closes and
 * whatever it covered, such as a ringing alarm's screen, comes back.
 *
 * The exception is a voice button on a car or headset when the user's "When locked"
 * setting allows it (SPEC "Locked, in the car"): then a locked session runs over the
 * lock screen, through the hands-free link only, showing nothing from the conversation,
 * and the panel closes when the link does.
 */
class AssistActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val assistant get() = viewModel.assistant
    private var awaitingResult = false

    /** The unlock prompt is up; being hidden behind the lock screen meanwhile isn't leaving. */
    private var awaitingUnlock = false

    /** The user backed out of unlocking: the lock-screen hint is showing instead of the panel. */
    private var lockedHint by mutableStateOf(false)

    /** A locked session is running over the lock screen; see [runLocked]. */
    private var lockedSession by mutableStateOf(false)

    /** Deciding whether a locked invocation may run locked; being stopped meanwhile isn't leaving. */
    private var deciding = false
    private var hintTimeout: Job? = null

    /** Whether this screen counts as visible for [Assistant.onForeground]; the hint doesn't. */
    private var counted = false

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
                if (lockedHint) {
                    LockedHint(onUnlock = ::retryUnlock, onClose = ::finish)
                    return@WigginsTheme
                }
                if (lockedSession) {
                    val listening by assistant.listening.collectAsStateWithLifecycle()
                    val thinking by assistant.thinking.collectAsStateWithLifecycle()
                    val speaking by assistant.speaking.collectAsStateWithLifecycle()
                    val level by assistant.inputLevel.collectAsStateWithLifecycle()
                    LockedSessionPanel(
                        listening = listening,
                        level = level,
                        thinking = thinking,
                        speaking = speaking,
                        onUnlock = { unlockThen(::leaveLocked) },
                        onClose = ::finish,
                    )
                    return@WigginsTheme
                }
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
                val startingToListen by assistant.startingToListen.collectAsStateWithLifecycle()
                val hint by assistant.hint.collectAsStateWithLifecycle()
                val asks by assistant.phoneActions.asks.collectAsStateWithLifecycle()
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
                    startingToListen = startingToListen,
                    onStopListening = assistant::finishListening,
                    hint = hint,
                    ask = asks.firstOrNull(),
                    onAnswerAsk = assistant.phoneActions::answer,
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
        enterForeground()
    }

    override fun onStop() {
        leaveForeground()
        super.onStop()
        // The panel's conversation lasts only while it's on screen.
        if (!awaitingResult && !awaitingUnlock && !deciding && !isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        if (isFinishing) assistant.closePanel()
        super.onDestroy()
    }

    private fun handleLaunch(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND -> {
                val fromKeyboard = intent.getBooleanExtra(Intent.EXTRA_ASSIST_INPUT_HINT_KEYBOARD, false)
                val keyguard = getSystemService(KeyguardManager::class.java)
                if (keyguard.isKeyguardLocked && !lockedSession) {
                    deciding = true
                    lifecycleScope.launch {
                        val locked = !fromKeyboard && assistant.mayRunLocked()
                        deciding = false
                        if (locked) runLocked() else unlockThen { viewModel.onAssistInvoked(fromKeyboard) }
                    }
                } else {
                    viewModel.onAssistInvoked(fromKeyboard)
                }
            }
        }
    }

    /**
     * Runs the invocation as a locked session: shown over the lock screen, waking the screen
     * and keeping it on while the session lasts, so the screen timing out doesn't end it.
     * It ends when the hands-free link closes, or falls back to the unlock prompt if the
     * link doesn't come up.
     */
    private fun runLocked() {
        lockedSession = true
        assistant.startLockedSession()
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch {
            assistant.linkOpen.dropWhile { !it }.first { !it }
            if (lockedSession) finish()
        }
        lifecycleScope.launch {
            assistant.unlockRequests.first()
            if (!lockedSession) return@launch
            leaveLocked()
            unlockThen { viewModel.onAssistInvoked(false) }
        }
        viewModel.onAssistInvoked(false)
    }

    /** The locked session becomes an ordinary panel (after the unlock), or gives way to the unlock prompt. */
    private fun leaveLocked() {
        if (!lockedSession) return
        lockedSession = false
        assistant.endLockedSession()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setTurnScreenOn(false)
        setShowWhenLocked(false)
    }

    private fun enterForeground() {
        if (counted || lockedHint) return
        counted = true
        assistant.onForeground()
    }

    private fun leaveForeground() {
        if (!counted) return
        counted = false
        assistant.onBackground()
    }

    /** What to do once the phone is unlocked: the invocation that found it locked. */
    private var afterUnlock: (() -> Unit)? = null

    /** Asks Android to show the unlock prompt (fingerprint, PIN) and runs [then] once the phone is unlocked. */
    private fun unlockThen(then: () -> Unit) {
        afterUnlock = then
        requestUnlock()
    }

    private fun requestUnlock() {
        if (awaitingUnlock) return
        awaitingUnlock = true
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = unlocked()

                override fun onDismissCancelled() = unlockFailed()

                override fun onDismissError() = unlockFailed()
            },
        )
    }

    private fun unlocked() {
        awaitingUnlock = false
        assistant.noteUnlocked()
        hintTimeout?.cancel()
        if (lockedHint) {
            lockedHint = false
            setShowWhenLocked(false)
        }
        enterForeground()
        afterUnlock?.invoke()
        afterUnlock = null
    }

    /**
     * Shows the hint over the lock screen, briefly. A toast can't do it: Android drops toasts
     * from an app whose notifications are off, which Wiggins' are unless the user turned them on.
     */
    private fun unlockFailed() {
        awaitingUnlock = false
        lockedHint = true
        setShowWhenLocked(true)
        hintTimeout?.cancel()
        hintTimeout = lifecycleScope.launch {
            delay(LOCKED_HINT_MS)
            finish()
        }
    }

    private fun retryUnlock() {
        hintTimeout?.cancel()
        requestUnlock()
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

        /** How long the lock-screen hint stays before the panel closes by itself. */
        const val LOCKED_HINT_MS = 5_000L
    }
}
