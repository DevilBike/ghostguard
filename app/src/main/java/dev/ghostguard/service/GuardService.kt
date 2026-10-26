package dev.ghostguard.service

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.LifecycleService
import dev.ghostguard.GhostGuardApp
import dev.ghostguard.R
import dev.ghostguard.core.ButtonMapping
import dev.ghostguard.data.CaptureMode
import dev.ghostguard.data.Config
import dev.ghostguard.filter.Button
import dev.ghostguard.filter.Emission
import dev.ghostguard.filter.PhantomFilter
import dev.ghostguard.filter.RawEvent
import dev.ghostguard.ui.MainActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The guard itself: owns the filter, both capture paths, and the forwarder,
 * and keeps a foreground notification so Android leaves it running.
 */
class GuardService : LifecycleService() {

    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private lateinit var forwarder: Forwarder
    private lateinit var mediaCapture: MediaCapture
    private lateinit var usbCapture: UsbHidCapture

    private val filter = PhantomFilter()
    private var config = Config()
    private var usbStatus: UsbHidCapture.Status = UsbHidCapture.Status.Stopped
    private var blockedSinceStart = 0

    private val ticker = object : Runnable {
        override fun run() {
            handle(filter.tick(SystemClock.uptimeMillis()), SOURCE_TICK, lastDeviceName)
            if (filter.hasPendingPress) handler.postDelayed(this, TICK_MS)
        }
    }

    private var lastDeviceName = ""

