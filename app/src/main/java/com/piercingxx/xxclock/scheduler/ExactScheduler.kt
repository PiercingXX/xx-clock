package com.piercingxx.xxclock.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import com.piercingxx.xxclock.Actions
import com.piercingxx.xxclock.data.ClockStore
import com.piercingxx.xxclock.data.ClockStoreBackend
import com.piercingxx.xxclock.model.Alarm
import com.piercingxx.xxclock.model.TimerItem
import com.piercingxx.xxclock.receiver.AlarmEventReceiver
import com.piercingxx.xxclock.ui.MainActivity

private const val SCHED_PREFS = "xx_clock_sched"
private const val KEY_DEGRADED_IDS = "alarm_degraded_ids"

/**
 * One scheduler-prefs file for every entry point. Receiver paths arrive in the
 * device-protected scope while UI/repository paths arrive credential-encrypted;
 * same name + different scope would mean two different files, so resolve
 * through device-protected storage exactly like data/ClockStore does.
 */
private fun schedPrefsOf(context: Context): SharedPreferences =
    context.applicationContext
        .createDeviceProtectedStorageContext()
        .getSharedPreferences(SCHED_PREFS, Context.MODE_PRIVATE)

/**
 * All AlarmManager access lives here.
 *
 * - Alarms use setAlarmClock(): exact, fires in Doze, exempt from exact-alarm
 *   permission gates, and surfaces the system-wide "next alarm" indicator.
 * - Timers use setExactAndAllowWhileIdle() for the soonest RUNNING deadline so the
 *   alarm-clock slot stays reserved for real alarms (AOSP DeskClock pattern).
 *
 * Revoked exact-alarm access never throws: [scheduleAlarmAt] degrades that one
 * alarm to an inexact window (both when [AlarmManager.canScheduleExactAlarms] is
 * false and when the exact call answers SecurityException) and records the
 * degradation so Setup can surface it. One unregistrable alarm therefore cannot
 * abort a reconcile pass and leave the rest of them dark.
 */
object ExactScheduler {

    // ---------------------------------------------------- collaborator seams

    // Injectable collaborators so JVM tests can drive the exact/window fallback
    // with recorders and an in-memory store (family rule: seams, not Android
    // mocks). Defaults are the production wiring.
    internal var storeOf: (Context) -> ClockStoreBackend = { ClockStore.get(it) }
    internal var canScheduleExact: (Context) -> Boolean = { context ->
        Build.VERSION.SDK_INT < 31 ||
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
    }
    internal var scheduleExact: (Context, Alarm, Long) -> Unit = { context, alarm, atMs ->
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAlarmClock(
            AlarmManager.AlarmClockInfo(atMs, showIntent(context, alarm.id)),
            firePendingIntent(context, Actions.FIRE_ALARM, alarm.id),
        )
    }
    internal var scheduleWindow: (Context, Alarm, Long) -> Unit = { context, alarm, atMs ->
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setWindow(AlarmManager.RTC_WAKEUP, atMs, ALARM_WINDOW_MS, firePendingIntent(context, Actions.FIRE_ALARM, alarm.id))
    }
    internal var cancelSystemAlarm: (Context, Long) -> Unit = { context, id ->
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(firePendingIntent(context, Actions.FIRE_ALARM, id))
    }
    internal var degradedStoreOf: (Context) -> DegradedAlarmStore = { PrefsDegradedAlarmStore(it) }

    private const val KEY_TIMER_ID = "timer_sched_id"
    private const val KEY_TIMER_AT = "timer_sched_at"
    private const val TIMER_WINDOW_MS = 10 * 60 * 1000L
    private const val ALARM_WINDOW_MS = 10 * 60 * 1000L

    // ---- PendingIntent request codes (P2.11) ----

    /** Kind tags packed into the top bits of a request code. */
    internal const val REQ_KIND_ALARM = 1
    internal const val REQ_KIND_TIMER = 2
    internal const val REQ_KIND_SHOW = 3
    private const val REQ_CODE_ID_MASK = (1 shl 29) - 1

