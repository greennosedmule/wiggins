package com.mulesipstea.wiggins.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.mulesipstea.wiggins.hivemind.Noise
import com.mulesipstea.wiggins.waggle.Mode
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    /** When a short tone says Wiggins has started listening (SPEC "Voice pipeline"). */
    val listeningTone: ListeningTone = ListeningTone.BLUETOOTH,
    /**
     * Use a connected car's or headset's hands-free link for listening and speech (SPEC "In
     * the car"). Off until the user turns it on, which is when Android asks for the
     * Bluetooth permission.
     */
    val handsFree: Boolean = false,
    /**
     * Bluetooth addresses of the trusted hands-free devices (SPEC "Permissions"): their voice
     * button runs a [PhoneContext.TRUSTED_DEVICE] session on a locked phone.
     */
    val trustedDevices: Set<String> = emptySet(),
    /** A trusted device works locked only after an unlock while it's connected, until it disconnects. */
    val unlockPerDrive: Boolean = false,
    /** What any other headset's voice button does on a locked phone. */
    val otherHeadsets: OtherHeadsets = OtherHeadsets.ASK_UNLOCK,
    /**
     * Ask the hub to give a persona the user's exact words rather than the normalized text
     * skills match on (`persona_raw_utterance`, `docs/hivemind-protocol.md` §11.1). On by default.
     */
    val personaRawUtterance: Boolean = true,
) {
    val sttMode get() = stt ?: SpeechMode.DEVICE
    val ttsMode get() = tts ?: SpeechMode.DEVICE
}

/**
 * When the listening tone plays. Bluetooth is the default: in a car or with earbuds the
 * screen isn't in view, while on the phone itself the panel shows that it's listening.
 */
enum class ListeningTone {
    NEVER,
    ALWAYS,

    /** Only when speech would play on a Bluetooth device. */
    BLUETOOTH,
    ;

    fun shouldPlay(onBluetooth: Boolean) = this == ALWAYS || (this == BLUETOOTH && onBluetooth)
}

/**
 * What the user allows the hub's persona per context (SPEC "Permissions"): the hub's tool
 * catalog, the user's choices from it, and how much history it sees on a trusted device.
 */
data class PersonaPermissions(
    /** Null until the hub has listed its persona's tools, or if it lists none. */
    val catalog: ToolCatalog? = null,
    /** Per context, `persona_tools` as sent: servers the user hasn't changed are left out. */
    val tools: Map<PhoneContext, JsonObject> = emptyMap(),
    /** `full` or `since_lock`, for a trusted device. Unlocked is always full; locked always since the lock. */
    val trustedHistory: String = "full",
) {
    fun toolsIn(where: PhoneContext) = tools[where] ?: JsonObject(emptyMap())

    fun historyIn(where: PhoneContext) = if (where == PhoneContext.TRUSTED_DEVICE) trustedHistory else where.history
}

