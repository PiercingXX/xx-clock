package com.piercingxx.xxclock.alarm

import com.piercingxx.xxclock.model.Alarm
import com.piercingxx.xxclock.model.TimerItem
import com.piercingxx.xxclock.time.NextOccurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class AlarmClockApiTest {

    private fun alarm(
        id: Long,
        hour: Int = 7,
        minute: Int = 0,
        mask: Int = 0,
        label: String = "",
        enabled: Boolean = true,
    ) = Alarm(id, hour, minute, mask, label, enabled, vibrate = true)

    @Test
    fun `setAlarmRequest rejects missing or invalid hour`() {
        assertNull(AlarmClockApi.setAlarmRequest(null, 0, emptyList(), "", true, null, true))
        assertNull(AlarmClockApi.setAlarmRequest(24, 0, emptyList(), "", true, null, true))
        assertNull(AlarmClockApi.setAlarmRequest(7, 60, emptyList(), "", true, null, true))
    }

    @Test
    fun `setAlarmRequest maps weekdays and drops silent ringtone`() {
        val req = AlarmClockApi.setAlarmRequest(
            hour = 6,
            minute = 30,
            calendarDays = listOf(Calendar.MONDAY, Calendar.FRIDAY),
            label = "  Gym  ",
            vibrate = false,
            ringtone = AlarmClockApi.RINGTONE_SILENT,
            skipUi = true,
        )!!
        assertEquals(6, req.hour)
        assertEquals(30, req.minute)
        assertEquals(
            Alarm.bitForCalendarDay(Calendar.MONDAY) or Alarm.bitForCalendarDay(Calendar.FRIDAY),
            req.daysMask,
        )
        assertEquals("Gym", req.label)
        assertEquals(false, req.vibrate)
        assertNull(req.soundUri)
        assertTrue(req.skipUi)
    }

    @Test
    fun `setTimerRequest accepts 1s through 24h and rejects the rest`() {
        assertNull(AlarmClockApi.setTimerRequest(null, "", true))
        assertNull(AlarmClockApi.setTimerRequest(0, "", true))
        assertNull(AlarmClockApi.setTimerRequest(86_401, "", true))
        val req = AlarmClockApi.setTimerRequest(90, "Eggs", true)!!
        assertEquals(90_000L, req.durationMs)
        assertEquals("Eggs", req.label)
    }

    @Test
    fun `searchHour applies AM PM only to 1-12`() {
        assertEquals(19, AlarmClockApi.searchHour(7, isPm = true))
        assertEquals(7, AlarmClockApi.searchHour(7, isPm = false))
        assertEquals(12, AlarmClockApi.searchHour(12, isPm = true))
        assertEquals(0, AlarmClockApi.searchHour(12, isPm = false))
        assertEquals(19, AlarmClockApi.searchHour(19, isPm = true))
        assertEquals(7, AlarmClockApi.searchHour(7, isPm = null))
    }

    @Test
    fun `dismiss with no mode dismisses the ringer or the only enabled alarm`() {
        val a = alarm(1)
        val b = alarm(2, hour = 8)
        assertEquals(listOf(a.id), AlarmClockApi.alarmsToDismiss(listOf(a, b), ringingAlarmId = 1, nextArmedId = 2, searchMode = null, hour = null, minute = null, label = null).map { it.id })
        assertEquals(listOf(1L), AlarmClockApi.alarmsToDismiss(listOf(a), ringingAlarmId = null, nextArmedId = 1, searchMode = null, hour = null, minute = null, label = null).map { it.id })
        assertTrue(AlarmClockApi.alarmsToDismiss(listOf(a, b), ringingAlarmId = null, nextArmedId = 1, searchMode = null, hour = null, minute = null, label = null).isEmpty())
    }

    @Test
    fun `dismiss NEXT prefers the ringer then the next armed`() {
        val a = alarm(1)
        val b = alarm(2)
        assertEquals(1L, AlarmClockApi.alarmsToDismiss(listOf(a, b), 1, 2, AlarmClockApi.SEARCH_NEXT, null, null, null).single().id)
        assertEquals(2L, AlarmClockApi.alarmsToDismiss(listOf(a, b), null, 2, AlarmClockApi.SEARCH_NEXT, null, null, null).single().id)
    }

    @Test
    fun `dismiss TIME and LABEL and ALL`() {
        val gym = alarm(1, hour = 6, minute = 30, label = "Gym")
        val work = alarm(2, hour = 7, minute = 0, label = "Work")
        val off = alarm(3, hour = 6, minute = 30, label = "Gym", enabled = false)
        assertEquals(listOf(1L), AlarmClockApi.alarmsToDismiss(listOf(gym, work, off), null, null, AlarmClockApi.SEARCH_TIME, 6, 30, null).map { it.id })
        assertEquals(listOf(1L), AlarmClockApi.alarmsToDismiss(listOf(gym, work, off), null, null, AlarmClockApi.SEARCH_LABEL, null, null, "gym").map { it.id })
        assertEquals(listOf(1L, 2L), AlarmClockApi.alarmsToDismiss(listOf(gym, work, off), null, null, AlarmClockApi.SEARCH_ALL, null, null, null).map { it.id })
    }

    @Test
    fun `skip upcoming of a repeating alarm is strictly after the next slot`() {
        val originalTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val weekly = alarm(1, hour = 7, minute = 0, mask = Alarm.ALL_DAYS_MASK)
            val now = 1_700_000_000_000L
            val next = NextOccurrence.alarmMillis(7, 0, Alarm.ALL_DAYS_MASK, now)
            val skip = AlarmClockApi.skipUpcomingUntilMs(weekly, now)!!
            assertEquals(next + 1, skip)
            assertTrue(NextOccurrence.alarmMillis(7, 0, Alarm.ALL_DAYS_MASK, skip) > next)
            assertNull(AlarmClockApi.skipUpcomingUntilMs(alarm(2), now))
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `timers to dismiss are the ringer, matching label, or every finished timer`() {
        val idle = TimerItem(1, 60_000, TimerItem.STATE_IDLE, 0, 60_000, "Eggs")
        val done = TimerItem(2, 60_000, TimerItem.STATE_FINISHED, 0, 0, "Eggs")
        val run = TimerItem(3, 90_000, TimerItem.STATE_RUNNING, 9L, 0, "Pasta")
        assertEquals(listOf(3L), AlarmClockApi.timersToDismiss(listOf(idle, done, run), ringingTimerId = 3, label = null).map { it.id })
        assertEquals(listOf(2L), AlarmClockApi.timersToDismiss(listOf(idle, done, run), ringingTimerId = null, label = null).map { it.id })
        assertEquals(listOf(2L), AlarmClockApi.timersToDismiss(listOf(idle, done, run), ringingTimerId = null, label = "egg").map { it.id })
    }

    @Test
    fun `snooze minutes default and clamp`() {
        assertEquals(10, AlarmClockApi.snoozeMinutes(null, 10))
        assertEquals(1, AlarmClockApi.snoozeMinutes(0, 10))
        assertEquals(120, AlarmClockApi.snoozeMinutes(999, 10))
        assertEquals(15, AlarmClockApi.snoozeMinutes(15, 10))
    }
}
