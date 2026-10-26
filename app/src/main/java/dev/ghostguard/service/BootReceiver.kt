package dev.ghostguard.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.ghostguard.GhostGuardApp

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val config = GhostGuardApp.from(context).settings.config.value
        if (config.guardEnabled && config.startOnBoot) {
            GuardService.start(context)
        }
    }
}
