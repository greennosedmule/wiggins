package com.mulesipstea.wiggins.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mulesipstea.wiggins.hivemind.ConnectionState

/** The full app: the saved conversation (SPEC "Conversation screen"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    connection: ConnectionState,
    transcript: List<TranscriptEntry>,
    thinking: Boolean,
    banner: Banner?,
    onSend: (String) -> Unit,
    onResend: (Long) -> Unit,
    onClearConversation: () -> Unit,
    canListen: Boolean,
    onListen: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMessages: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Wiggins")
                        StatusLine(connection)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenMessages) { Icon(Icons.AutoMirrored.Filled.List, "Hub messages") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings") }
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Setup") }, onClick = { menu = false; onOpenSetup() })
                        DropdownMenuItem(
                            text = { Text("Clear conversation") },
                            enabled = transcript.isNotEmpty(),
                            onClick = { menu = false; onClearConversation() },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            banner?.let { BannerRow(it) }
            ConversationList(
                transcript = transcript,
                thinking = thinking,
                onResend = onResend,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                EmptyConversation(canListen, onAsk = onSend)
            }
            InputRow(onSend, canListen, onListen)
        }
    }
}
