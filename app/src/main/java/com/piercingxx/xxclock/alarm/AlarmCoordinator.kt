package com.piercingxx.xxclock.alarm

import android.content.Context
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.piercingxx.xxclock.Actions
import com.piercingxx.xxclock.Prefs
import com.piercingxx.xxclock.R
import com.piercingxx.xxclock.data.ClockStore
import com.piercingxx.xxclock.model.Alarm
import com.piercingxx.xxclock.model.TimerItem
import com.piercingxx.xxclock.notify.Channels
import com.piercingxx.xxclock.scheduler.ExactScheduler
import com.piercingxx.xxclock.service.RingService
import com.piercingxx.xxclock.time.NextOccurrence
import com.piercingxx.xxclock.widget.DigitalWidgetProvider

/**
 * The alarm/timer lifecycle coordinator — the single place that mutates runtime state.
 *
 * Flow: AlarmManager -> AlarmEventReceiver -> handleAction() -> RingService (foreground,
 * carries CATEGORY_ALARM full-screen-intent notification) + AlarmAlertActivity (FSI target).
 *
 * Policies:
 *  - Newest wins: a firing alarm silences any currently ringing one.
 *  - Recurring alarms pre-schedule their next occurrence at fire time, so the system
 *    next-alarm indicator never goes dark and dismiss() needs no rescheduling.
 *  - One-shot alarms disable themselves at fire time.
 *  - Snooze is tracked as snoozedUntil state; editing an alarm cancels its snooze.
 */
object AlarmCoordinator {

    fun handleAction(context: Context, action: String?, id: Long, snoozeMinutes: Int) {
        when (action) {
            Actions.FIRE_ALARM -> fireAlarm(context, id)
            Actions.SNOOZE_ALARM -> snooze(context, id, if (snoozeMinutes > 0) snoozeMinutes else Prefs.SNOOZE_MINUTES_DEFAULT)
            Actions.DISMISS_ALARM -> dismiss(context, id)
            Actions.FIRE_TIMER -> fireTimer(context, id)
            Actions.STOP_TIMER -> stopTimer(context, id)
            Actions.ADD_MINUTE_TIMER -> addMinuteToRingingTimer(context, id)
            android.content.Intent.ACTION_BOOT_COMPLETED,
            android.content.Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.MY_PACKAGE_REPLACED",
            -> reconcile(context, force = true, recoverRinging = true)
            android.content.Intent.ACTION_TIME_CHANGED,
            android.content.Intent.ACTION_TIMEZONE_CHANGED,
            -> reconcile(context, force = false, recoverRinging = false)
        }
    }

    // ------------------------------------------------------------ alarms

    fun fireAlarm(context: Context, id: Long) {
        val store = ClockStore.get(context)
        val alarm = store.getAlarm(id) ?: return

        silenceOtherRinger(context, store, exceptId = id)

        if (alarm.repeating) {
            val next = NextOccurrence.alarmMillis(alarm.hour, alarm.minute, alarm.daysMask)
            ExactScheduler.scheduleAlarmAt(context, alarm, next)
        } else {
            store.saveAlarm(alarm.copy(enabled = false))
        }

        store.setSnoozedUntil(id, 0L)
        store.setRinging(id, true)
        if (!RingService.start(context, Actions.FIRE_ALARM, id)) {
            ringStartFailed(context, id, isAlarm = true)
        }
        DigitalWidgetProvider.refreshAll(context)
    }

    /** Newest-wins: silence any other ringer (alarm or timer) without a missed notification. */
    private fun silenceOtherRinger(context: Context, store: ClockStore, exceptId: Long) {
        store.ringingId()?.let { current ->
            if (current != exceptId) {
                autoSilence(context, current, isAlarm = store.getAlarm(current) != null, postNotification = false)
            }
        }
    }

