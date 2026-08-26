package com.piercingxx.xxclock.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.widget.ImageButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.piercingxx.xxclock.Actions
import com.piercingxx.xxclock.R
import com.piercingxx.xxclock.permissions.PermissionsGate

class MainActivity : AppCompatActivity() {

    private lateinit var nav: BottomNavigationView

    // Result is intentionally unobserved: the Setup checklist re-checks live state.
    private val notificationsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        nav = findViewById(R.id.bottom_nav)
        val prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE)

        findViewById<ImageButton>(R.id.btn_setup).setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            !PermissionsGate.notificationsGranted(this) &&
            !prefs.getBoolean(KEY_NOTIF_ASKED, false)
        ) {
            prefs.edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
            notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        nav.setOnItemSelectedListener { item ->
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragmentFor(item.itemId))
                .commit()
            prefs.edit().putString(KEY_TAB, tabKeyFor(item.itemId)).apply()
            true
        }

        val requested = tabFrom(intent)
        val startTab = requested ?: prefs.getString(KEY_TAB, TAB_CLOCK) ?: TAB_CLOCK
        nav.selectedItemId = itemIdFor(startTab)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Widget taps, SHOW_ALARMS, and the system next-alarm chip reuse this
        // instance (singleTop): honor a newly delivered tab request.
        if (!isFinishing && ::nav.isInitialized) {
            tabFrom(intent)?.let { nav.selectedItemId = itemIdFor(it) }
        }
    }

    private fun fragmentFor(itemId: Int): Fragment = when (itemId) {
        R.id.nav_alarms -> AlarmsFragment()
        R.id.nav_timers -> TimersFragment()
        else -> ClockFragment()
    }

    private fun tabKeyFor(itemId: Int): String = when (itemId) {
        R.id.nav_alarms -> TAB_ALARMS
        R.id.nav_timers -> TAB_TIMERS
        else -> TAB_CLOCK
    }

    private fun itemIdFor(tabKey: String): Int = when (tabKey) {
        TAB_ALARMS -> R.id.nav_alarms
        TAB_TIMERS -> R.id.nav_timers
        else -> R.id.nav_clock
    }

    companion object {
        private const val UI_PREFS = "xx_clock_ui"
        private const val KEY_TAB = "tab"
        private const val KEY_NOTIF_ASKED = "notif_asked"
        internal const val TAB_CLOCK = "clock"
        internal const val TAB_ALARMS = "alarms"
        internal const val TAB_TIMERS = "timers"

        /**
         * Tab implied by the incoming intent. [Actions.EXTRA_TAB] wins; otherwise
         * [AlarmClock.ACTION_SHOW_ALARMS] opens Alarms (xx-launcher clock widget
         * and any system "show alarms" caller). APP_CLOCK is the clock face.
         */
        internal fun tabFrom(intent: Intent?): String? {
            if (intent == null) return null
            intent.getStringExtra(Actions.EXTRA_TAB)?.let { return normalizeTab(it) }
            when (intent.action) {
                AlarmClock.ACTION_SHOW_ALARMS,
                AlarmClock.ACTION_SET_ALARM,
                AlarmClock.ACTION_DISMISS_ALARM,
                AlarmClock.ACTION_SNOOZE_ALARM,
                -> return TAB_ALARMS
                AlarmClock.ACTION_SHOW_TIMERS,
                AlarmClock.ACTION_SET_TIMER,
                AlarmClock.ACTION_DISMISS_TIMER,
                -> return TAB_TIMERS
            }
            if (intent.action == Intent.ACTION_MAIN &&
                intent.hasCategory("android.intent.category.APP_CLOCK")
            ) {
                return TAB_CLOCK
            }
            return null
        }

        internal fun normalizeTab(value: String): String = when (value.lowercase()) {
            TAB_ALARMS -> TAB_ALARMS
            TAB_TIMERS -> TAB_TIMERS
            else -> TAB_CLOCK
        }
    }
}
