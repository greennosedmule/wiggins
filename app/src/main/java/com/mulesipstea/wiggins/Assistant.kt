package com.mulesipstea.wiggins

import android.icu.util.LocaleData
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
import com.mulesipstea.wiggins.speech.Prerequisites
import com.mulesipstea.wiggins.speech.Speaker
import com.mulesipstea.wiggins.ui.Delivery
import com.mulesipstea.wiggins.ui.LoggedMessage
import com.mulesipstea.wiggins.ui.Problem
import com.mulesipstea.wiggins.ui.TranscriptEntry
import com.mulesipstea.wiggins.ui.Who
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong

/**
 * The assistant itself, owned by the application rather than a screen: the hub
 * connection, the conversation, speech and follow-ups. Screens come and go;
 * this outlives them, so a duplicate activity can't open a second connection,
 * and Waggle requests (M4) can outlive the UI.
 *
 * Main-thread confined: call it from the UI; hub callbacks are hopped onto [scope].
 */
class Assistant(private val app: WigginsApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val speaker = Speaker(app)
    private val client = HiveMindClient(app.http, scope)
    private val saved = ConversationStore(File(app.filesDir, "conversation.json"), scope)

    /** The assistant panel's conversation while it's open; it lives only in memory. */
    private var panel: ConversationStore? = null

    // OVOS keeps conversation memory on the hub keyed by session_id: a persona's chat
    // history, active skills. The app and the panel share one session, which changes
    // when the conversation is cleared.
    private var sessionId = newSessionId()

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

    /** What's stopping Wiggins right now, if anything; cleared once it's fixed. */
    val problem: StateFlow<Problem?> = _problem.asStateFlow()

    /** Wiggins is reading a reply aloud. */
    val speaking: StateFlow<Boolean> = speaker.speaking

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

    private val _listen = Channel<Unit>(Channel.CONFLATED)

    /** Each element asks the visible activity to start the user's recognizer. */
    val listenRequests: Flow<Unit> = _listen.receiveAsFlow()

    /** The hub wants an answer: listen once current speech ends. */
    private var followUp = false

    /** Number of Wiggins screens (the app and the panel) currently started. */
    private var visibleScreens = 0
    private val foreground get() = visibleScreens > 0
    private var idleDisconnect: Job? = null
    private var reconnect: Job? = null
    private var retries = 0

    /** The proxy refused our token (403): refresh it once before reporting. */
    private var forceTokenRefresh = false

    /** Questions asked while (re)connecting, sent once connected. */
    private val pending = mutableListOf<Pair<ConversationStore, Long>>()

    init {
        scope.launch { client.events.collect(::onHubEvent) }
        scope.launch { client.state.collect(::onConnectionState) }
        scope.launch {
            // Setting up the hub is a problem until it's done.
            settings.collect { s -> if (s != null && !s.isComplete) _problem.value = NOT_CONFIGURED }
        }
        speaker.onIdle = { scope.launch { onSpeechIdle() } }
    }

    /** A Wiggins screen is visible: cancel any pending idle disconnect and connect. */
    fun onForeground() {
        visibleScreens++
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
        followUp = false
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

    fun saveSettings(new: HubSettings) {
        scope.launch {
            app.settings.save(new)
            _problem.value = null
            client.disconnect()
            retry()
        }
    }

    fun refreshPrerequisites() {
        _prerequisites.value = Prerequisites.check(app)
    }

    /** Start the user's recognizer, interrupting any speech. */
    fun listen() {
        followUp = false
        speaker.stop()
        _listen.trySend(Unit)
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
        followUp = false
        speaker.stop()
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
        saved.clear()
        endTurn()
        sessionId = newSessionId()
        switchSession()
    }

    /** Stops reading aloud; the reply stays on screen. A pending follow-up won't auto-listen. */
    fun stopSpeaking() {
        followUp = false
        speaker.stop()
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
        panel = null
        _panelTranscript.value = null
        followUp = false
        endTurn()
        speaker.stop()
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
                setThinking(false)
                addToReply(event.utterance)
                if (event.expectResponse) {
                    // A question ends this reply; the answer starts the next exchange.
                    followUp = true
                    finishReply()
                }
                if (speakReplies.value) {
                    speaker.speak(event.utterance)
                } else if (!speaker.isSpeaking) {
                    // Silent: nothing to wait for before listening for an answer.
                    onSpeechIdle()
                }
            }
            HubEvent.Listen -> {
                followUp = true
                if (!speaker.isSpeaking) onSpeechIdle()
            }
            is HubEvent.Downlink -> {
                Log.i(TAG, "downlink ${event.hiveType} ${event.busType}")
                if (event.busType == "ovos.utterance.handled") {
                    setThinking(false)
                    endTurn()
                }
                val entry = LoggedMessage(logIds.incrementAndGet(), System.currentTimeMillis(), event.hiveType, event.busType, event.raw.toString())
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

    /** Speech finished: relaunch the recognizer if the hub expects an answer (SPEC "Follow-ups"). */
    private fun onSpeechIdle() {
        if (!followUp) return
        followUp = false
        if (foreground && _prerequisites.value.recognizer != null) _listen.trySend(Unit)
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

    private fun newSessionId() = java.util.UUID.randomUUID().toString()

    private companion object {
        const val TAG = "Wiggins"
        const val IDLE_DISCONNECT_MS = 60_000L
        const val LOG_LIMIT = 500
        const val MAX_RETRIES = 5
        const val RETRY_BASE_MS = 1_000L
        const val THINKING_TIMEOUT_MS = 30_000L
        const val REPLY_TIMEOUT_MS = 15_000L
        val NOT_CONFIGURED = Problem("Set up the hub connection to start.", Problem.Fix.SETTINGS)
    }
}
