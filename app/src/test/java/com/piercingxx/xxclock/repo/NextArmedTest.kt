package com.piercingxx.xxclock.repo

import com.piercingxx.xxclock.model.Alarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks the next-alarm-card / widget selection rule (the pure core of
 * [AlarmRepository.nextArmed]): an alarm stays armed while it is enabled OR a
 * snooze is still pending — the snoozed one-shot that disabled itself at fire
 * time must not vanish from Clock card and widget.
 */
class NextArmedTest {

    private val now = 1_000_000L

    private fun alarm(id: Long, enabled: Boolean, mask: Int = 0): Alarm =
        Alarm(id = id, hour = 7, minute = 30, daysMask = mask, label = "", enabled = enabled, vibrate = true)

    private fun select(
        alarms: List<Alarm>,
        snooze: Map<Long, Long> = emptyMap(),
        scheduled: Map<Long, Long> = emptyMap(),
        nowMs: Long = now,
    ): Pair<Alarm, Long>? = AlarmRepository.nextArmed(
        alarms = alarms,
        snoozedUntil = { snooze[it] ?: 0L },
        scheduledFire = { scheduled[it] ?: 0L },
        nowMs = nowMs,
    )

    @Test
    fun `a snoozed one-shot is armed although its definition was disabled at fire`() {
        // One-shot fired at `now`, disabled itself, user snoozed for 10 minutes;
        // the snooze wake-up is booked as its scheduled fire.
        val firedOneShot = alarm(1, enabled = false)
        val snoozedUntil = now + 10 * 60_000

        val next = select(listOf(firedOneShot), snooze = mapOf(1L to snoozedUntil))

        assertEquals(1L, next?.first?.id)
        assertEquals(snoozedUntil, next?.second)
    }

    @Test
    fun `a one-shot disabled at fire with no pending snooze is not armed`() {
        val firedOneShot = alarm(1, enabled = false)

        assertNull(select(listOf(firedOneShot)))
    }

    @Test
    fun `an elapsed snooze does not arm a disabled one-shot`() {
        val firedOneShot = alarm(1, enabled = false)

        val next = select(
            listOf(firedOneShot),
            snooze = mapOf(1L to now - 1),
            scheduled = mapOf(1L to now - 2),
        )

        assertNull(next)
    }

    @Test
    fun `timestamp keeps the later of snooze and scheduled fire`() {
        val weekly = alarm(2, enabled = true, mask = Alarm.ALL_DAYS_MASK)

        val next = select(
            listOf(weekly),
            snooze = mapOf(2L to now + 90_000),
            scheduled = mapOf(2L to now + 30_000),
        )
        assertEquals(now + 90_000L, next?.second)

        val rescheduled = select(
            listOf(weekly),
            snooze = mapOf(2L to now + 30_000),
            scheduled = mapOf(2L to now + 90_000),
        )
        assertEquals(now + 90_000L, rescheduled?.second)
    }

    @Test
    fun `soonest armed alarm wins and unarmed candidates are skipped`() {
        val snoozedOneShot = alarm(1, enabled = false) // armed via snooze only
        val enabledWeekly = alarm(2, enabled = true, mask = Alarm.ALL_DAYS_MASK)
        val mutedOneShot = alarm(3, enabled = false) // neither enabled nor snoozed

        val next = select(
            listOf(mutedOneShot, snoozedOneShot, enabledWeekly),
            snooze = mapOf(1L to now + 5 * 60_000),
            scheduled = mapOf(1L to now + 5 * 60_000, 2L to now + 3 * 60_000),
        )

        assertEquals(2L, next?.first?.id)
        assertEquals(now + 3 * 60_000L, next?.second)
    }
}
