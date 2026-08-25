package com.piercingxx.xxclock.theme

import android.content.SharedPreferences

/**
 * Minimal key-value seam between [ThemeStore] and SharedPreferences, so the
 * store (and the receiver that writes through it) is JVM-testable over an
 * in-memory map.
 */
interface ThemeKeyValueStore {
    fun getString(key: String): String?

    /** Writes [value], or removes the key when [value] is null. */
    fun putString(key: String, value: String?)

    fun getBoolean(key: String, default: Boolean): Boolean

    fun putBoolean(key: String, value: Boolean)
}

/** [ThemeKeyValueStore] over the app's real SharedPreferences. */
class SharedPreferencesThemeKeyValueStore(
    private val prefs: SharedPreferences,
) : ThemeKeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String?) {
        prefs.edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }

    override fun getBoolean(key: String, default: Boolean): Boolean =
        prefs.getBoolean(key, default)

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }
}

/**
 * Persists the launcher-synced theme ([SyncedTheme]) so it survives process
 * death: the receiver writes it, [ThemeSyncApplier] reads it on every activity
 * create/resume. Pure Kotlin over the [ThemeKeyValueStore] seam.
 */
class ThemeStore(private val kv: ThemeKeyValueStore) {

    fun save(theme: SyncedTheme) {
        kv.putString(KEY_BACKGROUND, theme.background.toString())
        kv.putBoolean(KEY_IS_DARK, theme.isDark)
        kv.putString(KEY_PRESET, theme.presetKey)
        if (theme.presetKey == null) {
            // Remember the custom ground separately so the in-app switcher's
            // "Custom" row can restore it after the user tries a named preset
            // (the active-theme slot above is overwritten by every save).
            kv.putString(KEY_CUSTOM_BACKGROUND, theme.background.toString())
        }
    }

    /**
     * The most recent custom ground ever saved (launcher broadcast or in-app
     * re-pick), or null when the launcher never sent one. Survives named-preset
     * saves — see [save].
     */
    fun lastCustomBackground(): Long? = kv.getString(KEY_CUSTOM_BACKGROUND)?.toLongOrNull()

    /** The persisted theme, or null when no launcher broadcast has ever landed. */
    fun load(): SyncedTheme? {
        val background = kv.getString(KEY_BACKGROUND)?.toLongOrNull() ?: return null
        return SyncedTheme(
            background = background,
            isDark = kv.getBoolean(KEY_IS_DARK, !prefersDarkForeground(background)),
            presetKey = kv.getString(KEY_PRESET),
        )
    }

    companion object {
        /** SharedPreferences file the synced theme lives in. */
        const val PREFS_NAME = "xx_clock_theme"

        const val KEY_BACKGROUND = "background"
        const val KEY_IS_DARK = "is_dark"
        const val KEY_PRESET = "preset_key"
        const val KEY_CUSTOM_BACKGROUND = "custom_background"
    }
}
