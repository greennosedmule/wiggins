package com.mulesipstea.wiggins.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mulesipstea.wiggins.net.EntraSignIn
import com.mulesipstea.wiggins.settings.AuthMode
import com.mulesipstea.wiggins.settings.HubSettings
import com.mulesipstea.wiggins.settings.HubUrl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(initial: HubSettings?, onSave: (HubSettings) -> Unit, onBack: () -> Unit) {
    val start = initial ?: HubSettings()
    var url by remember(start) { mutableStateOf(start.hubUrl) }
    var key by remember(start) { mutableStateOf(start.accessKey) }
    var password by remember(start) { mutableStateOf(start.password) }
    var mode by remember(start) { mutableStateOf(start.authMode) }
    var certAlias by remember(start) { mutableStateOf(start.clientCertAlias) }
    var issuer by remember(start) { mutableStateOf(start.signInIssuer) }
    var clientId by remember(start) { mutableStateOf(start.signInClientId) }
    var scope by remember(start) { mutableStateOf(start.signInScope) }
    var authState by remember(start) { mutableStateOf(start.authState) }
    var signInError by remember { mutableStateOf<String?>(null) }
    val signIn = rememberSignIn(
        onSignedIn = { authState = it; signInError = null },
        onError = { signInError = it },
    )
    val urlError = url.takeIf { it.isNotBlank() }
        ?.let { HubUrl.parse(it, mode) as? HubUrl.Result.Invalid }?.reason
    val chooseCertificate = rememberCertificateChooser(url) { certAlias = it }
    val canSave = urlError == null && url.isNotBlank() && key.isNotBlank() && password.isNotEmpty() &&
        when (mode) {
            AuthMode.NONE -> true
            AuthMode.CLIENT_CERT -> certAlias.isNotBlank()
            AuthMode.SIGN_IN -> authState.isNotBlank()
        }
    val save = {
        onSave(
            start.copy(
                hubUrl = url.trim(), accessKey = key.trim(), password = password, authMode = mode,
                clientCertAlias = certAlias,
                signInIssuer = issuer, signInClientId = clientId, signInScope = scope, authState = authState,
            ),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        bottomBar = {
            // Always in reach, however long the form gets.
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Button(
                    onClick = save,
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp).height(52.dp),
                ) { Text("Save and connect") }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingsSection("HiveMind hub", "The address, access key and password from the hub's add-client command.") {
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("Hub URL") },
                    placeholder = { Text("ws://hub.example:5678") },
                    isError = urlError != null,
                    supportingText = { Text(urlError ?: "ws:// is allowed on a LAN or VPN; HiveMind encrypts payloads either way.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = key, onValueChange = { key = it },
                    label = { Text("Access key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            SettingsSection("Reaching the hub", "How the connection gets past the hub's reverse proxy, if it has one.") {
                AuthModeOption(AuthMode.NONE, mode, "LAN or VPN", "No proxy sign-in. ws:// or wss://.") { mode = it }
                AuthModeOption(AuthMode.CLIENT_CERT, mode, "Client certificate", "The proxy checks a certificate on this phone. wss:// only.") { mode = it }
                AuthModeOption(AuthMode.SIGN_IN, mode, "Sign in", "Sign in with your identity provider; the proxy checks the token. wss:// only.") { mode = it }
                if (mode == AuthMode.SIGN_IN) {
                    OutlinedTextField(
                        value = issuer, onValueChange = { issuer = it },
                        label = { Text("Issuer URL") },
                        placeholder = { Text("https://login.microsoftonline.com/<tenant>/v2.0") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = clientId, onValueChange = { clientId = it },
                        label = { Text("Client ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = scope, onValueChange = { scope = it },
                        label = { Text("Hub API scope") },
                        placeholder = { Text("api://<proxy app>/hivemind") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val signedIn = authState.isNotBlank()
                    val account = remember(authState) { authState.takeIf { it.isNotBlank() }?.let(EntraSignIn::accountName) }
                    StatusRow(
                        ok = signedIn && signInError == null,
                        text = signInError ?: when {
                            account != null -> "Signed in as $account"
                            signedIn -> "Signed in"
                            else -> "Not signed in"
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { signIn(issuer, clientId, scope) },
                            enabled = issuer.isNotBlank() && clientId.isNotBlank() && scope.isNotBlank(),
                        ) { Text(if (signedIn) "Sign in again" else "Sign in") }
                        if (signedIn) OutlinedButton(onClick = { authState = "" }) { Text("Sign out") }
                    }
                }
                if (mode == AuthMode.CLIENT_CERT) {
                    StatusRow(ok = certAlias.isNotBlank(), text = if (certAlias.isBlank()) "No certificate chosen" else "Certificate: $certAlias")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = chooseCertificate.choose) { Text("Choose certificate") }
                        OutlinedButton(onClick = chooseCertificate.import) { Text("Import .p12 file") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthModeOption(value: AuthMode, selected: AuthMode, title: String, detail: String, onSelect: (AuthMode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = value == selected, role = Role.RadioButton, onClick = { onSelect(value) }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = value == selected, onClick = null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A titled card of related settings. */
@Composable
private fun SettingsSection(title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.padding(horizontal = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }
}

/** A check or warning mark with a short status. */
@Composable
private fun StatusRow(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            if (ok) Icons.Default.CheckCircle else Icons.Default.Info,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