    // WHY: request codes are ints while ids are longs — the kind rides the top
    // bits above a hi-xor-lo fold of the id, so ids 2^32 apart (identical under
    // toInt()) can no longer overwrite each other's FLAG_UPDATE_CURRENT intents.
    internal fun requestCode(kind: Int, id: Long): Int =
        (kind shl 29) or ((id xor (id ushr 32)).toInt() and REQ_CODE_ID_MASK)

    // ------------------------------------------------------------ alarms

    /**
     * Registers [alarm] at [atMs]. Returns true when registered exactly, false
     * when degraded to an inexact window (exact access revoked, or the exact
     * call answered SecurityException).
     */
    fun scheduleAlarmAt(context: Context, alarm: Alarm, atMs: Long): Boolean {
        storeOf(context).setScheduledFire(alarm.id, atMs)
        val degraded = if (canScheduleExact(context)) {
            try {
                scheduleExact(context, alarm, atMs)
                false
            } catch (_: SecurityException) {
                scheduleWindow(context, alarm, atMs)
                true
            }
        } else {
            scheduleWindow(context, alarm, atMs)
            true
        }
        rememberDegraded(context, alarm.id, degraded)
        return !degraded
    }

    fun cancelAlarm(context: Context, id: Long) {
        cancelSystemAlarm(context, id)
        storeOf(context).clearScheduledFire(id)
        rememberDegraded(context, id, degraded = false)
    }

    /** Ids currently known to live on inexact windows instead of exact slots. */
    fun degradedAlarmIds(context: Context): Set<Long> =
        degradedStoreOf(context)
            .ids()
            .mapNotNull { it.toLongOrNull() }
            .toSet()

    fun hasDegradedAlarm(context: Context): Boolean = degradedAlarmIds(context).isNotEmpty()

    private fun rememberDegraded(context: Context, id: Long, degraded: Boolean) {
        val store = degradedStoreOf(context)
        val key = id.toString()
        val current = store.ids()
        val updated = if (degraded) current + key else current - key
        if (updated != current) store.save(updated)
    }

    /** PendingIntent that opens MainActivity on the Alarms tab (system "next alarm" tap target). */
    private fun showIntent(context: Context, id: Long): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode(REQ_KIND_SHOW, id),
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
        val prefs = schedPrefsOf(context)
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
        if (canScheduleExact(context)) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.endsAtEpochMs, pi)
            } catch (_: SecurityException) {
                am.setWindow(AlarmManager.RTC_WAKEUP, next.endsAtEpochMs, TIMER_WINDOW_MS, pi)
            }
        } else {
            am.setWindow(AlarmManager.RTC_WAKEUP, next.endsAtEpochMs, TIMER_WINDOW_MS, pi)
        }
        prefs.edit().putLong(KEY_TIMER_ID, next.id).putLong(KEY_TIMER_AT, next.endsAtEpochMs).apply()
    }

    // ------------------------------------------------------------ shared

    private fun firePendingIntent(context: Context, action: String, id: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode(
                if (action == Actions.FIRE_ALARM) REQ_KIND_ALARM else REQ_KIND_TIMER,
                id,
            ),
            Intent(context, AlarmEventReceiver::class.java).setAction(action).putExtra(Actions.EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

/** Persistence for the degraded-marker set; interface so JVM tests substitute memory. */
interface DegradedAlarmStore {
    fun ids(): Set<String>
    fun save(ids: Set<String>)
}

private class PrefsDegradedAlarmStore(context: Context) : DegradedAlarmStore {
    private val prefs = schedPrefsOf(context)

    override fun ids(): Set<String> = prefs.getStringSet(KEY_DEGRADED_IDS, emptySet()) ?: emptySet()

    override fun save(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_DEGRADED_IDS, ids).apply()
    }
}
