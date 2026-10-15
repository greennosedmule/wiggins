package com.mulesipstea.wiggins.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.mulesipstea.wiggins.settings.PersonaPermissions
import com.mulesipstea.wiggins.settings.PhoneContext
import com.mulesipstea.wiggins.settings.ToolCatalog
import com.mulesipstea.wiggins.settings.ToolChoices
import kotlinx.serialization.json.JsonObject

/**
 * What may run where (SPEC "Permissions"): a tab per context with its phone actions, how
 * much history the persona sees, and the persona's tools as the hub lists them, per
 * service and per tool. The hub's limits are the most; this only narrows them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    persona: PersonaPermissions,
    actionContexts: Set<PhoneContext>,
    onActionsChange: (PhoneContext, Boolean) -> Unit,
    onTrustedHistoryChange: (String) -> Unit,
    onToolsChange: (PhoneContext, JsonObject?) -> Unit,
    onOpenActions: () -> Unit,
    onBack: () -> Unit,
    startAt: PhoneContext = PhoneContext.UNLOCKED,
    readAloudOnly: Set<PhoneContext> = emptySet(),
    locationIn: Set<PhoneContext> = setOf(PhoneContext.UNLOCKED, PhoneContext.TRUSTED_DEVICE),
    voiceAskIn: Set<PhoneContext> = setOf(PhoneContext.TRUSTED_DEVICE),
    onReadAloudOnlyChange: (PhoneContext, Boolean) -> Unit = { _, _ -> },
    onLocationChange: (PhoneContext, Boolean) -> Unit = { _, _ -> },
    onVoiceAskChange: (PhoneContext, Boolean) -> Unit = { _, _ -> },
) {
    var tab by rememberSaveable { mutableStateOf(startAt) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Permissions") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                PhoneContext.entries.forEach { c -> Tab(selected = tab == c, onClick = { tab = c }, text = { Text(c.label, maxLines = 1) }) }
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(CONTEXT_EXPLAINED.getValue(tab), style = MaterialTheme.typography.bodyMedium)
                Card {
                    SwitchLine(
                        "Phone actions",
                        "Alarms, timers, apps and the rest, by your rules. Ask me rules run only unlocked.",
                        tab in actionContexts,
                    ) { onActionsChange(tab, it) }
                    TextButton(onClick = onOpenActions) { Text("Edit the rules") }
                }
                Card {
                    SwitchLine(
                        "Notifications: read aloud only",
                        "On: the phone reads your notifications aloud itself, and the hub never sees them. " +
                            "Off: the hub may list them, so the persona can summarize them.",
                        tab in readAloudOnly,
                    ) { onReadAloudOnlyChange(tab, it) }
                    SwitchLine("Location", "The hub may ask where the phone is.", tab in locationIn) { onLocationChange(tab, it) }
                    if (tab != PhoneContext.UNLOCKED) {
                        SwitchLine(
                            "Confirm replies by voice",
                            "With no screen to tap, a reply is read back (\u201CReply to Alex on Signal: on my way. Send it?\u201D) " +
                                "and sent only if you say yes. Off: replies that ask aren't sent here.",
                            tab in voiceAskIn,
                        ) { onVoiceAskChange(tab, it) }
                    }
                }
                Card {
                    Text("Conversation the persona sees", style = MaterialTheme.typography.bodyLarge)
                    when (tab) {
                        PhoneContext.TRUSTED_DEVICE -> {
                            val choices = listOf("full" to "Everything", "since_lock" to "Since locking")
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                choices.forEachIndexed { i, (value, label) ->
                                    SegmentedButton(
                                        selected = persona.trustedHistory == value,
                                        onClick = { onTrustedHistoryChange(value) },
                                        shape = SegmentedButtonDefaults.itemShape(i, choices.size),
                                    ) { Text(label) }
                                }
                            }
                            Text(
                                "Since locking: the persona doesn't see what was said before the phone locked, so it can't repeat it.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        PhoneContext.UNLOCKED -> Text("Everything in this conversation.", style = MaterialTheme.typography.bodySmall)
                        PhoneContext.LOCKED -> Text("Only what's been said since the phone locked.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Tools(persona, tab, onToolsChange)
            }
        }
    }
}

@Composable
private fun Tools(persona: PersonaPermissions, where: PhoneContext, onToolsChange: (PhoneContext, JsonObject?) -> Unit) {
    val catalog = persona.catalog
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${catalog?.persona ?: "Persona"}'s tools", style = MaterialTheme.typography.titleMedium)
        if (catalog == null) {
            Text(
                "This hub hasn't listed its persona's tools. It decides what the persona may use here.",
                style = MaterialTheme.typography.bodySmall,
            )
            return@Column
        }
        Text(
            "What the persona may use here, up to what the hub allows. A service you change keeps exactly what you choose; " +
                "tools the hub adds to it later start off. Tools marked ⚠ delete or change things and are off until you turn them on.",
            style = MaterialTheme.typography.bodySmall,
        )
        val choices = persona.toolsIn(where)
        catalog.servers.forEach { server -> ServerCard(server, choices, where) { onToolsChange(where, it) } }
        TextButton(onClick = { onToolsChange(where, null) }, enabled = persona.tools[where] != null) { Text("Reset to defaults") }
    }
}

@Composable
private fun ServerCard(server: ToolCatalog.Server, choices: JsonObject, where: PhoneContext, onChange: (JsonObject) -> Unit) {
    var open by rememberSaveable(server.server) { mutableStateOf(false) }
    val state = ToolChoices.state(choices, server, where)
    val allowed = server.tools.any { it.allowedIn(where) }
    Card {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            TriStateCheckbox(
                state = when (state) {
                    ToolChoices.State.ALL -> ToggleableState.On
                    ToolChoices.State.NONE -> ToggleableState.Off
                    ToolChoices.State.MIXED -> ToggleableState.Indeterminate
                },
                onClick = { onChange(ToolChoices.setServer(choices, server, state != ToolChoices.State.ALL, where)) },
                enabled = allowed,
            )
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(server.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (!allowed) "Not allowed here by the hub" else when (state) {
                        ToolChoices.State.ALL -> "All"
                        ToolChoices.State.NONE -> "None"
                        ToolChoices.State.MIXED -> "Mixed"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, if (open) "Hide tools" else "Show tools")
        }
        if (open) {
            server.tools.forEach { tool ->
                val on = ToolChoices.isOn(choices, server.server, tool, where)
                val enabled = tool.allowedIn(where)
                Row(
                    Modifier.fillMaxWidth().toggleable(value = on, enabled = enabled, role = Role.Switch) {
                        onChange(ToolChoices.setTool(choices, server, tool.name, it, where))
                    }.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (tool.destructive) Icon(Icons.Default.Warning, "Deletes or changes things", tint = MaterialTheme.colorScheme.error)
                            Text(tool.label, style = MaterialTheme.typography.bodyMedium)
                        }
                        val detail = if (!enabled) "Not allowed here by the hub" else tool.description
                        if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = on, onCheckedChange = null, enabled = enabled)
                }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun SwitchLine(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
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

private val CONTEXT_EXPLAINED = mapOf(
    PhoneContext.UNLOCKED to "The phone is unlocked.",
    PhoneContext.TRUSTED_DEVICE to
        "The phone is locked, and you asked through a trusted car's or headset's own button. Anyone with it can use what's on here.",
    PhoneContext.LOCKED to
        "The phone is locked otherwise: another headset set to Talk only, and later the nightstand.",
)