/** Waggle settings besides the rules (SPEC "Phone actions (Waggle)"). */
data class ActionSettings(
    /** What happens to an intent no rule matches: block (the default) or ask. */
    val unmatched: Mode = Mode.BLOCK,
    /** The queries the user has enabled; all but phone.status are off until enabled. */
    val queries: Set<String> = emptySet(),
    /** Where phone actions run at all (SPEC "Permissions"): unlocked and through a trusted device, until changed. */
    val contexts: Set<PhoneContext> = DEFAULT_CONTEXTS,
    /** The apps whose notifications Waggle may read (SPEC "Notifications"); none until the user picks. */
    val notificationApps: Set<String> = emptySet(),
    /** Where notifications are only read aloud by the phone, never listed to the hub. Off everywhere by default. */
    val readAloudOnlyIn: Set<PhoneContext> = emptySet(),
    /** Where the hub may ask where the phone is (SPEC "Location"): not when locked, by default. */
    val locationIn: Set<PhoneContext> = DEFAULT_CONTEXTS,
    /** Where an ask the screen can't show is asked by voice instead: a reply, through a trusted device by default. */
    val voiceAskIn: Set<PhoneContext> = setOf(PhoneContext.TRUSTED_DEVICE),
) {
    /** What the phone won't run [where], so the hub leaves it alone (WAGGLE.md `waggle_off`). */
    fun waggleOff(where: PhoneContext): List<String> = buildList {
        if (where in readAloudOnlyIn) add("notifications.list")
        if (where !in locationIn) add("phone.location")
    }

    /** Whether an ask [where] can be confirmed by voice instead of a card: never unlocked, where there's a screen. */
    fun asksByVoice(where: PhoneContext) = where != PhoneContext.UNLOCKED && where in voiceAskIn

    companion object {
        val DEFAULT_CONTEXTS = setOf(PhoneContext.UNLOCKED, PhoneContext.TRUSTED_DEVICE)
    }
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

/**
 * The Noise handshake's key material (protocol v3): this phone's static key, and
 * the PSKs already stretched from the password with argon2id, by hub node id.
 */
class NoiseSecrets(val staticKey: ByteArray, val psks: Map<String, ByteArray>)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private val secrets = SecretStore(context)

    val settings: Flow<HubSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun save(settings: HubSettings) = withContext(Dispatchers.Default) {
        val key = secrets.encrypt(ACCESS_KEY.name, settings.accessKey)
        val password = secrets.encrypt(PASSWORD.name, settings.password)
        val authState = settings.authState.takeIf { it.isNotEmpty() }?.let { secrets.encrypt(AUTH_STATE.name, it) }
        context.dataStore.edit {
            // Stretched from the old password, so useless now.
            val oldPassword = it[PASSWORD]?.let { v -> runCatching { secrets.decrypt(PASSWORD.name, v) }.getOrNull() }
            if (oldPassword != settings.password) it.remove(NOISE_PSKS)
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
        // Settings saved before 0.7.0 have only the old "When the phone is locked" choice.
        val locked = if (it[OTHER_HEADSETS] == null) {
            LegacyWhenLocked.migrate(it[WHEN_LOCKED], it[TRUSTED_DEVICES].orEmpty())
        } else {
            Triple(
                it[TRUSTED_DEVICES].orEmpty(),
                it[UNLOCK_PER_DRIVE] ?: false,
                runCatching { OtherHeadsets.valueOf(it[OTHER_HEADSETS]!!) }.getOrDefault(OtherHeadsets.ASK_UNLOCK),
            )
        }
        SpeechSettings(
            stt = it[STT_MODE]?.let { m -> runCatching { SpeechMode.valueOf(m) }.getOrNull() },
            tts = it[TTS_MODE]?.let { m -> runCatching { SpeechMode.valueOf(m) }.getOrNull() },
            earlyTranscription = it[EARLY_TRANSCRIPTION] ?: true,
            hubSpeechAvailable = it[HUB_SPEECH_AVAILABLE],
            handsFree = it[HANDS_FREE] ?: false,
            listeningTone = it[LISTENING_TONE]?.let { t -> runCatching { ListeningTone.valueOf(t) }.getOrNull() } ?: ListeningTone.BLUETOOTH,
            trustedDevices = locked.first,
            unlockPerDrive = locked.second,
            otherHeadsets = locked.third,
            personaRawUtterance = it[PERSONA_RAW_UTTERANCE] ?: true,
        )
    }

    suspend fun saveSpeech(speech: SpeechSettings) {
        context.dataStore.edit {
            it.putOrRemove(STT_MODE, speech.stt?.name)
            it.putOrRemove(TTS_MODE, speech.tts?.name)
            it[EARLY_TRANSCRIPTION] = speech.earlyTranscription
            it.putOrRemove(HUB_SPEECH_AVAILABLE, speech.hubSpeechAvailable)
            it[LISTENING_TONE] = speech.listeningTone.name
            it[HANDS_FREE] = speech.handsFree
            it.remove(WHEN_LOCKED)
            it[TRUSTED_DEVICES] = speech.trustedDevices
            it[UNLOCK_PER_DRIVE] = speech.unlockPerDrive
            it[OTHER_HEADSETS] = speech.otherHeadsets.name
            it[PERSONA_RAW_UTTERANCE] = speech.personaRawUtterance
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

    val actions: Flow<ActionSettings> = context.dataStore.data.map {
        ActionSettings(
            unmatched = Mode.fromWire(it[UNMATCHED])?.takeIf { m -> m != Mode.RUN } ?: Mode.BLOCK,
            // Queries that need no permission and read nothing personal start on, once, even
            // for installs that predate them; after that the user's choice stands.
            queries = queriesFrom(it[QUERIES], it[QUERY_DEFAULTS_APPLIED]),
            contexts = contextsFrom(it[ACTION_CONTEXTS]) ?: ActionSettings.DEFAULT_CONTEXTS,
            notificationApps = it[NOTIFICATION_APPS].orEmpty(),
            readAloudOnlyIn = contextsFrom(it[READ_ALOUD_ONLY]) ?: emptySet(),
            locationIn = contextsFrom(it[LOCATION_CONTEXTS]) ?: ActionSettings.DEFAULT_CONTEXTS,
            voiceAskIn = contextsFrom(it[VOICE_ASK_CONTEXTS]) ?: setOf(PhoneContext.TRUSTED_DEVICE),
        )
    }

    private fun contextsFrom(wires: Set<String>?) = wires?.mapNotNull { c -> PhoneContext.entries.firstOrNull { e -> e.wire == c } }?.toSet()

    suspend fun setNotificationApps(packages: Set<String>) {
        context.dataStore.edit { it[NOTIFICATION_APPS] = packages }
    }

    /** Turns [where] in or out of one of the per-context sets: [key] is stored, [default] applies until then. */
    private suspend fun setContext(key: Preferences.Key<Set<String>>, default: Set<PhoneContext>, where: PhoneContext, on: Boolean) {
        context.dataStore.edit {
            val now = it[key] ?: default.map { c -> c.wire }.toSet()
            it[key] = if (on) now + where.wire else now - where.wire
        }
    }

    suspend fun setReadAloudOnly(where: PhoneContext, on: Boolean) = setContext(READ_ALOUD_ONLY, emptySet(), where, on)
    suspend fun setLocationIn(where: PhoneContext, on: Boolean) = setContext(LOCATION_CONTEXTS, ActionSettings.DEFAULT_CONTEXTS, where, on)
    suspend fun setVoiceAskIn(where: PhoneContext, on: Boolean) = setContext(VOICE_ASK_CONTEXTS, setOf(PhoneContext.TRUSTED_DEVICE), where, on)

    val persona: Flow<PersonaPermissions> = context.dataStore.data.map { prefs ->
        PersonaPermissions(
            catalog = prefs[TOOL_CATALOG]?.let { raw -> runCatching { ToolCatalog.parse(Json.parseToJsonElement(raw).jsonObject) }.getOrNull() },
            tools = PhoneContext.entries.mapNotNull { c ->
                prefs[toolsKey(c)]?.let { raw -> runCatching { c to Json.parseToJsonElement(raw).jsonObject }.getOrNull() }
            }.toMap(),
            trustedHistory = prefs[TRUSTED_HISTORY]?.takeIf { it == "since_lock" } ?: "full",
        )
    }

    /** Keeps the hub's latest persona list response ([data]), or forgets it (null). */
    suspend fun saveToolCatalog(data: JsonObject?) {
        context.dataStore.edit { it.putOrRemove(TOOL_CATALOG, data?.toString()) }
    }

    /** The user's tool choices in [where], or null to go back to the hub's defaults there. */
    suspend fun setTools(where: PhoneContext, choices: JsonObject?) {
        context.dataStore.edit { it.putOrRemove(toolsKey(where), choices?.toString()) }
    }

    suspend fun setTrustedHistory(history: String) {
        context.dataStore.edit { it[TRUSTED_HISTORY] = history }
    }

    /** Whether phone actions run in [where] (SPEC "Permissions"). */
    suspend fun setActionsIn(where: PhoneContext, on: Boolean) {
        context.dataStore.edit {
            val now = it[ACTION_CONTEXTS]?.toSet() ?: ActionSettings.DEFAULT_CONTEXTS.map { c -> c.wire }.toSet()
            it[ACTION_CONTEXTS] = if (on) now + where.wire else now - where.wire
        }
    }

    suspend fun setUnmatched(mode: Mode) {
        require(mode != Mode.RUN) { "unmatched intents can only be blocked or asked about" }
        context.dataStore.edit { it[UNMATCHED] = mode.wire }
    }

    suspend fun setQueryEnabled(name: String, enabled: Boolean) {
        context.dataStore.edit {
            val now = queriesFrom(it[QUERIES], it[QUERY_DEFAULTS_APPLIED])
            it[QUERIES] = if (enabled) now + name else now - name
            it[QUERY_DEFAULTS_APPLIED] = true
        }
    }

    private fun <T> MutablePreferences.putOrRemove(key: Preferences.Key<T>, value: T?) {
        if (value != null) this[key] = value else remove(key)
    }

    /** Stores refreshed tokens without touching the rest of the settings. */
    suspend fun saveAuthState(authState: String) = withContext(Dispatchers.Default) {
        val encrypted = secrets.encrypt(AUTH_STATE.name, authState)
        context.dataStore.edit { it[AUTH_STATE] = encrypted }
    }

    /** The Noise key material, making this phone's static key the first time. */
    suspend fun noiseSecrets(): NoiseSecrets = withContext(Dispatchers.Default) {
        lateinit var secretsNow: NoiseSecrets
        context.dataStore.edit {
            val staticKey = it[NOISE_STATIC_KEY]?.let { v -> secrets.decrypt(NOISE_STATIC_KEY.name, v).hexToByteArray() }
                ?: Noise.newPrivateKey().also { k -> it[NOISE_STATIC_KEY] = secrets.encrypt(NOISE_STATIC_KEY.name, k.toHexString()) }
            secretsNow = NoiseSecrets(staticKey, it.noisePsks())
        }
        secretsNow
    }

    /** Keeps a PSK stretched for the hub with [nodeId], so argon2id runs once per hub and password. */
    suspend fun saveNoisePsk(nodeId: String, psk: ByteArray) = withContext(Dispatchers.Default) {
        context.dataStore.edit {
            // Newest last; a phone talks to one hub, so a few entries are plenty.
            val psks = (it.noisePsks() - nodeId + (nodeId to psk)).entries.toList().takeLast(MAX_NOISE_PSKS)
            val json = JsonObject(psks.associate { (id, key) -> id to JsonPrimitive(key.toHexString()) })
            it[NOISE_PSKS] = secrets.encrypt(NOISE_PSKS.name, json.toString())
        }
    }

    /** Forgets the PSK for [nodeId], which the hub didn't accept. */
    suspend fun forgetNoisePsk(nodeId: String) = withContext(Dispatchers.Default) {
        context.dataStore.edit {
            val psks = it.noisePsks()
            if (nodeId !in psks) return@edit
            val json = JsonObject((psks - nodeId).mapValues { (_, key) -> JsonPrimitive(key.toHexString()) })
            it[NOISE_PSKS] = secrets.encrypt(NOISE_PSKS.name, json.toString())
        }
    }

    private fun Preferences.noisePsks(): Map<String, ByteArray> {
        val stored = this[NOISE_PSKS] ?: return emptyMap()
        return runCatching {
            Json.parseToJsonElement(secrets.decrypt(NOISE_PSKS.name, stored)).jsonObject
                .mapValues { (_, v) -> v.jsonPrimitive.content.hexToByteArray() }
        }.getOrDefault(emptyMap())
    }

    /** Use the Noise handshake even when the hub also offers the password one (a testing switch in Hub messages). */
    val forceNoise: Flow<Boolean> = context.dataStore.data.map { it[FORCE_NOISE] ?: false }

    suspend fun setForceNoise(on: Boolean) {
        context.dataStore.edit { it[FORCE_NOISE] = on }
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

    internal companion object {
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
        val PERSONA_RAW_UTTERANCE = booleanPreferencesKey("persona_raw_utterance")
        val HUB_SPEECH_AVAILABLE = booleanPreferencesKey("hub_speech_available")
        val LISTENING_TONE = stringPreferencesKey("listening_tone")
        val HANDS_FREE = booleanPreferencesKey("hands_free")
        /** Before 0.7.0: ask to unlock, trusted car or always. Read once, for the migration. */
        val WHEN_LOCKED = stringPreferencesKey("hands_free_when_locked")
        val UNLOCK_PER_DRIVE = booleanPreferencesKey("hands_free_unlock_per_drive")
        val OTHER_HEADSETS = stringPreferencesKey("hands_free_other_headsets")
        val ACTION_CONTEXTS = stringSetPreferencesKey("waggle_contexts")
        val NOTIFICATION_APPS = stringSetPreferencesKey("waggle_notification_apps")
        val READ_ALOUD_ONLY = stringSetPreferencesKey("waggle_read_aloud_only")
        val LOCATION_CONTEXTS = stringSetPreferencesKey("waggle_location_contexts")
        val VOICE_ASK_CONTEXTS = stringSetPreferencesKey("waggle_voice_ask_contexts")
        val TOOL_CATALOG = stringPreferencesKey("persona_tool_catalog")
        val TRUSTED_HISTORY = stringPreferencesKey("persona_history_trusted")
        fun toolsKey(where: PhoneContext) = stringPreferencesKey("persona_tools_${where.wire}")
        val TRUSTED_DEVICES = stringSetPreferencesKey("hands_free_trusted")
        val UNMATCHED = stringPreferencesKey("waggle_unmatched")
        val QUERIES = stringSetPreferencesKey("waggle_queries")
        val QUERY_DEFAULTS_APPLIED = booleanPreferencesKey("waggle_query_defaults_0_8")

        /** phone.status: the battery, network and ringer; no permission, nothing personal. */
        val QUERIES_ON_BY_DEFAULT = setOf("phone.status")

        /** The enabled queries from what's [stored], with the defaults until the user has chosen ([applied]). */
        internal fun queriesFrom(stored: Set<String>?, applied: Boolean?): Set<String> =
            stored.orEmpty() + if (applied == true) emptySet() else QUERIES_ON_BY_DEFAULT
        val NOISE_STATIC_KEY = stringPreferencesKey("noise_static_key_enc")
        val NOISE_PSKS = stringPreferencesKey("noise_psks_enc")
        val FORCE_NOISE = booleanPreferencesKey("force_noise")
        const val MAX_NOISE_PSKS = 4
    }
}
