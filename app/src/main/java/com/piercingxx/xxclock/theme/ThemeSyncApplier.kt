package com.piercingxx.xxclock.theme

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
import com.piercingxx.xxclock.ui.AlarmAlertActivity
import java.util.Collections
import java.util.WeakHashMap

/**
 * Applies the launcher-synced theme ([SyncedTheme]) to the running UI.
 *
 * XX Clock already renders two complete looks: a day look (Paper ground, Mist
 * surfaces, ink text — `values/`) and a night look (`values-night/`). The sync
 * uses that split: a dark preset flips the app into its night look via
 * [AppCompatDelegate.MODE_NIGHT_YES] and then paints the *exact* preset ground
 * (Graphite/Forest/Ocean/Burgundy — not just generic ink) over the window,
 * root content, and status/nav bars; Paper and Mist flip to the day look,
 * whose resources already ARE Paper and Mist.
 *
 * Wired from [com.piercingxx.xxclock.ClockApp]: [init] sets the persisted
 * night mode before any activity exists and registers lifecycle callbacks that
 * re-apply on every activity create/resume. [onThemeChanged] (called by
 * [ThemeSyncReceiver] on the main thread) repaints already-visible activities
 * immediately — night-mode changes recreate them, ground-only changes (dark
 * preset to dark preset) repaint in place.
 *
 * The full-screen [AlarmAlertActivity] keeps its purpose-built always-dark
 * high-contrast look and is excluded. Widget and notification surfaces are
 * out of scope.
 */
object ThemeSyncApplier {

    /** Activities currently started, repainted in place on a live theme change. */
    private val visible: MutableSet<Activity> =
        Collections.newSetFromMap(WeakHashMap())

    /** The persisted launcher-synced theme, or null before any broadcast. */
    fun storedTheme(context: Context): SyncedTheme? =
        ThemeStore(
            SharedPreferencesThemeKeyValueStore(
                context.getSharedPreferences(ThemeStore.PREFS_NAME, Context.MODE_PRIVATE)
            )
        ).load()

    /**
     * Called once from Application.onCreate: applies the persisted night mode
     * before any activity inflates, then keeps every activity grounded via
     * lifecycle callbacks.
     */
    fun init(app: Application) {
        applyNightMode(storedTheme(app))
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
                // Post-create: setContentView has run, the decor exists.
                applyGround(activity, storedTheme(activity))
            }

            override fun onActivityStarted(activity: Activity) {
                visible += activity
            }

            override fun onActivityResumed(activity: Activity) {
                val theme = storedTheme(activity)
                // A broadcast may have landed while we were backgrounded: a
                // changed mode recreates the activity; a same-mode ground
                // change is covered by the repaint below.
                applyNightMode(theme)
                applyGround(activity, theme)
            }

            override fun onActivityStopped(activity: Activity) {
                visible -= activity
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * A fresh broadcast arrived (main thread): flip night mode if needed and
     * repaint whatever is on screen with the new ground.
     */
    fun onThemeChanged(theme: SyncedTheme) {
        applyNightMode(theme)
        for (activity in visible.toList()) {
            applyGround(activity, theme)
        }
    }

    /** MODE_NIGHT_YES for dark presets, MODE_NIGHT_NO for Paper/Mist/light customs. */
    private fun applyNightMode(theme: SyncedTheme?) {
        if (theme == null) return // never synced: keep the DayNight default
        val mode = if (theme.isDark) {
            AppCompatDelegate.MODE_NIGHT_YES
        } else {
            AppCompatDelegate.MODE_NIGHT_NO
        }
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    /**
     * Paints the preset's exact ground over the window background, the root
     * content view, and the status/nav bars, and matches the system-icon
     * contrast to the theme's foreground.
     */
    private fun applyGround(activity: Activity, theme: SyncedTheme?) {
        if (theme == null) return
        if (activity is AlarmAlertActivity) return // always-dark alarm screen keeps its look
        val window = activity.window ?: return
        val ground = theme.background.toInt()

        window.setBackgroundDrawable(ColorDrawable(ground))
        @Suppress("DEPRECATION") // still effective below API 35; harmless under edge-to-edge
        window.statusBarColor = ground
        @Suppress("DEPRECATION")
        window.navigationBarColor = ground
        activity.findViewById<View>(android.R.id.content)?.setBackgroundColor(ground)

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !theme.isDark
            isAppearanceLightNavigationBars = !theme.isDark
        }
    }
}
