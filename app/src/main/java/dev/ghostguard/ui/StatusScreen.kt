package dev.ghostguard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ghostguard.GhostGuardApp
import dev.ghostguard.data.CaptureMode
import dev.ghostguard.service.GuardNotificationListener
import dev.ghostguard.service.GuardService
import dev.ghostguard.service.VolumeKeyAccessibilityService

@Composable
fun StatusScreen(
    app: GhostGuardApp,
    openNotificationAccess: () -> Unit,
    openAccessibility: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val config by app.settings.config.collectAsStateWithLifecycle()
    val entries by app.eventLog.entries.collectAsStateWithLifecycle()

    val blocked = entries.count { !it.allowed }
    val wouldBlock = entries.count { it.shadowed }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("GhostGuard", style = MaterialTheme.typography.headlineMedium)

        SettingRow(
            title = "Guard running",
            subtitle = "Watch headset buttons and drop the false ones",
            checked = config.guardEnabled,
            onCheckedChange = { on ->
                app.settings.update { it.copy(guardEnabled = on) }
                if (on) GuardService.start(context) else GuardService.stop(context)
            },
        )

        SettingRow(
            title = "Observe only",
            subtitle = "Let every press through, but record what would have been dropped. " +
                "Start here for a day, then turn it off.",
            checked = config.tuning.shadowMode,
            onCheckedChange = { on ->
                app.settings.update { it.copy(tuning = it.tuning.copy(shadowMode = on)) }
            },
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("How to catch the presses", style = MaterialTheme.typography.titleMedium)
                Text(
                    "USB takes the headset's button interface over outright, so nothing slips " +
                        "past. It needs a digital USB-C headset. Media keys work with anything, " +
                        "but only while Android routes the buttons here.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CaptureMode.entries.forEach { mode ->
                        FilterChip(
                            selected = config.captureMode == mode,
                            onClick = { app.settings.update { it.copy(captureMode = mode) } },
                            label = {
                                Text(
                                    when (mode) {
                                        CaptureMode.USB_HID -> "USB"
                                        CaptureMode.MEDIA_SESSION -> "Media keys"
                                        CaptureMode.BOTH -> "Both"
                                    }
                                )
                            },
                        )
                    }
                }
            }
        }

        SettingRow(
            title = "Filter volume keys too",
            subtitle = "Needs the accessibility permission below",
            checked = config.filterVolumeKeys,
            onCheckedChange = { on -> app.settings.update { it.copy(filterVolumeKeys = on) } },
        )

        SettingRow(
            title = "Start when the phone boots",
            subtitle = null,
            checked = config.startOnBoot,
            onCheckedChange = { on -> app.settings.update { it.copy(startOnBoot = on) } },
        )

        PermissionCard(
            title = "Notification access",
            granted = GuardNotificationListener.isEnabled(context),
            why = "Lets GhostGuard hand a real press to whatever is playing. Without it, " +
                "accepted presses go out to the system instead, which is less reliable.",
            onFix = openNotificationAccess,
        )

        if (config.filterVolumeKeys) {
            PermissionCard(
                title = "Accessibility access",
                granted = VolumeKeyAccessibilityService.isEnabled(context),
                why = "The only way to see volume keys before the system acts on them. " +
                    "GhostGuard reads key presses only.",
                onFix = openAccessibility,
            )
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Recent", style = MaterialTheme.typography.titleMedium)
                Text("${entries.size} presses recorded", style = MaterialTheme.typography.bodyMedium)
                Text("$blocked dropped as false", style = MaterialTheme.typography.bodyMedium)
                if (config.tuning.shadowMode) {
                    Text(
                        "$wouldBlock would have been dropped if the guard were enforcing",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall)
                }
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    granted: Boolean,
    why: String,
    onFix: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (granted) "$title · granted" else "$title · needed",
                style = MaterialTheme.typography.titleMedium,
                color = if (granted) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Text(why, style = MaterialTheme.typography.bodySmall)
            if (!granted) {
                TextButton(onClick = onFix) { Text("Open settings") }
            }
        }
    }
}
