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

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val request = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
            request.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val app = GhostGuardApp.from(this)
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
                )
            }
        }
    }
}

private enum class Tab(val label: String) { STATUS("Guard"), TUNING("Tuning"), LOG("Log") }

@Composable
private fun Root(
    app: GhostGuardApp,
    openNotificationAccess: () -> Unit,
    openAccessibility: () -> Unit,
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
            Tab.STATUS -> StatusScreen(app, openNotificationAccess, openAccessibility, content)
            Tab.TUNING -> TuningScreen(app, content)
            Tab.LOG -> LogScreen(app, content)
        }
    }
}
