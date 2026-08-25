package com.piercingxx.xxclock.theme

import androidx.appcompat.app.AppCompatDelegate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** In-memory [ThemeKeyValueStore] so the store round-trip needs no Android. */
private class ApplierTestKv : ThemeKeyValueStore {
    val strings = mutableMapOf<String, String>()
    val booleans = mutableMapOf<String, Boolean>()

    override fun getString(key: String): String? = strings[key]
    override fun putString(key: String, value: String?) {
        if (value == null) strings.remove(key) else strings[key] = value
    }
    override fun getBoolean(key: String, default: Boolean): Boolean = booleans[key] ?: default
    override fun putBoolean(key: String, value: Boolean) {
        booleans[key] = value
    }
}

/**
 * Pins the rule the owner asked for: **the ground is a choice, never an
 * observation.** XX Clock must not change its look because the system flipped
 * to dark mode, because of a sunset/sunrise schedule, or for any other ambient
 * reason — a theme picked at noon must look identical at midnight.
 *
 * Two halves, both pure JVM:
 *
 * 1. The resolution rules on [ThemeSyncApplier] — [ThemeSyncApplier.DEFAULT_THEME],
 *    [ThemeSyncApplier.effectiveTheme] and [ThemeSyncApplier.nightModeFor] are
 *    plain functions over [SyncedTheme] plus `AppCompatDelegate`'s compile-time
 *    int constants, so they run without a device. The object's initializer
 *    touches no `android.*` API.
 * 2. The declarations those rules depend on, asserted against the real XML —
 *    the same trick [ThemeSyncReceiverTest] uses for the manifest. A pure
 *    function that resolves to MODE_NIGHT_NO is worthless if the platform then
 *    force-darks the window anyway, and worthless on the alarm screen if that
 *    screen is still a DayNight surface.
 */
class ThemeSyncApplierTest {

    // Gradle unit tests run with the module directory (app/) as the working
    // directory; fall back to the repo-root-relative path for robustness.
    private fun repoFile(modulePath: String): String =
        sequenceOf(File("src/$modulePath"), File("app/src/$modulePath"))
            .first { it.exists() }
            .readText()

    private val manifestText: String by lazy { repoFile("main/AndroidManifest.xml") }
    private val dayThemesText: String by lazy { repoFile("main/res/values/themes.xml") }
    private val nightThemesText: String by lazy { repoFile("main/res/values-night/themes.xml") }
    private val applierSource: String by lazy {
        repoFile("main/java/com/piercingxx/xxclock/theme/ThemeSyncApplier.kt")
    }

    // ---- the default: nothing chosen is still a definite answer ----

    @Test
    fun `the unset default is the family's AMOLED Night`() {
        assertEquals(
            SyncedTheme(0xFF000000L, isDark = true, presetKey = "amoled-night"),
            ThemeSyncApplier.DEFAULT_THEME,
        )
        // Stated against the preset table too, so a change to either side fails.
        assertEquals(ThemePreset.AMOLED_NIGHT.background, ThemeSyncApplier.DEFAULT_THEME.background)
        assertEquals(ThemePreset.AMOLED_NIGHT.isDark, ThemeSyncApplier.DEFAULT_THEME.isDark)
        assertEquals(ThemePreset.AMOLED_NIGHT.key, ThemeSyncApplier.DEFAULT_THEME.presetKey)
    }

    @Test
    fun `an unset theme resolves to AMOLED Night, not to the system`() {
        val nothingChosen: SyncedTheme? = null
        assertEquals(ThemeSyncApplier.DEFAULT_THEME, ThemeSyncApplier.effectiveTheme(nothingChosen))
        // Fresh install / cleared data: ink black, whatever the hour.
        assertEquals(0xFF000000L, ThemeSyncApplier.effectiveTheme(nothingChosen).background)
        assertTrue(ThemeSyncApplier.effectiveTheme(nothingChosen).isDark)
    }

    @Test
    fun `an empty store resolves to the default through the real load path`() {
        val store = ThemeStore(ApplierTestKv())
        assertEquals(ThemeSyncApplier.DEFAULT_THEME, ThemeSyncApplier.effectiveTheme(store.load()))
    }

