package com.piercingxx.xxclock.repo

import android.content.Context
import com.piercingxx.xxclock.alarm.AlarmCoordinator
import com.piercingxx.xxclock.data.ClockStore
import com.piercingxx.xxclock.model.Alarm
import com.piercingxx.xxclock.scheduler.ExactScheduler
import com.piercingxx.xxclock.service.RingService
import com.piercingxx.xxclock.time.NextOccurrence
import com.piercingxx.xxclock.widget.DigitalWidgetProvider

/**
 * UI-facing facade for alarms. The UI layer ONLY talks to repositories — never to
 * ClockStore/ExactScheduler/AlarmCoordinator directly. Every mutation persists,
 * reschedules, and refreshes the widget atomically enough for v1.
 */
object AlarmRepository {

    fun getAll(context: Context): List<Alarm> = ClockStore.get(context).alarms()

    fun get(context: Context, id: Long): Alarm? = ClockStore.get(context).getAlarm(id)

    /** Persists an alarm; editing cancels any active ring/snooze for it. */
    fun save(context: Context, alarm: Alarm) {
        val store = ClockStore.get(context)
        if (store.isRinging(alarm.id)) {
            RingService.stop(context)
            store.setRinging(alarm.id, false)
        }
        store.saveAlarm(alarm)
        store.clearRuntime(alarm.id)
        if (alarm.enabled) {
            ExactScheduler.scheduleAlarmAt(
                context,
                alarm,
                NextOccurrence.alarmMillis(alarm.hour, alarm.minute, alarm.daysMask),
            )
        } else {
            ExactScheduler.cancelAlarm(context, alarm.id)
        }
        DigitalWidgetProvider.refreshAll(context)
    }

    fun delete(context: Context, id: Long) {
        // Deleting a ringing alarm must silence it first (mirrors TimerRepository).
        if (ClockStore.get(context).isRinging(id)) {
            AlarmCoordinator.dismiss(context, id)
        }
        ExactScheduler.cancelAlarm(context, id)
        ClockStore.get(context).deleteAlarm(id)
        DigitalWidgetProvider.refreshAll(context)
    }

    fun toggle(context: Context, id: Long, enabled: Boolean) {
        val alarm = get(context, id) ?: return
        save(context, alarm.copy(enabled = enabled))
    }

    fun snoozedUntil(context: Context, id: Long): Long = ClockStore.get(context).snoozedUntil(id)

    fun scheduledFire(context: Context, id: Long): Long = ClockStore.get(context).scheduledFire(id)

    fun isRinging(context: Context, id: Long): Boolean = ClockStore.get(context).isRinging(id)

    /** Soonest upcoming ring across armed alarms — enabled, or still snoozed. */
    fun nextArmed(context: Context): Pair<Alarm, Long>? {
        val store = ClockStore.get(context)
        return nextArmed(
            alarms = store.alarms(),
            snoozedUntil = store::snoozedUntil,
            scheduledFire = store::scheduledFire,
            nowMs = System.currentTimeMillis(),
        )
    }

    /**
     * Pure core of [nextArmed]. An alarm is armed while enabled OR a snooze is
     * still pending: a one-shot that disabled itself at fire time must keep
     * counting once the user snoozes it.
     */
    internal fun nextArmed(
        alarms: List<Alarm>,
        snoozedUntil: (Long) -> Long,
        scheduledFire: (Long) -> Long,
        nowMs: Long,
    ): Pair<Alarm, Long>? =
        alarms.asSequence()
            .map { Triple(it, snoozedUntil(it.id), scheduledFire(it.id)) }
            .filter { (alarm, snoozed, _) -> alarm.enabled || snoozed > nowMs }
            .map { (alarm, snoozed, fire) -> alarm to maxOf(snoozed, fire) }
            .filter { (_, at) -> at > nowMs }
            .minByOrNull { (_, at) -> at }

    /** Snoozes the ringing alarm with the default duration (used by in-app controls). */
    fun snoozeRinging(context: Context, id: Long, minutes: Int) {
        AlarmCoordinator.snooze(context, id, minutes)
    }

    /** Dismisses the ringing alarm (used by in-app controls). */
    fun dismissRinging(context: Context, id: Long) {
        AlarmCoordinator.dismiss(context, id)
    }
}
