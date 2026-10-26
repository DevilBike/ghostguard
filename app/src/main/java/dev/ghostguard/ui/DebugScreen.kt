package dev.ghostguard.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.hardware.usb.UsbManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ghostguard.BuildConfig
import dev.ghostguard.GhostGuardApp
import dev.ghostguard.service.Diagnostics
import dev.ghostguard.service.GuardNotificationListener
import dev.ghostguard.service.UsbHidCapture

/**
 * Everything needed to work out why presses are not showing up, in a form the
 * user can copy and paste to whoever is helping them.
 */
@Composable
fun DebugScreen(app: GhostGuardApp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by Diagnostics.state.collectAsStateWithLifecycle()
    val config by app.settings.config.collectAsStateWithLifecycle()

    var refreshes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refreshes++
        onPauseOrDispose {}
    }
    val usbDevices = remember(refreshes, state.usbStatus) { describeUsbDevices(context) }
    val crash = remember(refreshes) {
        runCatching { app.crashFile.takeIf { it.exists() }?.readText() }.getOrNull()
    }

    val report = buildString {
        appendLine("GhostGuard ${BuildConfig.VERSION_NAME} diagnostics")
        appendLine("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        appendLine("guard switch: ${if (config.guardEnabled) "on" else "off"}, mode: ${config.captureMode}, observe-only: ${config.tuning.shadowMode}")
        appendLine("service running: ${state.serviceRunning}")
        appendLine("usb: ${state.usbStatus}")
        appendLine("media session active: ${state.mediaSessionActive}")
        appendLine("notification access: ${GuardNotificationListener.isEnabled(context)}")
        appendLine("record permission: ${UsbHidCapture.hasRecordPermission(context)}")
        appendLine("raw events received: " + state.rawEventsBySource.ifEmpty { mapOf("none" to 0) }
            .entries.joinToString { "${it.key}=${it.value}" })
        appendLine()
        appendLine("USB devices:")
        appendLine(usbDevices)
        appendLine()
        appendLine("Trace (newest last):")
        state.trace.forEach { appendLine(it) }
        if (crash != null) {
            appendLine()
            appendLine("Last crash:")
            appendLine(crash)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Diagnostics", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Press a headset button a few times, then copy this and send it over.",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { copy(context, report) }) { Text("Copy all") }
            if (crash != null) {
                OutlinedButton(onClick = {
                    app.crashFile.delete()
                    refreshes++
                }) { Text("Clear crash") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Text(
                report,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private fun describeUsbDevices(context: Context): String {
    val usb = context.getSystemService(UsbManager::class.java)
    val devices = usb.deviceList.values
    if (devices.isEmpty()) return "  none connected"

    return devices.joinToString("\n") { device ->
        val interfaces = (0 until device.interfaceCount).joinToString { i ->
            val iface = device.getInterface(i)
            "#${iface.id} class ${iface.interfaceClass}/${iface.interfaceSubclass}" +
                " (${iface.endpointCount} ep)"
        }
        "  ${device.productName ?: "?"} [%04x:%04x] access=${usb.hasPermission(device)}\n    $interfaces"
            .format(device.vendorId, device.productId)
    }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("GhostGuard diagnostics", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}