    @Test
    fun `a chosen theme always beats the default`() {
        for (preset in ThemePreset.entries) {
            val chosen = resolveManualTheme(preset.key)!!
            assertEquals(
                "${preset.displayName} must survive resolution",
                chosen,
                ThemeSyncApplier.effectiveTheme(chosen),
            )
        }
        val custom = SyncedTheme(0xFF102030L, isDark = true, presetKey = null)
        assertEquals(custom, ThemeSyncApplier.effectiveTheme(custom))
    }

    @Test
    fun `what the launcher sets is what is set, across a store round-trip`() {
        val store = ThemeStore(ApplierTestKv())
        for (preset in ThemePreset.entries) {
            val broadcast = resolveSyncedTheme(preset.displayName, backgroundExtra = null)!!
            store.save(broadcast)
            // Reload (the process-death path) resolves to the same theme, and
            // to the same night mode. No ambient input anywhere in between.
            assertEquals(broadcast, ThemeSyncApplier.effectiveTheme(store.load()))
            assertEquals(
                ThemeSyncApplier.nightModeFor(broadcast),
                ThemeSyncApplier.nightModeFor(ThemeSyncApplier.effectiveTheme(store.load())),
            )
        }
    }

    // ---- night mode depends on isDark and on nothing else ----

    @Test
    fun `night mode follows the chosen theme's isDark`() {
        for (preset in ThemePreset.entries) {
            val expected =
                if (preset.isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            assertEquals(
                "${preset.displayName} must pin night mode from its own isDark",
                expected,
                ThemeSyncApplier.nightModeFor(resolveManualTheme(preset.key)!!),
            )
        }
    }

    @Test
    fun `night mode is a function of isDark alone - the ground does not matter`() {
        // Same flag, wildly different grounds: identical mode.
        assertEquals(
            ThemeSyncApplier.nightModeFor(SyncedTheme(0xFF000000L, isDark = true)),
            ThemeSyncApplier.nightModeFor(SyncedTheme(0xFF2A1018L, isDark = true)),
        )
        assertEquals(
            ThemeSyncApplier.nightModeFor(SyncedTheme(0xFFF3EEE2L, isDark = false)),
            ThemeSyncApplier.nightModeFor(SyncedTheme(0xFFE6EDF5L, isDark = false)),
        )
        // Same ground, opposite flag: the flag is what moves the mode.
        assertNotEquals(
            ThemeSyncApplier.nightModeFor(SyncedTheme(0xFF808080L, isDark = true)),
            ThemeSyncApplier.nightModeFor(SyncedTheme(0xFF808080L, isDark = false)),
        )
    }

    @Test
    fun `a light pick pins MODE_NIGHT_NO - system dark cannot drag it back to ink`() {
        // The owner's scenario: launcher broadcasts Paper, system is forced to
        // night. The resolved mode is NO, so the app keeps the values-/ day set.
        val paper = resolveSyncedTheme("Paper", backgroundExtra = null)!!
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, ThemeSyncApplier.nightModeFor(paper))
        val mist = resolveSyncedTheme("Mist", backgroundExtra = null)!!
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, ThemeSyncApplier.nightModeFor(mist))
        // A light Custom ground behaves identically.
        val lightCustom = resolveSyncedTheme(CUSTOM_THEME_NAME, backgroundExtra = 0xFFFAF5EAL)!!
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, ThemeSyncApplier.nightModeFor(lightCustom))
    }

    @Test
    fun `no theme anywhere resolves to MODE_NIGHT_FOLLOW_SYSTEM`() {
        val everyTheme = ThemePreset.entries.map { resolveManualTheme(it.key)!! } +
            ThemeSyncApplier.DEFAULT_THEME +
            ThemeSyncApplier.effectiveTheme(null as SyncedTheme?) +
            // Sweep the whole gray ramp as Custom grounds: both sides of the
            // 182 contrast threshold, every one of them a definite mode.
            (0..255).map { v ->
                val gray = 0xFF000000L or (v * 0x010101L)
                resolveSyncedTheme(CUSTOM_THEME_NAME, backgroundExtra = gray)!!
            }
        for (theme in everyTheme) {
            assertNotEquals(
                "$theme must not hand the look back to the system",
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
                ThemeSyncApplier.nightModeFor(theme),
            )
            assertTrue(
                "$theme must resolve to an explicit YES/NO",
                ThemeSyncApplier.nightModeFor(theme) == AppCompatDelegate.MODE_NIGHT_YES ||
                    ThemeSyncApplier.nightModeFor(theme) == AppCompatDelegate.MODE_NIGHT_NO,
            )
        }
    }

    // ---- the declarations the rule leans on ----

    @Test
    fun `both theme configs opt out of platform force-dark`() {
        // Force-dark is the platform recoloring a light-themed window when
        // system dark mode is on: an ambient input the pure rule cannot see.
        assertTrue(
            "values/themes.xml must set android:forceDarkAllowed=false",
            dayThemesText.contains("<item name=\"android:forceDarkAllowed\">false</item>"),
        )
        assertTrue(
            "values-night/themes.xml must set android:forceDarkAllowed=false",
            nightThemesText.contains("<item name=\"android:forceDarkAllowed\">false</item>"),
        )
    }

    @Test
    fun `the alarm alert screen is pinned to a non-DayNight theme`() {
        val activityBlock = manifestText
            .substringAfter(".ui.AlarmAlertActivity")
            .substringBefore("/>")
        assertTrue(
            "AlarmAlertActivity must declare android:theme=@style/Theme.XxClock.AlarmAlert",
            activityBlock.contains("android:theme=\"@style/Theme.XxClock.AlarmAlert\""),
        )
        val style = dayThemesText
            .substringAfter("name=\"Theme.XxClock.AlarmAlert\"")
            .substringBefore("</style>")
        assertTrue(
            "the alarm alert style must exist in values/themes.xml",
            dayThemesText.contains("Theme.XxClock.AlarmAlert"),
        )
        assertTrue(
            "the alarm alert style must parent a fixed Dark theme, not DayNight",
            dayThemesText.contains(
                "name=\"Theme.XxClock.AlarmAlert\" parent=\"Theme.Material3.Dark.NoActionBar\"",
            ),
        )
        assertTrue(
            "the alarm alert style must opt out of force-dark too",
            style.contains("<item name=\"android:forceDarkAllowed\">false</item>"),
        )
        // It must not be redefined per-configuration, or it would vary again.
        assertFalse(
            "Theme.XxClock.AlarmAlert must not have a values-night override",
            nightThemesText.contains("Theme.XxClock.AlarmAlert"),
        )
    }

    // ---- force dark: the platform inverting what we already painted ----
    //
    // Verified on-device: a correctly stored, resolved and painted Paper ground
    // still rendered as inverted Paper under system dark mode, because the
    // theme-level opt-out was not honored. The window-level revocation is the
    // enforcement; these pin that it exists and that its SCOPE is every window.
    // Source-level assertions, in the same spirit as the manifest/XML ones
    // above — the call itself needs a renderer, but where it is called from is
    // exactly the part that regressed and the part a JVM test can hold.

    @Test
    fun `every activity window revokes force dark, alarm screen included`() {
        assertTrue(
            "ThemeSyncApplier must clear View.isForceDarkAllowed on the decor view",
            applierSource.contains("window?.decorView?.isForceDarkAllowed = false"),
        )
        val revoke = applierSource.indexOf("disableForceDark(window)")
        val alarmBailout = applierSource.indexOf("if (activity is AlarmAlertActivity) return")
        assertTrue("the per-activity revocation must exist", revoke > 0)
        assertTrue("the alarm-alert ground bailout must exist", alarmBailout > 0)
        assertTrue(
            "force dark must be revoked BEFORE the alarm-alert early return, or the " +
                "one screen that shows at 3 a.m. is the one window left invertible",
            revoke < alarmBailout,
        )
    }

    @Test
    fun `dialog windows revoke force dark too`() {
        // A dialog owns its own decor view and RenderNode, so the activity's
        // revocation does not reach it.
        assertTrue(
            "a FragmentLifecycleCallbacks guard must cover DialogFragment windows",
            applierSource.contains("registerFragmentLifecycleCallbacks"),
        )
        assertTrue(
            "the guard must revoke force dark on the dialog's own window",
            applierSource.contains("disableForceDark(f.dialog?.window)"),
        )
    }

    @Test
    fun `the theme-level opt-out is kept as well as the window-level one`() {
        // Belt and braces, deliberately: the theme attribute is declaration-level
        // intent, the decor-view flag is the enforcement. Neither replaces the
        // other, and dropping the attribute because "the real fix is elsewhere"
        // is a regression.
        assertTrue(dayThemesText.contains("android:forceDarkAllowed"))
        assertTrue(nightThemesText.contains("android:forceDarkAllowed"))
        assertTrue(applierSource.contains("isForceDarkAllowed"))
    }
}
