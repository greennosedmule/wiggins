package com.mulesipstea.wiggins.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Who does speech-to-text or text-to-speech (SPEC "Speech modes"). */
enum class SpeechMode { HUB, DEVICE }

/**
 * The speech settings. A mode is null until the user picks one or the hub speech
 * check has run; until then the device does it.
 */
data class SpeechSettings(
    val stt: SpeechMode? = null,
    val tts: SpeechMode? = null,
    /** Transcribe at a short pause while waiting for the end of speech (SPEC "Hub speech-to-text"). */
    val earlyTranscription: Boolean = true,
    /** What the last hub speech check found: true if the hub synthesized, false if not, null if it hasn't run. */
    val hubSpeechAvailable: Boolean? = null,
) {
    val sttMode get() = stt ?: SpeechMode.DEVICE
    val ttsMode get() = tts ?: SpeechMode.DEVICE
}

/** How the connection to the hub's reverse proxy is authenticated (SPEC "Security requirements"). */
enum class AuthMode {
    /** LAN or VPN: no proxy auth; HiveMind's own encryption protects the traffic. */
    NONE,

    /** mTLS: a client certificate from Android KeyChain. */
    CLIENT_CERT,

    /** Sign in with the identity provider; the proxy checks the access token (M3). */
    SIGN_IN,
}

