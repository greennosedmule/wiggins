package com.mulesipstea.wiggins.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

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
    }
}