    fun snooze(context: Context, id: Long, minutes: Int) {
        val store = ClockStore.get(context)
        val alarm = store.getAlarm(id) ?: run { RingService.stop(context); return }
        RingService.stop(context)
        val until = System.currentTimeMillis() + minutes * 60_000L
        store.setRinging(id, false)
        store.setSnoozedUntil(id, until)
        ExactScheduler.scheduleAlarmAt(context, alarm, until)
        DigitalWidgetProvider.refreshAll(context)
    }

    fun dismiss(context: Context, id: Long) {
        RingService.stop(context)
        ClockStore.get(context).setRinging(id, false)
        DigitalWidgetProvider.refreshAll(context)
    }

    /** Auto-silence timeout reached while ringing. */
    fun autoSilence(context: Context, id: Long, isAlarm: Boolean, postNotification: Boolean = true) {
        RingService.stop(context)
        val store = ClockStore.get(context)
        store.setRinging(id, false)
        if (isAlarm) {
            store.clearRuntime(id)
            if (postNotification) postMissed(context, id, title = context.getString(R.string.notif_alarm_silenced))
        } else {
            stopTimerInternal(context, id)
        }
        DigitalWidgetProvider.refreshAll(context)
    }

    /**
     * Degrades gracefully when RingService cannot come up (FGS-start restrictions,
     * notification failures): clears ringing state and posts a fallback notification
     * so the user still learns the alarm/timer fired.
     */
    fun ringStartFailed(context: Context, id: Long, isAlarm: Boolean) {
        val store = ClockStore.get(context)
        store.setRinging(id, false)
        if (isAlarm) {
            store.clearRuntime(id)
            postMissed(context, id, title = context.getString(R.string.notif_alarm_missed))
        } else {
            stopTimerInternal(context, id)
            postMissed(context, id, title = context.getString(R.string.notif_timer_finished))
        }
        DigitalWidgetProvider.refreshAll(context)
    }

    // ------------------------------------------------------------ timers

    fun fireTimer(context: Context, id: Long, tryRing: Boolean = true) {
        val store = ClockStore.get(context)
        val timer = store.getTimer(id) ?: return

        silenceOtherRinger(context, store, exceptId = id)

        store.saveTimer(timer.copy(state = TimerItem.STATE_FINISHED, remainingMs = 0L, endsAtEpochMs = 0L))
        store.setRinging(id, true)
        val started = tryRing && RingService.start(context, Actions.FIRE_TIMER, id)
        if (!started) {
            store.setRinging(id, false)
            postMissed(context, id, title = context.getString(R.string.notif_timer_finished))
        }
        ExactScheduler.scheduleSoonestTimer(context)
    }

    fun stopTimer(context: Context, id: Long) {
        RingService.stop(context)
        stopTimerInternal(context, id)
    }

    private fun stopTimerInternal(context: Context, id: Long) {
        val store = ClockStore.get(context)
        store.setRinging(id, false)
        val timer = store.getTimer(id) ?: return
        store.saveTimer(
            timer.copy(
                state = TimerItem.STATE_IDLE,
                remainingMs = timer.durationMs,
                endsAtEpochMs = 0L,
            ),
        )
        ExactScheduler.scheduleSoonestTimer(context)
    }

    /** "+1 min" while a timer is ringing: restart it with one minute on the clock. */
    fun addMinuteToRingingTimer(context: Context, id: Long) {
        RingService.stop(context)
        val store = ClockStore.get(context)
        store.setRinging(id, false)
        val timer = store.getTimer(id) ?: return
        store.saveTimer(
            timer.copy(
                state = TimerItem.STATE_RUNNING,
                endsAtEpochMs = System.currentTimeMillis() + 60_000L,
                remainingMs = 0L,
            ),
        )
        ExactScheduler.scheduleSoonestTimer(context)
    }

    // ------------------------------------------------------------ reconciliation

