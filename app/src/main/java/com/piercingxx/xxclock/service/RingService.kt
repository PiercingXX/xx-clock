package com.piercingxx.xxclock.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.piercingxx.xxclock.Actions
import com.piercingxx.xxclock.Prefs
import com.piercingxx.xxclock.R
import com.piercingxx.xxclock.alarm.AlarmCoordinator
import com.piercingxx.xxclock.audio.KlaxonPlayer
import com.piercingxx.xxclock.data.ClockStore
import com.piercingxx.xxclock.model.TimerItem
import com.piercingxx.xxclock.notify.Channels
import com.piercingxx.xxclock.scheduler.ExactScheduler
import com.piercingxx.xxclock.ui.AlarmAlertActivity
import com.piercingxx.xxclock.time.TimerMath
import com.piercingxx.xxclock.util.Fmt

/**
 * Foreground service (specialUse) that rings an alarm or timer:
 *  - posts the CATEGORY_ALARM notification carrying the full-screen intent,
 *  - plays alarm-stream audio + vibration via [KlaxonPlayer],
 *  - holds a partial wakelock for the ring duration,
 *  - auto-silences after Prefs.AUTO_SILENCE_MINUTES.
 */
class RingService : android.app.Service() {

    private var player: KlaxonPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val id = intent?.getLongExtra(Actions.EXTRA_ID, -1L) ?: -1L
        if (id <= 0 || (action != Actions.FIRE_ALARM && action != Actions.FIRE_TIMER)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val isAlarm = action == Actions.FIRE_ALARM
        return if (begin(id, isAlarm)) START_REDELIVER_INTENT else START_NOT_STICKY
    }

    /**
     * Re-entrant (newest-wins): stops the previous player and its pending
     * auto-silence callback first, so a redelivered start intent after a
     * mid-ring process kill takes over cleanly instead of stacking rings.
     *
     * @return true when the ring session was armed (foreground entered, ringer
     *   started); false means the missed-notification path already ran.
     */
    private fun begin(id: Long, isAlarm: Boolean): Boolean {
        handler.removeCallbacksAndMessages(null)
        player?.stop()
        player = null
        return try {
            Channels.ensure(this, if (isAlarm) Channels.ID_ALARM else Channels.ID_TIMER)
            val notification = buildNotification(id, isAlarm)
            if (Build.VERSION.SDK_INT >= 34) {
                // specialUse type exists only on 34+; older platforms get the plain call.
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIFICATION_ID, notification)
            }

            acquireWakeLock()

            val store = ClockStore.get(this)
            player = KlaxonPlayer(this).also {
                it.start(
                    vibrate = vibrateFor(store, id, isAlarm),
                    gradualVolume = Prefs.GRADUAL_VOLUME_DEFAULT,
                    // Per-alarm ringtone; null (timers, or no pick) = system default.
                    soundUri = if (isAlarm) store.getAlarm(id)?.soundUri else null,
                )
            }

            handler.postDelayed(
                { AlarmCoordinator.autoSilence(this, id, isAlarm = isAlarm) },
                Prefs.AUTO_SILENCE_MINUTES * 60_000L,
            )
            true
        } catch (t: Exception) {
            // FGS-start rejection (ForegroundServiceStartNotAllowedException etc.) or any
            // setup failure: degrade to a missed/finished notification instead of crashing.
            AlarmCoordinator.ringStartFailed(this, id, isAlarm)
            stopSelf()
            false
        }
    }

    private fun vibrateFor(store: ClockStore, id: Long, isAlarm: Boolean): Boolean =
        if (isAlarm) store.getAlarm(id)?.vibrate ?: true else true

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.stop()
        player = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xxclock:ring").apply {
            setReferenceCounted(false)
            acquire(Prefs.RING_WAKELOCK_MS)
        }
    }

    private fun buildNotification(id: Long, isAlarm: Boolean): Notification {
        val store = ClockStore.get(this)
        val channelId = if (isAlarm) Channels.ID_ALARM else Channels.ID_TIMER

        val title: String
        val text: String
        if (isAlarm) {
            val alarm = store.getAlarm(id)
            title = alarm?.label?.ifBlank { getString(R.string.notif_alarm_default_title) } ?: getString(R.string.notif_alarm_default_title)
            text = Fmt.time(this, System.currentTimeMillis())
        } else {
            val timer = store.getTimer(id)
            title = timer?.label?.ifBlank { getString(R.string.notif_timer_default_title) } ?: getString(R.string.notif_timer_default_title)
            text = TimerMath.display(timer?.durationMs ?: 0L)
        }

        val alertIntent = PendingIntent.getActivity(
            this,
            ExactScheduler.requestCode(ExactScheduler.REQ_KIND_SHOW, id),
            Intent(this, AlarmAlertActivity::class.java)
                .setAction(Actions.FIRE_ALARM.takeIf { isAlarm } ?: Actions.FIRE_TIMER)
                .putExtra(Actions.EXTRA_ID, id)
                .putExtra(Actions.EXTRA_IS_ALARM, isAlarm),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(alertIntent)
            .setFullScreenIntent(alertIntent, true)

        if (isAlarm) {
            builder.addAction(
                0,
                getString(R.string.action_snooze),
                receiverActionPendingIntent(Actions.SNOOZE_ALARM, id, Prefs.SNOOZE_MINUTES_DEFAULT),
            )
            builder.addAction(
                0,
                getString(R.string.action_dismiss),
                receiverActionPendingIntent(Actions.DISMISS_ALARM, id, 0),
            )
        } else {
            builder.addAction(
                0,
                getString(R.string.action_add_minute),
                receiverActionPendingIntent(Actions.ADD_MINUTE_TIMER, id, 0),
            )
            builder.addAction(
                0,
                getString(R.string.action_stop),
                receiverActionPendingIntent(Actions.STOP_TIMER, id, 0),
            )
        }
        return builder.build()
    }

    private fun receiverActionPendingIntent(action: String, id: Long, snoozeMinutes: Int): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            ExactScheduler.requestCode(actionKind(action), id),
            Intent(this, com.piercingxx.xxclock.receiver.AlarmEventReceiver::class.java)
                .setAction(action)
                .putExtra(Actions.EXTRA_ID, id)
                .putExtra(Actions.EXTRA_SNOOZE_MINUTES, snoozeMinutes),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun actionKind(action: String): Int =
        if (action == Actions.SNOOZE_ALARM || action == Actions.DISMISS_ALARM) {
            ExactScheduler.REQ_KIND_ALARM
        } else {
            ExactScheduler.REQ_KIND_TIMER
        }

    companion object {
        private const val NOTIFICATION_ID = 42

        /** @return true when the service start was accepted. */
        fun start(context: Context, action: String, id: Long): Boolean = try {
            val intent = Intent(context, RingService::class.java).setAction(action).putExtra(Actions.EXTRA_ID, id)
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (_: Exception) {
            // Background-start restrictions (e.g. FGS-from-boot on Android 15+).
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RingService::class.java))
        }
    }
}
