package com.piercingxx.xxclock.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** Pure-JVM tests for the [Alarm] daysMask bit layout, day ordering, and summary(). */
class AlarmMaskTest {

    private fun maskOf(vararg days: Int): Int =
        days.fold(0) { acc, day -> acc or Alarm.bitForCalendarDay(day) }

    private val allCalendarDays =
        intArrayOf(
            Calendar.MONDAY,
            Calendar.TUESDAY,
            Calendar.WEDNESDAY,
            Calendar.THURSDAY,
            Calendar.FRIDAY,
            Calendar.SATURDAY,
            Calendar.SUNDAY,
        )

    @Test
    fun `bitForCalendarDay maps Mon to bit0 through Sun to bit6`() {
        assertEquals(1 shl 0, Alarm.bitForCalendarDay(Calendar.MONDAY))
        assertEquals(1 shl 1, Alarm.bitForCalendarDay(Calendar.TUESDAY))
        assertEquals(1 shl 2, Alarm.bitForCalendarDay(Calendar.WEDNESDAY))
        assertEquals(1 shl 3, Alarm.bitForCalendarDay(Calendar.THURSDAY))
        assertEquals(1 shl 4, Alarm.bitForCalendarDay(Calendar.FRIDAY))
        assertEquals(1 shl 5, Alarm.bitForCalendarDay(Calendar.SATURDAY))
        assertEquals(1 shl 6, Alarm.bitForCalendarDay(Calendar.SUNDAY))
    }

    @Test
    fun `bitForCalendarDay yields seven distinct powers of two`() {
        val bits = allCalendarDays.map { Alarm.bitForCalendarDay(it) }
        assertEquals(7, bits.toSet().size)
        assertTrue(bits.all { it != 0 && (it and (it - 1)) == 0 }) // each a single bit
    }

    @Test
    fun `round trip calendar day to bit and back`() {
        for (day in allCalendarDays) {
            val mask = Alarm.bitForCalendarDay(day)
            assertEquals(listOf(day), Alarm.calendarDays(mask))
        }
    }

    @Test
    fun `calendarDays orders monday-first`() {
        assertEquals(
            listOf(
                Calendar.MONDAY,
                Calendar.TUESDAY,
                Calendar.WEDNESDAY,
                Calendar.THURSDAY,
                Calendar.FRIDAY,
                Calendar.SATURDAY,
                Calendar.SUNDAY,
            ),
            Alarm.calendarDays(Alarm.ALL_DAYS_MASK),
        )
        assertEquals(
            listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY),
            Alarm.calendarDays(Alarm.WEEKDAYS_MASK),
        )
        assertEquals(
            listOf(Calendar.SATURDAY, Calendar.SUNDAY),
            Alarm.calendarDays(Alarm.WEEKENDS_MASK),
        )
    }

    @Test
    fun `summary for empty mask is Once`() {
        assertEquals("Once", Alarm.summary(0))
    }

    @Test
    fun `summary for weekdays weekends and every day`() {
        assertEquals("Weekdays", Alarm.summary(Alarm.WEEKDAYS_MASK))
        assertEquals("Weekends", Alarm.summary(Alarm.WEEKENDS_MASK))
        assertEquals("Every day", Alarm.summary(Alarm.ALL_DAYS_MASK))
    }

    @Test
    fun `summary for custom selection lists short names monday-first`() {
        assertEquals("Mon, Wed", Alarm.summary(maskOf(Calendar.WEDNESDAY, Calendar.MONDAY)))
        assertEquals("Tue", Alarm.summary(maskOf(Calendar.TUESDAY)))
        // Sat+Sun equals WEEKENDS_MASK and intentionally summarizes as "Weekends";
        // use a non-predefined combination to exercise the custom-list branch.
        assertEquals("Fri, Sun", Alarm.summary(maskOf(Calendar.SUNDAY, Calendar.FRIDAY)))
    }

    @Test
    fun `repeating is true exactly when mask is non-zero`() {
        val oneShot = Alarm(id = 1L, hour = 7, minute = 0, daysMask = 0, label = "", enabled = true, vibrate = true)
        assertFalse(oneShot.repeating)

        val weekly = Alarm(
            id = 2L,
            hour = 7,
            minute = 30,
            daysMask = maskOf(Calendar.MONDAY),
            label = "",
            enabled = true,
            vibrate = false,
        )
        assertTrue(weekly.repeating)
    }
}
