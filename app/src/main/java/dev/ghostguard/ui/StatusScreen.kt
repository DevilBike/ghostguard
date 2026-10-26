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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ghostguard.BuildConfig
import dev.ghostguard.GhostGuardApp
import dev.ghostguard.data.CaptureMode
import dev.ghostguard.service.GuardNotificationListener
import dev.ghostguard.service.GuardService
import dev.ghostguard.service.UsbHidCapture
import dev.ghostguard.service.VolumeKeyAccessibilityService

@Composable
fun StatusScreen(
    app: GhostGuardApp,
    openNotificationAccess: () -> Unit,
    openAccessibility: () -> Unit,
    requestMicrophone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val config by app.settings.config.collectAsStateWithLifecycle()
    val entries by app.eventLog.entries.collectAsStateWithLifecycle()

    // Permissions are granted in system settings, outside the app, so check
    // them again every time the user comes back.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose {}
    }
    val listenerGranted = remember(resumes) { GuardNotificationListener.isEnabled(context) }
    val accessibilityGranted = remember(resumes) { VolumeKeyAccessibilityService.isEnabled(context) }
    val microphoneGranted = remember(resumes) { UsbHidCapture.hasRecordPermission(context) }

    val blocked = entries.count { !it.allowed }
    val wouldBlock = entries.count { it.shadowed }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text("GhostGuard", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelMedium,
            )
        }

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
            title = "Resume after a dropout",
            subtitle = "If the plug loses contact for a moment and your music pauses, " +
                "start it again as soon as the earbuds come back",
            checked = config.resumeAfterGlitch,
            onCheckedChange = { on -> app.settings.update { it.copy(resumeAfterGlitch = on) } },
        )

        SettingRow(
            title = "Start when the phone boots",
            subtitle = null,
            checked = config.startOnBoot,
            onCheckedChange = { on -> app.settings.update { it.copy(startOnBoot = on) } },
        )

        PermissionCard(
            title = "Notification access",
            granted = listenerGranted,
            why = "Lets GhostGuard hand a real press to whatever is playing. Without it, " +
                "accepted presses go out to the system instead, which is less reliable.",
            onFix = openNotificationAccess,
        )

        if (config.captureMode != CaptureMode.MEDIA_SESSION) {
            PermissionCard(
                title = "Microphone (for USB access)",
                granted = microphoneGranted,
                why = "Android only lets an app open a USB headset that has a mic if the app " +
                    "may record audio. GhostGuard never opens the mic; it reads the button " +
                    "interface only.",
                onFix = requestMicrophone,
            )
        }

        if (config.filterVolumeKeys) {
            PermissionCard(
                title = "Accessibility access",
                granted = accessibilityGranted,
                why = "The only way to see volume keys before the system acts on them. " +
                    "GhostGuard reads key presses only.",
                onFix = openAccessibility,
            )
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Recent", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${entries.count { it.message == null }} presses recorded",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "${entries.count { it.message?.startsWith("lost") == true }} times the plug lost contact",
                    style = MaterialTheme.typography.bodyMedium,
                )
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
