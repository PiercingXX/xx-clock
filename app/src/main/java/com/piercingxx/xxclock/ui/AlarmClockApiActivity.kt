package com.piercingxx.xxclock.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.AlarmClock
import com.piercingxx.xxclock.Actions
import com.piercingxx.xxclock.Prefs
import com.piercingxx.xxclock.alarm.AlarmClockApi
import com.piercingxx.xxclock.alarm.AlarmCoordinator
import com.piercingxx.xxclock.data.ClockStore
import com.piercingxx.xxclock.model.Alarm
import com.piercingxx.xxclock.model.TimerItem
import com.piercingxx.xxclock.repo.AlarmRepository
import com.piercingxx.xxclock.repo.TimerRepository
import com.piercingxx.xxclock.scheduler.ExactScheduler
import com.piercingxx.xxclock.time.NextOccurrence

/**
 * Invisible handler for the public [AlarmClock] intents. SHOW_* land on
 * [MainActivity] so the user sees the tab; mutating actions land here so
 * SKIP_UI can finish without flashing a window.
 */
class AlarmClockApiActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
        finish()
    }

    private fun handle(intent: Intent?) {
        when (intent?.action) {
            AlarmClock.ACTION_SET_ALARM -> setAlarm(intent)
            AlarmClock.ACTION_SET_TIMER -> setTimer(intent)
            AlarmClock.ACTION_DISMISS_ALARM -> dismissAlarms(intent)
            AlarmClock.ACTION_SNOOZE_ALARM -> snooze(intent)
            AlarmClock.ACTION_DISMISS_TIMER -> dismissTimers(intent)
            else -> Unit
        }
    }

    private fun setAlarm(intent: Intent) {
        val request = AlarmClockApi.setAlarmRequest(
            hour = extraInt(intent, AlarmClock.EXTRA_HOUR),
            minute = if (intent.hasExtra(AlarmClock.EXTRA_MINUTES)) {
                intent.getIntExtra(AlarmClock.EXTRA_MINUTES, 0)
            } else {
                0
            },
            calendarDays = intent.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS).orEmpty(),
            label = intent.getStringExtra(AlarmClock.EXTRA_MESSAGE).orEmpty(),
            vibrate = if (intent.hasExtra(AlarmClock.EXTRA_VIBRATE)) {
                intent.getBooleanExtra(AlarmClock.EXTRA_VIBRATE, true)
            } else {
                true
            },
            ringtone = intent.getStringExtra(AlarmClock.EXTRA_RINGTONE),
            skipUi = intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false),
        )
        if (request == null) {
            show(MainActivity.TAB_ALARMS)
            return
        }
        val existing = AlarmClockApi.identicalAlarm(AlarmRepository.getAll(this), request)
        if (existing != null) {
            if (!existing.enabled) AlarmRepository.toggle(this, existing.id, enabled = true)
        } else {
            AlarmRepository.save(
                this,
                Alarm.newAlarm(request.hour, request.minute).copy(
                    daysMask = request.daysMask,
                    label = request.label,
                    vibrate = request.vibrate,
                    soundUri = request.soundUri,
                    enabled = true,
                ),
            )
        }
        if (!request.skipUi) show(MainActivity.TAB_ALARMS)
    }

    private fun setTimer(intent: Intent) {
        val request = AlarmClockApi.setTimerRequest(
            lengthSeconds = extraInt(intent, AlarmClock.EXTRA_LENGTH),
            label = intent.getStringExtra(AlarmClock.EXTRA_MESSAGE).orEmpty(),
            skipUi = intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false),
        )
        if (request == null) {
            show(MainActivity.TAB_TIMERS)
            return
        }
        TimerRepository.startOrReuse(this, request.durationMs, request.label)
        if (!request.skipUi) show(MainActivity.TAB_TIMERS)
    }

    private fun dismissAlarms(intent: Intent) {
        val store = ClockStore.get(this)
        val ringingId = store.ringingId()?.takeIf { store.getAlarm(it) != null }
        val nextId = AlarmRepository.nextArmed(this)?.first?.id
        val hour = AlarmClockApi.searchHour(
            extraInt(intent, AlarmClock.EXTRA_HOUR),
            if (intent.hasExtra(AlarmClock.EXTRA_IS_PM)) {
                intent.getBooleanExtra(AlarmClock.EXTRA_IS_PM, false)
            } else {
                null
            },
        )
        val matches = AlarmClockApi.alarmsToDismiss(
            alarms = AlarmRepository.getAll(this),
            ringingAlarmId = ringingId,
            nextArmedId = nextId,
            searchMode = intent.getStringExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE),
            hour = hour,
            minute = extraInt(intent, AlarmClock.EXTRA_MINUTES),
            label = intent.getStringExtra(AlarmClock.EXTRA_MESSAGE),
        )
        if (matches.isEmpty()) {
            show(MainActivity.TAB_ALARMS)
            return
        }
        val now = System.currentTimeMillis()
        for (alarm in matches) {
            if (store.isRinging(alarm.id)) {
                AlarmCoordinator.dismiss(this, alarm.id)
                continue
            }
            val skipUntil = AlarmClockApi.skipUpcomingUntilMs(alarm, now)
            if (skipUntil == null) {
                AlarmRepository.toggle(this, alarm.id, enabled = false)
            } else {
                store.setSnoozedUntil(alarm.id, skipUntil)
                ExactScheduler.scheduleAlarmAt(
                    this,
                    alarm,
                    NextOccurrence.alarmMillis(alarm.hour, alarm.minute, alarm.daysMask, skipUntil),
                )
            }
        }
    }

    private fun snooze(intent: Intent) {
        val store = ClockStore.get(this)
        val id = store.ringingId() ?: return
        if (store.getAlarm(id) == null) return
        val minutes = AlarmClockApi.snoozeMinutes(
            extraInt(intent, AlarmClock.EXTRA_ALARM_SNOOZE_DURATION),
            Prefs.SNOOZE_MINUTES_DEFAULT,
        )
        AlarmCoordinator.snooze(this, id, minutes)
    }

    private fun dismissTimers(intent: Intent) {
        val store = ClockStore.get(this)
        val ringingId = store.ringingId()?.takeIf { store.getTimer(it) != null }
        val matches = AlarmClockApi.timersToDismiss(
            timers = TimerRepository.getAll(this),
            ringingTimerId = ringingId,
            label = intent.getStringExtra(AlarmClock.EXTRA_MESSAGE),
        )
        for (timer in matches) {
            if (store.isRinging(timer.id) || timer.state == TimerItem.STATE_FINISHED) {
                TimerRepository.stopRinging(this, timer.id)
            } else {
                TimerRepository.reset(this, timer.id)
            }
        }
    }

    private fun show(tab: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                .putExtra(Actions.EXTRA_TAB, tab),
        )
    }

    private fun extraInt(intent: Intent, key: String): Int? =
        if (intent.hasExtra(key)) intent.getIntExtra(key, 0) else null
}
