package com.piercingxx.xxclock.time

import com.piercingxx.xxclock.model.Alarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Calendar

/**
 * Pure-JVM tests for [NextOccurrence]. All `now` instants are constructed
 * explicitly with ZonedDateTime.of(...) — never from the system clock.
 */
class NextOccurrenceTest {

    private val ny: ZoneId = ZoneId.of("America/New_York")
    private val utc: ZoneId = ZoneId.of("UTC")

    private fun at(
        zone: ZoneId,
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): ZonedDateTime = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)

    // 2024-06-08 = Saturday, 2024-06-09 = Sunday, 2024-06-10 = Monday.

    @Test
    fun `one-shot with today's slot in the future returns today`() {
        val now = at(ny, 2024, 6, 10, 10, 0) // Monday morning
        val result = NextOccurrence.alarm(now, hour = 15, minute = 30, daysMask = 0)
        assertEquals(at(ny, 2024, 6, 10, 15, 30), result)
    }

    @Test
    fun `one-shot with today's slot already passed returns tomorrow`() {
        val now = at(ny, 2024, 6, 10, 16, 0)
        val result = NextOccurrence.alarm(now, hour = 15, minute = 30, daysMask = 0)
        assertEquals(at(ny, 2024, 6, 11, 15, 30), result)
    }

    @Test
    fun `weekly mask skips non-matching days`() {
        val now = at(ny, 2024, 6, 10, 10, 0) // Monday
        val fridayOnly = Alarm.bitForCalendarDay(Calendar.FRIDAY)
        val result = NextOccurrence.alarm(now, hour = 7, minute = 0, daysMask = fridayOnly)
        assertEquals(at(ny, 2024, 6, 14, 7, 0), result) // Friday of the same week
    }

    @Test
    fun `weekly mask skips today when today matches but its slot has passed`() {
        val now = at(ny, 2024, 6, 10, 10, 0) // Monday
        val mondayOnly = Alarm.bitForCalendarDay(Calendar.MONDAY)
        val result = NextOccurrence.alarm(now, hour = 9, minute = 0, daysMask = mondayOnly)
        assertEquals(at(ny, 2024, 6, 17, 9, 0), result) // next Monday
    }

    @Test
    fun `week boundary wrap - sunday mask from saturday evening lands next sunday`() {
        val now = at(ny, 2024, 6, 15, 20, 0) // Saturday evening
        val sundayOnly = Alarm.bitForCalendarDay(Calendar.SUNDAY)
        val result = NextOccurrence.alarm(now, hour = 21, minute = 30, daysMask = sundayOnly)
        assertEquals(at(ny, 2024, 6, 16, 21, 30), result)
    }

    @Test
    fun `week boundary wrap - early-morning sunday reachable from late saturday night`() {
        val now = at(ny, 2024, 6, 15, 23, 0) // Saturday 23:00
        val sundayOnly = Alarm.bitForCalendarDay(Calendar.SUNDAY)
        val result = NextOccurrence.alarm(now, hour = 1, minute = 30, daysMask = sundayOnly)
        assertEquals(at(ny, 2024, 6, 16, 1, 30), result) // Sunday 01:30 is still ahead of now
    }

    @Test
    fun `every-day mask finds tomorrow same time when today's time has passed`() {
        val now = at(ny, 2024, 6, 10, 10, 0)
        val result = NextOccurrence.alarm(now, hour = 9, minute = 15, daysMask = Alarm.ALL_DAYS_MASK)
        assertEquals(at(ny, 2024, 6, 11, 9, 15), result)
    }

    @Test
    fun `candidate exactly equal to now is accepted (strictly at or after)`() {
        val now = at(ny, 2024, 6, 10, 15, 30)
        val result = NextOccurrence.alarm(now, hour = 15, minute = 30, daysMask = 0)
        assertEquals(now, result)
    }

    @Test
    fun `result is never before now across sampled instants`() {
        val masks = intArrayOf(0, Alarm.ALL_DAYS_MASK, Alarm.WEEKDAYS_MASK, Alarm.WEEKENDS_MASK)
        // Fri evening and Sun evening sweeps cross midnight and the weekend boundary.
        val starts = listOf(
            at(utc, 2024, 6, 7, 22, 0), // Friday 22:00 UTC
            at(utc, 2024, 6, 9, 22, 0), // Sunday 22:00 UTC
            at(ny, 2024, 6, 7, 22, 0), // Friday 22:00 New York
            at(ny, 2024, 6, 9, 22, 0), // Sunday 22:00 New York
        )
        for (start in starts) {
            var now = start
            repeat(16) { // 16 x 45 min = 12 h span per sweep
                for (mask in masks) {
                    val result = NextOccurrence.alarm(now, hour = 9, minute = 30, daysMask = mask)
                    assertFalse(
                        "next occurrence $result precedes now $now (mask=$mask)",
                        result.isBefore(now),
                    )
                }
                now = now.plusMinutes(45)
            }
        }
    }

    @Test
    fun `dst spring-forward gap resolves sanely without exception`() {
        // 2024-03-10 02:00-03:00 does not exist in America/New_York; request 02:30.
        val now = at(ny, 2024, 3, 9, 23, 0) // Saturday night before the transition
        val result = NextOccurrence.alarm(now, hour = 2, minute = 30, daysMask = 0)
        assertTrue(result.isAfter(now))
        // ZonedDateTime resolution shifts a gap forward by its length: 02:30 -> 03:30 EDT.
        val resolved = result.withZoneSameInstant(ny)
        assertEquals(LocalDate.of(2024, 3, 10), resolved.toLocalDate())
        assertEquals(3, resolved.hour)
        assertEquals(30, resolved.minute)
        assertEquals(ZoneOffset.ofHours(-4), resolved.offset)
    }

    @Test
    fun `dst fall-back ambiguous time resolves without exception`() {
        // 2024-11-03 01:30 occurs twice in America/New_York (fall-back).
        val now = at(ny, 2024, 11, 2, 23, 0) // Saturday night before the transition
        val result = NextOccurrence.alarm(now, hour = 1, minute = 30, daysMask = 0)
        assertTrue(result.isAfter(now))
        assertEquals(LocalDate.of(2024, 11, 3), result.toLocalDate())
        assertEquals(1, result.hour)
        assertEquals(30, result.minute)
        // Ambiguous wall times resolve to the earlier offset (EDT, first occurrence).
        assertEquals(ZoneOffset.ofHours(-4), result.offset)
    }
}
