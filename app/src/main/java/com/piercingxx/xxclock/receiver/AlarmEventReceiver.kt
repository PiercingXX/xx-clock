package com.piercingxx.xxclock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.piercingxx.xxclock.Actions
import com.piercingxx.xxclock.alarm.AlarmCoordinator
import kotlin.concurrent.thread

private const val LOG_TAG = "xxclock"

/**
 * Single manifest receiver: fire/snooze/dismiss intents from AlarmManager and the
 * ringing notification, plus system broadcasts (locked-boot / unlock / boot /
 * time-set / timezone / package-replaced) that trigger reconciliation. Uses
 * goAsync + worker thread so AlarmManager grants the app its temporary
 * allowlist while we start the FGS.
 */
class AlarmEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        val appContext = context.applicationContext
        thread(name = "alarm-event") {
            try {
                // Device-protected storage scope for everything downstream
                // (ClockStore, ExactScheduler bookkeeping): alarm state must be
                // readable and writable before first unlock, when LOCKED_BOOT_COMPLETED
                // arrives on a freshly rebooted device.
                val storageContext = appContext.createDeviceProtectedStorageContext()
                AlarmCoordinator.handleAction(
                    storageContext,
                    intent.action,
                    intent.getLongExtra(Actions.EXTRA_ID, -1L),
                    intent.getIntExtra(Actions.EXTRA_SNOOZE_MINUTES, 0),
                )
            } catch (t: Throwable) {
                // Boot-critical: nothing re-delivers a failed worker until first
                // unlock, so an unexpected throwable must not escape past finish().
                Log.w(LOG_TAG, "alarm event ${intent.action} failed", t)
            } finally {
                result.finish()
            }
        }
    }
}
