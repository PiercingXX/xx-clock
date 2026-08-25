package com.piercingxx.xxclock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.piercingxx.xxclock.Actions
import com.piercingxx.xxclock.alarm.AlarmCoordinator
import kotlin.concurrent.thread

/**
 * Single manifest receiver: fire/snooze/dismiss intents from AlarmManager and the
 * ringing notification, plus system broadcasts (boot / time-set / timezone /
 * package-replaced) that trigger reconciliation. Uses goAsync + worker thread so
 * AlarmManager grants the app its temporary allowlist while we start the FGS.
 */
class AlarmEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        val appContext = context.applicationContext
        thread(name = "alarm-event") {
            try {
                AlarmCoordinator.handleAction(
                    appContext,
                    intent.action,
                    intent.getLongExtra(Actions.EXTRA_ID, -1L),
                    intent.getIntExtra(Actions.EXTRA_SNOOZE_MINUTES, 0),
                )
            } finally {
                result.finish()
            }
        }
    }
}
