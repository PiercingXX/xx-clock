package com.piercingxx.xxclock.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Runtime permission/special-access gate checks + intents to request each.
 * The Setup screen renders these as a checklist; nothing here mutates state.
 */
object PermissionsGate {

    fun notificationsGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    fun exactAlarmsGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            (context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager)
                .canScheduleExactAlarms()

    fun dndPolicyAccessGranted(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }

    fun fullScreenIntentGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 34 ||
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .canUseFullScreenIntent()

    fun batteryExemptionGranted(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun notificationsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))

    /** Exact-alarm settings on 31+; app-details fallback below (no toggle exists there). */
    fun exactAlarmsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= 31) {
            @Suppress("InlinedApi")
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.parse("package:${context.packageName}"))
        } else {
            notificationsIntent(context)
        }

    fun dndIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

    /** Full-screen-intent settings on 34+; notification settings fallback below. */
    fun fullScreenIntentIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= 34) {
            @Suppress("InlinedApi")
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                .setData(Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
        }

    /** Sideloaded alarm clock: battery-optimization exemption is an acceptable,
     *  documented use case (Doze can otherwise delay ring delivery). Play policy
     *  does not apply to sideloaded apps. */
    @Suppress("BatteryLife", "InlinedApi")
    fun batteryIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
}
