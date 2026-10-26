package dev.ghostguard.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.ghostguard.GhostGuardApp
import dev.ghostguard.service.GuardService

class MainActivity : ComponentActivity() {

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            // A USB headset can only be opened once the record permission is
            // held, so let a running guard try again straight away.
            if (granted[Manifest.permission.RECORD_AUDIO] == true &&
                GhostGuardApp.from(this).settings.config.value.guardEnabled
            ) {
                GuardService.start(this)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (savedInstanceState == null) permissions.launch(wanted.toTypedArray())

        val app = GhostGuardApp.from(this)
        // If the guard is meant to be on but died, opening the app revives it.
        if (app.settings.config.value.guardEnabled) GuardService.start(this)

        setContent {
            GhostGuardTheme {
                Root(
                    app = app,
                    openNotificationAccess = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    openAccessibility = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    requestMicrophone = {
                        permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    },
                )
            }
        }
    }
}

private enum class Tab(val label: String) { STATUS("Guard"), TUNING("Tuning"), LOG("Log"), DEBUG("Debug") }

@Composable
private fun Root(
    app: GhostGuardApp,
    openNotificationAccess: () -> Unit,
    openAccessibility: () -> Unit,
    requestMicrophone: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.STATUS) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        label = { Text(entry.label) },
                        icon = {
                            Icon(
                                imageVector = when (entry) {
                                    Tab.STATUS -> Icons.Filled.Shield
                                    Tab.TUNING -> Icons.Filled.Tune
                                    Tab.LOG -> Icons.Filled.List
                                    Tab.DEBUG -> Icons.Filled.BugReport
                                },
                                contentDescription = entry.label,
                            )
                        },
                    )
                }
            }
        },
    ) { padding ->
        val content = Modifier.padding(padding)
        when (tab) {
            Tab.STATUS -> StatusScreen(app, openNotificationAccess, openAccessibility, requestMicrophone, content)
            Tab.TUNING -> TuningScreen(app, content)
            Tab.LOG -> LogScreen(app, content)
            Tab.DEBUG -> DebugScreen(app, content)
        }
    }
}
