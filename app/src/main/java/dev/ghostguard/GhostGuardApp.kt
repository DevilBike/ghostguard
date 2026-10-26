package dev.ghostguard

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.ghostguard.data.EventLog
import dev.ghostguard.data.Settings
import java.io.File

class GhostGuardApp : Application() {

    lateinit var settings: Settings
        private set

    lateinit var eventLog: EventLog
        private set

    val crashFile: File get() = File(filesDir, "last_crash.txt")

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        eventLog = EventLog(File(filesDir, "events.csv"))

        // Keep the last crash so the debug screen can show it after a restart.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                crashFile.writeText("${java.util.Date()}\n${error.stackTraceToString()}")
            }
            previous?.uncaughtException(thread, error)
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Guard status",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows that GhostGuard is watching your headset buttons."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "guard_status"

        fun from(context: Context): GhostGuardApp =
            context.applicationContext as GhostGuardApp
    }
}
