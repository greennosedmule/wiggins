package com.mulesipstea.wiggins.ui

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mulesipstea.wiggins.actions.RuleEntity
import com.mulesipstea.wiggins.settings.ActionSettings
import com.mulesipstea.wiggins.waggle.Mode
import com.mulesipstea.wiggins.waggle.Rule
import com.mulesipstea.wiggins.waggle.queries.Queries

/** What each query gives the hub, in the user's terms. */
private val QUERY_DETAILS = mapOf(
    "calendar.next" to ("Upcoming events" to "Lets the hub read your next calendar events, to answer \"what's next?\""),
    "contacts.lookup" to ("Contact lookup" to "Lets the hub look up a contact's phone numbers by name, to call or text them. It never gets the whole address book."),
    "apps.list" to ("Installed apps" to "Lets the hub see your launcher apps' names, to open one by name."),
)

/**
 * The rules for what the hub may do on the phone (SPEC "Phone actions (Waggle)"): the
 * allowlist, what happens to an intent no rule matches, and which queries are enabled.
 * They're edited only here, never from the hub.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneActionsScreen(
    rules: List<RuleEntity>,
    settings: ActionSettings,
    onSaveRule: (Rule, Long) -> Unit,
    onDeleteRule: (RuleEntity) -> Unit,
    onResetRules: () -> Unit,
    onUnmatchedChange: (Mode) -> Unit,
    onQueryEnabledChange: (String, Boolean) -> Unit,
    onOpenLog: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<RuleEntity?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    // A query that needs a permission is enabled once the permission is granted.
    var pendingQuery by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingQuery?.let { if (granted) onQueryEnabledChange(it, true) }
        pendingQuery = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Phone actions") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = onOpenLog) { Icon(Icons.AutoMirrored.Filled.List, "Action log") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Section("Rules", "What the hub may launch. Run starts it, Ask shows you first, Block refuses. The most specific matching rule wins.") {
                rules.forEach { rule -> RuleRow(rule) { editing = rule } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { editing = RuleEntity(action = "android.intent.action.", mode = Mode.ASK.wire) }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text("Add rule", Modifier.padding(start = 6.dp))
                    }
                    TextButton(onClick = { confirmReset = true }) { Text("Reset to defaults") }
                }
            }
            Section("Anything else", "What happens when the hub asks for something no rule covers.") {
                ModeChoice(
                    selected = settings.unmatched,
                    choices = listOf(Mode.BLOCK, Mode.ASK),
                    onSelect = onUnmatchedChange,
                )
                Text(
                    if (settings.unmatched == Mode.ASK) {
                        "You're asked each time, and can allow it always."
                    } else {
                        "Refused. Safest: new skill actions need a rule first."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Section("Queries", "What the hub may read from the phone. Each is off until you turn it on.") {
                Queries.all.forEach { query ->
                    val (title, detail) = QUERY_DETAILS[query.name] ?: (query.name to "")
                    val enabled = query.name in settings.queries
                    val toggle = { on: Boolean ->
                        val needed = query.permission
                        if (on && needed != null && context.checkSelfPermission(needed) != PackageManager.PERMISSION_GRANTED) {
                            pendingQuery = query.name
                            permission.launch(needed)
                        } else {
                            onQueryEnabledChange(query.name, on)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().toggleable(value = enabled, role = Role.Switch, onValueChange = toggle),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(title, style = MaterialTheme.typography.bodyLarge)
                            Text(detail, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = enabled, onCheckedChange = null)
                    }
                }
            }
        }
    }

    editing?.let { rule ->
        RuleEditor(
            initial = rule,
            onSave = { onSaveRule(it, rule.id); editing = null },
            onDelete = if (rule.id != 0L) ({ onDeleteRule(rule); editing = null }) else null,
            onDismiss = { editing = null },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset the rules?") },
            text = { Text("Your rules are replaced by the ones Wiggins ships with.") },
            confirmButton = { TextButton(onClick = { onResetRules(); confirmReset = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RuleRow(rule: RuleEntity, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(shortAction(rule.action), style = MaterialTheme.typography.bodyLarge)
            val details = listOfNotNull(
                rule.scheme?.let { "$it:" },
                rule.category?.removePrefix("android.intent.category.")?.let { "category $it" },
                rule.packageName,
            )
            if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
        val mode = Mode.fromWire(rule.mode) ?: Mode.BLOCK
        Surface(
            color = when (mode) {
                Mode.RUN -> MaterialTheme.colorScheme.secondaryContainer
                Mode.ASK -> MaterialTheme.colorScheme.tertiaryContainer
                Mode.BLOCK -> MaterialTheme.colorScheme.errorContainer
            },
            shape = RoundedCornerShape(8.dp),
        ) { Text(mode.label(), Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium) }
    }
}

/** Adds or edits a rule. Fields left blank match anything. */
@Composable
private fun RuleEditor(initial: RuleEntity, onSave: (Rule) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    var action by remember { mutableStateOf(initial.action) }
    var scheme by remember { mutableStateOf(initial.scheme.orEmpty()) }
    var pkg by remember { mutableStateOf(initial.packageName.orEmpty()) }
    var category by remember { mutableStateOf(initial.category.orEmpty()) }
    var mode by remember { mutableStateOf(Mode.fromWire(initial.mode) ?: Mode.ASK) }
    val valid = action.isNotBlank() && !action.endsWith(".")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "Add rule" else "Edit rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(action, { action = it.trim() }, label = { Text("Action") }, singleLine = true)
                OutlinedTextField(scheme, { scheme = it.trim() }, label = { Text("URI scheme (optional)") }, placeholder = { Text("tel") }, singleLine = true)
                OutlinedTextField(category, { category = it.trim() }, label = { Text("Category (optional)") }, singleLine = true)
                OutlinedTextField(pkg, { pkg = it.trim() }, label = { Text("Package (optional)") }, singleLine = true)
                ModeChoice(selected = mode, choices = Mode.entries, onSelect = { mode = it })
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        Rule(
                            action = action,
                            scheme = scheme.takeIf { it.isNotEmpty() }?.removeSuffix(":")?.lowercase(),
                            packageName = pkg.takeIf { it.isNotEmpty() },
                            category = category.takeIf { it.isNotEmpty() },
                            mode = mode,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text("Delete", color = MaterialTheme.colorScheme.error) } }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun ModeChoice(selected: Mode, choices: List<Mode>, onSelect: (Mode) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        choices.forEachIndexed { i, mode ->
            SegmentedButton(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(i, choices.size),
            ) { Text(mode.label()) }
        }
    }
}

private fun Mode.label() = when (this) {
    Mode.RUN -> "Run"
    Mode.ASK -> "Ask"
    Mode.BLOCK -> "Block"
}

@Composable
private fun Section(title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.padding(horizontal = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        }
    }
}
