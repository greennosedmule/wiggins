package com.mulesipstea.wiggins.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mulesipstea.wiggins.net.EntraSignIn
import com.mulesipstea.wiggins.settings.AuthMode
import com.mulesipstea.wiggins.settings.HubSettings
import com.mulesipstea.wiggins.settings.HubUrl
import com.mulesipstea.wiggins.settings.ListeningTone
import com.mulesipstea.wiggins.settings.OtherHeadsets
import com.mulesipstea.wiggins.settings.SpeechMode
import com.mulesipstea.wiggins.settings.SpeechSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    initial: HubSettings?,
    initialSpeech: SpeechSettings,
    onSave: (HubSettings, SpeechSettings) -> Unit,
    onBack: () -> Unit,
) {
    val start = initial ?: HubSettings()
    // A mode left unchosen stays null, so the hub speech check can still pick it.
    var speech by remember(initialSpeech) { mutableStateOf(initialSpeech) }
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
    val edited = start.copy(
        hubUrl = url.trim(), accessKey = key.trim(), password = password, authMode = mode,
        clientCertAlias = certAlias,
        signInIssuer = issuer, signInClientId = clientId, signInScope = scope, authState = authState,
    )
    val save = { onSave(edited, speech) }
    // Speech settings alone can always be saved.
    val savable = canSave || (edited == start && speech != initialSpeech)

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
                    enabled = savable,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp).height(52.dp),
                ) { Text(if (edited == start) "Save" else "Save and connect") }
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
            SettingsSection(
                "Speech",
                when (speech.hubSpeechAvailable) {
                    true -> "The hub can transcribe and speak. Its voices are usually better than the phone's."
                    false -> "The hub didn't answer a speech request, so the phone does both. Check again on the Setup screen."
                    null -> "Not checked yet: the hub is asked when Wiggins next connects."
                },
            ) {
                SpeechModeChoice("Speech to text", speech.sttMode, "Wiggins records, the hub transcribes", "Your speech recognizer app") {
                    speech = speech.copy(stt = it)
                }
                if (speech.sttMode == SpeechMode.HUB) {
                    Row(
                        Modifier.fillMaxWidth().toggleable(
                            value = speech.earlyTranscription,
                            role = Role.Switch,
                            onValueChange = { speech = speech.copy(earlyTranscription = it) },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Early transcription", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Start transcribing at a short pause, so answers come sooner. Costs the hub some extra work.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = speech.earlyTranscription, onCheckedChange = null)
                    }
                }
                SpeechModeChoice("Spoken replies", speech.ttsMode, "Hub's voice", "Phone's TTS engine") {
                    speech = speech.copy(tts = it)
                }
                ListeningToneChoice(speech.listeningTone) { speech = speech.copy(listeningTone = it) }
                HandsFreeSwitch(speech.handsFree) { speech = speech.copy(handsFree = it) }
                if (speech.handsFree) {
                    LockedChoices(
                        speech = speech,
                        onChange = { speech = it },
                    )
                }
            }
            SettingsSection(
                "Persona",
                "Some hubs have a persona: an AI model that answers what none of the hub's skills handle.",
            ) {
                SwitchRow(
                    "Send my exact words",
                    "On: the persona gets your question exactly as you said or typed it. " +
                        "Off: it gets the hub's tidied-up version, the one skills match on, with contractions spelled out and the question mark removed. " +
                        "Skills see the tidied version either way. A hub without a persona ignores this.",
                    speech.personaRawUtterance,
                ) { speech = speech.copy(personaRawUtterance = it) }
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

/** Hub or Device for one direction of speech. */
@Composable
private fun SpeechModeChoice(title: String, selected: SpeechMode, hubDetail: String, deviceDetail: String, onSelect: (SpeechMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SpeechMode.entries.forEachIndexed { i, value ->
                SegmentedButton(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(i, SpeechMode.entries.size),
                ) { Text(if (value == SpeechMode.HUB) "Hub" else "Phone") }
            }
        }
        Text(if (selected == SpeechMode.HUB) hubDetail else deviceDetail, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Use the car's or headset's own mic and speaker (SPEC "In the car"). Turning it on asks
 * for Android's "Nearby devices" (Bluetooth) permission; it stays off if that's refused.
 */
/** A setting that's on or off, with what it does underneath its name. */
@Composable
private fun SwitchRow(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = on, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = on, onCheckedChange = null)
    }
}

@Composable
private fun HandsFreeSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onChange(true)
    }
    val toggle = { wanted: Boolean ->
        if (wanted && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            onChange(wanted)
        }
    }
    Row(
        Modifier.fillMaxWidth().toggleable(value = on, role = Role.Switch, onValueChange = toggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Car and headset", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Listen and speak through a connected car or Bluetooth headset, like a phone call, instead of the phone's mic and media audio.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = on, onCheckedChange = null)
    }
}

/**
 * What the car's or headset's voice button does on a locked phone (SPEC "Locked, in the
 * car"), and for "Trusted car", which paired devices are trusted.
 */
@SuppressLint("MissingPermission")
/**
 * Which cars and headsets work on a locked phone (SPEC "Permissions"): the trusted ones,
 * with an optional unlock once per drive, and what any other headset's button does.
 */
@Composable
private fun LockedChoices(speech: SpeechSettings, onChange: (SpeechSettings) -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Trusted devices", style = MaterialTheme.typography.bodyLarge)
        Text(
            "A trusted car or headset's voice button works while the phone is locked, as long as no other " +
                "hands-free device is connected. Anyone with it can then use what you allow for trusted devices " +
                "(Phone actions › Where they run).",
            style = MaterialTheme.typography.bodySmall,
        )
        if (speech.sttMode != SpeechMode.HUB) {
            Text(
                "Needs hub speech to text: the phone's recognizer can't listen over the lock screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        val paired = remember {
            runCatching {
                context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
                    .map { (it.name ?: it.address) to it.address }
                    .sortedBy { it.first.lowercase() }
            }.getOrDefault(emptyList())
        }
        if (paired.isEmpty()) {
            Text("No paired Bluetooth devices.", style = MaterialTheme.typography.bodySmall)
        }
        val trusted = speech.trustedDevices
        paired.forEach { (name, address) ->
            val on = address in trusted
            Row(
                Modifier.fillMaxWidth().toggleable(
                    value = on,
                    role = Role.Checkbox,
                    onValueChange = { onChange(speech.copy(trustedDevices = if (it) trusted + address else trusted - address)) },
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = on, onCheckedChange = null)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                    Text(address, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        SwitchRow(
            "Unlock once per drive",
            "A trusted device works locked only after you've unlocked the phone while it was connected, until it disconnects.",
            speech.unlockPerDrive,
        ) { onChange(speech.copy(unlockPerDrive = it)) }
        Text("Other headsets", style = MaterialTheme.typography.bodyLarge)
        val choices = listOf(OtherHeadsets.ASK_UNLOCK to "Ask to unlock", OtherHeadsets.TALK_ONLY to "Talk only")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            choices.forEachIndexed { i, (value, label) ->
                SegmentedButton(
                    selected = value == speech.otherHeadsets,
                    onClick = { onChange(speech.copy(otherHeadsets = value)) },
                    shape = SegmentedButtonDefaults.itemShape(i, choices.size),
                ) { Text(label, maxLines = 1) }
            }
        }
        Text(
            when (speech.otherHeadsets) {
                OtherHeadsets.ASK_UNLOCK -> "Any other headset's voice button asks for the unlock first, like the assist gesture."
                OtherHeadsets.TALK_ONLY ->
                    "Any other headset's voice button works while the phone is locked, with what you allow when locked: " +
                        "by default no phone actions, and the hub's persona sees only what's said since."
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** When the short tone before listening plays. */
@Composable
private fun ListeningToneChoice(selected: ListeningTone, onSelect: (ListeningTone) -> Unit) {
    val choices = listOf(ListeningTone.NEVER to "Never", ListeningTone.BLUETOOTH to "Bluetooth", ListeningTone.ALWAYS to "Always")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Listening tone", style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            choices.forEachIndexed { i, (value, label) ->
                SegmentedButton(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(i, choices.size),
                ) { Text(label) }
            }
        }
        Text(
            when (selected) {
                ListeningTone.NEVER -> "No tone; the screen shows when Wiggins is listening"
                ListeningTone.BLUETOOTH -> "A short tone before listening, when speech plays on a car, headset or earbuds"
                ListeningTone.ALWAYS -> "A short tone every time Wiggins starts listening"
            },
            style = MaterialTheme.typography.bodySmall,
        )
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
