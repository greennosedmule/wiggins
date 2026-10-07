package com.mulesipstea.wiggins.hivemind

import kotlinx.serialization.json.JsonObject

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Handshaking : ConnectionState
    data class Connected(val peer: String?) : ConnectionState
    /** [retryable] is false when retrying can't help, such as a wrong key or password. */
    data class Failed(val reason: String, val retryable: Boolean, val httpCode: Int? = null) : ConnectionState
}

sealed interface HubEvent {
    /** A `speak` message from the hub. */
    data class Speak(val utterance: String, val expectResponse: Boolean) : HubEvent

    /** The hub asked the satellite to listen (`mycroft.mic.listen`). */
    data object Listen : HubEvent

    /**
     * The hub's transcription of audio Wiggins sent (`recognizer_loop:b64_transcribe.response`),
     * for the request with [id]; [text] is null or blank if it heard nothing.
     */
    data class Transcription(val id: String, val text: String?) : HubEvent

    /** The hub's speech for a `speak:b64_audio` request with [id]: a base64 WAV file. */
    data class SpeechAudio(val id: String, val wavBase64: String) : HubEvent

    /** A Waggle request from the hub: [type] is `waggle.intent` or `waggle.query`. */
    data class WaggleRequest(val type: String, val data: JsonObject) : HubEvent

    /** Every downlink bus message, handled or not, for the message log M1 keeps. */
    data class Downlink(val hiveType: String, val busType: String?, val raw: JsonObject) : HubEvent
}
