package com.piercingxx.xxclock.model

import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Locale

/**
 * A weekly-recurring (or one-shot) alarm definition.
 *
 * [daysMask] bit layout: bit0 = Monday ... bit6 = Sunday.
 * daysMask == 0 means a one-shot alarm (fires once at the next matching time).
 * The source of truth is (hour, minute, daysMask) — never an absolute fire time;
 * the next occurrence is always computed fresh (see time/NextOccurrence).
 *
 * [soundUri] is the per-alarm ringtone as a URI string, or null for the
 * system default alarm sound. A string (not android.net.Uri) keeps the model
 * pure Kotlin/JVM-testable; only the playback edge (KlaxonPlayer) parses it,
 * and it falls back to the default when the URI no longer resolves.
 */
data class Alarm(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val daysMask: Int,
    val label: String,
    val enabled: Boolean,
    val vibrate: Boolean,
    val soundUri: String? = null,
) {
    val repeating: Boolean get() = daysMask != 0

    companion object {
        val WEEKDAYS_MASK: Int = bitForCalendarDay(Calendar.MONDAY) or
            bitForCalendarDay(Calendar.TUESDAY) or
            bitForCalendarDay(Calendar.WEDNESDAY) or
            bitForCalendarDay(Calendar.THURSDAY) or
            bitForCalendarDay(Calendar.FRIDAY)
        val WEEKENDS_MASK: Int = bitForCalendarDay(Calendar.SATURDAY) or
            bitForCalendarDay(Calendar.SUNDAY)
        val ALL_DAYS_MASK: Int = WEEKDAYS_MASK or WEEKENDS_MASK

        fun newAlarm(hour: Int, minute: Int): Alarm = Alarm(
            id = System.currentTimeMillis(),
            hour = hour,
            minute = minute,
            daysMask = 0,
            label = "",
            enabled = true,
            vibrate = true,
        )

        /** Maps [Calendar.DAY_OF_WEEK] values to mask bits (Mon=bit0 .. Sun=bit6). */
        fun bitForCalendarDay(calendarDayOfWeek: Int): Int = when (calendarDayOfWeek) {
            Calendar.MONDAY -> 1 shl 0
            Calendar.TUESDAY -> 1 shl 1
            Calendar.WEDNESDAY -> 1 shl 2
            Calendar.THURSDAY -> 1 shl 3
            Calendar.FRIDAY -> 1 shl 4
            Calendar.SATURDAY -> 1 shl 5
            Calendar.SUNDAY -> 1 shl 6
            else -> 0
        }

        /** Days in a mask, ordered Monday-first, as [Calendar.DAY_OF_WEEK] values. */
        fun calendarDays(mask: Int): List<Int> = listOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
            Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY,
        ).filter { mask and bitForCalendarDay(it) != 0 }

        /** Human-readable summary: Once / Weekdays / Weekends / Every day / "Mon, Wed". */
        fun summary(mask: Int): String = when {
            mask == 0 -> "Once"
            mask == WEEKDAYS_MASK -> "Weekdays"
            mask == WEEKENDS_MASK -> "Weekends"
            mask == ALL_DAYS_MASK -> "Every day"
            else -> calendarDays(mask).joinToString(", ") { shortDayName(it) }
        }

        /** Deterministic English short name (Mon..Sun) for a [Calendar.DAY_OF_WEEK] value. */
        fun shortDayName(calendarDayOfWeek: Int): String {
            val iso = ((calendarDayOfWeek + 5) % 7) + 1 // Calendar(2=Mon..1=Sun) -> ISO(1=Mon..7=Sun)
            return DayOfWeek.of(iso).getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
        }
    }
}
