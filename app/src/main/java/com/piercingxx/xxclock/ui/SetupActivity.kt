package com.piercingxx.xxclock.ui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.piercingxx.xxclock.R
import com.piercingxx.xxclock.permissions.PermissionsGate
import com.piercingxx.xxclock.theme.CUSTOM_PRESET_KEY
import com.piercingxx.xxclock.theme.SharedPreferencesThemeKeyValueStore
import com.piercingxx.xxclock.theme.ThemePreset
import com.piercingxx.xxclock.theme.ThemeStore
import com.piercingxx.xxclock.theme.ThemeSyncApplier
import com.piercingxx.xxclock.theme.manualPresetKeys
import com.piercingxx.xxclock.theme.resolveManualTheme

/**
 * Onboarding checklist of the special access XX Clock needs to ring reliably.
 * Each row explains WHY the grant matters, shows live status as a colored dot
 * (green = granted, amber = missing), and launches the matching system screen.
 * States are re-checked in [onResume] so returning from Settings refreshes.
 *
 * Also hosts the in-app theme switcher: the eight family presets (the same
 * set the launcher broadcasts — see theme/ThemePreset.kt). A manual pick is
 * resolved to the exact [com.piercingxx.xxclock.theme.SyncedTheme] the
 * matching broadcast would produce and pushed through the same
 * [ThemeStore]-persist + [ThemeSyncApplier]-repaint path, so downstream the
 * two are indistinguishable (last writer — tap or broadcast — wins).
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
        buildThemeRows()
    }

    override fun onResume() {
        super.onResume()
        refreshStates()
        refreshThemeRows()
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

    // ------------------------------------------------------------------ theme

    private fun themeStore(): ThemeStore = ThemeStore(
        SharedPreferencesThemeKeyValueStore(
            getSharedPreferences(ThemeStore.PREFS_NAME, Context.MODE_PRIVATE),
        ),
    )

    /**
     * Inflates one row per family preset key. Built from [manualPresetKeys]
     * (never a hardcoded list) so the switcher can only ever drift from the
     * launcher if the shared ThemePreset contract itself drifts.
     */
    private fun buildThemeRows() {
        val list = findViewById<LinearLayout>(R.id.theme_preset_list)
        for (key in manualPresetKeys()) {
            val row = layoutInflater.inflate(R.layout.item_theme_preset, list, false)
            row.tag = key
            row.findViewById<TextView>(R.id.theme_row_name).text =
                ThemePreset.fromKey(key)?.displayName ?: getString(R.string.theme_custom)
            row.setOnClickListener { pickTheme(key) }
            list.addView(row)
        }
    }

    /** The user tapped [presetKey]: persist + repaint exactly like a broadcast. */
    private fun pickTheme(presetKey: String) {
        val store = themeStore()
        // Custom with no remembered ground resolves to null: nothing to paint,
        // so the tap is a no-op (the row also renders disabled — see refresh).
        val theme = resolveManualTheme(presetKey, store.lastCustomBackground()) ?: return
        store.save(theme)
        ThemeSyncApplier.onThemeChanged(theme)
        refreshThemeRows()
    }

    /** Re-tints swatches and moves the checkmark to the active preset. */
    private fun refreshThemeRows() {
        val list = findViewById<LinearLayout>(R.id.theme_preset_list)
        val store = themeStore()
        // Nothing chosen yet is still a definite answer (AMOLED Night), so the
        // picker always has a checkmark somewhere — it shows what IS set, and
        // there is no "the system decides" row to point at.
        val active = ThemeSyncApplier.effectiveTheme(store.load())
        val customGround = store.lastCustomBackground()
        // A theme without a preset key IS the custom ground.
        val activeKey = active.presetKey ?: CUSTOM_PRESET_KEY
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i)
            val key = row.tag as? String ?: continue
            val ground = ThemePreset.fromKey(key)?.background ?: customGround
            val swatch = row.findViewById<View>(R.id.theme_row_swatch)
            (swatch.background.mutate() as? GradientDrawable)?.setColor(
                (ground ?: 0xFF131316L).toInt(),
            )
            row.findViewById<View>(R.id.theme_row_check).visibility =
                if (key == activeKey) View.VISIBLE else View.INVISIBLE
            // Custom is only pickable once the launcher has broadcast a ground.
            val pickable = ground != null
            row.isEnabled = pickable
            row.alpha = if (pickable) 1f else 0.4f
        }
    }
}
