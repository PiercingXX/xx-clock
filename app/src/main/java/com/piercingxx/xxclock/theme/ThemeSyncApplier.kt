package com.piercingxx.xxclock.theme

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
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
 * **The ground is a choice, never an observation.** XX Clock's look is decided
 * by exactly two inputs — the in-app picker (Setup → Theme) and the launcher's
 * `xx.launcher.THEME_CHANGED` broadcast — and by nothing else. It does not read
 * the system dark-mode setting, a sunrise/sunset auto-dark schedule, or the
 * clock it happens to be displaying. A theme picked at noon looks identical at
 * midnight. That is a product rule, not an implementation detail: the owner
 * rejected time-of-day switching outright.
 *
 * The mechanism still uses the app's two resource sets. `values/` carries the
 * light look (Paper/Mist grounds, ink text), `values-night/` the dark one, and
 * a dark preset flips the app into the night set via
 * [AppCompatDelegate.MODE_NIGHT_YES] before painting the *exact* preset ground
 * (Graphite/Forest/Ocean/Burgundy — not just generic ink) over the window, root
 * content, and status/nav bars; Paper and Mist flip to
 * [AppCompatDelegate.MODE_NIGHT_NO], whose
 * resources already ARE Paper and Mist. The night qualifier is therefore an
 * internal switch this class throws from the chosen theme's [SyncedTheme.isDark]
 * — the OS never gets a vote, because the app is never left in
 * [AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM]. See [nightModeFor].
 *
 * Resolving and painting the right theme is not sufficient on its own: the
 * platform can still invert what we painted, *after* we painted it. See
 * [disableForceDark] — every window this app opens revokes that permission.
 *
 * Nothing chosen yet (fresh install, cleared app data) is not "no answer": it
 * resolves to [DEFAULT_THEME], the family's AMOLED Night. Previously this case
 * fell through to the `DayNight` default, which is exactly how the app ended up
 * tracking the system's day/night state — Paper by day, ink by night.
 *
 * Wired from [com.piercingxx.xxclock.ClockApp]: [init] settles night mode before
 * any activity exists and registers lifecycle callbacks that re-apply on every
 * activity create/resume, so an activity recreated by a configuration change
 * comes back with the chosen theme rather than the configuration's opinion.
 * [onThemeChanged] (called by [ThemeSyncReceiver] and by the in-app picker on
 * the main thread) repaints already-visible activities immediately —
 * night-mode changes recreate them, ground-only changes (dark preset to dark
 * preset) repaint in place.
 *
 * The full-screen [AlarmAlertActivity] is excluded from ground *painting*: it
 * keeps its purpose-built always-dark high-contrast look, pinned by its own
 * non-DayNight theme in the manifest (`Theme.XxClock.AlarmAlert`) so neither
 * the system nor a light preset can wash out its buttons at 3 a.m. Constant by
 * design is not the same as ambient — that screen has no time-of-day input
 * either. It is NOT excluded from [disableForceDark]; nothing is. Widget and
 * notification surfaces draw from `values/`-only colors and never vary by
 * configuration.
 */
object ThemeSyncApplier {

    /**
     * The look the app wears when the user has never chosen one: AMOLED Night,
     * the same `DEFAULT_THEME` the sibling apps use. A definite answer matters
     * more than a "neutral" one — the neutral answer was follow-the-system, and
     * follow-the-system is the bug. A fresh install is ink black at noon.
     */
    val DEFAULT_THEME = SyncedTheme(
        background = ThemePreset.AMOLED_NIGHT.background,
        isDark = ThemePreset.AMOLED_NIGHT.isDark,
        presetKey = ThemePreset.AMOLED_NIGHT.key,
    )

    /** Activities currently started, repainted in place on a live theme change. */
    private val visible: MutableSet<Activity> =
        Collections.newSetFromMap(WeakHashMap())

    /** The persisted chosen theme, or null when nothing has ever been chosen. */
    fun storedTheme(context: Context): SyncedTheme? =
        ThemeStore(
            SharedPreferencesThemeKeyValueStore(
                context.getSharedPreferences(ThemeStore.PREFS_NAME, Context.MODE_PRIVATE)
            )
        ).load()

    /**
     * The whole "what is set" rule, as a pure function: the chosen theme, or
     * [DEFAULT_THEME]. Never null, so no caller downstream can have a "leave it
     * to the platform" branch — that branch was the time-of-day bug.
     */
    fun effectiveTheme(stored: SyncedTheme?): SyncedTheme = stored ?: DEFAULT_THEME

