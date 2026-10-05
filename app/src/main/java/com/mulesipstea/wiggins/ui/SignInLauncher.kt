package com.mulesipstea.wiggins.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.mulesipstea.wiggins.net.EntraSignIn
import com.mulesipstea.wiggins.net.SignInException
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationService

/**
 * Runs the sign-in flow in a browser tab and reports the new AuthState (serialized)
 * or an error. Returns a function taking the issuer, client ID and scope.
 */
@Composable
fun rememberSignIn(onSignedIn: (String) -> Unit, onError: (String) -> Unit): (String, String, String) -> Unit {
    val context = LocalContext.current
    val service = remember(context) { AuthorizationService(context) }
    DisposableEffect(service) { onDispose { service.dispose() } }
    val scope = rememberCoroutineScope()
    val signedIn = rememberUpdatedState(onSignedIn)
    val failed = rememberUpdatedState(onError)

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        scope.launch {
            try {
                signedIn.value(EntraSignIn.finishSignIn(service, result.data))
            } catch (e: SignInException) {
                failed.value(e.message ?: "Sign-in failed")
            }
        }
    }
    return remember(service) {
        { issuer, clientId, apiScope ->
            scope.launch {
                try {
                    launcher.launch(EntraSignIn.signInIntent(service, issuer, clientId, apiScope))
                } catch (e: SignInException) {
                    failed.value(e.message ?: "Sign-in failed")
                }
            }
        }
    }
}
