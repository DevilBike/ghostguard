package dev.ghostguard.filter

/**
 * The logical buttons GhostGuard knows how to reason about. Everything the
 * capture layers see (USB HID reports, media key events, volume keys) is
 * normalised into one of these before it reaches [PhantomFilter].
 */
enum class Button {
    PLAY_PAUSE,
    PLAY,
    PAUSE,
    NEXT,
    PREVIOUS,
    STOP,
    VOLUME_UP,
    VOLUME_DOWN,
    UNKNOWN,
}

/** A single press or release as reported by the hardware. */
data class RawEvent(
    val button: Button,
    val down: Boolean,
    val timestampMs: Long,
    /** Stable identifier of the source device, used for per-device stats. */
    val deviceKey: String = "",
    /** True for auto-repeat events synthesised while a key is held. */
    val repeat: Boolean = false,
)

/** Why the filter made the call it made. Surfaced verbatim in the event log. */
enum class Reason {
    /** Nothing suspicious: a normal, deliberate press. */
    CLEAN,
    /** A held press that passed the duration gate. */
    CLEAN_LONG_PRESS,
    /** Arrived inside the cooldown window after a plug/unplug or device change. */
    PLUG_QUARANTINE,
    /** Arrived inside the cooldown window that follows detected chatter. */
    CHATTER_QUARANTINE,
    /** Several events packed too tightly to be human. */
    CHATTER_BURST,
    /** Same button again before the debounce window expired. */
    DEBOUNCE,
    /** A different button immediately after an accepted one. */
    CROSS_TALK,
    /** Press and release too close together to be a finger. */
    TOO_SHORT,
    /** Held past the sanity limit: a shorted or stuck contact. */
    STUCK,
    /** A release with no matching press. */
    ORPHAN_UP,
    /** Byte-for-byte repeat of an event already seen. */
    DUPLICATE,
    /** The user switched this button off entirely. */
    BUTTON_DISABLED,
}

/** What the filter decided to do about one press. */
sealed interface Emission {
    val button: Button
    val timestampMs: Long

    /**
     * Forward this press to the real media player.
     *
     * @param shadowedReason set when observe-only mode is on and the press
     *   *would* have been blocked. The press is still forwarded; the reason is
     *   only recorded so the user can see what filtering would have done.
     */
    data class Accepted(
        override val button: Button,
        override val timestampMs: Long,
        val longPress: Boolean,
        val holdMs: Long,
        val reason: Reason,
        val shadowedReason: Reason? = null,
    ) : Emission

    /** Swallow this press. Nothing downstream ever sees it. */
    data class Blocked(
        override val button: Button,
        override val timestampMs: Long,
        val reason: Reason,
        val holdMs: Long? = null,
    ) : Emission
}

/**
 * Tunables. Defaults are deliberately conservative: they catch the electrical
 * chatter typical of a flexing or damp USB-C cable without eating presses a
 * person would notice losing.
 */
data class Tuning(
    /** Ignore the same button again within this window of an accepted press. */
    val debounceMs: Long = 450,
    /** Ignore a *different* button this soon after an accepted press. */
    val crossButtonDebounceMs: Long = 150,
    /** Require press-to-release to last at least this long. */
    val minHoldMs: Long = 45,
    /** Hold longer than this and it counts as a long press. */
    val longPressMs: Long = 600,
    /** Hold longer than this and the contact is assumed stuck, not held. */
    val maxHoldMs: Long = 4_000,
    /** This many presses inside [burstWindowMs] marks the whole run as chatter. */
    val burstThreshold: Int = 4,
    val burstWindowMs: Long = 1_200,
    /** How long to stay shut after chatter is detected. */
    val chatterCooldownMs: Long = 2_500,
    /** How long to stay shut after a plug, unplug, or input-device change. */
    val plugQuarantineMs: Long = 1_500,
    /**
     * When false, presses are judged at press-down using only debounce and
     * burst rules. Lower latency, but the duration gate is skipped.
     */
    val requireHoldDuration: Boolean = true,
    /** Observe-only: log what would have been blocked, but forward everything. */
    val shadowMode: Boolean = true,
    /** Buttons the user has switched off completely. */
    val disabledButtons: Set<Button> = emptySet(),
)
