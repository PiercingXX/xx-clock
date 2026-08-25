package com.piercingxx.xxclock.time

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pure recurrence math — unit-tested, no Android dependencies.
 *
 * Alarms are defined by (hour, minute, daysMask); the next occurrence is computed
 * fresh against the current wall clock and system zone, so DST transitions and
 * time-zone changes resolve naturally through ZonedDateTime arithmetic.
 */
object NextOccurrence {

    /**
     * Next datetime strictly at/after [now] matching hour:minute on a masked weekday.
     * daysMask == 0 (one-shot) means today if still in the future, otherwise tomorrow.
     * Checks at most 8 candidate days, which always contains a match.
     */
    fun alarm(now: ZonedDateTime, hour: Int, minute: Int, daysMask: Int): ZonedDateTime {
        require(hour in 0..23) { "hour out of range: $hour" }
        require(minute in 0..59) { "minute out of range: $minute" }
        var date = now.toLocalDate()
        repeat(8) {
            val candidate = date.atTime(hour, minute).atZone(now.zone)
            val dayBit = 1 shl (candidate.dayOfWeek.value - 1)
            val dayMatches = daysMask == 0 || (daysMask and dayBit) != 0
            if (!candidate.isBefore(now) && dayMatches) return candidate
            date = date.plusDays(1)
        }
        error("Unreachable: no occurrence within 8 days (hour=$hour minute=$minute daysMask=$daysMask)")
    }

    /** Epoch-milliseconds variant using the system default zone. */
    fun alarmMillis(
        hour: Int,
        minute: Int,
        daysMask: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): Long = alarm(
        Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()),
        hour,
        minute,
        daysMask,
    ).toInstant().toEpochMilli()
}
