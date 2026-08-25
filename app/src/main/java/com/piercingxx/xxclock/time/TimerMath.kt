package com.piercingxx.xxclock.time

import com.piercingxx.xxclock.model.TimerItem
import java.util.Locale
import kotlin.math.max

/** Pure timer math — unit-tested, no Android dependencies. */
object TimerMath {

    /** Milliseconds left on [t] at instant [nowMs]. Never negative. */
    fun remainingMs(t: TimerItem, nowMs: Long): Long = when (t.state) {
        TimerItem.STATE_RUNNING -> max(0L, t.endsAtEpochMs - nowMs)
        TimerItem.STATE_PAUSED, TimerItem.STATE_FINISHED -> max(0L, t.remainingMs)
        else -> t.durationMs
    }

    /** True when a RUNNING timer has reached (or passed) its deadline at [nowMs]. */
    fun isExpired(t: TimerItem, nowMs: Long): Boolean =
        t.state == TimerItem.STATE_RUNNING && nowMs >= t.endsAtEpochMs

    /** "H:MM:SS" above one hour, otherwise "MM:SS". Rounds up so 1ms still shows 0:01. */
    fun display(ms: Long): String {
        val totalSeconds = (ms + 999) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
