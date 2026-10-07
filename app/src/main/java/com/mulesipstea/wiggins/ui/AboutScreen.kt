package com.mulesipstea.wiggins.ui

import androidx.annotation.RawRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mulesipstea.wiggins.R

private const val SOURCE = "https://github.com/greennosedmule/wiggins"

/** Open-source components in the app, with their licenses. */
private val COMPONENTS = listOf(
    "Android Jetpack (AndroidX, Compose, Material 3, DataStore, Room)" to "Apache-2.0",
    "Kotlin, kotlinx.coroutines, kotlinx.serialization" to "Apache-2.0",
    "OkHttp and Okio" to "Apache-2.0",
    "Tink" to "Apache-2.0",
    "AppAuth for Android" to "Apache-2.0",
    "android-vad (Georgiy Konovalov)" to "MIT",
    "WebRTC voice activity detector (WebRTC project authors)" to "BSD-3-Clause",
    "Material icons" to "Apache-2.0",
    "Libre Baskerville font" to "SIL OFL 1.1",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                WigginsAvatar(Modifier.size(72.dp))
                Column {
                    Text("Wiggins", style = MaterialTheme.typography.headlineSmall)
                    Text("Version $version", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                "An Android assistant for OpenVoiceOS, through HiveMind. Named for the leader of the Baker Street " +
                    "Irregulars, who ran errands for Sherlock Holmes.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { uri.openUri(SOURCE) }) { Text("Source code on GitHub") }

            LicenseCard("Wiggins", "Apache License 2.0", R.raw.license_apache)
            Text("Open-source components", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    COMPONENTS.forEach { (name, license) ->
                        Row {
                            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text(license, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            LicenseCard("Libre Baskerville", "SIL Open Font License 1.1", R.raw.license_ofl)
            LicenseCard("android-vad", "MIT License", R.raw.license_android_vad)
            LicenseCard("WebRTC voice activity detector", "BSD 3-Clause License and patent grant", R.raw.license_webrtc)
        }
    }
}

/** A license name that expands to its full text. */
@Composable
private fun LicenseCard(subject: String, license: String, @RawRes text: Int) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$subject: $license", style = MaterialTheme.typography.bodyMedium)
            if (open) {
                val body = remember(text) { context.resources.openRawResource(text).bufferedReader().use { it.readText() } }
                Text(body, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            } else {
                Text("Tap to read the full text.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
