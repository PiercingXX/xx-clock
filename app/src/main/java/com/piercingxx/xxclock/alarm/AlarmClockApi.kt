package com.piercingxx.xxclock.alarm

import com.piercingxx.xxclock.model.Alarm
import com.piercingxx.xxclock.model.TimerItem
import com.piercingxx.xxclock.time.NextOccurrence

/**
 * Pure AlarmClock contract (android.provider.AlarmClock) — no android.* so the
 * SET/SHOW/DISMISS/SNOOZE rules are JVM-testable. The NoDisplay activity
 * extracts extras and applies these results through the repositories.
 */
object AlarmClockApi {

    const val SEARCH_TIME = "android.time"
    const val SEARCH_NEXT = "android.next"
    const val SEARCH_ALL = "android.all"
    const val SEARCH_LABEL = "android.label"
    const val RINGTONE_SILENT = "silent"
    const val MAX_TIMER_SECONDS = 86_400
    const val MIN_TIMER_SECONDS = 1

    data class SetAlarmRequest(
        val hour: Int,
        val minute: Int,
        val daysMask: Int,
        val label: String,
        val vibrate: Boolean,
        val soundUri: String?,
        val skipUi: Boolean,
    )

    data class SetTimerRequest(
        val durationMs: Long,
        val label: String,
        val skipUi: Boolean,
    )

    /** Null when the intent has no usable time — caller must open the alarms UI. */
    fun setAlarmRequest(
        hour: Int?,
        minute: Int,
        calendarDays: Collection<Int>,
        label: String,
        vibrate: Boolean,
        ringtone: String?,
        skipUi: Boolean,
    ): SetAlarmRequest? {
        if (hour == null || hour !in 0..23 || minute !in 0..59) return null
        val sound = ringtone
            ?.takeIf { it.isNotBlank() && it != RINGTONE_SILENT }
        return SetAlarmRequest(
            hour = hour,
            minute = minute,
            daysMask = daysMaskFromCalendarDays(calendarDays),
            label = label.trim(),
            vibrate = vibrate,
            soundUri = sound,
            skipUi = skipUi,
        )
    }

    fun daysMaskFromCalendarDays(calendarDays: Collection<Int>): Int =
        calendarDays.fold(0) { acc, day -> acc or Alarm.bitForCalendarDay(day) }

    /** Null when length is missing or out of 1s..24h — caller must open the timers UI. */
    fun setTimerRequest(lengthSeconds: Int?, label: String, skipUi: Boolean): SetTimerRequest? {
        if (lengthSeconds == null || lengthSeconds !in MIN_TIMER_SECONDS..MAX_TIMER_SECONDS) return null
        return SetTimerRequest(
            durationMs = lengthSeconds * 1000L,
            label = label.trim(),
            skipUi = skipUi,
        )
    }

    /**
     * Hour used for TIME search. [isPm] is only applied when the given hour
     * is in 1..12 (voice "seven pm"); a 0..23 hour is left alone.
     */
    fun searchHour(hour: Int?, isPm: Boolean?): Int? {
        if (hour == null) return null
        if (isPm == null || hour !in 1..12) return hour.takeIf { it in 0..23 }
        return if (isPm) {
            if (hour == 12) 12 else hour + 12
        } else {
            if (hour == 12) 0 else hour
        }
    }

    fun identicalAlarm(alarms: List<Alarm>, request: SetAlarmRequest): Alarm? =
        alarms.firstOrNull {
            it.hour == request.hour &&
                it.minute == request.minute &&
                it.daysMask == request.daysMask &&
                it.label == request.label &&
                it.vibrate == request.vibrate &&
                it.soundUri == request.soundUri
        }

    fun reusableTimer(timers: List<TimerItem>, request: SetTimerRequest): TimerItem? =
        timers.firstOrNull {
            it.state == TimerItem.STATE_IDLE &&
                it.durationMs == request.durationMs &&
                it.label == request.label
        }

    /**
     * Alarms selected by a DISMISS_ALARM search. Empty means "open the UI
     * and let the user pick". A currently ringing alarm always wins for
     * NEXT / unspecified.
     */
    fun alarmsToDismiss(
        alarms: List<Alarm>,
        ringingAlarmId: Long?,
        nextArmedId: Long?,
        searchMode: String?,
        hour: Int?,
        minute: Int?,
        label: String?,
    ): List<Alarm> {
        val enabled = alarms.filter { it.enabled }
        return when (searchMode) {
            SEARCH_ALL -> enabled
            SEARCH_NEXT -> {
                val id = ringingAlarmId ?: nextArmedId ?: return emptyList()
                alarms.filter { it.id == id }
            }
            SEARCH_LABEL -> {
                val q = label?.trim().orEmpty()
                if (q.isEmpty()) emptyList()
                else enabled.filter { it.label.contains(q, ignoreCase = true) }
            }
            SEARCH_TIME -> {
                if (hour == null || hour !in 0..23) return emptyList()
                val min = minute ?: 0
                if (min !in 0..59) return emptyList()
                enabled.filter { it.hour == hour && it.minute == min }
            }
            else -> when {
                ringingAlarmId != null -> alarms.filter { it.id == ringingAlarmId }
                enabled.size == 1 -> enabled
                else -> emptyList()
            }
        }
    }

    /**
     * Skip the upcoming instance: one-shots disable; repeating alarms move
     * their next ring to strictly after the upcoming slot.
     */
    fun skipUpcomingUntilMs(alarm: Alarm, nowMs: Long): Long? {
        if (!alarm.repeating) return null
        return NextOccurrence.alarmMillis(alarm.hour, alarm.minute, alarm.daysMask, nowMs) + 1L
    }

    fun timersToDismiss(
        timers: List<TimerItem>,
        ringingTimerId: Long?,
        label: String?,
    ): List<TimerItem> {
        if (ringingTimerId != null) return timers.filter { it.id == ringingTimerId }
        val q = label?.trim().orEmpty()
        if (q.isNotEmpty()) {
            return timers.filter {
                it.label.contains(q, ignoreCase = true) &&
                    (it.state == TimerItem.STATE_FINISHED || it.state == TimerItem.STATE_RUNNING)
            }
        }
        return timers.filter { it.state == TimerItem.STATE_FINISHED }
    }

    fun snoozeMinutes(requested: Int?, defaultMinutes: Int): Int {
        val n = requested ?: defaultMinutes
        return n.coerceIn(1, 120)
    }
}
