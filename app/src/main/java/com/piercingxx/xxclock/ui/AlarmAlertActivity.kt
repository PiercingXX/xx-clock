package com.piercingxx.xxclock.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.TextClock
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.piercingxx.xxclock.Actions
import com.piercingxx.xxclock.Prefs
import com.piercingxx.xxclock.R
import com.piercingxx.xxclock.alarm.AlarmCoordinator
import com.piercingxx.xxclock.data.ClockStore
import com.piercingxx.xxclock.repo.AlarmRepository

/**
 * Full-screen alarm/timer alert launched via notification full-screen intent.
 * Shows over the lock screen (showWhenLocked/turnScreenOn in manifest), plays on
 * the alarm stream (volume rocker adjusts alarm volume while visible).
 */
class AlarmAlertActivity : AppCompatActivity() {

    private var id: Long = -1L
    private var isAlarm: Boolean = true
    private val handler = Handler(Looper.getMainLooper())

    private val ringingPoller = object : Runnable {
        override fun run() {
            if (id > 0 && !AlarmRepository.isRinging(this@AlarmAlertActivity, id)) {
                finish()
                return
            }
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setVolumeControlStream(android.media.AudioManager.STREAM_ALARM)
        setContentView(R.layout.activity_alarm_alert)

        id = intent.getLongExtra(Actions.EXTRA_ID, -1L)
        isAlarm = intent.getBooleanExtra(Actions.EXTRA_IS_ALARM, true)

        val store = ClockStore.get(this)
        val labelView = findViewById<TextView>(R.id.alert_label)
        val snoozeButton = findViewById<Button>(R.id.alert_snooze)
        val stopButton = findViewById<Button>(R.id.alert_stop)

        if (isAlarm) {
            labelView.text = store.getAlarm(id)?.label?.ifBlank { getString(R.string.notif_alarm_default_title) }
            snoozeButton.text = getString(R.string.action_snooze_minutes, Prefs.SNOOZE_MINUTES_DEFAULT)
            snoozeButton.setOnClickListener {
                AlarmCoordinator.snooze(this, id, Prefs.SNOOZE_MINUTES_DEFAULT)
                finish()
            }
            stopButton.setOnClickListener {
                AlarmCoordinator.dismiss(this, id)
                finish()
            }
        } else {
            labelView.text = store.getTimer(id)?.label?.ifBlank { getString(R.string.notif_timer_default_title) }
            snoozeButton.setText(R.string.action_add_minute)
            snoozeButton.setOnClickListener {
                AlarmCoordinator.addMinuteToRingingTimer(this, id)
                finish()
            }
            stopButton.setOnClickListener {
                AlarmCoordinator.stopTimer(this, id)
                finish()
            }
        }

        // If a fresh instance races a user action in the notification, exit gracefully.
        if (id <= 0 || !store.isRinging(id)) finish()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ringingPoller)
    }

    override fun onPause() {
        handler.removeCallbacks(ringingPoller)
        super.onPause()
    }
}
