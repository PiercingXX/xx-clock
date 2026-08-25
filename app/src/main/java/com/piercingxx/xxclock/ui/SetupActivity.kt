package com.piercingxx.xxclock.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.piercingxx.xxclock.R
import com.piercingxx.xxclock.permissions.PermissionsGate

/**
 * Onboarding checklist of the special access XX Clock needs to ring reliably.
 * Each row explains WHY the grant matters, shows live status as a colored dot
 * (green = granted, amber = missing), and launches the matching system screen.
 * States are re-checked in [onResume] so returning from Settings refreshes.
 */
class SetupActivity : AppCompatActivity() {

    private data class ChecklistRow(
        val granted: (Context) -> Boolean,
        val request: (Context) -> Intent,
        val dotId: Int,
        val buttonId: Int,
    )

    private val rows: List<ChecklistRow> = listOf(
        ChecklistRow(
            granted = PermissionsGate::notificationsGranted,
            request = PermissionsGate::notificationsIntent,
            dotId = R.id.dot_notifications,
            buttonId = R.id.btn_notifications,
        ),
        ChecklistRow(
            granted = PermissionsGate::exactAlarmsGranted,
            request = PermissionsGate::exactAlarmsIntent,
            dotId = R.id.dot_exact,
            buttonId = R.id.btn_exact,
        ),
        ChecklistRow(
            granted = PermissionsGate::dndPolicyAccessGranted,
            request = { PermissionsGate.dndIntent() },
            dotId = R.id.dot_dnd,
            buttonId = R.id.btn_dnd,
        ),
        ChecklistRow(
            granted = PermissionsGate::fullScreenIntentGranted,
            request = PermissionsGate::fullScreenIntentIntent,
            dotId = R.id.dot_full_screen,
            buttonId = R.id.btn_full_screen,
        ),
        ChecklistRow(
            granted = PermissionsGate::batteryExemptionGranted,
            request = PermissionsGate::batteryIntent,
            dotId = R.id.dot_battery,
            buttonId = R.id.btn_battery,
        ),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        for (row in rows) {
            findViewById<View>(row.buttonId).setOnClickListener {
                startActivity(row.request(this))
            }
        }
        findViewById<Button>(R.id.btn_done).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        refreshStates()
    }

    private fun refreshStates() {
        for (row in rows) {
            val granted = row.granted(this)
            findViewById<View>(row.dotId).setBackgroundResource(
                if (granted) R.drawable.widget_dot_granted else R.drawable.widget_dot_missing,
            )
            findViewById<View>(row.buttonId).isEnabled = !granted
        }
    }
}
