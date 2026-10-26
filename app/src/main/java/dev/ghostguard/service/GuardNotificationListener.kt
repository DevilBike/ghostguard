package dev.ghostguard.service

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * Holds nothing and does nothing. Its only job is to exist, because
 * `MediaSessionManager.getActiveSessions` will only talk to an app that has an
 * enabled notification listener, and that is how GhostGuard finds the player
 * it should forward a real press to.
 */
class GuardNotificationListener : NotificationListenerService() {

    companion object {
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ).orEmpty()
            val me = ComponentName(context, GuardNotificationListener::class.java)
            return enabled.split(':').any {
                ComponentName.unflattenFromString(it) == me
            }
        }
    }
}
