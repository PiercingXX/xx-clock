package com.piercingxx.xxclock

import android.app.Application
import com.piercingxx.xxclock.alarm.AlarmCoordinator
import com.piercingxx.xxclock.notify.Channels
import com.piercingxx.xxclock.theme.ThemeSyncApplier

class ClockApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Launcher-synced theme: set night mode before any activity inflates
        // and keep every activity painted with the synced ground.
        ThemeSyncApplier.init(this)
        Channels.ensureAll(this)
        // Rebuild scheduling after process death / update; safe to call any time.
        AlarmCoordinator.reconcile(this)
    }
}