    /** A plug, unplug, or route change is the most likely moment for chatter. */
    private val plugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handler.post {
                filter.quarantineFor(SystemClock.uptimeMillis())
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> usbCapture.scan()
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        usbCapture.stop()
                        filter.reset()
                    }
                }
                mediaCapture.reassert()
                updateNotification()
            }
        }
    }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener {
        // Another player just took the stage; claim the media buttons back.
        handler.postDelayed({ mediaCapture.reassert() }, REASSERT_DELAY_MS)
    }

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("ghostguard-filter").also { it.start() }
        handler = Handler(worker.looper)

        forwarder = Forwarder(this)
        mediaCapture = MediaCapture(this, handler, ::onMediaKey)
        usbCapture = UsbHidCapture(this, ::onUsbEvent) { status ->
            handler.post {
                usbStatus = status
                updateNotification()
            }
        }

        GuardBridge.volumeKeySink = ::onVolumeKey

        ContextCompat.registerReceiver(
            this,
            plugReceiver,
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(AudioManager.ACTION_HEADSET_PLUG)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )

        watchActiveSessions()

        lifecycleScope.launch {
            GhostGuardApp.from(this@GuardService).settings.config.collectLatest { applyConfig(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForegroundCompat()
        // Notification access may have been granted since onCreate.
        handler.post { watchActiveSessions() }
        return START_STICKY
    }

    override fun onDestroy() {
        GuardBridge.volumeKeySink = null
        runCatching {
            getSystemService(MediaSessionManager::class.java)
                .removeOnActiveSessionsChangedListener(sessionsChanged)
        }
        runCatching { unregisterReceiver(plugReceiver) }
        mediaCapture.stop()
        usbCapture.stop()
        worker.quitSafely()
        super.onDestroy()
    }

    private var watchingSessions = false

    /** Needs notification access, so it is retried until that is granted. */
    private fun watchActiveSessions() {
        if (watchingSessions) return
        watchingSessions = runCatching {
            getSystemService(MediaSessionManager::class.java).addOnActiveSessionsChangedListener(
                sessionsChanged,
                ComponentName(this, GuardNotificationListener::class.java),
            )
        }.isSuccess
    }

    private fun applyConfig(next: Config) {
        handler.post {
            config = next
            filter.tuning = next.tuning

            val wantsUsb = next.captureMode != CaptureMode.MEDIA_SESSION
            val wantsMedia = next.captureMode != CaptureMode.USB_HID ||
                usbStatus !is UsbHidCapture.Status.Claimed

            if (wantsUsb) usbCapture.start() else usbCapture.stop()
            if (wantsMedia) mediaCapture.start() else mediaCapture.stop()

            updateNotification()
        }
    }

    // ---- capture callbacks -------------------------------------------------

    private fun onMediaKey(event: KeyEvent) {
        if (SystemClock.uptimeMillis() < forwarder.ignoreSelfUntilUptimeMs) return
        val button = ButtonMapping.fromKeyCode(event.keyCode)
        if (button == Button.UNKNOWN) return

        submit(
            RawEvent(
                button = button,
                down = event.action == KeyEvent.ACTION_DOWN,
                timestampMs = event.eventTime,
                deviceKey = event.deviceId.toString(),
                repeat = event.repeatCount > 0,
            ),
            source = SOURCE_MEDIA,
            deviceName = deviceNameFor(event.deviceId),
        )
    }

    private fun onUsbEvent(button: Button, down: Boolean, uptimeMs: Long, deviceName: String) {
        submit(
            RawEvent(button = button, down = down, timestampMs = uptimeMs, deviceKey = deviceName),
            source = SOURCE_USB,
            deviceName = deviceName,
        )
    }

    /**
     * Called from the accessibility service on its own thread. Volume keys are
     * always consumed while filtering is on; accepted ones are re-applied by
     * the forwarder, so the volume still moves.
     */
    private fun onVolumeKey(event: KeyEvent): Boolean {
        if (!config.guardEnabled || !config.filterVolumeKeys) return false
        val button = ButtonMapping.fromKeyCode(event.keyCode)
        if (!ButtonMapping.isVolume(button)) return false

        submit(
            RawEvent(
                button = button,
                down = event.action == KeyEvent.ACTION_DOWN,
                timestampMs = event.eventTime,
                deviceKey = event.deviceId.toString(),
                repeat = event.repeatCount > 0,
            ),
            source = SOURCE_VOLUME,
            deviceName = deviceNameFor(event.deviceId),
        )
        return true
    }

    // ---- filtering ---------------------------------------------------------

    private fun submit(event: RawEvent, source: String, deviceName: String) {
        handler.post {
            lastDeviceName = deviceName
            handle(filter.submit(event), source, deviceName)
            if (filter.hasPendingPress) {
                handler.removeCallbacks(ticker)
                handler.postDelayed(ticker, TICK_MS)
            }
        }
    }

    private fun handle(emissions: List<Emission>, source: String, deviceName: String) {
        if (emissions.isEmpty()) return
        val log = GhostGuardApp.from(this).eventLog

        for (emission in emissions) {
            log.record(emission, source, deviceName)
            when (emission) {
                is Emission.Accepted -> forwarder.dispatch(emission.button, emission.longPress)
                is Emission.Blocked -> blockedSinceStart++
            }
        }
        updateNotification()
    }

    // ---- notification ------------------------------------------------------

    private fun startForegroundCompat() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), type)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun updateNotification() {
        androidx.core.app.NotificationManagerCompat.from(this)
            .takeIf { it.areNotificationsEnabled() }
            ?.let { runCatching { it.notify(NOTIFICATION_ID, buildNotification()) } }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val mode = when (val status = usbStatus) {
            is UsbHidCapture.Status.Claimed -> "Filtering ${status.deviceName} over USB"
            else -> if (mediaCapture.isRunning) "Filtering media keys" else "Idle"
        }
        val tail = if (config.tuning.shadowMode) {
            " · observing only"
        } else {
            " · $blockedSinceStart blocked"
        }

        return NotificationCompat.Builder(this, GhostGuardApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle("GhostGuard")
            .setContentText(mode + tail)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun deviceNameFor(deviceId: Int): String =
        runCatching { android.view.InputDevice.getDevice(deviceId)?.name }.getOrNull()
            ?: "input $deviceId"

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val TICK_MS = 30L
        private const val REASSERT_DELAY_MS = 400L
        private const val SOURCE_USB = "usb"
        private const val SOURCE_MEDIA = "media-key"
        private const val SOURCE_VOLUME = "volume-key"
        private const val SOURCE_TICK = "hold"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GuardService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GuardService::class.java))
        }
    }
}
