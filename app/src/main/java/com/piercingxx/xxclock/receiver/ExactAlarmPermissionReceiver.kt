package com.piercingxx.xxclock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.piercingxx.xxclock.Actions
import com.piercingxx.xxclock.alarm.AlarmCoordinator

/**
 * Manifest receiver for the system's exact-alarm permission broadcast
 * ([Actions.EXACT_ALARM_PERMISSION_CHANGED], API 31+): the OS sends it when the
 * user grants or revokes SCHEDULE_EXACT_ALARM. Both directions rebuild all
 * scheduling — a grant restores exact registrations that a force-stop would
 * otherwise never see again, a revoke re-registers everything onto inexact
 * windows (ExactScheduler degrades per alarm instead of throwing).
 *
 * recoverRinging stays false: like TIME_SET, this is an in-process trigger that
 * must not misclassify a live ringer as missed.
 */
class ExactAlarmPermissionReceiver(
    /** Extracts the inbound action; injectable because the JVM Intent stub returns null. */
    private val extractAction: (Intent?) -> String? = { intent -> intent?.action },
    /** Rebuild hook; injectable so JVM tests can record the reconcile call. */
    private val onPermissionChanged: (Context) -> Unit = { context ->
        AlarmCoordinator.reconcile(context, force = true, recoverRinging = false)
    },
) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (extractAction(intent) != Actions.EXACT_ALARM_PERMISSION_CHANGED) return
        context?.let(onPermissionChanged)
    }
}
