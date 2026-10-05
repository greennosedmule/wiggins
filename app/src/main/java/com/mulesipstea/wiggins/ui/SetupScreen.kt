package com.mulesipstea.wiggins.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mulesipstea.wiggins.hivemind.ConnectionState
import com.mulesipstea.wiggins.speech.Prerequisites

private const val FUTO_VOICE_INPUT = "https://voiceinput.futo.org/"
private const val RHVOICE = "https://f-droid.org/packages/com.github.olga_yakovleva.rhvoice.android/"

/** SPEC "Prerequisites": what's set up, and a way to fix what isn't. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    prerequisites: Prerequisites,
    hubConfigured: Boolean,
    connection: ConnectionState,
    onOpenHubSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Setup") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Item(
                title = "HiveMind hub",
                ok = hubConfigured && connection is ConnectionState.Connected,
                detail = when {
                    !hubConfigured -> "Enter the hub URL, access key and password."
                    connection is ConnectionState.Connected -> "Connected."
                    connection is ConnectionState.Failed -> "Not connected: ${connection.reason}"
                    else -> "Not connected yet."
                },
                action = "Hub settings" to onOpenHubSettings,
            )
            Item(
                title = "Speech recognizer",
                ok = prerequisites.recognizer != null,
                detail = prerequisites.recognizer?.let { "$it turns your speech into text." }
                    ?: "No app handles speech recognition. Typing still works. FUTO Voice Input works offline without Google services.",
                action = if (prerequisites.recognizer == null) "Get FUTO Voice Input" to { context.open(Uri.parse(FUTO_VOICE_INPUT)) } else null,
            )
            Item(
                title = "Text-to-speech engine",
                ok = prerequisites.ttsEngines.isNotEmpty(),
                detail = if (prerequisites.ttsEngines.isEmpty()) {
                    "No TTS engine is installed, so replies are shown but not spoken. RHVoice is one open-source engine."
                } else {
                    "Installed: ${prerequisites.ttsEngines.joinToString()}. Pick the default in TTS settings."
                },
                action = if (prerequisites.ttsEngines.isEmpty()) {
                    "Get RHVoice" to { context.open(Uri.parse(RHVOICE)) }
                } else {
                    "TTS settings" to { context.openSettings(Intent(TTS_SETTINGS)) }
                },
            )
            Item(
                title = "Default assistant",
                ok = prerequisites.isAssistant,
                detail = if (prerequisites.isAssistant) {
                    "The assist gesture opens Wiggins and starts listening."
                } else {
                    "Make Wiggins the default digital assistant app so the assist gesture opens it."
                },
                action = if (!prerequisites.isAssistant) {
                    "Open assistant settings" to {
                        // The role can't be requested in-app (requestable="false"); the user picks it in Settings.
                        context.openSettings(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS), Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                    }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun Item(title: String, ok: Boolean, detail: String, action: Pair<String, () -> Unit>?) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = if (ok) "Done" else "Needs attention",
            tint = if (ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(end = 12.dp, top = 2.dp),
        )
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            action?.let { (label, onClick) -> OutlinedButton(onClick = onClick) { Text(label) } }
        }
    }
}

private const val TTS_SETTINGS = "com.android.settings.TTS_SETTINGS"

private fun Context.open(uri: Uri) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: ActivityNotFoundException) {
    }
}

/** Opens the first settings screen that exists, ending with the main settings app. */
private fun Context.openSettings(vararg intents: Intent) {
    for (intent in intents.toList() + Intent(Settings.ACTION_SETTINGS)) {
        try {
            startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
        }
    }
}
