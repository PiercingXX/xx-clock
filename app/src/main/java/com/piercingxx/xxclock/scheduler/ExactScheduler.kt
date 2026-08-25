package com.piercingxx.xxclock.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.piercingxx.xxclock.Actions
import com.piercingxx.xxclock.data.ClockStore
import com.piercingxx.xxclock.model.Alarm
import com.piercingxx.xxclock.model.TimerItem
import com.piercingxx.xxclock.receiver.AlarmEventReceiver
import com.piercingxx.xxclock.ui.MainActivity

/**
 * All AlarmManager access lives here.
 *
 * - Alarms use setAlarmClock(): exact, fires in Doze, exempt from exact-alarm
 *   permission gates, and surfaces the system-wide "next alarm" indicator.
 * - Timers use setExactAndAllowWhileIdle() for the soonest RUNNING deadline so the
 *   alarm-clock slot stays reserved for real alarms (AOSP DeskClock pattern).
 */
object ExactScheduler {

    private const val SCHED_PREFS = "xx_clock_sched"
    private const val KEY_TIMER_ID = "timer_sched_id"
    private const val KEY_TIMER_AT = "timer_sched_at"
    private const val TIMER_WINDOW_MS = 10 * 60 * 1000L

    // ------------------------------------------------------------ alarms

    fun scheduleAlarmAt(context: Context, alarm: Alarm, atMs: Long) {
        ClockStore.get(context).setScheduledFire(alarm.id, atMs)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAlarmClock(
            AlarmManager.AlarmClockInfo(atMs, showIntent(context, alarm.id)),
            firePendingIntent(context, Actions.FIRE_ALARM, alarm.id),
        )
    }

    fun cancelAlarm(context: Context, id: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(firePendingIntent(context, Actions.FIRE_ALARM, id))
        ClockStore.get(context).clearScheduledFire(id)
    }

    /** PendingIntent that opens MainActivity on the Alarms tab (system "next alarm" tap target). */
    private fun showIntent(context: Context, id: Long): PendingIntent = PendingIntent.getActivity(
        context,
        id.toInt(),
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(Actions.EXTRA_TAB, "alarms"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // ------------------------------------------------------------ timers

    /**
     * Ensures exactly one system alarm exists for the soonest RUNNING timer.
     * Cancels stale registrations when the soonest deadline changes.
     */
    @Synchronized
    fun scheduleSoonestTimer(context: Context) {
        val prefs = context.getSharedPreferences(SCHED_PREFS, Context.MODE_PRIVATE)
        val oldId = prefs.getLong(KEY_TIMER_ID, -1L)
        val oldAt = prefs.getLong(KEY_TIMER_AT, 0L)

        val next: TimerItem? = ClockStore.get(context).timers()
            .filter { it.state == TimerItem.STATE_RUNNING }
            .minByOrNull { it.endsAtEpochMs }

        if (next != null && next.id == oldId && next.endsAtEpochMs == oldAt) return

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (oldId != -1L) am.cancel(firePendingIntent(context, Actions.FIRE_TIMER, oldId))

        if (next == null) {
            prefs.edit().remove(KEY_TIMER_ID).remove(KEY_TIMER_AT).apply()
            return
        }

        val pi = firePendingIntent(context, Actions.FIRE_TIMER, next.id)
        val canExact = Build.VERSION.SDK_INT < 31 ||
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.endsAtEpochMs, pi)
        } else {
            am.setWindow(AlarmManager.RTC_WAKEUP, next.endsAtEpochMs, TIMER_WINDOW_MS, pi)
        }
        prefs.edit().putLong(KEY_TIMER_ID, next.id).putLong(KEY_TIMER_AT, next.endsAtEpochMs).apply()
    }

    // ------------------------------------------------------------ shared

    private fun firePendingIntent(context: Context, action: String, id: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            id.toInt(),
            Intent(context, AlarmEventReceiver::class.java).setAction(action).putExtra(Actions.EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
