package com.piercingxx.xxclock

import android.app.Application
import com.piercingxx.xxclock.alarm.AlarmCoordinator
import com.piercingxx.xxclock.notify.Channels
import com.piercingxx.xxclock.theme.ThemeSyncApplier

class ClockApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Theme: pin night mode from the CHOSEN theme (or the AMOLED Night
        // default) before any activity inflates, and keep every activity
        // painted with that ground. First thing in the process, so nothing can
        // inflate against the system's day/night state on the way in.
        ThemeSyncApplier.init(this)
        Channels.ensureAll(this)
        // Rebuild scheduling after process death / update; safe to call any time.
        AlarmCoordinator.reconcile(this)
    }
}
