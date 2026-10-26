package dev.ghostguard.service

import android.content.Context
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import dev.ghostguard.GhostGuardApp

/**
 * Holds nothing and does nothing with notifications. Its only job is to exist,
 * because `MediaSessionManager.getActiveSessions` will only talk to an app that
 * has an enabled notification listener, and that is how GhostGuard finds the
 * player it should forward a real press to.
 */
class GuardNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Access may have just been granted while the guard was already
        // running; poke it so it can pick up the media session APIs.
        if (GhostGuardApp.from(this).settings.config.value.guardEnabled) {
            GuardService.start(this)
        }
    }

    companion object {
        fun isEnabled(context: Context): Boolean =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    }
}
