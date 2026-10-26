package dev.ghostguard.data

import android.content.Context
import android.content.SharedPreferences
import dev.ghostguard.filter.Button
import dev.ghostguard.filter.Tuning
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which capture path the guard should use. */
enum class CaptureMode {
    /** Claim the headset's HID interface over USB. Deterministic, but only
     *  works for digital USB-C headsets that expose buttons as HID. */
    USB_HID,

    /** Pose as a media session and filter the media keys Android routes to
     *  us. Works with anything, but only while we hold the routing. */
    MEDIA_SESSION,

    /** Try USB first and keep the media session as a safety net. */
    BOTH,
}

/** Everything the user can change, persisted in SharedPreferences. */
data class Config(
    val guardEnabled: Boolean = false,
    val captureMode: CaptureMode = CaptureMode.BOTH,
    val filterVolumeKeys: Boolean = false,
    val startOnBoot: Boolean = true,
    val tuning: Tuning = Tuning(),
)

class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ghostguard", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(read())
    val config: StateFlow<Config> = _config.asStateFlow()

    fun update(transform: (Config) -> Config) {
        val next = transform(_config.value)
        write(next)
        _config.value = next
    }

    private fun read(): Config {
        val defaults = Tuning()
        return Config(
            guardEnabled = prefs.getBoolean(KEY_ENABLED, false),
            captureMode = runCatching {
                CaptureMode.valueOf(prefs.getString(KEY_MODE, null) ?: CaptureMode.BOTH.name)
            }.getOrDefault(CaptureMode.BOTH),
            filterVolumeKeys = prefs.getBoolean(KEY_VOLUME, false),
            startOnBoot = prefs.getBoolean(KEY_BOOT, true),
            tuning = Tuning(
                debounceMs = prefs.getLong(KEY_DEBOUNCE, defaults.debounceMs),
                crossButtonDebounceMs = prefs.getLong(KEY_CROSS, defaults.crossButtonDebounceMs),
                minHoldMs = prefs.getLong(KEY_MIN_HOLD, defaults.minHoldMs),
                longPressMs = prefs.getLong(KEY_LONG_PRESS, defaults.longPressMs),
                maxHoldMs = prefs.getLong(KEY_MAX_HOLD, defaults.maxHoldMs),
                burstThreshold = prefs.getInt(KEY_BURST_N, defaults.burstThreshold),
                burstWindowMs = prefs.getLong(KEY_BURST_WINDOW, defaults.burstWindowMs),
                chatterCooldownMs = prefs.getLong(KEY_CHATTER_COOLDOWN, defaults.chatterCooldownMs),
                plugQuarantineMs = prefs.getLong(KEY_PLUG_QUARANTINE, defaults.plugQuarantineMs),
                requireHoldDuration = prefs.getBoolean(KEY_HOLD_GATE, defaults.requireHoldDuration),
                shadowMode = prefs.getBoolean(KEY_SHADOW, defaults.shadowMode),
                disabledButtons = prefs.getStringSet(KEY_DISABLED, emptySet())
                    .orEmpty()
                    .mapNotNull { name -> runCatching { Button.valueOf(name) }.getOrNull() }
                    .toSet(),
            ),
        )
    }

    private fun write(config: Config) {
        val t = config.tuning
        prefs.edit()
            .putBoolean(KEY_ENABLED, config.guardEnabled)
            .putString(KEY_MODE, config.captureMode.name)
            .putBoolean(KEY_VOLUME, config.filterVolumeKeys)
            .putBoolean(KEY_BOOT, config.startOnBoot)
            .putLong(KEY_DEBOUNCE, t.debounceMs)
            .putLong(KEY_CROSS, t.crossButtonDebounceMs)
            .putLong(KEY_MIN_HOLD, t.minHoldMs)
            .putLong(KEY_LONG_PRESS, t.longPressMs)
            .putLong(KEY_MAX_HOLD, t.maxHoldMs)
            .putInt(KEY_BURST_N, t.burstThreshold)
            .putLong(KEY_BURST_WINDOW, t.burstWindowMs)
            .putLong(KEY_CHATTER_COOLDOWN, t.chatterCooldownMs)
            .putLong(KEY_PLUG_QUARANTINE, t.plugQuarantineMs)
            .putBoolean(KEY_HOLD_GATE, t.requireHoldDuration)
            .putBoolean(KEY_SHADOW, t.shadowMode)
            .putStringSet(KEY_DISABLED, t.disabledButtons.map { it.name }.toSet())
            .apply()
    }

    private companion object {
        const val KEY_ENABLED = "guard_enabled"
        const val KEY_MODE = "capture_mode"
        const val KEY_VOLUME = "filter_volume_keys"
        const val KEY_BOOT = "start_on_boot"
        const val KEY_DEBOUNCE = "debounce_ms"
        const val KEY_CROSS = "cross_debounce_ms"
        const val KEY_MIN_HOLD = "min_hold_ms"
        const val KEY_LONG_PRESS = "long_press_ms"
        const val KEY_MAX_HOLD = "max_hold_ms"
        const val KEY_BURST_N = "burst_threshold"
        const val KEY_BURST_WINDOW = "burst_window_ms"
        const val KEY_CHATTER_COOLDOWN = "chatter_cooldown_ms"
        const val KEY_PLUG_QUARANTINE = "plug_quarantine_ms"
        const val KEY_HOLD_GATE = "require_hold_duration"
        const val KEY_SHADOW = "shadow_mode"
        const val KEY_DISABLED = "disabled_buttons"
    }
}
