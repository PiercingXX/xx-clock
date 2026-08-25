package com.piercingxx.xxclock.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * In-memory [ThemeKeyValueStore] so persistence is JVM-testable without
 * Android (mirrors TxxT's theme-sync test fake).
 */
private class InMemoryThemeKv : ThemeKeyValueStore {
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
 * Verifies the theme-sync receiver's two halves without the Android platform.
 *
 * `Intent`/`Context` are android.jar stubs on the JVM, so every extractor and
 * side effect is an injected seam and [ThemeSyncReceiver.onReceive] is driven
 * with nulls — the routing (action gate, name/background resolution, persist,
 * live-apply) is the receiver's real code. The manifest half locks the wiring
 * the OS needs to deliver the launcher's package-targeted broadcast.
 */
class ThemeSyncReceiverTest {

    // Gradle unit tests run with the module directory (app/) as the working
    // directory; fall back to the repo-root-relative path for robustness.
    private val manifestText: String =
        sequenceOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first { it.exists() }.readText()

    /** A receiver whose seams report into [persisted] / [applied]. */
    private fun receiver(
        persisted: MutableList<SyncedTheme>,
        applied: MutableList<SyncedTheme> = mutableListOf(),
        action: String? = ThemeSyncReceiver.ACTION_THEME_CHANGED,
        themeName: String? = null,
        background: Long? = null,
    ): ThemeSyncReceiver = ThemeSyncReceiver(
        extractAction = { action },
        extractThemeName = { themeName },
        extractBackground = { background },
        persistTheme = { _, theme -> persisted += theme },
        applyLive = { _, theme -> applied += theme },
    )

    // ---- manifest wiring ----

    @Test
    fun `manifest declares the theme-sync receiver component`() {
        assertTrue(
            "AndroidManifest.xml must declare the .theme.ThemeSyncReceiver component",
            manifestText.contains(".theme.ThemeSyncReceiver"),
        )
    }

    @Test
    fun `manifest exports the receiver for the cross-app broadcast`() {
        val receiverBlock = manifestText
            .substringAfter(".theme.ThemeSyncReceiver")
            .substringBefore("</receiver>")
        assertTrue(
            "ThemeSyncReceiver must be exported to receive the launcher's broadcast",
            receiverBlock.contains("android:exported=\"true\""),
        )
    }

    @Test
    fun `manifest registers the receiver for the launcher theme-changed action`() {
        assertTrue(
            "AndroidManifest.xml must register ${ThemeSyncReceiver.ACTION_THEME_CHANGED}",
            manifestText.contains(ThemeSyncReceiver.ACTION_THEME_CHANGED),
        )
    }

    @Test
    fun `declared theme-sync receiver name resolves to a class`() {
        // Throws ClassNotFoundException if the declared name is not a real class.
        Class.forName("com.piercingxx.xxclock.theme.ThemeSyncReceiver")
    }

    // ---- routing and persistence via seams ----

    @Test
    fun `a named preset broadcast persists that preset's ground and night look`() {
        val persisted = mutableListOf<SyncedTheme>()
        receiver(persisted, themeName = "Graphite").onReceive(null, null)
        assertEquals(
            listOf(SyncedTheme(0xFF131316L, isDark = true, presetKey = "graphite")),
            persisted,
        )
    }

    @Test
    fun `a paper broadcast persists the day look`() {
        val persisted = mutableListOf<SyncedTheme>()
        receiver(persisted, themeName = "Paper").onReceive(null, null)
        assertEquals(
            listOf(SyncedTheme(0xFFF3EEE2L, isDark = false, presetKey = "paper")),
            persisted,
        )
    }

    @Test
    fun `a custom broadcast is honored via the background extra and contrast rule`() {
        val persisted = mutableListOf<SyncedTheme>()
        receiver(persisted, themeName = "Custom", background = 0xFF2A1018L)
            .onReceive(null, null)
        assertEquals(
            listOf(SyncedTheme(0xFF2A1018L, isDark = true, presetKey = null)),
            persisted,
        )
    }

    @Test
    fun `a light custom background persists the day look`() {
        val persisted = mutableListOf<SyncedTheme>()
        receiver(persisted, themeName = "Custom", background = 0xFFFAF5EAL)
            .onReceive(null, null)
        assertEquals(listOf(SyncedTheme(0xFFFAF5EAL, isDark = false)), persisted)
    }

    @Test
    fun `a wrong or missing action persists nothing`() {
        val persisted = mutableListOf<SyncedTheme>()
        receiver(persisted, action = "xx.launcher.SOMETHING_ELSE", themeName = "Graphite")
            .onReceive(null, null)
        receiver(persisted, action = null, themeName = "Graphite").onReceive(null, null)
        assertTrue(persisted.isEmpty())
    }

    @Test
    fun `an unknown theme name persists nothing`() {
        val persisted = mutableListOf<SyncedTheme>()
        receiver(persisted, themeName = "Solarized", background = 0xFF000000L)
            .onReceive(null, null)
        receiver(persisted, themeName = null).onReceive(null, null)
        assertTrue(persisted.isEmpty())
    }

    @Test
    fun `the live UI is nudged with exactly the persisted theme`() {
        val persisted = mutableListOf<SyncedTheme>()
        val applied = mutableListOf<SyncedTheme>()
        receiver(persisted, applied, themeName = "Ocean Drift").onReceive(null, null)
        assertEquals(persisted, applied)
        assertEquals(listOf(SyncedTheme(0xFF0F1C2EL, isDark = true, presetKey = "ocean-drift")), applied)
    }

    // ---- ThemeStore round-trip (the receiver's default persistence target) ----

    @Test
    fun `store round-trips a named preset theme`() {
        val store = ThemeStore(InMemoryThemeKv())
        val theme = SyncedTheme(0xFF10261BL, isDark = true, presetKey = "forest-night")
        store.save(theme)
        assertEquals(theme, store.load())
    }

    @Test
    fun `store round-trips a custom theme and clears a stale preset key`() {
        val kv = InMemoryThemeKv()
        val store = ThemeStore(kv)
        store.save(SyncedTheme(0xFF131316L, isDark = true, presetKey = "graphite"))
        store.save(SyncedTheme(0xFF102030L, isDark = true, presetKey = null))
        assertEquals(SyncedTheme(0xFF102030L, isDark = true, presetKey = null), store.load())
        assertNull(kv.strings[ThemeStore.KEY_PRESET])
    }

    @Test
    fun `an empty store loads null`() {
        assertNull(ThemeStore(InMemoryThemeKv()).load())
    }

    // ---- remembered custom ground (feeds the in-app switcher's Custom row) ----

    @Test
    fun `a custom save remembers its ground separately`() {
        val store = ThemeStore(InMemoryThemeKv())
        store.save(SyncedTheme(0xFF102030L, isDark = true, presetKey = null))
        assertEquals(0xFF102030L, store.lastCustomBackground())
    }

    @Test
    fun `a later named-preset save keeps the remembered custom ground`() {
        val store = ThemeStore(InMemoryThemeKv())
        store.save(SyncedTheme(0xFF102030L, isDark = true, presetKey = null))
        store.save(SyncedTheme(0xFF131316L, isDark = true, presetKey = "graphite"))
        // The active slot moved on, but Custom can still be re-picked in-app.
        assertEquals(0xFF102030L, store.lastCustomBackground())
        assertEquals(SyncedTheme(0xFF131316L, isDark = true, presetKey = "graphite"), store.load())
    }

    @Test
    fun `no custom broadcast ever - no remembered ground`() {
        val store = ThemeStore(InMemoryThemeKv())
        store.save(SyncedTheme(0xFF131316L, isDark = true, presetKey = "graphite"))
        assertNull(store.lastCustomBackground())
    }
}
