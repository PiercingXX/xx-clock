package com.piercingxx.xxclock.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes

/**
 * Notification channels. Created at app start AND defensively before each notify()
 * (channels are immutable once created; sound/vibration stay silent here because
 * RingService plays alarm audio and vibrates itself — the channel only carries
 * heads-up importance and the DND bypass flag).
 */
object Channels {
    const val ID_ALARM = "alarm_firing"
    const val ID_TIMER = "timer_firing"
    const val ID_MISSED = "missed"

    fun ensureAll(context: Context) {
        ensure(context, ID_ALARM)
        ensure(context, ID_TIMER)
        ensure(context, ID_MISSED)
    }

    fun ensure(context: Context, channelId: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(channelId) != null) return
        when (channelId) {
            ID_ALARM -> nm.createNotificationChannel(alarmChannel(channelId, "Alarms"))
            ID_TIMER -> nm.createNotificationChannel(alarmChannel(channelId, "Timers"))
            else -> nm.createNotificationChannel(
                NotificationChannel(ID_MISSED, "Missed", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    /**
     * IMPORTANCE_HIGH + setBypassDnd(true): the channel rings through Do Not Disturb
     * once the user grants Notification Policy Access; combined with CATEGORY_ALARM
     * notifications on USAGE_ALARM audio, alarms survive DND even without that grant.
     */
    private fun alarmChannel(channelId: String, name: String): NotificationChannel =
        NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_HIGH).apply {
            setBypassDnd(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setSound(null, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            enableVibration(false)
        }
}
