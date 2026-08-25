package com.piercingxx.xxclock.util

import android.content.Context
import android.text.format.DateFormat
import java.util.Date

/** Locale-aware time formatting helpers. */
object Fmt {
    fun time(context: Context, epochMs: Long): String =
        DateFormat.getTimeFormat(context).format(Date(epochMs))

    /** Short relative description like "in 7 h 53 m". */
    fun until(nowMs: Long, targetMs: Long): String {
        var seconds = (targetMs - nowMs) / 1000
        if (seconds < 0) seconds = 0
        val days = seconds / 86_400
        val hours = (seconds % 86_400) / 3600
        val minutes = (seconds % 3600) / 60
        return buildString {
            if (days > 0) append(days).append(" d ")
            if (hours > 0 || days > 0) append(hours).append(" h ")
            append(minutes).append(" m")
        }
    }
}
