package dev.ghostguard.filter

/**
 * Decides whether a button event came from a finger or from a fault.
 *
 * Pure logic, no Android types, so it can be unit tested on the JVM. The
 * capture layers normalise whatever they see into [RawEvent] and feed it to
 * [submit]; the host must also call [tick] on a short timer (~30 ms) so held
 * and stuck buttons can be resolved without waiting for a release.
 *
 * Not thread safe. Call it from a single looper or hold a lock around it.
 */
class PhantomFilter(tuning: Tuning = Tuning()) {

    var tuning: Tuning = tuning
        set(value) {
            field = value
            // A press already in flight was judged under the old settings;
            // drop it rather than mixing rules mid-press.
            pending?.let { droppedDown = it.button }
            pending = null
        }

    private class Pending(
        val button: Button,
        val downAt: Long,
        var longPressEmitted: Boolean = false,
    )

    private var pending: Pending? = null

    /**
     * A press we already finished with, whose release should be swallowed
     * silently instead of reported as an orphan.
     */
    private var droppedDown: Button? = null

    private var quarantineUntilMs = Long.MIN_VALUE
    private var quarantineReason = Reason.PLUG_QUARANTINE

    private val lastAcceptedPerButton = HashMap<Button, Long>()
    private var lastAcceptedAnyAt = Long.MIN_VALUE

    private val recentDowns = ArrayDeque<Long>()

    private var lastRawSignature: Triple<Button, Boolean, Long>? = null

    private val reasonCounts = LinkedHashMap<Reason, Int>()
    private var allowedCount = 0
    private var blockedCount = 0

    /** True while a press is in flight, so the host knows to keep ticking. */
    val hasPendingPress: Boolean get() = pending != null

    /** Shut the gate until [untilMs]. Called on plug, unplug, or device change. */
    fun quarantine(untilMs: Long, reason: Reason = Reason.PLUG_QUARANTINE) {
        if (untilMs > quarantineUntilMs) {
            quarantineUntilMs = untilMs
            quarantineReason = reason
        }
        pending?.let { droppedDown = it.button }
        pending = null
    }

    fun quarantineFor(nowMs: Long, reason: Reason = Reason.PLUG_QUARANTINE) =
        quarantine(nowMs + tuning.plugQuarantineMs, reason)

    /** Feed one hardware event. Returns what should happen downstream. */
    fun submit(event: RawEvent): List<Emission> {
        // Auto-repeat is synthesised by the OS while a key is held; we measure
        // hold time ourselves, so it carries no information.
        if (event.repeat) return emptyList()

        val signature = Triple(event.button, event.down, event.timestampMs)
        if (signature == lastRawSignature) {
            return listOf(record(Emission.Blocked(event.button, event.timestampMs, Reason.DUPLICATE)))
        }
        lastRawSignature = signature

        return if (event.down) onDown(event) else onUp(event)
    }

    private fun onDown(event: RawEvent): List<Emission> {
        val now = event.timestampMs

        // A new press while one is still in flight means we never saw the
        // release. Close out the old one as chatter.
        val stale = pending
        val emissions = ArrayList<Emission>(2)
        if (stale != null) {
            pending = null
            if (!stale.longPressEmitted) {
                emissions += resolve(stale.button, now, Reason.CHATTER_BURST, now - stale.downAt)
            }
        }

        pruneBursts(now)
        recentDowns.addLast(now)

        val gate = gateReason(event.button, now)
        if (gate != null) {
            if (gate == Reason.CHATTER_BURST) {
                quarantine(now + tuning.chatterCooldownMs, Reason.CHATTER_QUARANTINE)
            }
            droppedDown = event.button
            emissions += resolve(event.button, now, gate, holdMs = 0)
            return emissions
        }

        if (tuning.requireHoldDuration) {
            pending = Pending(event.button, now)
        } else {
            droppedDown = event.button
            emissions += resolve(event.button, now, Reason.CLEAN, holdMs = 0)
        }
        return emissions
    }

    private fun onUp(event: RawEvent): List<Emission> {
        val now = event.timestampMs
        val inFlight = pending

        if (inFlight != null && inFlight.button == event.button) {
            pending = null
            val hold = now - inFlight.downAt
            // The long press was already reported from tick(); the release
            // just closes the gesture.
            if (inFlight.longPressEmitted) return emptyList()
            val reason = if (hold < tuning.minHoldMs) Reason.TOO_SHORT else Reason.CLEAN
            return listOf(resolve(event.button, now, reason, hold))
        }

        if (droppedDown == event.button) {
            droppedDown = null
            return emptyList()
        }

        return listOf(record(Emission.Blocked(event.button, now, Reason.ORPHAN_UP)))
    }

