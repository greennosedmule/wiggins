package com.mulesipstea.wiggins.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
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
import androidx.compose.material3.Checkbox
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.mulesipstea.wiggins.actions.NotificationReader
import com.mulesipstea.wiggins.actions.Notifications
import com.mulesipstea.wiggins.actions.RuleEntity
import com.mulesipstea.wiggins.settings.ActionSettings
import com.mulesipstea.wiggins.settings.PhoneContext
import com.mulesipstea.wiggins.waggle.Mode
import com.mulesipstea.wiggins.waggle.Rule
import com.mulesipstea.wiggins.waggle.commands.DoNotDisturb
import com.mulesipstea.wiggins.waggle.queries.Queries

/** What each query gives the hub, in the user's terms. */
private val QUERY_DETAILS = mapOf(
    "calendar.next" to ("Upcoming events" to "Lets the hub read your next calendar events, to answer \"what's next?\""),
    "contacts.lookup" to ("Contact lookup" to "Lets the hub look up a contact's phone numbers by name, to call or text them. It never gets the whole address book."),
    "apps.list" to ("Installed apps" to "Lets the hub see your launcher apps' names, to open one by name."),
    "notifications.list" to ("Notifications to the hub" to "Lets the hub list the notifications of the apps you picked above, for the persona to read or summarize."),
    "phone.location" to ("Location" to "Lets the hub ask where the phone is, as coordinates. Off when the phone is locked, unless you change that in Permissions."),
    "phone.status" to ("Phone status" to "Lets the hub read the battery, whether you're on Wi-Fi or mobile data, the ringer and Do Not Disturb. Nothing personal."),
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
    onContextChange: (PhoneContext, Boolean) -> Unit = { _, _ -> },
    onOpenPermissions: () -> Unit = {},
    onNotificationAppsChange: (Set<String>) -> Unit = {},
) {
    val context = LocalContext.current
    // Granted or revoked in Android's settings, so checked again whenever this screen comes back.
    var dndAccess by remember { mutableStateOf(DoNotDisturb.hasAccess(context)) }
    val notifications = remember { Notifications(context) }
    var notificationAccess by remember { mutableStateOf(notifications.hasAccess()) }
    var backgroundLocation by remember { mutableStateOf(hasBackgroundLocation(context)) }
    LifecycleResumeEffect(Unit) {
        dndAccess = DoNotDisturb.hasAccess(context)
        notificationAccess = notifications.hasAccess()
        backgroundLocation = hasBackgroundLocation(context)
        onPauseOrDispose {}
    }
    // "All the time" is a second step after the foreground grant; Android shows it in its settings.
    val backgroundPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { backgroundLocation = it }
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
            Section("Where they run", "Phone actions happen only where they're on. Ask me needs the phone's screen, so it never runs on a locked phone.") {
                CONTEXT_DETAILS.forEach { (where, detail) ->
                    val on = where in settings.contexts
                    Row(
                        Modifier.fillMaxWidth().toggleable(value = on, role = Role.Switch, onValueChange = { onContextChange(where, it) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(where.label, style = MaterialTheme.typography.bodyLarge)
                            Text(detail, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = on, onCheckedChange = null)
                    }
                }
                TextButton(onClick = onOpenPermissions) { Text("The persona's tools in each: Permissions") }
            }
            Section("Rules", "What the hub may launch. Allow starts it, Ask me shows you first, Never refuses. The most specific matching rule wins.") {
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
            Section("Phone settings", "Flashlight, volume, ringer and Do Not Disturb: Wiggins sets them itself, by the rules above.") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Do Not Disturb access", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (dndAccess) "Granted." else "Needed to turn Do Not Disturb on or off, and to silence the ringer. Android grants it in its settings.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (!dndAccess) {
                        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }) { Text("Grant") }
                    }
                }
            }
            Section(
                "Notifications",
                "Lets the hub read the notifications of the apps you pick, and reply to messages through them. " +
                    "Where it can't show them to the hub (Permissions), the phone reads them aloud itself.",
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Notification access", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (notificationAccess) "Granted." else "Android grants it in its settings. Wiggins reads only the apps you pick below.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (!notificationAccess) TextButton(onClick = { openNotificationAccess(context) }) { Text("Grant") }
                }
                NotificationApps(settings.notificationApps, onNotificationAppsChange)
            }
            Section("Queries", "What the hub may read from the phone. Each is off until you turn it on, except phone status.") {
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
                if ("phone.location" in settings.queries && !backgroundLocation) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Location works while Wiggins is open. Allow it all the time to answer when it isn't.",
                            Modifier.weight(1f).padding(end = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { backgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }) { Text("Allow") }
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
    Mode.RUN -> "Allow"
    Mode.ASK -> "Ask me"
    Mode.BLOCK -> "Never"
}

/** The contexts, in order, with what each one is (SPEC "Permissions"). */
private val CONTEXT_DETAILS = listOf(
    PhoneContext.UNLOCKED to "The phone is unlocked.",
    PhoneContext.TRUSTED_DEVICE to "Locked, through a trusted car's or headset's voice button. Allow rules run; Ask me rules don't.",
    PhoneContext.LOCKED to "Locked otherwise, such as a talk-only headset. Allow rules run; Ask me rules don't.",
)

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

private fun hasBackgroundLocation(context: Context) =
    context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun openNotificationAccess(context: Context) {
    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(context, NotificationReader::class.java).flattenToString())
    runCatching { context.startActivity(intent) }.onFailure { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
}

/** The apps whose notifications the hub may read: launcher apps, ticked; collapsed to a count. */
@Composable
private fun NotificationApps(picked: Set<String>, onChange: (Set<String>) -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val apps = remember(open) {
        if (!open) emptyList() else runCatching {
            val pm = context.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.ResolveInfoFlags.of(0))
                .mapNotNull { info -> info.activityInfo?.packageName?.takeIf { it != context.packageName }?.let { it to info.loadLabel(pm).toString() } }
                .distinctBy { it.first }.sortedBy { it.second.lowercase() }
        }.getOrDefault(emptyList())
    }
    TextButton(onClick = { open = !open }) {
        Text(if (picked.isEmpty()) "Pick apps (none yet)" else "Apps: ${picked.size} picked")
    }
    if (open) {
        apps.forEach { (pkg, label) ->
            val on = pkg in picked
            Row(
                Modifier.fillMaxWidth().toggleable(value = on, role = Role.Checkbox) { onChange(if (it) picked + pkg else picked - pkg) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = on, onCheckedChange = null)
                Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

