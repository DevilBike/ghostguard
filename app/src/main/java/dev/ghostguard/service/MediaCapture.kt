package dev.ghostguard.service

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.view.KeyEvent

/**
 * The fallback capture path: hold a media session so Android routes headset
 * media keys here first, filter them, then pass the survivors on.
 *
 * This is best effort by nature. Android gives media buttons to the session it
 * considers most relevant, so another player that starts after us can take the
 * routing back; [reassert] exists to claim it again when that happens. For a
 * digital USB-C headset the USB path in [UsbHidCapture] is deterministic and
 * should be preferred.
 */
class MediaCapture(
    private val context: Context,
    private val handler: Handler,
    private val onKey: (KeyEvent) -> Unit,
) {

    private var session: MediaSession? = null

    val isRunning: Boolean get() = session != null

    fun start() {
        if (session != null) return

        val session = MediaSession(context, TAG)
        @Suppress("DEPRECATION")
        session.setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        session.setCallback(
            object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = mediaButtonIntent.keyEvent() ?: return false
                    onKey(event)
                    // Always claim the event: anything we let fall through
                    // would reach the player unfiltered.
                    return true
                }
            },
            handler,
        )
        session.setPlaybackState(playingState())
        session.isActive = true
        this.session = session
    }

    /**
     * Report playback again so we move back to the front of the media button
     * queue, e.g. after another app started playing.
     */
    fun reassert() {
        val session = session ?: return
        session.setPlaybackState(playingState())
        session.isActive = true
    }

    fun stop() {
        session?.let {
            it.isActive = false
            it.release()
        }
        session = null
    }

    private fun playingState(): PlaybackState = PlaybackState.Builder()
        .setActions(
            PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_STOP
        )
        // We never make a sound; this state exists purely to win the routing.
        .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
        .build()

    private fun Intent.keyEvent(): KeyEvent? {
        @Suppress("DEPRECATION")
        return getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent
    }

    private companion object {
        const val TAG = "GhostGuard"
    }
}
