package com.piercingxx.xxclock

import android.app.Application
import android.os.UserManager
import com.piercingxx.xxclock.alarm.AlarmCoordinator
import com.piercingxx.xxclock.data.ClockStore
import com.piercingxx.xxclock.notify.Channels
import com.piercingxx.xxclock.theme.ThemeSyncApplier

class ClockApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // CE SharedPreferences handle: receiver paths run on a device-protected
        // context, and createCredentialProtectedStorageContext() is @hide.
        ClockStore.attachCredentialApp(this)
        // Theme: pin night mode from the CHOSEN theme (or the AMOLED Night
        // default) before any activity inflates, and keep every activity
        // painted with that ground. First thing in the process, so nothing can
        // inflate against the system's day/night state on the way in.
        ThemeSyncApplier.init(this)
        Channels.ensureAll(this)
        // Rebuild scheduling after process death / update; safe to call any time.
        // Not before first unlock (direct boot): this process then exists only
        // for the alarm components. LOCKED_BOOT_COMPLETED owns pre-unlock
        // re-arm; ACTION_USER_UNLOCKED owns CE→DE migration if we started locked
        // (onCreate will not run again at unlock).
        if (getSystemService(UserManager::class.java)?.isUserUnlocked == true) {
            AlarmCoordinator.reconcile(this)
        }
    }
}
