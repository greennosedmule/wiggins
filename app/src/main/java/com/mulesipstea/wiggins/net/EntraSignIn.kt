package com.mulesipstea.wiggins.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SignInException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The proxy sign-in mode: Wiggins is an OAuth public client of Entra
 * (authorization code + PKCE, via AppAuth in a browser tab) and sends the
 * access token for the hub's API scope on the websocket upgrade. Pomerium
 * checks it (bearer_token_format: idp_access_token) and the route's app role.
 * See docs/m3-pomerium-auth.md.
 */
object EntraSignIn {
    val redirectUri: Uri = Uri.parse("com.mulesipstea.wiggins://oauth2redirect")

    /** The sign-in request, as an intent for the browser tab. */
    suspend fun signInIntent(service: AuthorizationService, issuer: String, clientId: String, scope: String): Intent {
        val config = discover(issuer)
        val request = AuthorizationRequest.Builder(config, clientId, ResponseTypeValues.CODE, redirectUri)
            // offline_access gets a refresh token, so later connects refresh silently;
            // profile puts the account's name in the ID token, for display.
            .setScopes(scope, "openid", "profile", "offline_access")
            .setPrompt("select_account")
            .build()
        return service.getAuthorizationRequestIntent(request)
    }

    /** Completes sign-in from the browser tab's result; returns the serialized AuthState. */
    suspend fun finishSignIn(service: AuthorizationService, result: Intent?): String {
        val data = result ?: throw SignInException("Sign-in was cancelled")
        val response = AuthorizationResponse.fromIntent(data)
        AuthorizationException.fromIntent(data)?.let { throw SignInException(it.errorDescription ?: it.error ?: "Sign-in failed", it) }
        response ?: throw SignInException("Sign-in was cancelled")
        val state = AuthState(response, null)
        val tokens = suspendCancellableCoroutine { cont ->
            service.performTokenRequest(response.createTokenExchangeRequest()) { tokens, ex ->
                if (tokens != null) cont.resume(tokens) else cont.resumeWithException(SignInException(ex?.errorDescription ?: "Token exchange failed", ex))
            }
        }
        state.update(tokens, null)
        return state.jsonSerializeString()
    }

    /**
     * A current access token, refreshing it if needed or if [forceRefresh] (the proxy
     * rejected the last one). Returns the token and the AuthState to store (it
     * changes when tokens refresh).
     */
    suspend fun freshAccessToken(context: Context, authState: String, forceRefresh: Boolean = false): Pair<String, String> {
        val state = runCatching { AuthState.jsonDeserialize(authState) }.getOrElse { throw SignInException("Not signed in") }
        if (!state.isAuthorized) throw SignInException("Not signed in")
        if (forceRefresh) state.needsTokenRefresh = true
        val service = AuthorizationService(context)
        try {
            val token = suspendCancellableCoroutine { cont ->
                state.performActionWithFreshTokens(service) { accessToken, _, ex ->
                    when {
                        accessToken != null -> cont.resume(accessToken)
                        ex?.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR ->
                            cont.resumeWithException(SignInException("Sign-in expired; sign in again in settings", ex))
                        else -> cont.resumeWithException(SignInException(ex?.errorDescription ?: ex?.message ?: "Couldn't refresh sign-in", ex))
                    }
                }
            }
            return token to state.jsonSerializeString()
        } finally {
            service.dispose()
        }
    }

    /** The signed-in account's name, from the ID token, for display. */
    fun accountName(authState: String): String? = runCatching {
        val claims = AuthState.jsonDeserialize(authState).parsedIdToken?.additionalClaims
        (claims?.get("preferred_username") ?: claims?.get("name")) as? String
    }.getOrNull()

    private suspend fun discover(issuer: String): AuthorizationServiceConfiguration =
        suspendCancellableCoroutine { cont ->
            AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(issuer.trim().trimEnd('/'))) { config, ex ->
                if (config != null) cont.resume(config) else cont.resumeWithException(SignInException("Couldn't read the issuer's configuration: ${ex?.errorDescription ?: ex?.message}", ex))
            }
        }
}
