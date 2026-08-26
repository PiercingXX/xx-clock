package com.piercingxx.xxclock.theme

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.UserManager
import android.view.View
import android.view.Window
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.piercingxx.xxclock.ui.AlarmAlertActivity
import java.util.Collections
import java.util.WeakHashMap

/**
 * Applies the chosen theme ([SyncedTheme]) to the running UI.
 *
 * The ground is a choice, never an observation: exactly two inputs may move it
 * — the in-app picker and the launcher's theme broadcast — and the OS never
 * gets a vote. [nightModeFor] maps [SyncedTheme.isDark] straight to
 * MODE_NIGHT_YES/NO and must NEVER return
 * [AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM], the one value that would hand
 * the look back to the system clock; nothing chosen yet resolves to
 * [DEFAULT_THEME] (AMOLED Night), never to "leave it to the platform". Full
 * rules: CONTRACT.md, "Theme authority".
 *
 * Wired from [com.piercingxx.xxclock.ClockApp]: [init] settles night mode
 * before any activity inflates and re-applies on every activity create/resume;
 * [onThemeChanged] repaints already-visible activities immediately. The
 * platform can still invert what we painted after the fact, so every window
 * revokes force dark — see [disableForceDark]. [AlarmAlertActivity] keeps its
 * purpose-built always-dark look: excluded from ground painting, NOT from
 * [disableForceDark].
 */
object ThemeSyncApplier {

    /**
     * The look worn when the user has never chosen one: AMOLED Night, the
     * family default. A definite answer beats a neutral one — the neutral
     * answer (follow the system) was the bug.
     */
    val DEFAULT_THEME = SyncedTheme(
        background = ThemePreset.AMOLED_NIGHT.background,
        isDark = ThemePreset.AMOLED_NIGHT.isDark,
        presetKey = ThemePreset.AMOLED_NIGHT.key,
    )

    /** Activities currently started, repainted in place on a live theme change. */
    private val visible: MutableSet<Activity> =
        Collections.newSetFromMap(WeakHashMap())

    /**
     * The persisted chosen theme, or null when nothing has ever been chosen.
     *
     * Credential-encrypted (`xx_clock_theme`). Must not be opened before first
     * unlock: [SharedPreferences] on CE storage throws and would crash
     * [init] from Application.onCreate during LOCKED_BOOT_COMPLETED, taking
     * the alarm re-arm path with it. Locked → null → [DEFAULT_THEME].
     */
    fun storedTheme(context: Context): SyncedTheme? {
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked != true) {
            return null
        }
        return runCatching {
            ThemeStore(
                SharedPreferencesThemeKeyValueStore(
                    context.getSharedPreferences(ThemeStore.PREFS_NAME, Context.MODE_PRIVATE),
                ),
            ).load()
        }.getOrNull()
    }

    /**
     * The whole "what is set" rule, as a pure function: the chosen theme, or
     * [DEFAULT_THEME]. Never null, so no caller has a "leave it to the
     * platform" branch.
     */
    fun effectiveTheme(stored: SyncedTheme?): SyncedTheme = stored ?: DEFAULT_THEME

    /** [effectiveTheme] over the persisted store. Cheap: one SharedPreferences read. */
    fun activeTheme(context: Context): SyncedTheme = effectiveTheme(storedTheme(context))

    /**
     * The night mode a theme resolves to — a total function of
     * [SyncedTheme.isDark] and of nothing else. Deliberately never returns
     * [AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM] and has no null/unknown
     * branch that could: that value would hand the app's look back to the OS
     * clock.
     */
    fun nightModeFor(theme: SyncedTheme): Int =
        if (theme.isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO

    /**
     * Revokes the platform's permission to force-dark this window's content —
     * the last ambient input into the look: the renderer re-inverting an
     * already-drawn light window while the SYSTEM is in night mode, after
     * everything above has done its job.
     *
     * Done on the decor view rather than via the theme attribute: the flag
     * lives on a RenderNode and makes the native force-dark pass skip that
     * node plus its whole subtree, consulting no theme or configuration on the
     * way — on device the attribute alone demonstrably did not stop the
     * inversion (details: CONTRACT.md, "Theme authority"; the attribute stays
     * as declaration-level intent). The RenderNode scope is one window, so
     * activities, dialogs and popups must EACH ask. API 29+, and minSdk is 29.
     */
    fun disableForceDark(window: Window?) {
        window?.decorView?.isForceDarkAllowed = false
    }

    /**
     * Called once from Application.onCreate: settles night mode before any
     * activity inflates, then keeps every activity grounded via lifecycle
     * callbacks. Application.onCreate runs before any component in the
     * process — including the theme receiver waking a cold process.
     */
    fun init(app: Application) {
        applyNightMode(activeTheme(app))
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
                // Post-create: setContentView has run, the decor exists. Also
                // covers recreation after a uiMode change — rebuilt from the
                // store, not from the configuration that recreated it.
                applyToActivity(activity, activeTheme(activity))
                guardDialogWindows(activity)
            }

            override fun onActivityStarted(activity: Activity) {
                visible += activity
            }

            override fun onActivityResumed(activity: Activity) {
                val theme = activeTheme(activity)
                // Covers a broadcast that landed while backgrounded, and
                // re-asserts the mode so nothing leaves a stale one standing.
                applyNightMode(theme)
                applyToActivity(activity, theme)
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
     * A fresh choice arrived (main thread) — launcher broadcast or in-app pick:
     * flip night mode if needed and repaint whatever is on screen.
     */
    fun onThemeChanged(theme: SyncedTheme) {
        applyNightMode(theme)
        for (activity in visible.toList()) {
            applyToActivity(activity, theme)
        }
    }

    /** Pins the process to the chosen theme's night mode. See [nightModeFor]. */
    private fun applyNightMode(theme: SyncedTheme) {
        val mode = nightModeFor(theme)
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    /**
     * Everything one activity window needs, in the order it needs it: the
     * force-dark revocation comes FIRST and applies to every activity,
     * [AlarmAlertActivity] included. Putting it after the early return below
     * is the exact regression this ordering exists to prevent (test-pinned).
     */
    private fun applyToActivity(activity: Activity, theme: SyncedTheme) {
        val window = activity.window ?: return
        disableForceDark(window)
        if (activity is AlarmAlertActivity) return // always-dark alarm screen keeps its look
        applyGround(window, activity, theme)
    }

    /**
     * A dialog owns its own window, decor view and RenderNode, so the
     * activity's revocation does not reach it (the alarm editor's TimePicker
     * would stay force-darkable on a light ground). Registered once per
     * activity, recursively, so child fragment managers and DialogFragments
     * added later are covered without anyone remembering this rule.
     */
    private fun guardDialogWindows(activity: Activity) {
        if (activity !is FragmentActivity) return
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
                    // onStart is where DialogFragment shows its dialog, so the
                    // window and decor exist by then.
                    if (f is DialogFragment) disableForceDark(f.dialog?.window)
                }
            },
            true,
        )
    }

    /**
     * Paints the theme's exact ground over the window background, the root
     * content view, and the status/nav bars, and matches the system-icon
     * contrast to the theme's foreground.
     */
    private fun applyGround(window: Window, activity: Activity, theme: SyncedTheme) {
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
