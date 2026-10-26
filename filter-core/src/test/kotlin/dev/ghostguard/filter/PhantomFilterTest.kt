package dev.ghostguard.filter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The scenarios here are the ones that matter in the field: a real tap, the
 * sub-millisecond chatter of a damaged cable, a burst at the moment of
 * plugging in, and a contact that shorts and stays shorted.
 */
class PhantomFilterTest {

    private val enforcing = Tuning(shadowMode = false)

    private fun PhantomFilter.press(
        button: Button = Button.PLAY_PAUSE,
        downAt: Long,
        holdMs: Long,
    ): List<Emission> {
        val out = ArrayList<Emission>()
        out += submit(RawEvent(button, down = true, timestampMs = downAt))
        out += submit(RawEvent(button, down = false, timestampMs = downAt + holdMs))
        return out
    }

    private fun accepted(emissions: List<Emission>) =
        emissions.filterIsInstance<Emission.Accepted>()

    private fun blocked(emissions: List<Emission>) =
        emissions.filterIsInstance<Emission.Blocked>()

    @Test
    fun `a normal tap is forwarded once`() {
        val filter = PhantomFilter(enforcing)
        val out = filter.press(downAt = 1_000, holdMs = 90)

        assertEquals(1, out.size)
        val tap = out.single() as Emission.Accepted
        assertEquals(Button.PLAY_PAUSE, tap.button)
        assertEquals(Reason.CLEAN, tap.reason)
        assertEquals(90, tap.holdMs)
        assertTrue(!tap.longPress)
    }

    @Test
    fun `a press shorter than the hold gate is blocked`() {
        val filter = PhantomFilter(enforcing)
        val out = filter.press(downAt = 1_000, holdMs = 8)

        assertEquals(Reason.TOO_SHORT, blocked(out).single().reason)
        assertTrue(accepted(out).isEmpty())
    }

    @Test
    fun `a repeated press inside the debounce window is blocked`() {
        val filter = PhantomFilter(enforcing)
        filter.press(downAt = 1_000, holdMs = 80)
        val out = filter.press(downAt = 1_200, holdMs = 80)

        assertEquals(Reason.DEBOUNCE, blocked(out).single().reason)
    }

    @Test
    fun `the same press after the debounce window is allowed`() {
        val filter = PhantomFilter(enforcing)
        filter.press(downAt = 1_000, holdMs = 80)
        val out = filter.press(downAt = 1_600, holdMs = 80)

        assertEquals(Reason.CLEAN, accepted(out).single().reason)
    }

    @Test
    fun `a different button immediately after an accepted one is cross talk`() {
        val filter = PhantomFilter(enforcing)
        filter.press(Button.PLAY_PAUSE, downAt = 1_000, holdMs = 80)
        val out = filter.press(Button.NEXT, downAt = 1_120, holdMs = 80)

        assertEquals(Reason.CROSS_TALK, blocked(out).single().reason)
    }

    @Test
    fun `chatter trips burst detection and then stays quarantined`() {
        val filter = PhantomFilter(enforcing)
        // Four presses in 120 ms: electrically plausible, humanly impossible.
        val emissions = ArrayList<Emission>()
        for (i in 0 until 4) {
            emissions += filter.press(downAt = 1_000 + i * 40L, holdMs = 5)
        }

        val reasons = blocked(emissions).map { it.reason }
        assertTrue(Reason.CHATTER_BURST in reasons, "expected a burst verdict in $reasons")
        assertTrue(accepted(emissions).size <= 1, "at most the first press should survive")

        // Still shut a second later, because chatter rarely arrives alone.
        val during = filter.press(downAt = 2_000, holdMs = 90)
        assertEquals(Reason.CHATTER_QUARANTINE, blocked(during).single().reason)

        // Open again once the cooldown has passed.
        val after = filter.press(downAt = 6_000, holdMs = 90)
        assertEquals(Reason.CLEAN, accepted(after).single().reason)
    }

    @Test
    fun `deliberate presses at human speed are not mistaken for chatter`() {
        val filter = PhantomFilter(enforcing)
        // Skipping four tracks, roughly two presses per second.
        val emissions = ArrayList<Emission>()
        for (i in 0 until 4) {
            emissions += filter.press(Button.NEXT, downAt = 1_000 + i * 600L, holdMs = 70)
        }

        assertEquals(4, accepted(emissions).size)
        assertTrue(blocked(emissions).isEmpty())
    }