    /**
     * Called on boot, time-set, timezone-change, package-replace and app start.
     * Rebuilds all scheduling from persisted definitions; never starts the ring
     * service for alarms (a missed alarm becomes a notification), but gives
     * recently-expired timers a grace window to still ring.
     *
     * [force] — re-register even when persisted bookkeeping matches (required for
     * BOOT_COMPLETED / MY_PACKAGE_REPLACED: system alarm registrations are wiped by
     * reboot and package update while our SharedPreferences survive).
     * [recoverRinging] — treat a persisted ringing flag as "process died mid-ring"
     * (boot / app start). In-process triggers like TIME_SET must not misclassify a
     * live ringer as missed.
     */
    fun reconcile(context: Context, force: Boolean = false, recoverRinging: Boolean = true) {
        val store = ClockStore.get(context)
        val now = System.currentTimeMillis()

        for (alarm in store.alarms()) {
            when {
                store.isRinging(alarm.id) && recoverRinging -> {
                    // Process died mid-ring: mark missed; next occurrence was already scheduled.
                    store.setRinging(alarm.id, false)
                    store.clearRuntime(alarm.id)
                    if (alarm.repeating) {
                        val next = NextOccurrence.alarmMillis(alarm.hour, alarm.minute, alarm.daysMask)
                        ExactScheduler.scheduleAlarmAt(context, alarm, next)
                    }
                    postMissed(context, alarm.id, title = context.getString(R.string.notif_alarm_missed))
                }
                !alarm.enabled -> {
                    // A disabled alarm may still owe a snoozed ring (one-shots disable at fire time).
                    val snoozedUntil = store.snoozedUntil(alarm.id)
                    if (snoozedUntil > now) {
                        if (force || store.scheduledFire(alarm.id) != snoozedUntil) {
                            ExactScheduler.scheduleAlarmAt(context, alarm, snoozedUntil)
                        }
                    } else {
                        if (snoozedUntil in 1..now) {
                            store.setSnoozedUntil(alarm.id, 0L)
                            postMissed(context, alarm.id, title = context.getString(R.string.notif_alarm_missed))
                        }
                        ExactScheduler.cancelAlarm(context, alarm.id)
                    }
                }
                else -> {
                    val snoozedUntil = store.snoozedUntil(alarm.id)
                    val expected = if (snoozedUntil > now) {
                        snoozedUntil
                    } else {
                        NextOccurrence.alarmMillis(alarm.hour, alarm.minute, alarm.daysMask, now)
                    }
                    if (force || store.scheduledFire(alarm.id) != expected) {
                        ExactScheduler.scheduleAlarmAt(context, alarm, expected)
                    }
                }
            }
        }

        for (timer in store.timers()) {
            if (store.isRinging(timer.id)) {
                if (recoverRinging) {
                    // Process died mid-ring: reset the timer and tell the user it finished.
                    store.setRinging(timer.id, false)
                    store.saveTimer(timer.copy(state = TimerItem.STATE_IDLE, remainingMs = timer.durationMs, endsAtEpochMs = 0L))
                    postMissed(context, timer.id, title = context.getString(R.string.notif_timer_finished_away))
                }
                continue
            }
            if (timer.state == TimerItem.STATE_RUNNING && timer.endsAtEpochMs <= now) {
                val overdue = now - timer.endsAtEpochMs
                if (overdue <= Prefs.TIMER_BOOT_GRACE_MS) {
                    fireTimer(context, timer.id, tryRing = true)
                } else {
                    store.saveTimer(timer.copy(state = TimerItem.STATE_FINISHED, remainingMs = 0L, endsAtEpochMs = 0L))
                    postMissed(context, timer.id, title = context.getString(R.string.notif_timer_finished_away))
                }
            }
        }
        ExactScheduler.scheduleSoonestTimer(context)
        DigitalWidgetProvider.refreshAll(context)
    }

    // ------------------------------------------------------------ notifications

    private fun postMissed(context: Context, id: Long, title: String) {
        Channels.ensure(context, Channels.ID_MISSED)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            (id % Int.MAX_VALUE).toInt(),
            NotificationCompat.Builder(context, Channels.ID_MISSED)
                .setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle(title)
                .setAutoCancel(true)
                .build(),
        )
    }
}
