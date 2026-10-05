package com.mulesipstea.wiggins.hivemind

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.Base64

/**
 * One HiveMind connection at a time over an OkHttp websocket. The protocol
 * itself is in [HiveProtocol]; this class owns the socket and publishes state
 * and events.
 */
class HiveMindClient(private val http: OkHttpClient, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<HubEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<HubEvent> = _events.asSharedFlow()

    private val lock = Any()
    private var socket: WebSocket? = null
    private var protocol: HiveProtocol? = null
    private var session: SessionContext? = null

    /**
     * Connects as OVOS session [sessionId], with [transport], which carries the auth
     * mode's TLS settings (a client certificate), and with any [headers] the reverse
     * proxy needs. The hub keeps one session per connection, so a different session
     * means a new connection.
     */
    fun connect(
        url: HttpUrl,
        accessKey: String,
        password: String,
        context: SessionContext,
        sessionId: String,
        transport: OkHttpClient = http,
        headers: Map<String, String> = emptyMap(),
    ) = synchronized(lock) {
        close()
        val proto = HiveProtocol(USERAGENT, password, sessionId = sessionId).also { it.setSession(context) }
        protocol = proto
        session = context
        _state.value = ConnectionState.Connecting
        val request = Request.Builder().url(authorizedUrl(url, USERAGENT, accessKey))
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        socket = transport.newWebSocket(request, Listener(proto))
    }

    fun disconnect() = synchronized(lock) {
        close()
        _state.value = ConnectionState.Disconnected
    }

    /** Sends a data-less BUS message such as `recognizer_loop:record_begin`; false if not connected. */
    fun sendBus(type: String, context: SessionContext): Boolean = synchronized(lock) {
        val frame = protocol?.bus(type, JsonObject(emptyMap()), context) ?: return false
        socket?.send(frame) ?: false
    }

    /** Sends an utterance; false if not connected. */
    fun sendUtterance(text: String, context: SessionContext): Boolean = synchronized(lock) {
        val frame = protocol?.utterance(text, context) ?: return false
        socket?.send(frame) ?: false
    }

    private fun close() {
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
        protocol = null
    }

    private inner class Listener(private val proto: HiveProtocol) : WebSocketListener() {
        private fun current() = protocol === proto

        override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(lock) {
            if (current()) _state.value = ConnectionState.Handshaking
        }

        override fun onMessage(webSocket: WebSocket, text: String): Unit = synchronized(lock) {
            if (!current()) return
            for (out in proto.onFrame(text)) {
                when (out) {
                    is HiveProtocol.Output.Send -> webSocket.send(out.text)
                    is HiveProtocol.Output.Connected -> _state.value = ConnectionState.Connected(out.peer)
                    is HiveProtocol.Output.Event -> emit(out.event)
                    is HiveProtocol.Output.Fail -> {
                        Log.w(TAG, "protocol failure: ${out.reason}")
                        webSocket.close(NORMAL_CLOSURE, null)
                        socket = null
                        protocol = null
                        _state.value = ConnectionState.Failed(out.reason, retryable = false)
                    }
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            Log.w(TAG, "ignoring binary frame of ${bytes.size} bytes (binarize is off)")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String): Unit = synchronized(lock) {
            if (!current()) return
            val beforeHandshake = !proto.isReady
            ended(closeReason(code), retryable = !beforeHandshake)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?): Unit = synchronized(lock) {
            if (!current()) return
            Log.w(TAG, "websocket failure", t)
            val httpError = response != null && response.code != 101
            ended(
                when {
                    httpError -> upgradeRefused(response.code)
                    // The hub aborts the TCP connection on most errors (§13).
                    _state.value is ConnectionState.Connected -> "Lost the connection to the hub"
                    else -> unreachable(t)
                },
                retryable = !httpError,
                httpCode = response?.code?.takeIf { httpError },
            )
        }

        /** The hub signals errors only by closing; infer the cause from how far we got (§13). */
        private fun closeReason(code: Int): String = when (_state.value) {
            ConnectionState.Connecting, ConnectionState.Handshaking ->
                if (proto.isReady) "Hub closed the connection" else "Hub rejected the access key (closed before handshake)"
            else -> "Hub closed the connection (code $code)"
        }

        private fun ended(reason: String, retryable: Boolean, httpCode: Int? = null) {
            socket = null
            protocol = null
            _state.value = ConnectionState.Failed(reason, retryable, httpCode)
        }
    }

    /** A network failure in the user's terms; the exception itself goes to the log. */
    private fun unreachable(t: Throwable): String = when (t) {
        is java.net.UnknownHostException -> "Can't find the hub. Check the hub URL, or your VPN if the hub is only reachable through it."
        is javax.net.ssl.SSLException -> "Couldn't make a secure connection to the hub."
        is java.net.ConnectException, is java.net.NoRouteToHostException, is java.net.SocketTimeoutException ->
            "Can't reach the hub. Check your network, or your VPN if the hub is only reachable through it."
        else -> "Can't reach the hub."
    }

    /** Why the proxy refused the websocket upgrade, in the user's terms. */
    private fun upgradeRefused(code: Int) = when (code) {
        401, 302, 303, 307 -> "The hub's proxy wants a sign-in (HTTP $code); sign in again in settings"
        // Pomerium answers 403 both for a token it rejects and for a missing role.
        403 -> "The hub's proxy refused this sign-in: the account may lack access, or its token was rejected (HTTP 403)"
        495, 496 -> "The hub's proxy rejected or didn't receive the client certificate (HTTP $code)"
        else -> "Hub answered HTTP $code"
    }

    private fun emit(event: HubEvent) {
        if (!_events.tryEmit(event)) scope.launch { _events.emit(event) }
    }

    companion object {
        const val USERAGENT = "Wiggins"
        private const val TAG = "HiveMindClient"
        private const val NORMAL_CLOSURE = 1000

        /**
         * `ws(s)://host:port/?authorization=<base64("useragent:key")>`. The value must
         * reach the hub raw (no percent-encoding of `+ / =`) and be the only query
         * parameter (§2.2).
         */
        fun authorizedUrl(url: HttpUrl, useragent: String, accessKey: String): HttpUrl {
            require(':' !in useragent && ':' !in accessKey) { "useragent and access key can't contain ':'" }
            val auth = Base64.getEncoder().encodeToString("$useragent:$accessKey".toByteArray(Charsets.UTF_8))
            return url.newBuilder().encodedPath("/").query(null).encodedQuery("authorization=$auth").build()
        }
    }
}
