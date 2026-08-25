package com.piercingxx.xxclock.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
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

        val requested = intent?.getStringExtra(Actions.EXTRA_TAB)?.let(::normalizeTab)
        val startTab = requested ?: prefs.getString(KEY_TAB, TAB_CLOCK) ?: TAB_CLOCK
        nav.selectedItemId = itemIdFor(startTab)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Widget taps and the system next-alarm chip reuse this instance
        // (singleTop/clearTop): honor a newly delivered tab request.
        if (!isFinishing && ::nav.isInitialized) {
            intent.getStringExtra(Actions.EXTRA_TAB)?.let(::normalizeTab)?.let {
                nav.selectedItemId = itemIdFor(it)
            }
        }
    }

    private fun fragmentFor(itemId: Int): Fragment = when (itemId) {
        R.id.nav_alarms -> AlarmsFragment()
        R.id.nav_timers -> TimersFragment()
        else -> ClockFragment()
    }

    private fun normalizeTab(value: String): String = when (value.lowercase()) {
        TAB_ALARMS -> TAB_ALARMS
        TAB_TIMERS -> TAB_TIMERS
        else -> TAB_CLOCK
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
        private const val TAB_CLOCK = "clock"
        private const val TAB_ALARMS = "alarms"
        private const val TAB_TIMERS = "timers"
    }
}
