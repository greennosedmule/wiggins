package com.mulesipstea.wiggins.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mulesipstea.wiggins.actions.PhoneActions
import kotlinx.coroutines.delay

/**
 * The hub asks to launch something (SPEC "Asking"): the hub's own description, and beneath
 * it what would actually run, so a hub can't disguise a request. "Allow always" (for an
 * intent no rule covers) adds a rule, so it isn't asked again.
 */
@Composable
internal fun AskCard(ask: PhoneActions.Ask, onAnswer: (allow: Boolean, always: Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ask.key) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val secondsLeft = ((ask.deadlineMillis - now + 999) / 1000).coerceAtLeast(0)
    val request = ask.request
    Surface(
        color = colors.tertiaryContainer,
        contentColor = colors.onTertiaryContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("ask"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("The hub asks to", style = MaterialTheme.typography.labelLarge)
            Text(request.description?.takeIf { it.isNotBlank() } ?: shortAction(request.action), style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Detail("Action", request.action)
                request.data?.let { Detail("Data", it) }
                if (request.categories.isNotEmpty()) Detail("Category", request.categories.joinToString())
                request.packageName?.let { Detail("Package", it) }
                Detail("Opens", ask.target ?: "a choice of apps")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onAnswer(false, false) }) { Text("Deny") }
                Button(onClick = { onAnswer(true, false) }) { Text("Allow") }
                if (ask.rule == null) TextButton(onClick = { onAnswer(true, true) }) { Text("Allow always") }
            }
            Text("No answer in $secondsLeft s counts as no.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row {
        Text("$label: ", style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

/** `android.intent.action.SET_ALARM` → `SET_ALARM`. */
internal fun shortAction(action: String) = action.removePrefix("android.intent.action.")
