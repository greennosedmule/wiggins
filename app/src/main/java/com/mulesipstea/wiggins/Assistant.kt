package com.mulesipstea.wiggins

import android.app.KeyguardManager
import android.icu.util.LocaleData
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.icu.util.ULocale
import android.text.format.DateFormat
import android.util.Log
import com.mulesipstea.wiggins.actions.ActionDatabase
import com.mulesipstea.wiggins.actions.PhoneActions
import com.mulesipstea.wiggins.speech.YesNo
import com.mulesipstea.wiggins.actions.Notifications
import com.mulesipstea.wiggins.hivemind.ConnectionState
import com.mulesipstea.wiggins.hivemind.HiveMindClient
import com.mulesipstea.wiggins.hivemind.HubEvent
import com.mulesipstea.wiggins.hivemind.Noise
import com.mulesipstea.wiggins.hivemind.StoredNoiseKeys
import com.mulesipstea.wiggins.hivemind.SessionContext
import com.mulesipstea.wiggins.net.ClientCertificateException
import com.mulesipstea.wiggins.net.ClientCertificates
import com.mulesipstea.wiggins.net.EntraSignIn
import com.mulesipstea.wiggins.net.SignInException
import com.mulesipstea.wiggins.settings.ActionSettings
import com.mulesipstea.wiggins.settings.AuthMode
import com.mulesipstea.wiggins.settings.HubSettings
import com.mulesipstea.wiggins.settings.HubUrl
import com.mulesipstea.wiggins.settings.PersonaPermissions
import com.mulesipstea.wiggins.settings.PhoneContext
import com.mulesipstea.wiggins.settings.ToolCatalog
import com.mulesipstea.wiggins.settings.SpeechMode
import com.mulesipstea.wiggins.settings.SpeechSettings
import com.mulesipstea.wiggins.speech.AudioOutput
import com.mulesipstea.wiggins.speech.HandsFree
import com.mulesipstea.wiggins.speech.HubSpeaker
import com.mulesipstea.wiggins.speech.LinkKeeper
import com.mulesipstea.wiggins.speech.ListeningCue
import com.mulesipstea.wiggins.speech.PcmPlayer
import com.mulesipstea.wiggins.speech.Prerequisites
import com.mulesipstea.wiggins.speech.Recorder
import com.mulesipstea.wiggins.speech.SetupItem
import com.mulesipstea.wiggins.speech.Speaker
import com.mulesipstea.wiggins.speech.SpeechRoute
import com.mulesipstea.wiggins.speech.Transcription
import com.mulesipstea.wiggins.speech.WebRtcDetector
import com.mulesipstea.wiggins.turns.TurnRecorder
import com.mulesipstea.wiggins.ui.Delivery
import com.mulesipstea.wiggins.ui.LoggedMessage
import com.mulesipstea.wiggins.ui.Problem
import com.mulesipstea.wiggins.ui.TranscriptEntry
import com.mulesipstea.wiggins.ui.Who
import com.mulesipstea.wiggins.waggle.Waggle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * The assistant itself, owned by the application rather than a screen: the hub
 * connection, the conversation, speech and follow-ups. Screens come and go;
 * this outlives them, so a duplicate activity can't open a second connection,
 * and Waggle requests (M5) can outlive the UI.
 *
 * Main-thread confined: call it from the UI; hub callbacks are hopped onto [scope].
 */
