package com.mulesipstea.wiggins

import android.icu.util.LocaleData
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.icu.util.ULocale
import android.text.format.DateFormat
import android.util.Log
import com.mulesipstea.wiggins.hivemind.ConnectionState
import com.mulesipstea.wiggins.hivemind.HiveMindClient
import com.mulesipstea.wiggins.hivemind.HubEvent
import com.mulesipstea.wiggins.hivemind.SessionContext
import com.mulesipstea.wiggins.net.ClientCertificateException
import com.mulesipstea.wiggins.net.ClientCertificates
import com.mulesipstea.wiggins.net.EntraSignIn
import com.mulesipstea.wiggins.net.SignInException
import com.mulesipstea.wiggins.settings.AuthMode
import com.mulesipstea.wiggins.settings.HubSettings
import com.mulesipstea.wiggins.settings.HubUrl
import com.mulesipstea.wiggins.settings.SpeechMode
import com.mulesipstea.wiggins.settings.SpeechSettings
import com.mulesipstea.wiggins.speech.HubSpeaker
import com.mulesipstea.wiggins.speech.PcmPlayer
import com.mulesipstea.wiggins.speech.Prerequisites
import com.mulesipstea.wiggins.speech.Recorder
import com.mulesipstea.wiggins.speech.Speaker
import com.mulesipstea.wiggins.speech.Transcription
import com.mulesipstea.wiggins.speech.WebRtcDetector
import com.mulesipstea.wiggins.ui.Delivery
import com.mulesipstea.wiggins.ui.LoggedMessage
import com.mulesipstea.wiggins.ui.Problem
import com.mulesipstea.wiggins.ui.TranscriptEntry
import com.mulesipstea.wiggins.ui.Who
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
    private val speaker = Speaker(app)
    private val client = HiveMindClient(app.http, scope)
    private val recorder = Recorder(app, scope, ::WebRtcDetector)
    private val hubSpeaker = HubSpeaker(
        scope,
        request = { id, text -> client.sendSynthesize(id, text, sessionContext()) },
        play = PcmPlayer::play,
        fallback = { text -> speaker.speakAndWait(text) },
        onHubAnswered = ::onHubSpeechAnswered,
        newId = ::newRequestId,
    )
    private val saved = ConversationStore(File(app.filesDir, "conversation.json"), scope)

    /** The assistant panel's conversation while it's open; it lives only in memory. */
    private var panel: ConversationStore? = null

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
    private var turnOpen = false

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

    private val _listen = Channel<Unit>(Channel.CONFLATED)

    /** Each element asks the visible activity to start the user's recognizer (device STT). */
    val listenRequests: Flow<Unit> = _listen.receiveAsFlow()

    /** The hub wants an answer: listen once current speech ends. */
    private var followUp = false

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

    private val playbackFocus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
        )
        .build()

    init {
        scope.launch { client.events.collect(::onHubEvent) }
        scope.launch { client.state.collect(::onConnectionState) }
        scope.launch {
            // Setting up the hub is a problem until it's done.
            settings.collect { s -> if (s != null && !s.isComplete) _problem.value = NOT_CONFIGURED }
        }
        speaker.onIdle = { scope.launch { if (!isSpeaking) onSpeechIdle() } }
        hubSpeaker.onIdle = { if (!isSpeaking) onSpeechIdle() }
        scope.launch {
            // Back on device speech, the hub's speech services no longer matter.
            speech.collect { s ->
                if (speechProblem.value == HUB_SPEECH_SILENT && s.sttMode == SpeechMode.DEVICE && s.ttsMode == SpeechMode.DEVICE) {
                    speechProblem.value = null
                }
            }
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
        expireIfIdle()
        refreshPrerequisites()
        retries = 0
        forceTokenRefresh = false
        idleDisconnect?.cancel()
        val state = connection.value
        if (state is ConnectionState.Disconnected || state is ConnectionState.Failed) connect()
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

    /** Listens in the chosen mode: records for the hub, or starts the user's recognizer. */
    private suspend fun startListening() {
        val mode = app.settings.speech.first().sttMode
        when {
            mode == SpeechMode.DEVICE -> if (_prerequisites.value.recognizer != null) _listen.trySend(Unit)
            !recorder.hasPermission() -> _micRequests.trySend(Unit)
            // An assist invocation asks before its screen has started; record only once it has.
            withTimeoutOrNull(FOREGROUND_WAIT_MS) { inForeground.first { it } } != null -> recordUtterance()
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
        client.sendBus("recognizer_loop:record_end", sessionContext())
    }

    /** Records an utterance and has the hub transcribe it (SPEC "Hub speech-to-text"). */
    private fun recordUtterance() {
        cancelListening()
        lateinit var request: Transcription
        request = Transcription(
            scope,
            early = speech.value.earlyTranscription,
            send = { id, wav -> sendAudio(request, id, wav) },
            onResult = { result -> onTranscribed(request, result) },
            newId = ::newRequestId,
        )
        recording = request
        client.sendBus("recognizer_loop:record_begin", sessionContext())
        recorder.start(object : Recorder.Listener {
            override fun onPause(wav: () -> ByteArray) = request.onPause(wav)
            override fun onResume() = request.onResume()
            override fun onEnd(wav: () -> ByteArray) {
                if (recording === request) recording = null
                client.sendBus("recognizer_loop:record_end", sessionContext())
                expireIfIdle()
                // The bubble goes in the conversation on screen when the utterance ends.
                transcribing[request] = conversation to conversation.add(Who.USER, "", Delivery.TRANSCRIBING)
                request.onEnd(wav)
            }

            override fun onNothing(micFailed: Boolean) {
                if (recording === request) recording = null
                client.sendBus("recognizer_loop:record_end", sessionContext())
                request.cancel()
                if (micFailed) showHint("Couldn't use the microphone")
            }
        })
    }

    /** Drops transcriptions whose bubble is in [store] (the panel closed): their words won't be sent. */
    private fun dropTranscriptions(store: ConversationStore) {
        transcribing.entries.removeAll { (request, bubble) -> (bubble.first === store).also { if (it) request.cancel() } }
    }

    /** Sends audio for transcription once connected (the panel may still be connecting). */
    private fun sendAudio(request: Transcription, id: String, wav: ByteArray) {
        val context = sessionContext()
        scope.launch {
            val connected = withTimeoutOrNull(CONNECT_WAIT_MS) { connection.first { it is ConnectionState.Connected } } != null
            val sent = connected && withContext(Dispatchers.Default) { client.sendTranscribe(id, wav, context) }
            if (!sent) request.onSendFailed(id)
        }
    }

    private fun onTranscribed(request: Transcription, result: Transcription.Result) {
        val (store, id) = transcribing.remove(request) ?: return
        if (result != Transcription.Result.NotSent) onHubSpeechAnswered(result != Transcription.Result.NoAnswer)
        when (result) {
            is Transcription.Result.Heard -> {
                store.setText(id, result.text, Delivery.PENDING)
                followUp = false
                deliver(id, store)
            }
            else -> {
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

    /** Stops any reply being read aloud, by either voice. */
    private fun stopVoice() {
        speaker.stop()
        hubSpeaker.stop()
    }

    /**
     * The user's recognizer opened. Tells the hub we're recording, which also restarts
     * a waiting skill's get_response timeout while the user speaks.
     */
    fun onRecognizerStarted() {
        client.sendBus("recognizer_loop:record_begin", sessionContext())
    }

    /** The recognizer returned; null if the user cancelled or nothing was heard. */
    fun onRecognized(text: String?) {
        client.sendBus("recognizer_loop:record_end", sessionContext())
        if (!text.isNullOrBlank()) send(text)
    }

    fun send(text: String) {
        val utterance = text.trim()
        if (utterance.isEmpty()) return
        expireIfIdle()
        followUp = false
        stopVoice()
        cancelListening()
        deliver(conversation.add(Who.USER, utterance, Delivery.PENDING))
    }

    /** Sends a question again that didn't reach the hub. */
    fun resend(id: Long) {
        if (conversation.get(id)?.delivery != Delivery.NOT_SENT) return
        conversation.setDelivery(id, Delivery.PENDING)
        retries = 0
        deliver(id)
    }

    /** Clears the saved conversation, and starts a new hub session so OVOS forgets it too. */
    fun clearConversation() {
        endTurn()
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
        followUp = false
        cancelListening()
        endTurn()
        stopVoice()
    }

    /** Reconnects as the current session if a connection is open or opening (the hub keeps one session per connection). */
    private fun switchSession() {
        when (connection.value) {
            is ConnectionState.Connected, ConnectionState.Connecting, ConnectionState.Handshaking -> {
                client.disconnect()
                connect()
            }
            else -> Unit
        }
    }

    fun clearLog() = _log.update { emptyList() }

    private fun deliver(id: Long, store: ConversationStore = conversation) {
        val text = store.get(id)?.text ?: return
        if (client.sendUtterance(text, sessionContext())) {
            store.setDelivery(id, Delivery.SENT)
            touchSession()
            finishReply()
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
            client.connect(url, s.accessKey, s.password, sessionContext(), sessionId, transport, headers)
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
        // No answer is coming over a connection that's gone.
        if (state is ConnectionState.Failed || state is ConnectionState.Disconnected) {
            setThinking(false)
            endTurn()
        }
        when (state) {
            is ConnectionState.Connected -> {
                retries = 0
                forceTokenRefresh = false
                _problem.value = null
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
                touchSession()
                setThinking(false)
                addToReply(event.utterance)
                if (event.expectResponse) {
                    // A question ends this reply; the answer starts the next exchange.
                    followUp = true
                    finishReply()
                }
                if (speakReplies.value) {
                    if (speech.value.ttsMode == SpeechMode.HUB) hubSpeaker.speak(event.utterance) else speaker.speak(event.utterance)
                } else if (!isSpeaking) {
                    // Silent: nothing to wait for before listening for an answer.
                    onSpeechIdle()
                }
            }
            HubEvent.Listen -> {
                followUp = true
                if (!isSpeaking) onSpeechIdle()
            }
            // Early answers come while the utterance is still being recorded.
            is HubEvent.Transcription -> (transcribing.keys + listOfNotNull(recording)).forEach { it.onAnswer(event.id, event.text) }
            is HubEvent.SpeechAudio -> {
                val check = speechCheck
                if (check != null && check.first == event.id) check.second.complete(true) else hubSpeaker.onAudio(event.id, event.wavBase64)
            }
            is HubEvent.Downlink -> {
                Log.i(TAG, "downlink ${event.hiveType} ${event.busType}")
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
    private fun addToReply(text: String) {
        val open = reply?.takeIf { it.first === conversation }
        when {
            !turnOpen -> conversation.add(Who.HUB, text)
            open != null -> open.first.append(open.second, text)
            else -> reply = conversation to conversation.add(Who.HUB, text, streaming = true)
        }
        if (reply != null) {
            replyTimeout?.cancel()
            // In case the end of the turn never arrives.
            replyTimeout = scope.launch {
                delay(REPLY_TIMEOUT_MS)
                finishReply()
            }
        }
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

    /** Speech finished: listen again if the hub expects an answer (SPEC "Follow-ups"). */
    private fun onSpeechIdle() {
        if (!followUp) return
        followUp = false
        if (foreground && canListen.value) scope.launch { startListening() }
    }

    /** The phone's language, timezone, units and formats, so skills answer for the phone. */
    private fun sessionContext(): SessionContext {
        val locale = Locale.getDefault()
        val imperial = LocaleData.getMeasurementSystem(ULocale.forLocale(locale)) == LocaleData.MeasurementSystem.US
        val order = DateFormat.getDateFormatOrder(app).joinToString("") { it.uppercase() }
        return SessionContext(
            lang = locale.toLanguageTag(),
            timezone = TimeZone.getDefault().id,
            systemUnit = if (imperial) "imperial" else "metric",
            timeFormat = if (DateFormat.is24HourFormat(app)) "full" else "half",
            dateFormat = order.takeIf { it in setOf("MDY", "DMY", "YMD") } ?: "DMY",
        )
    }

    private companion object {
        const val TAG = "Wiggins"
        const val IDLE_DISCONNECT_MS = 60_000L
        const val LOG_LIMIT = 500
        const val MAX_RETRIES = 5
        const val RETRY_BASE_MS = 1_000L
        const val THINKING_TIMEOUT_MS = 30_000L
        const val REPLY_TIMEOUT_MS = 15_000L
        const val IDLE_SESSION_MS = 30 * 60 * 1000L
        const val CONNECT_WAIT_MS = 10_000L
        const val FOREGROUND_WAIT_MS = 2_000L
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
