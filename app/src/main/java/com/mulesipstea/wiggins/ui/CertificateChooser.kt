package com.mulesipstea.wiggins.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.security.KeyChain
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class CertificateChooser(val choose: () -> Unit, val import: () -> Unit)

/**
 * Picks a client certificate through the system KeyChain UI, which also grants
 * Wiggins access to it. Importing a .p12 hands the file to the system
 * installer (it asks for the password), then opens the chooser.
 */
@Composable
fun rememberCertificateChooser(hubUrl: String, onChosen: (String) -> Unit): CertificateChooser {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val currentUrl = rememberUpdatedState(hubUrl)
    val chosen = rememberUpdatedState(onChosen)
    val main = remember { Handler(Looper.getMainLooper()) }

    val choose: () -> Unit = {
        // The hub's host and port pre-select a matching certificate in the system dialog.
        val host = currentUrl.value.trim().replaceFirst(Regex("^wss?://"), "https://").toHttpUrlOrNull()
        KeyChain.choosePrivateKeyAlias(activity, { alias -> if (alias != null) main.post { chosen.value(alias) } }, null, null, host?.host, host?.port ?: -1, null)
    }
    val install = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { choose() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val bytes = uri?.let { context.contentResolver.openInputStream(it)?.use { s -> s.readBytes() } } ?: return@rememberLauncherForActivityResult
        try {
            install.launch(KeyChain.createInstallIntent().putExtra(KeyChain.EXTRA_PKCS12, bytes))
        } catch (_: ActivityNotFoundException) {
        }
    }
    return remember { CertificateChooser(choose = choose, import = { pick.launch(arrayOf("application/x-pkcs12", "application/octet-stream")) }) }
}

private fun Context.findActivity(): Activity {
    var c = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    error("No activity")
}