    /**
     * Resolve held presses. Call every ~30 ms while the service is running;
     * without it a long press is only reported when the button is released.
     */
    fun tick(nowMs: Long): List<Emission> {
        val inFlight = pending ?: return emptyList()
        if (inFlight.longPressEmitted) return emptyList()

        val hold = nowMs - inFlight.downAt
        return when {
            hold >= tuning.maxHoldMs -> {
                pending = null
                droppedDown = inFlight.button
                quarantine(nowMs + tuning.chatterCooldownMs, Reason.CHATTER_QUARANTINE)
                listOf(resolve(inFlight.button, nowMs, Reason.STUCK, hold))
            }

            hold >= tuning.longPressMs -> {
                inFlight.longPressEmitted = true
                listOf(resolve(inFlight.button, nowMs, Reason.CLEAN_LONG_PRESS, hold))
            }

            else -> emptyList()
        }
    }

    private fun gateReason(button: Button, now: Long): Reason? {
        if (button in tuning.disabledButtons) return Reason.BUTTON_DISABLED
        if (now < quarantineUntilMs) return quarantineReason
        if (recentDowns.size >= tuning.burstThreshold) return Reason.CHATTER_BURST

        val lastSame = lastAcceptedPerButton[button]
        if (lastSame != null && now - lastSame < tuning.debounceMs) return Reason.DEBOUNCE
        if (lastAcceptedAnyAt != Long.MIN_VALUE &&
            now - lastAcceptedAnyAt < tuning.crossButtonDebounceMs
        ) {
            return Reason.CROSS_TALK
        }
        return null
    }

    private fun pruneBursts(now: Long) {
        while (recentDowns.isNotEmpty() && now - recentDowns.first() > tuning.burstWindowMs) {
            recentDowns.removeFirst()
        }
    }

    /**
     * Turn a verdict into an emission. Bookkeeping always follows the real
     * verdict, even in observe-only mode, so the counters and the thresholds
     * the user sees reflect what enforcement would actually have done.
     */
    private fun resolve(button: Button, now: Long, reason: Reason, holdMs: Long): Emission {
        val clean = reason == Reason.CLEAN || reason == Reason.CLEAN_LONG_PRESS
        if (clean) {
            lastAcceptedPerButton[button] = now
            lastAcceptedAnyAt = now
            return record(
                Emission.Accepted(
                    button = button,
                    timestampMs = now,
                    longPress = reason == Reason.CLEAN_LONG_PRESS,
                    holdMs = holdMs,
                    reason = reason,
                )
            )
        }

        if (tuning.shadowMode) {
            return record(
                Emission.Accepted(
                    button = button,
                    timestampMs = now,
                    longPress = false,
                    holdMs = holdMs,
                    reason = Reason.CLEAN,
                    shadowedReason = reason,
                )
            )
        }

        return record(Emission.Blocked(button, now, reason, holdMs))
    }

    private fun <T : Emission> record(emission: T): T {
        val reason = when (emission) {
            is Emission.Accepted -> emission.shadowedReason ?: emission.reason
            is Emission.Blocked -> emission.reason
            else -> Reason.CLEAN
        }
        reasonCounts[reason] = (reasonCounts[reason] ?: 0) + 1
        when (emission) {
            is Emission.Accepted -> allowedCount++
            is Emission.Blocked -> blockedCount++
            else -> Unit
        }
        return emission
    }

    data class Stats(
        val allowed: Int,
        val blocked: Int,
        val byReason: Map<Reason, Int>,
    )

    fun stats() = Stats(allowedCount, blockedCount, LinkedHashMap(reasonCounts))

    fun resetStats() {
        allowedCount = 0
        blockedCount = 0
        reasonCounts.clear()
    }

    /** Forget all in-flight and historical state, e.g. after a device swap. */
    fun reset() {
        pending = null
        droppedDown = null
        quarantineUntilMs = Long.MIN_VALUE
        lastAcceptedPerButton.clear()
        lastAcceptedAnyAt = Long.MIN_VALUE
        recentDowns.clear()
        lastRawSignature = null
    }
}
