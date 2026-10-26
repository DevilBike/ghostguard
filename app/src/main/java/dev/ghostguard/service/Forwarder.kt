package dev.ghostguard.service

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.KeyEvent
import dev.ghostguard.core.ButtonMapping
import dev.ghostguard.filter.Button

/**
 * Sends the presses that survived filtering on to whatever is actually
 * playing. Preferring a specific [MediaController] over a broadcast keeps the
 * command from being routed straight back to our own session.
 */
class Forwarder(private val context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(context, GuardNotificationListener::class.java)

    /**
     * Until this time we ignore media buttons arriving at our own session,
     * because they are the echo of a key event we just dispatched ourselves.
     */
    @Volatile
    var ignoreSelfUntilUptimeMs: Long = 0L
        private set

    /** True when notification listener access has been granted. */
    fun canSeeOtherSessions(): Boolean = runCatching {
        sessionManager.getActiveSessions(listenerComponent)
    }.isSuccess

    /** Returns a short description of where the press went, for the log. */
    fun dispatch(button: Button, longPress: Boolean): String {
        if (ButtonMapping.isVolume(button)) return adjustVolume(button)

        val target = pickTarget()
        if (target != null) {
            sendTo(target, button, longPress)
            return target.packageName
        }

        // Nothing is holding a session: fall back to the system route and
        // briefly ignore the echo it sends back to us.
        ignoreSelfUntilUptimeMs = SystemClock.uptimeMillis() + ECHO_WINDOW_MS
        val keyCode = ButtonMapping.toKeyCode(button)
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        return "system"
    }

    private fun pickTarget(): MediaController? {
        val sessions = runCatching { sessionManager.getActiveSessions(listenerComponent) }
            .getOrDefault(emptyList())
            .filter { it.packageName != context.packageName }

        return sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull { it.playbackState != null }
            ?: sessions.firstOrNull()
    }

    private fun sendTo(controller: MediaController, button: Button, longPress: Boolean) {
        val controls = controller.transportControls
        when (button) {
            Button.PLAY_PAUSE ->
                if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    controls.pause()
                } else {
                    controls.play()
                }

            Button.PLAY -> controls.play()
            Button.PAUSE -> controls.pause()
            Button.NEXT -> if (longPress) controls.fastForward() else controls.skipToNext()
            Button.PREVIOUS -> if (longPress) controls.rewind() else controls.skipToPrevious()
            Button.STOP -> controls.stop()
            Button.VOLUME_UP, Button.VOLUME_DOWN, Button.UNKNOWN -> Unit
        }
    }

    private fun adjustVolume(button: Button): String {
        val direction =
            if (button == Button.VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        return "volume"
    }

    private companion object {
        const val ECHO_WINDOW_MS = 600L
    }
}
