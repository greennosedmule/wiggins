package com.mulesipstea.wiggins.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.mulesipstea.wiggins.hivemind.ConnectionState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/**
 * Every message type the hub sent down, newest first; M1 uses this to see what stock
 * skills send. Also which handshake the connection used, and a switch to force Noise.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    messages: List<LoggedMessage>,
    connection: ConnectionState,
    forceNoise: Boolean,
    onForceNoiseChange: (Boolean) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    val counts = remember(messages) {
        messages.groupingBy { it.busType ?: it.hiveType }.eachCount().entries.sortedByDescending { it.value }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hub messages") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = onClear) { Icon(Icons.Default.Delete, "Clear") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp)) {
            item {
                val handshake = when (connection) {
                    is ConnectionState.Connected ->
                        if (connection.protocolVersion >= 3) "Noise (protocol v3)" else "password (protocol v${connection.protocolVersion})"
                    else -> "not connected"
                }
                Text("Handshake: $handshake", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Force the Noise handshake", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "For testing. Hubs before hivemind-core 5 can't send large messages over Noise, so hub speech may fail.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = forceNoise, onCheckedChange = onForceNoiseChange)
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Types seen", style = MaterialTheme.typography.titleSmall)
                counts.forEach { (type, n) -> Text("$type × $n", fontFamily = FontFamily.Monospace) }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }
            items(messages, key = { it.id }) { LogRow(it) }
        }
    }
}

@Composable
private fun LogRow(message: LoggedMessage) {
    var expanded by remember { mutableStateOf(false) }
    val time = remember(message.timeMillis) { DateFormat.getTimeInstance().format(Date(message.timeMillis)) }
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 6.dp)) {
        Text("$time  ${message.busType ?: "—"}", style = MaterialTheme.typography.bodyMedium)
        Text(message.hiveType, style = MaterialTheme.typography.labelSmall)
        if (expanded) Text(message.json, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}