class Assistant(private val app: WigginsApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** The Bluetooth hands-free link to a car or headset, while a turn is going on (SPEC "In the car"). */
    private val handsFree = HandsFree(app)
    private val linkKeeper = LinkKeeper(scope, LINK_GRACE_MS) {
        handsFree.close()
        _linkOpen.value = false
    }
    private val _linkOpen = MutableStateFlow(false)

    /** The hands-free link is up. */
    val linkOpen: StateFlow<Boolean> = _linkOpen.asStateFlow()

    /** Which hands-free devices may carry a session on a locked phone (SPEC "Permissions"). */
    private val lockedSessions get() = app.lockedSessions

    /**
     * A locked voice session is running: the phone stays locked, and Wiggins listens and
     * speaks over the hands-free link only, runs no phone actions and tells the hub.
     */
    private val _locked = MutableStateFlow(false)
    private val _unlockRequests = Channel<Unit>(Channel.CONFLATED)

    /** Each element asks the locked panel to fall back to the unlock prompt: the link didn't come up. */
    val unlockRequests: Flow<Unit> = _unlockRequests.receiveAsFlow()

    /** The user's recognizer is listening (device speech-to-text). */
    private val recognizing = MutableStateFlow(false)

    /** Listening is starting: waiting for the panel's screen, the link or the tone. */
    private val starting = MutableStateFlow(false)
    private val speaker = Speaker(app) { handsFree.route().playback.takeIf { handsFree.isOpen } ?: Speaker.DEFAULT_ATTRIBUTES }
    private val client = HiveMindClient(app.http, scope)
    private val recorder = Recorder(app, scope, ::WebRtcDetector)
    /** Waggle: the hub's requests to launch intents and run queries, within the user's rules. */
    val phoneActions = PhoneActions(
        app, scope, ActionDatabase.open(app), app.settings,
        isForeground = { foreground },
        phoneContext = { phoneContext() },
        notifications = Notifications(app),
        speak = { lines -> speakAndWait(lines) },
        confirmByVoice = { question -> confirmByVoice(question) },
    )

    /** The spoken yes or no being transcribed, which never becomes an utterance. */
    private var confirming: Transcription? = null
    private val hubSpeaker = HubSpeaker(
        scope,
        request = { id, text -> client.sendSynthesize(id, text, sessionContext()) },
        play = { wav -> PcmPlayer.play(wav, handsFree.route()) },
        fallback = { text -> speaker.speakAndWait(text) },
        onHubAnswered = ::onHubSpeechAnswered,
        newId = ::newRequestId,
    )
    /** Tells the hub when a reply is being read aloud, which skills speaking with wait=True wait for. */
    private val audioOutput = AudioOutput { type -> client.sendBus(type, sessionContext()) }
    private val saved = ConversationStore(File(app.filesDir, "conversation.json"), scope)

    /** Where phone actions run, for the flags every message carries. */
    private val actionSettings = app.settings.actions.stateIn(scope, SharingStarted.Eagerly, ActionSettings())

    /** The hub's persona tools and what the user allows per context (SPEC "Permissions"). */
    val persona: StateFlow<PersonaPermissions> = app.settings.persona.stateIn(scope, SharingStarted.Eagerly, PersonaPermissions())

    fun setTools(where: PhoneContext, choices: JsonObject?) = scope.launch { app.settings.setTools(where, choices) }
    fun setTrustedHistory(history: String) = scope.launch { app.settings.setTrustedHistory(history) }

    /** The assistant panel's conversation while it's open; it lives only in memory. */
    private var panel: ConversationStore? = null

    /** What's known about each turn, kept with its question (SPEC "Reply details"). */
    private val turns = TurnRecorder()

    /** The question whose turn is open, which its replies point at. */
    private var turnOwner: Pair<ConversationStore, Long>? = null

    // OVOS keeps conversation memory on the hub keyed by session_id: a persona's chat
    // history, active skills. The app and the panel share the saved conversation's
    // session, which ends when the conversation is cleared or after IDLE_SESSION_MS
    // without a question or reply. The hub can't be asked whether it still remembers,
    // so the idle time is the best guess at when it's stale; ending it also clears the
    // screen, so what's shown is what the hub knows.
    private val sessionId get() = saved.sessionId
    private var idleExpiry: Job? = null

    /** Where questions and replies go right now: the panel while it's open, else the saved conversation. */
    private val conversation get() = panel ?: saved
    private val logIds = AtomicLong()

    val connection: StateFlow<ConnectionState> = client.state
    val transcript: StateFlow<List<TranscriptEntry>> = saved.entries

    private val _panelTranscript = MutableStateFlow<StateFlow<List<TranscriptEntry>>?>(null)

    /** The open panel's conversation, or null when no panel is open. */
    val panelTranscript: StateFlow<StateFlow<List<TranscriptEntry>>?> = _panelTranscript.asStateFlow()
    val settings: StateFlow<HubSettings?> = app.settings.settings.stateIn(scope, SharingStarted.Eagerly, null)

    private val _problem = MutableStateFlow<Problem?>(null)

    /** Hub speech isn't answering, or the microphone isn't allowed: shown when nothing worse is wrong. */
    private val speechProblem = MutableStateFlow<Problem?>(null)

    /** What's stopping Wiggins right now, if anything; cleared once it's fixed. */
    val problem: StateFlow<Problem?> = combine(_problem, speechProblem) { connection, speech -> connection ?: speech }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Wiggins is reading a reply aloud. */
    val speaking: StateFlow<Boolean> = combine(speaker.speaking, hubSpeaker.speaking) { device, hub -> device || hub }
        .stateIn(scope, SharingStarted.Eagerly, false)
    private val isSpeaking get() = speaker.isSpeaking || hubSpeaker.isSpeaking

    /** Speech-to-text and text-to-speech modes (SPEC "Speech modes"). */
    val speech: StateFlow<SpeechSettings> = app.settings.speech.stateIn(scope, SharingStarted.Eagerly, SpeechSettings())

    /** Wiggins is recording an utterance for the hub's STT. */
    val listening: StateFlow<Boolean> = recorder.recording

    /** Listening was asked for and is getting ready: the hands-free link, the tone. */
    val startingToListen: StateFlow<Boolean> = combine(starting, listening) { s, l -> s && !l }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** The microphone's level while [listening], 0 to 1. */
    val inputLevel: StateFlow<Float> = recorder.level

    private val _hint = MutableStateFlow<String?>(null)

    /** A word on what just happened to speech input ("Didn't catch that"), shown briefly. */
    val hint: StateFlow<String?> = _hint.asStateFlow()
    private var hintClear: Job? = null

    /** The utterance being recorded for the hub's STT, if any. */
    private var recording: Transcription? = null

    /** Utterances the hub is transcribing, with their "Transcribing…" bubble. */
    private val transcribing = mutableMapOf<Transcription, Pair<ConversationStore, Long>>()

    private val _micRequests = Channel<Unit>(Channel.CONFLATED)

    /** Each element asks the visible activity to request the microphone permission. */
    val micPermissionRequests: Flow<Unit> = _micRequests.receiveAsFlow()

    /** The hub speech check's request, while it runs. */
    private var speechCheck: Pair<String, CompletableDeferred<Boolean>>? = null
    private val _checkingHubSpeech = MutableStateFlow(false)
    val checkingHubSpeech: StateFlow<Boolean> = _checkingHubSpeech.asStateFlow()

    /** Replies are spoken as well as shown (a setting, on by default). */
    val speakReplies: StateFlow<Boolean> = app.settings.speakReplies.stateIn(scope, SharingStarted.Eagerly, true)

    private val _thinking = MutableStateFlow(false)

    /** A question reached the hub and its answer hasn't arrived yet. */
    val thinking: StateFlow<Boolean> = _thinking.asStateFlow()
    private var thinkingTimeout: Job? = null

    /** A question reached the hub and OVOS hasn't finished handling it (ovos.utterance.handled). */
    private val _turnOpen = MutableStateFlow(false)
    private var turnOpen: Boolean
        get() = _turnOpen.value
        set(value) { _turnOpen.value = value }

    /** Some of this turn's reply has arrived; without it, losing the connection loses the answer. */
    private var replyHeard = false

    /** The reply being built from this turn's speak messages, which OVOS may send a sentence at a time. */
    private var reply: Pair<ConversationStore, Long>? = null
    private var replyTimeout: Job? = null

    private val _log = MutableStateFlow<List<LoggedMessage>>(emptyList())
    val log: StateFlow<List<LoggedMessage>> = _log.asStateFlow()

    private val _prerequisites = MutableStateFlow(Prerequisites.check(app))
    val prerequisites: StateFlow<Prerequisites> = _prerequisites.asStateFlow()

    /** Whether the mic button can listen: hub STT, or a recognizer app for device STT. */
    val canListen: StateFlow<Boolean> = combine(speech, _prerequisites) { s, p -> p.canListen(s) }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** What's left to set up for the chosen speech modes (SPEC "Prerequisites"). */
    val missingSetup: StateFlow<Set<SetupItem>> = combine(speech, _prerequisites) { s, p -> p.missing(s) }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** What was missing when the setup banner was last dismissed; null until loaded. */
    val dismissedSetup: StateFlow<Set<String>?> = app.settings.dismissedSetup.stateIn(scope, SharingStarted.Eagerly, null)

    /** Hides the setup banner until something else is missing. */
    fun dismissSetupBanner() {
        val missing = missingSetup.value.map { it.key }.toSet()
        scope.launch { app.settings.dismissSetup(missing) }
    }

    private val _listen = Channel<Unit>(Channel.CONFLATED)

    /** Each element asks the visible activity to start the user's recognizer (device STT). */
    val listenRequests: Flow<Unit> = _listen.receiveAsFlow()

    /** The hub wants an answer: listen once current speech ends. */
    private var followUp = false

    /** A PSK is being stretched in the background; see [prepareNoiseKey]. */
    private var stretching = false

    /** Number of Wiggins screens (the app and the panel) currently started. */
    private var visibleScreens = 0
    private val foreground get() = visibleScreens > 0
    private val inForeground = MutableStateFlow(false)
    private var idleDisconnect: Job? = null
    private var reconnect: Job? = null
    private var retries = 0

    /** The proxy refused our token (403): refresh it once before reporting. */
    private var forceTokenRefresh = false

    /** Questions asked while (re)connecting, sent once connected. */
    private val pending = mutableListOf<Pair<ConversationStore, Long>>()

    private val cue = ListeningCue(app.getSystemService(AudioManager::class.java))

    private val playbackFocus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
        )
        .build()

    init {
        scope.launch { client.events.collect(::onHubEvent) }
        scope.launch {
            // The hands-free link stays while the turn goes on: listening, waiting, speaking.
            combine(starting, listening, recognizing, thinking, speaking) { s, a, b, c, d -> s || a || b || c || d }
                .distinctUntilChanged()
                .collect(linkKeeper::busy)
        }
        scope.launch { client.state.collect(::onConnectionState) }
        scope.launch {
            // Setting up the hub is a problem until it's done.
            settings.collect { s -> if (s != null && !s.isComplete) _problem.value = NOT_CONFIGURED }
        }
        speaker.onIdle = { scope.launch { if (!isSpeaking) onPlaybackIdle() } }
        speaker.onProgress = { tag, progress -> scope.launch { turns.onSpeech(tag, progress) } }
        hubSpeaker.onProgress = turns::onSpeech
        scope.launch { phoneActions.asks.collect { asks -> turns.onAsks(asks.map { it.request.id }.toSet()) } }
        hubSpeaker.onIdle = { if (!isSpeaking) onPlaybackIdle() }
        scope.launch {
            // Back on device speech, the hub's speech services no longer matter.
            speech.collect { s ->
                if (speechProblem.value == HUB_SPEECH_SILENT && s.sttMode == SpeechMode.DEVICE && s.ttsMode == SpeechMode.DEVICE) {
                    speechProblem.value = null
                }
            }
        }
        scope.launch {
            // The hub's copy of the rules follows every change made on the phone.
            phoneActions.capabilityChanges.collect { announceCapabilities() }
        }
        scope.launch {
            // Music ducks while the hub's voice speaks (the device engine manages its own).
            val audio = app.getSystemService(AudioManager::class.java)
            hubSpeaker.speaking.collect { on -> if (on) audio.requestAudioFocus(playbackFocus) else audio.abandonAudioFocusRequest(playbackFocus) }
        }
    }


    /** A Wiggins screen is visible: cancel any pending idle disconnect and connect. */
    fun onForeground() {
        visibleScreens++
        inForeground.value = true
        if (!app.getSystemService(KeyguardManager::class.java).isKeyguardLocked) noteUnlocked()
        expireIfIdle()
        refreshPrerequisites()
        retries = 0
        forceTokenRefresh = false
        idleDisconnect?.cancel()
        val state = connection.value
        if (state is ConnectionState.Disconnected || state is ConnectionState.Failed) connect()
        // Do Not Disturb access, say, may have changed in Android's settings meanwhile.
        else announceCapabilities()
    }

    /** No Wiggins screen is visible: disconnect after a short idle period. */
    fun onBackground() {
        visibleScreens = (visibleScreens - 1).coerceAtLeast(0)
        if (foreground) return
        inForeground.value = false
        followUp = false
        cancelListening()
        reconnect?.cancel()
        idleDisconnect?.cancel()
        idleDisconnect = scope.launch {
            // A pending confirmation keeps the connection, so its answer can reach the hub.
            phoneActions.asks.first { it.isEmpty() }
            // So is a reply still being read aloud, so its audio_output_end reaches the hub.
            speaking.first { !it }
            delay(IDLE_DISCONNECT_MS)
            client.disconnect()
        }
    }

    /** A user-requested reconnect starts the retry budget afresh. */
    fun retry() {
        retries = 0
        forceTokenRefresh = false
        connect()
    }

    /** Saves the settings; a changed hub connection reconnects, and a different hub is checked for speech again. */
    fun saveSettings(new: HubSettings, newSpeech: SpeechSettings) {
        scope.launch {
            val old = app.settings.settings.first()
            app.settings.saveSpeech(newSpeech)
            if (new == old) return@launch
            app.settings.save(new)
            if (new.hubUrl.trim() != old.hubUrl.trim()) app.settings.forgetHubSpeechCheck()
            _problem.value = null
            awaitTurnEnd()
            client.disconnect()
            retry()
        }
    }

    /** Switches speech-to-text and text-to-speech to the device (the hub's speech isn't answering). */
    fun useDeviceSpeech() {
        speechProblem.value = null
        scope.launch {
            app.settings.saveSpeech(app.settings.speech.first().copy(stt = SpeechMode.DEVICE, tts = SpeechMode.DEVICE))
        }
    }

    fun refreshPrerequisites() {
        _prerequisites.value = Prerequisites.check(app)
        if (_prerequisites.value.micAllowed && speechProblem.value == MIC_DENIED) speechProblem.value = null
    }

    /** Start listening, interrupting any speech. */
    fun listen() {
        followUp = false
        stopVoice()
        scope.launch { startListening() }
    }

    /**
     * Listens in the chosen mode: records for the hub, or starts the user's recognizer. The
     * listening tone, if wanted, plays first and is over before anything listens.
     */
    private suspend fun startListening() {
        starting.value = true
        try {
            startListeningNow()
        } finally {
            starting.value = false
        }
    }

    private suspend fun startListeningNow() {
        val speech = app.settings.speech.first()
        when {
            speech.sttMode == SpeechMode.DEVICE -> if (_prerequisites.value.recognizer != null) {
                val route = openHandsFree(speech.handsFree)
                cue.playIfWanted(speech.listeningTone, route)
                _listen.trySend(Unit)
            }
            !recorder.hasPermission() -> _micRequests.trySend(Unit)
            else -> {
                // The link first: a car drops a voice-recognition session no app takes up
                // within about 5 s, and waking the screen for the panel can take most of that.
                val route = openHandsFree(speech.handsFree)
                if (_locked.value && !handsFree.isOpen) {
                    // A locked session runs only over the link; without it, ask for the unlock.
                    Log.w(TAG, "locked session without a hands-free link; asking for the unlock")
                    _unlockRequests.trySend(Unit)
                    return
                }
                // An assist invocation asks before its screen has started; record only once it has.
                // If it never starts, the link closes after its grace period.
                // Over the lock screen it has to wake the screen first, which takes longer.
                val wait = if (_locked.value) LOCKED_FOREGROUND_WAIT_MS else FOREGROUND_WAIT_MS
                if (withTimeoutOrNull(wait) { inForeground.first { it } } == null) return
                cue.playIfWanted(speech.listeningTone, route)
                recordUtterance(route.input)
            }
        }
    }

    /** The answer to the microphone permission request. */
    fun onMicPermission(granted: Boolean) {
        refreshPrerequisites()
        if (granted) {
            recordUtterance()
        } else {
            speechProblem.value = MIC_DENIED
        }
    }

    /** The mic button while recording: the utterance is over, transcribe it now. */
    fun finishListening() = recorder.finish()

    /** Back, Cancel or leaving: discard the utterance being recorded. */
    fun cancelListening() {
        val request = recording ?: return
        recording = null
        recorder.cancel()
        request.cancel()
        turns.listeningCancelled()
        client.sendBus("recognizer_loop:record_end", sessionContext())
    }

    /** Records an utterance and has the hub transcribe it (SPEC "Hub speech-to-text"). */
    /**
     * Opens the hands-free link if it's wanted and a car or headset is connected, so the tone,
     * the listening and the reply all use it; returns the route to use either way.
     */
    private suspend fun openHandsFree(wanted: Boolean): SpeechRoute {
        if (wanted && handsFree.open()) {
            _linkOpen.value = true
            linkKeeper.opened()
        }
        return handsFree.route()
    }

    private fun recordUtterance(input: AudioDeviceInfo? = handsFree.route().input) {
        cancelListening()
        lateinit var request: Transcription
        request = Transcription(
            scope,
            early = speech.value.earlyTranscription,
            send = { id, pcm -> sendAudio(request, id, pcm) },
            onResult = { result -> onTranscribed(request, result) },
            newId = ::newRequestId,
        )
        recording = request
        client.sendBus("recognizer_loop:record_begin", sessionContext())
        turns.listeningStarted()
        recorder.start(object : Recorder.Listener {
            override fun onPause(pcm: () -> ByteArray) = request.onPause(pcm)
            override fun onResume() = request.onResume()
            override fun onEnd(pcm: () -> ByteArray) {
                if (recording === request) recording = null
                client.sendBus("recognizer_loop:record_end", sessionContext())
                expireIfIdle()
                // The bubble goes in the conversation on screen when the utterance ends.
                val bubble = conversation to conversation.add(Who.USER, "", Delivery.TRANSCRIBING)
                transcribing[request] = bubble
                turns.recorded(bubble)
                request.onEnd(pcm)
            }

            override fun onNothing(micFailed: Boolean) {
                if (recording === request) recording = null
                client.sendBus("recognizer_loop:record_end", sessionContext())
                request.cancel()
                turns.listeningCancelled()
                if (micFailed) showHint("Couldn't use the microphone")
            }
        }, input)
    }

    /** Drops transcriptions whose bubble is in [store] (the panel closed): their words won't be sent. */
    private fun dropTranscriptions(store: ConversationStore) {
        transcribing.entries.removeAll { (request, bubble) -> (bubble.first === store).also { if (it) request.cancel() } }
    }

    /** Sends audio for transcription once connected (the panel may still be connecting). */
    private fun sendAudio(request: Transcription, id: String, pcm: ByteArray) {
        val context = sessionContext()
        scope.launch {
            val connected = withTimeoutOrNull(CONNECT_WAIT_MS) { connection.first { it is ConnectionState.Connected } } != null
            val sent = connected && withContext(Dispatchers.Default) { client.sendTranscribe(id, pcm, context) }
            if (!sent) request.onSendFailed(id)
        }
    }

    private fun onTranscribed(request: Transcription, result: Transcription.Result) {
        val (store, id) = transcribing.remove(request) ?: return
        if (result != Transcription.Result.NotSent) onHubSpeechAnswered(result != Transcription.Result.NoAnswer)
        when (result) {
            is Transcription.Result.Heard -> {
                turns.transcribed(store to id)
                store.setText(id, result.text, Delivery.PENDING)
                followUp = false
                deliver(id, store)
            }
            else -> {
                turns.forget(store to id)
                store.remove(id)
                showHint(if (result == Transcription.Result.NotSent) "Couldn't reach the hub" else "Didn't catch that")
            }
        }
    }

    private fun showHint(text: String) {
        _hint.value = text
        hintClear?.cancel()
        hintClear = scope.launch {
            delay(HINT_MS)
            _hint.value = null
        }
    }

    /** Whether the hub's speech service answered in time; a miss shows the status banner until one does. */
    private fun onHubSpeechAnswered(answered: Boolean) {
        if (answered) {
            if (speechProblem.value == HUB_SPEECH_SILENT) speechProblem.value = null
        } else {
            speechProblem.value = HUB_SPEECH_SILENT
        }
    }

    /**
     * Asks the hub to synthesize a short phrase (SPEC "Speech modes"). The hub's plugin
     * can't load without both STT and TTS, so an answer means both are there. The result
     * becomes the default for modes the user hasn't chosen.
     */
    fun checkHubSpeech() {
        if (speechCheck != null) return
        val id = newRequestId()
        val answer = CompletableDeferred<Boolean>()
        if (!client.sendSynthesize(id, SPEECH_CHECK_PHRASE, sessionContext())) return
        speechCheck = id to answer
        _checkingHubSpeech.value = true
        scope.launch {
            val available = withTimeoutOrNull(SPEECH_CHECK_TIMEOUT_MS) { answer.await() } ?: false
            speechCheck = null
            _checkingHubSpeech.value = false
            // A dropped connection says nothing about the hub's speech: check again next time.
            if (available || connection.value is ConnectionState.Connected) app.settings.saveHubSpeechCheck(available)
        }
    }

    private fun newRequestId() = UUID.randomUUID().toString()

    /**
     * Sends a Waggle response, waiting through a reconnect if the connection dropped
     * while the request was being handled (a network handover, say).
     */
    private suspend fun sendWhenConnected(type: String, data: JsonObject) {
        withTimeoutOrNull(CONNECT_WAIT_MS) {
            while (true) {
                connection.first { it is ConnectionState.Connected }
                if (client.sendBus(type, data, sessionContext())) return@withTimeoutOrNull
                delay(RESEND_PAUSE_MS)
            }
        } ?: Log.w(TAG, "dropped $type: no connection")
    }

    /** Tells the hub what the phone allows (WAGGLE.md `waggle.capabilities`), if connected. */
    private fun announceCapabilities() {
        if (connection.value !is ConnectionState.Connected) return
        scope.launch { client.sendBus(Waggle.CAPABILITIES, phoneActions.capabilities().toJson(), sessionContext()) }
    }

    /** Stops any reply being read aloud, by either voice. */
    private fun stopVoice() {
        speaker.stop()
        hubSpeaker.stop()
        // The voices don't report a stop as idle; the hub still needs to hear it ended.
        audioOutput.onIdle()
    }

    /**
     * The user's recognizer opened. Tells the hub we're recording, which also restarts
     * a waiting skill's get_response timeout while the user speaks.
     */
    fun onRecognizerStarted() {
        recognizing.value = true
        turns.listeningStarted(device = true)
        client.sendBus("recognizer_loop:record_begin", sessionContext())
    }

    /** The recognizer returned; null if the user cancelled or nothing was heard. */
    fun onRecognized(text: String?) {
        recognizing.value = false
        client.sendBus("recognizer_loop:record_end", sessionContext())
        if (text.isNullOrBlank()) turns.listeningCancelled() else send(text)
    }

    fun send(text: String) {
        val utterance = text.trim()
        if (utterance.isEmpty()) return
        expireIfIdle()
        followUp = false
        stopVoice()
        cancelListening()
        val id = conversation.add(Who.USER, utterance, Delivery.PENDING)
        turns.asked(conversation to id)
        deliver(id)
    }

    /** Sends a question again that didn't reach the hub, or whose answer a dropped connection lost. */
    fun resend(id: Long) {
        if (conversation.get(id)?.delivery !in setOf(Delivery.NOT_SENT, Delivery.UNANSWERED)) return
        conversation.setDelivery(id, Delivery.PENDING)
        retries = 0
        // Timed from the retry, not from when it first failed.
        turns.forget(conversation to id)
        turns.asked(conversation to id)
        deliver(id)
    }

    /** Clears the saved conversation, and starts a new hub session so OVOS forgets it too. */
    fun clearConversation() {
        endTurn()
        // Under the old session, which a skill may be waiting on; speech still playing belongs to no turn now.
        audioOutput.onIdle()
        saved.newSession()
        switchSession()
    }

    /** Ends the conversation if it has been idle too long; see [sessionId]. */
    private fun expireIfIdle() {
        if (saved.idleFor(IDLE_SESSION_MS)) clearConversation()
    }

    /** A question or reply passed: the session is in use. Re-arms the idle check. */
    private fun touchSession() {
        saved.touch()
        idleExpiry?.cancel()
        idleExpiry = scope.launch {
            delay(IDLE_SESSION_MS + 1_000)
            expireIfIdle()
        }
    }

    /** Stops reading aloud; the reply stays on screen. A pending follow-up won't auto-listen. */
    fun stopSpeaking() {
        followUp = false
        stopVoice()
    }

    /** Turns spoken replies on or off; turning them off also stops any speech now. */
    fun setSpeakReplies(on: Boolean) {
        if (!on) stopSpeaking()
        scope.launch { app.settings.setSpeakReplies(on) }
    }

    /**
     * The assistant panel opened: start a fresh conversation that lives only while
     * it's on screen, and fresh OVOS session state (active skills, pending questions).
     */
    fun openPanel() {
        if (panel != null) return
        val store = ConversationStore(null, scope)
        panel = store
        _panelTranscript.value = store.entries
        followUp = false
        setThinking(false)
        endTurn()
    }

    /** The panel closed: drop its conversation and stop talking. */
    fun closePanel() {
        val store = panel ?: return
        pending.removeAll { it.first === store }
        dropTranscriptions(store)
        panel = null
        _panelTranscript.value = null
        _locked.value = false
        followUp = false
        cancelListening()
        endTurn()
        stopVoice()
    }

    /**
     * Reconnects as the current session if a connection is open or opening (the hub keeps
     * one session per connection), once any turn in progress has ended: the hub answers on
     * the connection the question came on, so reconnecting now would lose the answer.
     */
    private fun switchSession() {
        scope.launch {
            awaitTurnEnd()
            when (connection.value) {
                is ConnectionState.Connected, ConnectionState.Connecting, ConnectionState.Handshaking -> {
                    client.disconnect()
                    connect()
                }
                else -> Unit
            }
        }
    }

    /** Until no turn is waiting for its answer, or for the thinking timeout at most. */
    private suspend fun awaitTurnEnd() {
        withTimeoutOrNull(THINKING_TIMEOUT_MS) { _turnOpen.first { !it } }
    }

    fun clearLog() = _log.update { emptyList() }

    val forceNoise: StateFlow<Boolean> = app.settings.forceNoise.stateIn(scope, SharingStarted.Eagerly, false)

    /** The testing switch: Noise even when the hub offers the password handshake. Takes effect on reconnecting. */
    fun setForceNoise(on: Boolean) {
        scope.launch {
            app.settings.setForceNoise(on)
            switchSession()
        }
    }

    private fun deliver(id: Long, store: ConversationStore = conversation) {
        val text = store.get(id)?.text ?: return
        if (client.sendUtterance(text, sessionContext())) {
            store.setDelivery(id, Delivery.SENT)
            touchSession()
            finishReply()
            turns.sent(store to id) { details -> store.setDetails(id, details) }
            turnOwner = store to id
            replyHeard = false
            turnOpen = true
            setThinking(true)
            return
        }
        when (val state = connection.value) {
            is ConnectionState.Failed -> if (state.retryable) {
                pending += store to id
                connect()
            } else {
                store.setDelivery(id, Delivery.NOT_SENT)
            }
            ConnectionState.Disconnected -> {
                pending += store to id
                connect()
            }
            else -> pending += store to id // Connecting or handshaking: send when connected.
        }
    }

    private fun connect() {
        reconnect?.cancel()
        scope.launch {
            val s = app.settings.settings.first()
            if (!s.isComplete) {
                _problem.value = NOT_CONFIGURED
                failPending()
                return@launch
            }
            val url = when (val parsed = HubUrl.parse(s.hubUrl, s.authMode)) {
                is HubUrl.Result.Invalid -> return@launch settingsProblem("Hub URL: ${parsed.reason}")
                is HubUrl.Result.Ok -> parsed.url
            }
            val transport = when (s.authMode) {
                AuthMode.NONE, AuthMode.SIGN_IN -> app.http
                AuthMode.CLIENT_CERT -> try {
                    withContext(Dispatchers.IO) { ClientCertificates.clientFor(app, app.http, s.clientCertAlias) }
                } catch (e: ClientCertificateException) {
                    return@launch settingsProblem(e.message ?: "Client certificate unavailable")
                }
            }
            val headers = if (s.authMode == AuthMode.SIGN_IN) {
                try {
                    val (token, updated) = EntraSignIn.freshAccessToken(app, s.authState, forceTokenRefresh)
                    if (updated != s.authState) app.settings.saveAuthState(updated)
                    mapOf("Authorization" to "Bearer $token")
                } catch (e: SignInException) {
                    return@launch settingsProblem(e.message ?: "Sign in again in settings")
                }
            } else {
                emptyMap()
            }
            val noise = app.settings.noiseSecrets()
            val noiseKeys = StoredNoiseKeys(
                noise.staticKey, s.password, noise.psks,
                onDerived = { nodeId, psk -> scope.launch { app.settings.saveNoisePsk(nodeId, psk) } },
                onRejected = { nodeId -> scope.launch { app.settings.forgetNoisePsk(nodeId) } },
            )
            val forceNoise = app.settings.forceNoise.first()
            client.connect(url, s.accessKey, s.password, sessionContext(), sessionId, transport, headers, noiseKeys, forceNoise)
        }
    }

    /**
     * Stretches the password for this hub's Noise handshake ahead of time (argon2id
     * takes a second or more), so the first Noise connection doesn't wait for it:
     * after a password-handshake connection to a hub that offers both (4.7–4.15),
     * before it moves to Noise only. The node id must survive that: the hub has to
     * keep its identity file (~/.config/hivemind/_identity.json).
     */
    private fun prepareNoiseKey(nodeId: String) {
        if (stretching) return
        stretching = true
        scope.launch {
            try {
                if (nodeId in app.settings.noiseSecrets().psks) return@launch
                val password = app.settings.settings.first().password
                val psk = withContext(Dispatchers.Default) { Noise.derivePsk(password, nodeId) }
                // Only if the password didn't change meanwhile.
                if (app.settings.settings.first().password == password) app.settings.saveNoisePsk(nodeId, psk)
            } finally {
                stretching = false
            }
        }
    }

    private fun settingsProblem(message: String) {
        _problem.value = Problem(message, Problem.Fix.SETTINGS)
        failPending()
    }

    private fun failPending() {
        pending.forEach { (store, id) -> store.setDelivery(id, Delivery.NOT_SENT) }
        pending.clear()
    }

    private fun onConnectionState(state: ConnectionState) {
        // No answer is coming over a connection that's gone: the hub sends it to the
        // connection the question came on, never to a new one. A question with no reply yet
        // says so and offers to ask again; it isn't re-sent by itself, since the hub may
        // already have acted on it.
        if (state !is ConnectionState.Connected && turnOpen) {
            if (!replyHeard) turnOwner?.let { (store, id) -> store.setDelivery(id, Delivery.UNANSWERED) }
            setThinking(false)
            endTurn()
        } else if (state is ConnectionState.Failed || state is ConnectionState.Disconnected) {
            setThinking(false)
            endTurn()
        }
        when (state) {
            is ConnectionState.Connected -> {
                if (state.protocolVersion < Noise.PROTOCOL_VERSION) state.noiseNodeId?.let(::prepareNoiseKey)
                retries = 0
                forceTokenRefresh = false
                _problem.value = null
                announceCapabilities()
                // The persona's tools, for the permissions screen; a hub without them doesn't answer.
                client.sendBus(PERSONA_LIST, sessionContext())
                // Once per hub (and once after upgrading from before hub speech).
                scope.launch { if (app.settings.speech.first().hubSpeechAvailable == null) checkHubSpeech() }
                val queued = pending.toList()
                pending.clear()
                queued.forEach { (store, id) -> deliver(id, store) }
            }
            is ConnectionState.Failed -> when {
                // Hidden: onForeground() reconnects. Questions waiting would be stale by then.
                !foreground -> failPending()
                // Pomerium answers 403 to an expired or revoked token too: refresh once and retry.
                state.httpCode == 403 && !forceTokenRefresh && settings.value?.authMode == AuthMode.SIGN_IN -> {
                    forceTokenRefresh = true
                    connect()
                }
                state.retryable && retries < MAX_RETRIES -> {
                    val wait = RETRY_BASE_MS shl retries++
                    reconnect = scope.launch {
                        delay(wait)
                        connect()
                    }
                }
                else -> {
                    failPending()
                    _problem.value = Problem(state.reason, if (state.retryable) Problem.Fix.RETRY else Problem.Fix.SETTINGS)
                }
            }
            else -> Unit
        }
    }

    private fun onHubEvent(event: HubEvent) {
        when (event) {
            is HubEvent.Speak -> {
                replyHeard = true
                touchSession()
                setThinking(false)
                val store = conversation
                val entry = addToReply(event.utterance)
                turnOwner?.takeIf { it.first === store }?.let { (_, owner) -> store.setTurn(entry, owner) }
                val tag = turns.onSpeak(event.utterance, event.meta, event.expectResponse, spoken = speakReplies.value) {
                    TurnRecorder.Sink { details -> store.setDetails(entry, details) }
                }
                if (event.expectResponse) {
                    // A question ends this reply; the answer starts the next exchange.
                    followUp = true
                    finishReply()
                }
                if (speakReplies.value) {
                    if (speech.value.ttsMode == SpeechMode.HUB) hubSpeaker.speak(event.utterance, tag) else speaker.speak(event.utterance, tag)
                }
                // Muted, an utterance with no words, or no TTS engine: nothing plays, which the hub hears at once.
                audioOutput.onSpeak(playing = isSpeaking)
                if (!speakReplies.value && !isSpeaking) {
                    // Silent: nothing to wait for before listening for an answer.
                    onSpeechIdle()
                }
            }
            HubEvent.Listen -> {
                followUp = true
                if (!isSpeaking) onSpeechIdle()
            }
            // Early answers come while the utterance is still being recorded.
            is HubEvent.WaggleRequest -> scope.launch {
                // The hub has the question and is acting on it: asking again could act twice.
                replyHeard = true
                val intent = event.type == Waggle.INTENT
                val id = (event.data["id"] as? JsonPrimitive)?.contentOrNull
                val name = (event.data[if (intent) "action" else "name"] as? JsonPrimitive)?.contentOrNull
                if (id != null) turns.actionStarted(id, event.type.removePrefix("waggle."), name ?: "?")
                val (handled, responseType) = when (event.type) {
                    Waggle.INTENT -> phoneActions.onIntent(event.data) to Waggle.INTENT_RESPONSE
                    Waggle.COMMAND -> phoneActions.onCommand(event.data) to Waggle.COMMAND_RESPONSE
                    else -> phoneActions.onQuery(event.data) to Waggle.QUERY_RESPONSE
                }
                handled?.let { response ->
                    turns.actionDone(response.id, if (response.ok) "ok" else response.error?.wire ?: "error")
                    sendWhenConnected(responseType, response.toJson())
                }
            }
            is HubEvent.Transcription -> (transcribing.keys + listOfNotNull(recording, confirming)).forEach { it.onAnswer(event.id, event.text) }
            is HubEvent.SpeechAudio -> {
                val check = speechCheck
                if (check != null && check.first == event.id) check.second.complete(true) else hubSpeaker.onAudio(event.id, event.wavBase64)
            }
            is HubEvent.Downlink -> {
                Log.i(TAG, "downlink ${event.hiveType} ${event.busType}")
                (event.raw["payload"] as? JsonObject)?.let { payload ->
                    turns.onDownlink(event.busType, payload)
                    if (event.busType == PERSONA_LIST_RESPONSE) {
                        val data = payload["data"] as? JsonObject ?: JsonObject(emptyMap())
                        scope.launch { app.settings.saveToolCatalog(data.takeIf { ToolCatalog.parse(it) != null }) }
                    }
                }
                if (event.busType == "ovos.utterance.handled") {
                    setThinking(false)
                    endTurn()
                }
                val entry = LoggedMessage(logIds.incrementAndGet(), System.currentTimeMillis(), event.hiveType, event.busType, elideLongStrings(event.raw).toString())
                _log.update { (listOf(entry) + it).take(LOG_LIMIT) }
            }
        }
    }

    /**
     * Shows a sentence of the hub's reply. During a turn, sentences join one streaming
     * entry until the turn ends; speech the hub starts by itself (a timer going off)
     * is an entry of its own.
     */
    private fun addToReply(text: String): Long {
        val open = reply?.takeIf { it.first === conversation }
        val id = when {
            !turnOpen -> conversation.add(Who.HUB, text)
            open != null -> open.second.also { open.first.append(it, text) }
            else -> conversation.add(Who.HUB, text, streaming = true).also { reply = conversation to it }
        }
        if (reply != null) {
            replyTimeout?.cancel()
            // In case the end of the turn never arrives.
            replyTimeout = scope.launch {
                delay(REPLY_TIMEOUT_MS)
                finishReply()
            }
        }
        return id
    }

    /** No more is coming for the reply being built. */
    private fun finishReply() {
        reply?.let { (store, id) -> store.finish(id) }
        reply = null
        replyTimeout?.cancel()
    }

    private fun endTurn() {
        finishReply()
        turnOpen = false
        turnOwner = null
        turns.endTurn()
    }

    /** Shows the hub working on an answer, for at most [THINKING_TIMEOUT_MS]. */
    private fun setThinking(on: Boolean) {
        thinkingTimeout?.cancel()
        _thinking.value = on
        if (on) thinkingTimeout = scope.launch {
            delay(THINKING_TIMEOUT_MS)
            _thinking.value = false
        }
    }

    /** Both voices have played their queues to the end. */
    private fun onPlaybackIdle() {
        audioOutput.onIdle()
        onSpeechIdle()
    }

    /** Speech finished: listen again if the hub expects an answer (SPEC "Follow-ups"). */
    private fun onSpeechIdle() {
        if (!followUp) return
        followUp = false
        if (foreground && canListen.value) scope.launch { startListening() }
    }

    /**
     * What a voice invocation on the locked phone may run as, or null to ask for the unlock
     * (SPEC "Permissions"). It needs hub speech-to-text (the user's recognizer can't show
     * over the lock screen), the hands-free setting on and a hands-free device connected;
     * then [LockedSessions] decides. [voiceButton]: a device's voice button, not the gesture.
     */
    suspend fun lockedContextFor(voiceButton: Boolean): PhoneContext? {
        // From storage, not [speech]: in a process the car's button just started, that
        // still holds the defaults.
        val s = app.settings.speech.first()
        if (!s.handsFree || s.sttMode != SpeechMode.HUB) return null
        val connected = handsFree.connectedAddresses()
        decidedTrust = s.trustedDevices
        return lockedSessions.contextFor(voiceButton, connected, s.trustedDevices, s.unlockPerDrive, s.otherHeadsets).also {
            Log.i(TAG, "locked invocation: voice button $voiceButton, ${connected.size} device(s) connected, " +
                "${connected.count { d -> d in s.trustedDevices }} trusted: runs ${it?.wire ?: "after the unlock"}")
        }
    }

    /** The panel is starting a locked session, as [context]. */
    fun startLockedSession(context: PhoneContext) {
        _unlockRequests.tryReceive() // A stale request from an earlier session mustn't end this one.
        // The devices the decision was made with, read from storage: in a process the car's
        // button just started, [speech] may still hold the defaults.
        lockedSessions.start(context, decidedTrust)
        loggedUntrustedRoute = false
        _locked.value = true
    }

    /**
     * The panel's locked session ends without closing the panel: the phone was unlocked, so
     * it carries on as an ordinary one, or the link didn't come up and the unlock is asked for.
     */
    fun endLockedSession() {
        _locked.value = false
        lockedSessions.end()
    }

    /**
     * Wiggins saw the phone unlocked (its unlock prompt succeeded, or a Wiggins screen is
     * showing on an unlocked phone): arm the hands-free devices connected now. Android's
     * own unlock broadcast isn't used: it can reach a cached app late, and checked against
     * the devices connected by then it could arm a car that connected after the unlock.
     */
    fun noteUnlocked() {
        scope.launch {
            val connected = handsFree.connectedAddresses()
            lockedSessions.onUnlocked(connected)
            Log.i(TAG, "unlocked with ${connected.size} hands-free device(s) connected")
        }
    }

    /**
     * Where the phone is now (SPEC "Permissions"), checked on every message: a trusted
     * session whose audio no longer goes to a trusted device, or that an untrusted device
     * joined, is a locked one from then on.
     */
    private fun phoneContext(): PhoneContext {
        val keyguardLocked = app.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (!_locked.value) return if (keyguardLocked) PhoneContext.LOCKED else PhoneContext.UNLOCKED
        val context = lockedSessions.current ?: PhoneContext.LOCKED
        if (context == PhoneContext.TRUSTED_DEVICE) {
            val routed = handsFree.routedAddress()
            if (routed !in lockedSessions.trusted) {
                // Said once per session: why a trusted device's session runs as locked.
                if (!loggedUntrustedRoute) {
                    loggedUntrustedRoute = true
                    Log.w(TAG, "trusted session runs as locked: the call audio goes to ${if (routed == null) "no Bluetooth device Android names" else "an untrusted device"}")
                }
                return PhoneContext.LOCKED
            }
        }
        return context
    }

    private var loggedUntrustedRoute = false

    /** The trusted devices [lockedContextFor] decided with, for the session it starts. */
    private var decidedTrust: Set<String> = emptySet()

    /**
     * Speaks [lines] with the phone's current voice and returns once they're spoken: Waggle's
     * read-aloud and voice asks, which the hub waits for in silence.
     */
    private suspend fun speakAndWait(lines: List<String>) {
        if (lines.isEmpty()) return
        if (speech.value.ttsMode == SpeechMode.HUB) {
            lines.forEach { hubSpeaker.speak(it) }
            hubSpeaker.speaking.first { !it }
        } else {
            lines.forEach { speaker.speakAndWait(it) }
        }
    }

    /**
     * Asks [question] aloud and listens for yes or no (SPEC "Voice confirmation"), once
     * more if the answer was unclear: true, false, or null. The answer is transcribed by
     * the hub's speech-to-text like any recording, but never sent as an utterance, so
     * neither Waggle nor the persona hears it.
     */
    private suspend fun confirmByVoice(question: String): Boolean? {
        for (attempt in 0 until 2) {
            speakAndWait(listOf(if (attempt == 0) question else "Sorry, was that a yes or a no?"))
            val heard = listenForAnswer() ?: continue
            YesNo.parse(heard)?.let { return it }
        }
        return null
    }

    /** Records one short answer and returns its transcription, or null if nothing was heard. */
    private suspend fun listenForAnswer(): String? {
        if (!recorder.hasPermission()) return null
        cancelListening()
        val done = CompletableDeferred<String?>()
        lateinit var request: Transcription
        request = Transcription(
            scope,
            early = false,
            send = { id, pcm -> sendAudio(request, id, pcm) },
            onResult = { result -> done.complete((result as? Transcription.Result.Heard)?.text) },
            newId = ::newRequestId,
        )
        confirming = request
        val route = handsFree.route()
        cue.playIfWanted(speech.value.listeningTone, route)
        recorder.start(object : Recorder.Listener {
            override fun onPause(pcm: () -> ByteArray) = Unit
            override fun onResume() = Unit
            override fun onEnd(pcm: () -> ByteArray) = request.onEnd(pcm)
            override fun onNothing(micFailed: Boolean) {
                request.cancel()
                done.complete(null)
            }
        }, route.input)
        return try {
            withTimeoutOrNull(ANSWER_WAIT_MS) { done.await() }
        } finally {
            if (confirming === request) confirming = null
            recorder.cancel()
            request.cancel()
        }
    }

    /** The phone's language, timezone, units and formats, so skills answer for the phone. */
    private fun sessionContext(): SessionContext {
        val where = phoneContext()
        val locale = Locale.getDefault()
        val imperial = LocaleData.getMeasurementSystem(ULocale.forLocale(locale)) == LocaleData.MeasurementSystem.US
        val order = DateFormat.getDateFormatOrder(app).joinToString("") { it.uppercase() }
        return SessionContext(
            lang = locale.toLanguageTag(),
            timezone = TimeZone.getDefault().id,
            systemUnit = if (imperial) "imperial" else "metric",
            timeFormat = if (DateFormat.is24HourFormat(app)) "full" else "half",
            dateFormat = order.takeIf { it in setOf("MDY", "DMY", "YMD") } ?: "DMY",
            locked = where != PhoneContext.UNLOCKED,
            watching = foreground && where == PhoneContext.UNLOCKED && !handsFree.isOpen,
            rawUtterance = speech.value.personaRawUtterance,
            phoneContext = where.wire,
            canAsk = where == PhoneContext.UNLOCKED,
            phoneActions = where in actionSettings.value.contexts,
            waggleOff = actionSettings.value.waggleOff(where),
            askByVoice = if (actionSettings.value.asksByVoice(where)) PhoneActions.VOICE_ASKABLE.toList() else emptyList(),
            personaHistory = persona.value.historyIn(where),
            personaTools = persona.value.toolsIn(where),
        )
    }

    private companion object {
        const val TAG = "Wiggins"

        /** OVOS-PERSONA-1's list query, and its answer with the hub's tool catalog. */
        const val PERSONA_LIST = "ovos.persona.list"

        /** How long a spoken yes or no may take, recording and transcription together. */
        const val ANSWER_WAIT_MS = 12_000L
        const val PERSONA_LIST_RESPONSE = "ovos.persona.list.response"
        const val IDLE_DISCONNECT_MS = 60_000L
        const val LOG_LIMIT = 500
        const val MAX_RETRIES = 5
        const val RETRY_BASE_MS = 1_000L
        const val THINKING_TIMEOUT_MS = 30_000L
        const val REPLY_TIMEOUT_MS = 15_000L
        const val IDLE_SESSION_MS = 30 * 60 * 1000L
        const val CONNECT_WAIT_MS = 10_000L
        const val FOREGROUND_WAIT_MS = 2_000L
        const val LOCKED_FOREGROUND_WAIT_MS = 8_000L

        /** How long the hands-free link outlasts the turn, so a follow-up keeps it. */
        const val LINK_GRACE_MS = 3_000L
        const val RESEND_PAUSE_MS = 500L
        const val HINT_MS = 3_000L
        const val SPEECH_CHECK_TIMEOUT_MS = 10_000L
        const val SPEECH_CHECK_PHRASE = "Hello."
        const val LOGGED_STRING_LIMIT = 2_000
        val HUB_SPEECH_SILENT = Problem("Hub speech isn't answering.", Problem.Fix.DEVICE_SPEECH)
        val MIC_DENIED = Problem("Wiggins needs the microphone to listen.", Problem.Fix.MIC_PERMISSION)

        /** The message log keeps a note of audio, not megabytes of base64. */
        fun elideLongStrings(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.mapValues { elideLongStrings(it.value) })
            is JsonArray -> JsonArray(element.map(::elideLongStrings))
            is JsonPrimitive -> if (element.isString && element.content.length > LOGGED_STRING_LIMIT) {
                JsonPrimitive("<${element.content.length} characters>")
            } else {
                element
            }
        }
        val NOT_CONFIGURED = Problem("Set up the hub connection to start.", Problem.Fix.SETTINGS)
    }
}
