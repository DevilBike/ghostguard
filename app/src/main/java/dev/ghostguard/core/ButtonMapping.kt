package dev.ghostguard.core

import android.view.KeyEvent
import dev.ghostguard.filter.Button

/** Translations between what the hardware says and what the filter reasons about. */
object ButtonMapping {

    fun fromKeyCode(keyCode: Int): Button = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> Button.PLAY_PAUSE
        KeyEvent.KEYCODE_MEDIA_PLAY -> Button.PLAY
        KeyEvent.KEYCODE_MEDIA_PAUSE -> Button.PAUSE
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> Button.NEXT
        KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> Button.PREVIOUS
        KeyEvent.KEYCODE_MEDIA_STOP -> Button.STOP
        KeyEvent.KEYCODE_VOLUME_UP -> Button.VOLUME_UP
        KeyEvent.KEYCODE_VOLUME_DOWN -> Button.VOLUME_DOWN
        else -> Button.UNKNOWN
    }

    fun toKeyCode(button: Button): Int = when (button) {
        Button.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        Button.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
        Button.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
        Button.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
        Button.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        Button.STOP -> KeyEvent.KEYCODE_MEDIA_STOP
        Button.VOLUME_UP -> KeyEvent.KEYCODE_VOLUME_UP
        Button.VOLUME_DOWN -> KeyEvent.KEYCODE_VOLUME_DOWN
        Button.UNKNOWN -> KeyEvent.KEYCODE_UNKNOWN
    }

    /**
     * HID Consumer Page (0x0C) usages that headset buttons are built from.
     * See the USB HID Usage Tables, section 15.
     */
    fun fromConsumerUsage(usage: Int): Button = when (usage) {
        0x00CD -> Button.PLAY_PAUSE
        0x00B0 -> Button.PLAY
        0x00B1 -> Button.PAUSE
        0x00B5, 0x00B3 -> Button.NEXT      // scan next track, fast forward
        0x00B6, 0x00B4 -> Button.PREVIOUS  // scan previous track, rewind
        0x00B7 -> Button.STOP
        0x00E9 -> Button.VOLUME_UP
        0x00EA -> Button.VOLUME_DOWN
        else -> Button.UNKNOWN
    }

    /** Telephony Page (0x0B): the hook switch doubles as play/pause on headsets. */
    fun fromTelephonyUsage(usage: Int): Button = when (usage) {
        0x0020, 0x0021 -> Button.PLAY_PAUSE // hook switch, flash
        else -> Button.UNKNOWN
    }

    val transportButtons = listOf(
        Button.PLAY_PAUSE,
        Button.PLAY,
        Button.PAUSE,
        Button.NEXT,
        Button.PREVIOUS,
        Button.STOP,
    )

    val volumeButtons = listOf(Button.VOLUME_UP, Button.VOLUME_DOWN)

    fun isVolume(button: Button) = button in volumeButtons

    fun label(button: Button): String = when (button) {
        Button.PLAY_PAUSE -> "Play / pause"
        Button.PLAY -> "Play"
        Button.PAUSE -> "Pause"
        Button.NEXT -> "Next track"
        Button.PREVIOUS -> "Previous track"
        Button.STOP -> "Stop"
        Button.VOLUME_UP -> "Volume up"
        Button.VOLUME_DOWN -> "Volume down"
        Button.UNKNOWN -> "Unknown"
    }
}
