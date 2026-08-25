package com.piercingxx.xxclock

import android.app.Application
import com.piercingxx.xxclock.alarm.AlarmCoordinator
import com.piercingxx.xxclock.notify.Channels

class ClockApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Channels.ensureAll(this)
        // Rebuild scheduling after process death / update; safe to call any time.
        AlarmCoordinator.reconcile(this)
    }
}