    @Test
    fun `everything is blocked during the plug quarantine`() {
        val filter = PhantomFilter(enforcing)
        filter.quarantineFor(nowMs = 1_000)

        val during = filter.press(downAt = 1_300, holdMs = 90)
        assertEquals(Reason.PLUG_QUARANTINE, blocked(during).single().reason)

        val after = filter.press(downAt = 3_000, holdMs = 90)
        assertEquals(Reason.CLEAN, accepted(after).single().reason)
    }

    @Test
    fun `a held button reports a long press without waiting for release`() {
        val filter = PhantomFilter(enforcing)
        filter.submit(RawEvent(Button.NEXT, down = true, timestampMs = 1_000))

        assertTrue(filter.tick(1_300).isEmpty(), "not long enough yet")

        val long = accepted(filter.tick(1_700)).single()
        assertTrue(long.longPress)
        assertEquals(Reason.CLEAN_LONG_PRESS, long.reason)

        // The release only closes the gesture; it must not fire a second action.
        assertTrue(filter.submit(RawEvent(Button.NEXT, down = false, timestampMs = 2_100)).isEmpty())
    }

    @Test
    fun `a contact held past the sanity limit is treated as stuck`() {
        val filter = PhantomFilter(enforcing.copy(longPressMs = 10_000))
        filter.submit(RawEvent(Button.PLAY_PAUSE, down = true, timestampMs = 1_000))

        val stuck = blocked(filter.tick(5_500)).single()
        assertEquals(Reason.STUCK, stuck.reason)

        // The eventual release is swallowed rather than reported as an orphan.
        assertTrue(filter.submit(RawEvent(Button.PLAY_PAUSE, down = false, timestampMs = 9_000)).isEmpty())
    }

    @Test
    fun `a release with no press is an orphan`() {
        val filter = PhantomFilter(enforcing)
        val out = filter.submit(RawEvent(Button.PLAY_PAUSE, down = false, timestampMs = 1_000))

        assertEquals(Reason.ORPHAN_UP, blocked(out).single().reason)
    }

    @Test
    fun `an identical duplicate event is dropped`() {
        val filter = PhantomFilter(enforcing)
        filter.submit(RawEvent(Button.PLAY_PAUSE, down = true, timestampMs = 1_000))
        val out = filter.submit(RawEvent(Button.PLAY_PAUSE, down = true, timestampMs = 1_000))

        assertEquals(Reason.DUPLICATE, blocked(out).single().reason)
    }

    @Test
    fun `auto repeat events are ignored`() {
        val filter = PhantomFilter(enforcing)
        val out = filter.submit(
            RawEvent(Button.PLAY_PAUSE, down = true, timestampMs = 1_000, repeat = true)
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `a disabled button never gets through`() {
        val filter = PhantomFilter(enforcing.copy(disabledButtons = setOf(Button.NEXT)))

        assertEquals(Reason.BUTTON_DISABLED, blocked(filter.press(Button.NEXT, 1_000, 90)).single().reason)
        assertEquals(Reason.CLEAN, accepted(filter.press(Button.PLAY_PAUSE, 2_000, 90)).single().reason)
    }

    @Test
    fun `observe mode forwards presses but records what it would have blocked`() {
        val filter = PhantomFilter(Tuning(shadowMode = true))
        val out = filter.press(downAt = 1_000, holdMs = 8)

        val tap = accepted(out).single()
        assertEquals(Reason.TOO_SHORT, tap.shadowedReason)
        assertTrue(blocked(out).isEmpty())

        // Bookkeeping follows the real verdict, so the shadowed press must not
        // have armed the debounce window.
        val next = accepted(filter.press(downAt = 1_100, holdMs = 90)).single()
        assertNull(next.shadowedReason)
    }

    @Test
    fun `stats count each verdict by reason`() {
        val filter = PhantomFilter(enforcing)
        filter.press(downAt = 1_000, holdMs = 90)
        filter.press(downAt = 1_100, holdMs = 4)

        val stats = filter.stats()
        assertEquals(1, stats.allowed)
        assertEquals(1, stats.blocked)
        assertEquals(1, stats.byReason[Reason.CLEAN])
        assertEquals(1, stats.byReason[Reason.DEBOUNCE])
    }

    @Test
    fun `immediate mode decides at press down`() {
        val filter = PhantomFilter(enforcing.copy(requireHoldDuration = false))
        val out = filter.submit(RawEvent(Button.PLAY_PAUSE, down = true, timestampMs = 1_000))

        assertEquals(Reason.CLEAN, accepted(out).single().reason)
        assertTrue(filter.submit(RawEvent(Button.PLAY_PAUSE, down = false, timestampMs = 1_005)).isEmpty())
    }
}
