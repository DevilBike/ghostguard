package dev.ghostguard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ghostguard.GhostGuardApp
import dev.ghostguard.core.ButtonMapping
import dev.ghostguard.data.LogEntry
import dev.ghostguard.filter.Reason
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CLOCK = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

@Composable
fun LogScreen(app: GhostGuardApp, modifier: Modifier = Modifier) {
    val entries by app.eventLog.entries.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Event log", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = { app.eventLog.clear() }) { Text("Clear") }
        }

        if (entries.isEmpty()) {
            Text(
                "Nothing yet. Turn the guard on, plug the headset in, and press a button.",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(entries.asReversed()) { entry -> EntryRow(entry) }
        }
    }
}

@Composable
private fun EntryRow(entry: LogEntry) {
    if (entry.message != null) {
        ConnectionRow(entry)
        return
    }
    val verdict = when {
        entry.shadowed -> "would block"
        entry.allowed -> "allowed"
        else -> "blocked"
    }
    val tint = when {
        entry.shadowed -> MaterialTheme.colorScheme.tertiary
        entry.allowed -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.error
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    CLOCK.format(Date(entry.wallClockMs)),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(ButtonMapping.label(entry.button), style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "$verdict · ${explain(entry.reason)}" +
                    (entry.holdMs?.let { " · held ${it} ms" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = tint,
            )
            Text(
                "${entry.source} · ${entry.deviceName}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun ConnectionRow(entry: LogEntry) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    CLOCK.format(Date(entry.wallClockMs)),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text("Connection", style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                entry.message.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(entry.deviceName, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun explain(reason: Reason): String = when (reason) {
    Reason.CLEAN -> "looked deliberate"
    Reason.CLEAN_LONG_PRESS -> "held down"
    Reason.PLUG_QUARANTINE -> "right after a plug change"
    Reason.CHATTER_QUARANTINE -> "cooling down after chatter"
    Reason.CHATTER_BURST -> "too many presses at once"
    Reason.DEBOUNCE -> "same button again too soon"
    Reason.CROSS_TALK -> "another button too soon after one"
    Reason.TOO_SHORT -> "too short to be a finger"
    Reason.STUCK -> "held far too long, contact stuck"
    Reason.ORPHAN_UP -> "release with no press"
    Reason.DUPLICATE -> "exact duplicate"
    Reason.BUTTON_DISABLED -> "button switched off"
}
