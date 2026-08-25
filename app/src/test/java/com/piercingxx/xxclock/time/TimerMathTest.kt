package com.piercingxx.xxclock.time

import com.piercingxx.xxclock.model.TimerItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for [TimerMath]. All clocks are injected explicitly. */
class TimerMathTest {

    private fun timer(
        state: String,
        durationMs: Long = 60_000L,
        endsAtEpochMs: Long = 0L,
        remainingMs: Long = durationMs,
    ): TimerItem = TimerItem(
        id = 1L,
        durationMs = durationMs,
        state = state,
        endsAtEpochMs = endsAtEpochMs,
        remainingMs = remainingMs,
        label = "",
    )

    // ---- remainingMs ----

    @Test
    fun `idle returns full duration regardless of now`() {
        val t = timer(TimerItem.STATE_IDLE, durationMs = 90_000L)
        assertEquals(90_000L, TimerMath.remainingMs(t, nowMs = 0L))
        assertEquals(90_000L, TimerMath.remainingMs(t, nowMs = 999_999_999L))
    }

    @Test
    fun `running with deadline in the future returns positive remainder`() {
        val t = timer(
            TimerItem.STATE_RUNNING,
            durationMs = 60_000L,
            endsAtEpochMs = 200_000L,
        )
        assertEquals(50_000L, TimerMath.remainingMs(t, nowMs = 150_000L))
        assertEquals(199_999L, TimerMath.remainingMs(t, nowMs = 1L))
    }

    @Test
    fun `running past the deadline clamps to zero`() {
        val t = timer(
            TimerItem.STATE_RUNNING,
            durationMs = 60_000L,
            endsAtEpochMs = 100_000L,
        )
        assertEquals(0L, TimerMath.remainingMs(t, nowMs = 100_001L))
        assertEquals(0L, TimerMath.remainingMs(t, nowMs = Long.MAX_VALUE / 2))
    }

    @Test
    fun `running exactly at the deadline returns zero`() {
        val t = timer(
            TimerItem.STATE_RUNNING,
            durationMs = 60_000L,
            endsAtEpochMs = 100_000L,
        )
        assertEquals(0L, TimerMath.remainingMs(t, nowMs = 100_000L))
    }

    @Test
    fun `paused is frozen at stored remaining regardless of now`() {
        val t = timer(
            TimerItem.STATE_PAUSED,
            durationMs = 60_000L,
            endsAtEpochMs = 500_000L, // stale deadline must be ignored while paused
            remainingMs = 12_345L,
        )
        assertEquals(12_345L, TimerMath.remainingMs(t, nowMs = 400_000L))
        assertEquals(12_345L, TimerMath.remainingMs(t, nowMs = 900_000L))
    }

    @Test
    fun `finished reports its stored remaining (typically zero)`() {
        val finished = timer(TimerItem.STATE_FINISHED, remainingMs = 0L)
        assertEquals(0L, TimerMath.remainingMs(finished, nowMs = 123_456L))

        val stoppedWithLeftover = timer(TimerItem.STATE_FINISHED, remainingMs = 5_000L)
        assertEquals(5_000L, TimerMath.remainingMs(stoppedWithLeftover, nowMs = 123_456L))
    }

    // ---- isExpired ----

    @Test
    fun `running at exactly endsAt is expired`() {
        val t = timer(
            TimerItem.STATE_RUNNING,
            durationMs = 60_000L,
            endsAtEpochMs = 100_000L,
        )
        assertTrue(TimerMath.isExpired(t, nowMs = 100_000L))
    }

    @Test
    fun `running before endsAt is not expired`() {
        val t = timer(
            TimerItem.STATE_RUNNING,
            durationMs = 60_000L,
            endsAtEpochMs = 100_000L,
        )
        assertFalse(TimerMath.isExpired(t, nowMs = 99_999L))
    }

    @Test
    fun `running after endsAt is expired`() {
        val t = timer(
            TimerItem.STATE_RUNNING,
            durationMs = 60_000L,
            endsAtEpochMs = 100_000L,
        )
        assertTrue(TimerMath.isExpired(t, nowMs = 100_001L))
    }

    @Test
    fun `paused never expires even with stale past deadline`() {
        val t = timer(
            TimerItem.STATE_PAUSED,
            durationMs = 60_000L,
            endsAtEpochMs = 100_000L, // long in the past relative to nowMs below
            remainingMs = 30_000L,
        )
        assertFalse(TimerMath.isExpired(t, nowMs = 100_000L))
        assertFalse(TimerMath.isExpired(t, nowMs = 999_999_999L))
    }

    @Test
    fun `idle and finished never expire via this predicate`() {
        val idle = timer(TimerItem.STATE_IDLE, endsAtEpochMs = 0L)
        assertFalse(TimerMath.isExpired(idle, nowMs = 1L))

        val finished = timer(
            TimerItem.STATE_FINISHED,
            endsAtEpochMs = 100_000L,
            remainingMs = 0L,
        )
        assertFalse(TimerMath.isExpired(finished, nowMs = 100_001L))
    }

    // ---- display ----

    @Test
    fun `display zero is 00-00`() {
        assertEquals("00:00", TimerMath.display(0L))
    }

    @Test
    fun `display rounds up so sub-second still shows one second`() {
        assertEquals("00:01", TimerMath.display(1L))
        assertEquals("00:01", TimerMath.display(999L))
    }

    @Test
    fun `display fifty-nine seconds`() {
        assertEquals("00:59", TimerMath.display(59_000L))
    }

    @Test
    fun `display just-under-a-minute shows a full minute (ceil semantics)`() {
        assertEquals("01:00", TimerMath.display(59_999L))
    }

    @Test
    fun `display just-under-an-hour shows a full hour (ceil semantics)`() {
        assertEquals("1:00:00", TimerMath.display(3_599_999L))
    }

    @Test
    fun `display exactly one hour`() {
        assertEquals("1:00:00", TimerMath.display(3_600_000L))
    }

    @Test
    fun `display hours minutes seconds`() {
        // 3661000 ms -> 3661 s -> 1 h 01 m 01 s.
        assertEquals("1:01:01", TimerMath.display(3_661_000L))
        // Ceil carries into seconds: 3661.999 s displays as 1:01:02.
        assertEquals("1:01:02", TimerMath.display(3_661_999L))
    }
}
