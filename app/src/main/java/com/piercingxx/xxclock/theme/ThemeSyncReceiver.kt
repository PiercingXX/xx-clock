package com.piercingxx.xxclock.theme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * `BroadcastReceiver` for the xx-launcher's family-wide theme broadcast
 * (`xx.launcher.THEME_CHANGED`, explicitly targeted at this package).
 *
 * The launcher carries the active preset's display name (and, always, the
 * resolved background ARGB int — the only source of truth for "Custom"). The
 * receiver resolves the payload with [resolveSyncedTheme], persists the result
 * to the `xx_clock_theme` SharedPreferences via [ThemeStore] (so it survives
 * process death and is read by [ThemeSyncApplier] on every activity
 * create/resume), then nudges [ThemeSyncApplier] so a foregrounded app
 * repaints immediately.
 *
 * Every collaborator is an injectable seam (mirroring TxxT's
 * ThemeSyncReceiver) so a JVM unit test can drive [onReceive] — even with a
 * null [Context]/[Intent] — without mocking the Android platform.
 */
class ThemeSyncReceiver(
    /** Action string to match against the inbound [Intent]. */
    private val action: String = ACTION_THEME_CHANGED,
    /** Extracts the inbound action; injectable because the JVM Intent stub returns null. */
    private val extractAction: (Intent?) -> String? = { intent -> intent?.action },
    /** Extracts the preset display name from the broadcast. */
    private val extractThemeName: (Intent?) -> String? = { intent ->
        intent?.getStringExtra(EXTRA_THEME_NAME)
    },
    /** Extracts the resolved background ARGB (as an unsigned 0xAARRGGBB long), if present. */
    private val extractBackground: (Intent?) -> Long? = { intent ->
        if (intent != null && intent.hasExtra(EXTRA_BACKGROUND)) {
            intent.getIntExtra(EXTRA_BACKGROUND, 0).toLong() and 0xFFFFFFFFL
        } else {
            null
        }
    },
    /** Persists the resolved theme. Defaults to the app's real `xx_clock_theme` store. */
    private val persistTheme: (Context?, SyncedTheme) -> Unit = { context, theme ->
        if (context != null) {
            ThemeStore(
                SharedPreferencesThemeKeyValueStore(
                    context.getSharedPreferences(ThemeStore.PREFS_NAME, Context.MODE_PRIVATE)
                )
            ).save(theme)
        }
    },
    /** Applies the theme to a live UI (night mode + repaint of visible activities). */
    private val applyLive: (Context?, SyncedTheme) -> Unit = { _, theme ->
        ThemeSyncApplier.onThemeChanged(theme)
    },
) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (extractAction(intent) != action) return

        val theme = resolveSyncedTheme(
            extractThemeName(intent),
            extractBackground(intent),
        ) ?: return

        persistTheme(context, theme)
        applyLive(context, theme)
    }

    companion object {
        /** The xx-launcher's theme-change broadcast action. */
        const val ACTION_THEME_CHANGED = "xx.launcher.THEME_CHANGED"

        /** Extra: active preset display name ("AMOLED Night" … "Mist", or "Custom"). */
        const val EXTRA_THEME_NAME = "xx.launcher.extra.THEME_NAME"

        /** Extra: resolved background ARGB int (present even for Custom). */
        const val EXTRA_BACKGROUND = "xx.launcher.extra.BACKGROUND"
    }
}