data class HubSettings(
    val hubUrl: String = "",
    val authMode: AuthMode = AuthMode.NONE,
    val accessKey: String = "",
    val password: String = "",
    /** KeyChain alias of the client certificate, for [AuthMode.CLIENT_CERT]. */
    val clientCertAlias: String = "",
    /** OIDC issuer URL, for [AuthMode.SIGN_IN], e.g. https://login.microsoftonline.com/<tenant>/v2.0. */
    val signInIssuer: String = "",
    val signInClientId: String = "",
    /** The hub API's scope, e.g. api://<proxy app>/hivemind. */
    val signInScope: String = "",
    /** AppAuth's serialized AuthState (holds the refresh token); empty when signed out. */
    val authState: String = "",
) {
    val isComplete get() = hubUrl.isNotBlank() && accessKey.isNotBlank() && password.isNotBlank() &&
        when (authMode) {
            AuthMode.NONE -> true
            AuthMode.CLIENT_CERT -> clientCertAlias.isNotBlank()
            AuthMode.SIGN_IN -> authState.isNotBlank()
        }
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private val secrets = SecretStore(context)

    val settings: Flow<HubSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun save(settings: HubSettings) = withContext(Dispatchers.Default) {
        val key = secrets.encrypt(ACCESS_KEY.name, settings.accessKey)
        val password = secrets.encrypt(PASSWORD.name, settings.password)
        val authState = settings.authState.takeIf { it.isNotEmpty() }?.let { secrets.encrypt(AUTH_STATE.name, it) }
        context.dataStore.edit {
            it[HUB_URL] = settings.hubUrl.trim()
            it[AUTH_MODE] = settings.authMode.name
            it[ACCESS_KEY] = key
            it[PASSWORD] = password
            it[CLIENT_CERT_ALIAS] = settings.clientCertAlias
            it[SIGN_IN_ISSUER] = settings.signInIssuer.trim()
            it[SIGN_IN_CLIENT_ID] = settings.signInClientId.trim()
            it[SIGN_IN_SCOPE] = settings.signInScope.trim()
            if (authState != null) it[AUTH_STATE] = authState else it.remove(AUTH_STATE)
        }
    }

    /** Whether replies are spoken as well as shown. On by default. */
    val speakReplies: Flow<Boolean> = context.dataStore.data.map { it[SPEAK_REPLIES] ?: true }

    suspend fun setSpeakReplies(on: Boolean) {
        context.dataStore.edit { it[SPEAK_REPLIES] = on }
    }

    val speech: Flow<SpeechSettings> = context.dataStore.data.map {
        SpeechSettings(
            stt = it[STT_MODE]?.let { m -> runCatching { SpeechMode.valueOf(m) }.getOrNull() },
            tts = it[TTS_MODE]?.let { m -> runCatching { SpeechMode.valueOf(m) }.getOrNull() },
            earlyTranscription = it[EARLY_TRANSCRIPTION] ?: true,
            hubSpeechAvailable = it[HUB_SPEECH_AVAILABLE],
        )
    }

    suspend fun saveSpeech(speech: SpeechSettings) {
        context.dataStore.edit {
            it.putOrRemove(STT_MODE, speech.stt?.name)
            it.putOrRemove(TTS_MODE, speech.tts?.name)
            it[EARLY_TRANSCRIPTION] = speech.earlyTranscription
            it.putOrRemove(HUB_SPEECH_AVAILABLE, speech.hubSpeechAvailable)
        }
    }

    /**
     * Records the hub speech check's result, and makes it the default for any mode
     * the user hasn't chosen (SPEC "Speech modes").
     */
    suspend fun saveHubSpeechCheck(available: Boolean) {
        val default = if (available) SpeechMode.HUB else SpeechMode.DEVICE
        context.dataStore.edit {
            it[HUB_SPEECH_AVAILABLE] = available
            if (it[STT_MODE] == null) it[STT_MODE] = default.name
            if (it[TTS_MODE] == null) it[TTS_MODE] = default.name
        }
    }

    /** Forgets the hub speech check (a different hub), so it runs again on the next connection. */
    suspend fun forgetHubSpeechCheck() {
        context.dataStore.edit { it.remove(HUB_SPEECH_AVAILABLE) }
    }

    private fun <T> MutablePreferences.putOrRemove(key: Preferences.Key<T>, value: T?) {
        if (value != null) this[key] = value else remove(key)
    }

    /** Stores refreshed tokens without touching the rest of the settings. */
    suspend fun saveAuthState(authState: String) = withContext(Dispatchers.Default) {
        val encrypted = secrets.encrypt(AUTH_STATE.name, authState)
        context.dataStore.edit { it[AUTH_STATE] = encrypted }
    }

    private fun Preferences.toSettings() = HubSettings(
        hubUrl = this[HUB_URL].orEmpty(),
        authMode = this[AUTH_MODE]?.let { runCatching { AuthMode.valueOf(it) }.getOrNull() } ?: AuthMode.NONE,
        accessKey = this[ACCESS_KEY]?.let { secrets.decrypt(ACCESS_KEY.name, it) }.orEmpty(),
        password = this[PASSWORD]?.let { secrets.decrypt(PASSWORD.name, it) }.orEmpty(),
        clientCertAlias = this[CLIENT_CERT_ALIAS].orEmpty(),
        signInIssuer = this[SIGN_IN_ISSUER].orEmpty(),
        signInClientId = this[SIGN_IN_CLIENT_ID].orEmpty(),
        signInScope = this[SIGN_IN_SCOPE].orEmpty(),
        authState = this[AUTH_STATE]?.let { secrets.decrypt(AUTH_STATE.name, it) }.orEmpty(),
    )

    private companion object {
        val HUB_URL = stringPreferencesKey("hub_url")
        val AUTH_MODE = stringPreferencesKey("auth_mode")
        val ACCESS_KEY = stringPreferencesKey("access_key_enc")
        val PASSWORD = stringPreferencesKey("password_enc")
        val CLIENT_CERT_ALIAS = stringPreferencesKey("client_cert_alias")
        val SIGN_IN_ISSUER = stringPreferencesKey("sign_in_issuer")
        val SIGN_IN_CLIENT_ID = stringPreferencesKey("sign_in_client_id")
        val SIGN_IN_SCOPE = stringPreferencesKey("sign_in_scope")
        val AUTH_STATE = stringPreferencesKey("auth_state_enc")
        val SPEAK_REPLIES = booleanPreferencesKey("speak_replies")
        val STT_MODE = stringPreferencesKey("stt_mode")
        val TTS_MODE = stringPreferencesKey("tts_mode")
        val EARLY_TRANSCRIPTION = booleanPreferencesKey("early_transcription")
        val HUB_SPEECH_AVAILABLE = booleanPreferencesKey("hub_speech_available")
    }
}
