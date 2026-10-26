package dev.ghostguard.service

import android.content.ComponentName
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.SystemClock

/**
 * Handles the other way a bad cable stops your music: not a phantom button,
 * but the plug losing contact for a moment. Android sees the headset vanish,
 * tells every player the audio is "becoming noisy", and the player pauses.
 * No button event exists to filter, so the only fix is to notice the headset
 * coming straight back and press play again on the player that was going.
 */
class DropoutResumer(
    private val context: Context,
    private val handler: Handler,
    private val onConnectionEvent: (message: String, deviceName: String) -> Unit,
) {

    var enabled: Boolean = true

    private val audio = context.getSystemService(AudioManager::class.java)
    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(context, GuardNotificationListener::class.java)

    private class Dropout(val player: MediaController?, val atMs: Long, val deviceName: String)

    private var dropout: Dropout? = null
    private var noisyPlayer: MediaController? = null
    private var noisyAtMs = 0L
    private var registered = false

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            removed.firstOrNull { it.isHeadsetOutput() }?.let { onDropped(it.productName.toString()) }
        }

        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            // Also called once at registration with the devices already
            // present; with no dropout pending those are simply ignored.
            added.firstOrNull { it.isHeadsetOutput() }?.let { onReturned(it.productName.toString()) }
        }
    }

    fun start() {
        if (registered) return
        audio.registerAudioDeviceCallback(callback, handler)
        registered = true
    }

    fun stop() {
        if (!registered) return
        audio.unregisterAudioDeviceCallback(callback)
        registered = false
        dropout = null
    }

    /**
     * ACTION_AUDIO_BECOMING_NOISY can land before the device-removed callback,
     * while the player still reports playing, so remember who it was.
     */
    fun onBecomingNoisy() {
        noisyPlayer = playingPlayer()
        noisyAtMs = SystemClock.elapsedRealtime()
    }

    private fun onDropped(deviceName: String) {
        val now = SystemClock.elapsedRealtime()
        val player = noisyPlayer?.takeIf { now - noisyAtMs < NOISY_MATCH_MS } ?: playingPlayer()
        noisyPlayer = null
        dropout = Dropout(player, now, deviceName)

        onConnectionEvent(
            "lost contact" + (player?.let { " while ${it.packageName} was playing" } ?: ""),
            deviceName,
        )
    }

    private fun onReturned(deviceName: String) {
        val lost = dropout ?: return
        dropout = null

        val gap = SystemClock.elapsedRealtime() - lost.atMs
        val player = lost.player
        when {
            player == null -> onConnectionEvent("back after $gap ms (nothing was playing)", deviceName)
            !enabled -> onConnectionEvent("back after $gap ms (auto-resume is off)", deviceName)
            gap > RESUME_WINDOW_MS -> onConnectionEvent(
                "back after $gap ms, too long to be a glitch, so not resuming",
                deviceName,
            )

            else -> handler.postDelayed({
                // Give the audio route a moment to settle on the headset,
                // or the first second plays out of the speaker.
                runCatching { player.transportControls.play() }
                    .onSuccess {
                        onConnectionEvent("back after $gap ms, resumed ${player.packageName}", deviceName)
                    }
                    .onFailure {
                        onConnectionEvent("back after $gap ms, could not resume: $it", deviceName)
                    }
            }, ROUTE_SETTLE_MS)
        }
    }

    /** The player that is going now, or that paused in the last few seconds. */
    private fun playingPlayer(): MediaController? {
        val sessions = runCatching { sessionManager.getActiveSessions(listenerComponent) }
            .getOrDefault(emptyList())
            .filter { it.packageName != context.packageName }
        val now = SystemClock.elapsedRealtime()

        return sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull {
                val state = it.playbackState ?: return@firstOrNull false
                state.state == PlaybackState.STATE_PAUSED &&
                    now - state.lastPositionUpdateTime < JUST_PAUSED_MS
            }
    }

    private fun AudioDeviceInfo.isHeadsetOutput(): Boolean =
        isSink && type in HEADSET_TYPES

    private companion object {
        const val RESUME_WINDOW_MS = 8_000L
        const val ROUTE_SETTLE_MS = 700L
        const val JUST_PAUSED_MS = 3_000L
        const val NOISY_MATCH_MS = 2_000L

        val HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        )
    }
}