    /** [effectiveTheme] over the persisted store. Cheap: one SharedPreferences read. */
    fun activeTheme(context: Context): SyncedTheme = effectiveTheme(storedTheme(context))

    /**
     * The night mode a theme resolves to — a total function of
     * [SyncedTheme.isDark] and of nothing else. Deliberately never returns
     * [AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM] and has no null/unknown
     * branch that could: `FOLLOW_SYSTEM` is the one value that would hand the
     * app's look back to the OS clock.
     */
    fun nightModeFor(theme: SyncedTheme): Int =
        if (theme.isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO

    /**
     * Revokes the platform's permission to force-dark this window's content.
     *
     * Force dark is the renderer re-inverting an already-drawn light window
     * when the *system* is in night mode. It is the last ambient input into
     * this app's look and it acts after everything above has done its job: on
     * device, a correctly stored, correctly resolved and correctly painted
     * Paper ground (#F3EEE2) came out as a dark warm brown with white text —
     * Paper lightness-inverted, not any preset we ship.
     *
     * `android:forceDarkAllowed=false` in the theme did not stop it there, and
     * the reason matters for anyone tempted to "fix" this by also setting
     * `android:isLightTheme=false`: both attributes are inputs to ONE
     * computation, `ViewRootImpl.updateForceDarkMode()`, which reads
     * `isLightTheme && forceDarkAllowed` off some context's theme and then
     * enables the feature window-wide via `ThreadedRenderer.setForceDark()`.
     * That lookup demonstrably did not see our value, so feeding it a second
     * value it also would not see buys nothing.
     *
     * [View.setForceDarkAllowed] is a different mechanism, one layer down and
     * downstream of that decision: it clears the flag on the view's `RenderNode`,
     * and the native force-dark pass skips any node with the flag cleared
     * *together with its whole subtree*. Setting it on the decor view therefore
     * covers everything drawn in the window, whatever `setForceDark()` decided,
     * and consults no theme, context or configuration on the way. That is why
     * it is authoritative and the theme attribute is not — the theme opt-out
     * stays as declaration-level intent, not as the enforcement.
     *
     * The flag lives on a RenderNode, so its scope is one window. Activities,
     * dialogs and popups each own one; every one of them has to ask. API 29+,
     * and minSdk is 29.
     */
    fun disableForceDark(window: Window?) {
        window?.decorView?.isForceDarkAllowed = false
    }

    /**
     * Called once from Application.onCreate: settles night mode before any
     * activity inflates, then keeps every activity grounded via lifecycle
     * callbacks. Application.onCreate runs before any component in the process
     * — including the theme receiver waking a cold process — so there is no
     * window in which a `DayNight` activity could inflate against the system's
     * night configuration.
     */
    fun init(app: Application) {
        applyNightMode(activeTheme(app))
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
                // Post-create: setContentView has run, the decor exists. This
                // also covers recreation after a uiMode configuration change —
                // the rebuilt activity is repainted from the store, not from
                // the configuration that recreated it.
                applyToActivity(activity, activeTheme(activity))
                guardDialogWindows(activity)
            }

            override fun onActivityStarted(activity: Activity) {
                visible += activity
            }

            override fun onActivityResumed(activity: Activity) {
                val theme = activeTheme(activity)
                // A broadcast may have landed while we were backgrounded: a
                // changed mode recreates the activity; a same-mode ground
                // change is covered by the repaint below. Re-asserting the
                // night mode here also means nothing else in the process can
                // leave a stale mode standing.
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
     * Everything one activity window needs, in the order it needs it.
     *
     * The force-dark revocation comes FIRST and applies to every activity,
     * [AlarmAlertActivity] included — that screen opts out of ground painting,
     * not out of being left alone by the renderer. Putting the call after the
     * early return below is the exact regression this ordering exists to
     * prevent, and a test pins it.
     */
    private fun applyToActivity(activity: Activity, theme: SyncedTheme) {
        val window = activity.window ?: return
        disableForceDark(window)
        if (activity is AlarmAlertActivity) return // always-dark alarm screen keeps its look
        applyGround(window, activity, theme)
    }

    /**
     * A dialog is its own window with its own decor view and its own RenderNode,
     * so the activity's revocation does not reach it — the alarm editor (and its
     * TimePicker) would still be force-darkable on a light ground.
     *
     * Registered once per activity, recursively, so it covers child fragment
     * managers (the alarm editor is shown from AlarmsFragment's) and any
     * DialogFragment added later without anyone remembering this rule.
     */
    private fun guardDialogWindows(activity: Activity) {
        if (activity !is FragmentActivity) return
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
                    // onStart is where DialogFragment shows its dialog, so the
                    // window and its decor exist by the time this runs.
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
