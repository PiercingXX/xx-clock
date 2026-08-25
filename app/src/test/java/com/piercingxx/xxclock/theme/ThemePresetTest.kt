package com.piercingxx.xxclock.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the family theme-preset model and its sync-resolution rule. */
class ThemePresetTest {

    // ---- display-name resolution ----

    @Test
    fun `all seven presets resolve by their exact display name`() {
        for (preset in ThemePreset.entries) {
            assertEquals(preset, ThemePreset.fromDisplayName(preset.displayName))
        }
        assertEquals(7, ThemePreset.entries.size)
    }

    @Test
    fun `display-name resolution is case-insensitive`() {
        assertEquals(ThemePreset.AMOLED_NIGHT, ThemePreset.fromDisplayName("aMoLeD nIgHt"))
        assertEquals(ThemePreset.PAPER, ThemePreset.fromDisplayName("PAPER"))
        assertEquals(ThemePreset.OCEAN_DRIFT, ThemePreset.fromDisplayName("ocean drift"))
    }

    @Test
    fun `unknown and null display names resolve to null`() {
        assertNull(ThemePreset.fromDisplayName("Solarized"))
        assertNull(ThemePreset.fromDisplayName(""))
        assertNull(ThemePreset.fromDisplayName(null))
        // "Custom" is deliberately NOT a preset: it routes through the background extra.
        assertNull(ThemePreset.fromDisplayName(CUSTOM_THEME_NAME))
    }

    @Test
    fun `presets resolve by stable key`() {
        for (preset in ThemePreset.entries) {
            assertEquals(preset, ThemePreset.fromKey(preset.key))
        }
        assertNull(ThemePreset.fromKey("neon"))
        assertNull(ThemePreset.fromKey(null))
    }

    // ---- grounds and dark-light classification ----

    @Test
    fun `preset grounds match the family contract`() {
        assertEquals(0xFF000000L, ThemePreset.AMOLED_NIGHT.background)
        assertEquals(0xFF131316L, ThemePreset.GRAPHITE.background)
        assertEquals(0xFF10261BL, ThemePreset.FOREST_NIGHT.background)
        assertEquals(0xFF0F1C2EL, ThemePreset.OCEAN_DRIFT.background)
        assertEquals(0xFF2A1018L, ThemePreset.BURGUNDY.background)
        assertEquals(0xFFF3EEE2L, ThemePreset.PAPER.background)
        assertEquals(0xFFE6EDF5L, ThemePreset.MIST.background)
    }

    @Test
    fun `night presets are dark, paper and mist are light`() {
        val dark = ThemePreset.entries.filter { it.isDark }
        assertEquals(
            listOf(
                ThemePreset.AMOLED_NIGHT,
                ThemePreset.GRAPHITE,
                ThemePreset.FOREST_NIGHT,
                ThemePreset.OCEAN_DRIFT,
                ThemePreset.BURGUNDY,
            ),
            dark,
        )
        assertFalse(ThemePreset.PAPER.isDark)
        assertFalse(ThemePreset.MIST.isDark)
    }

    @Test
    fun `every preset classification agrees with the contrast rule`() {
        for (preset in ThemePreset.entries) {
            assertEquals(
                "${preset.displayName} isDark must match the family contrast rule",
                preset.isDark,
                !prefersDarkForeground(preset.background),
            )
        }
    }

    // ---- contrast rule ----

    @Test
    fun `luminance uses the family weights`() {
        assertEquals(0.0, luminance(0xFF000000L), 1e-9)
        assertEquals(255.0, luminance(0xFFFFFFFFL), 1e-9)
        // Pure channels expose the 0,299-0,587-0,114 weighting.
        assertEquals(0.299 * 255, luminance(0xFFFF0000L), 1e-9)
        assertEquals(0.587 * 255, luminance(0xFF00FF00L), 1e-9)
        assertEquals(0.114 * 255, luminance(0xFF0000FFL), 1e-9)
    }

    @Test
    fun `threshold 182 is exclusive - gray 182 keeps white, gray 183 flips to ink`() {
        // 0xB6 = 182: luminance exactly 182 -> NOT above the threshold -> white.
        assertFalse(prefersDarkForeground(0xFFB6B6B6L))
        assertEquals(FOREGROUND_WHITE, foregroundFor(0xFFB6B6B6L))
        // 0xB7 = 183: above the threshold -> ink.
        assertTrue(prefersDarkForeground(0xFFB7B7B7L))
        assertEquals(FOREGROUND_INK, foregroundFor(0xFFB7B7B7L))
    }

    @Test
    fun `foreground picks white over dark grounds and ink over light grounds`() {
        assertEquals(FOREGROUND_WHITE, foregroundFor(ThemePreset.BURGUNDY.background))
        assertEquals(FOREGROUND_WHITE, foregroundFor(ThemePreset.OCEAN_DRIFT.background))
        assertEquals(FOREGROUND_INK, foregroundFor(ThemePreset.PAPER.background))
        assertEquals(FOREGROUND_INK, foregroundFor(ThemePreset.MIST.background))
    }

    // ---- broadcast resolution ----

    @Test
    fun `a named preset resolves to its own ground and classification`() {
        val theme = resolveSyncedTheme("Forest Night", backgroundExtra = 0xFF123456L)
        assertEquals(
            SyncedTheme(0xFF10261BL, isDark = true, presetKey = "forest-night"),
            theme,
        )
        // The preset's own ground wins over the extra for named presets.
        assertEquals(ThemePreset.FOREST_NIGHT.background, theme?.background)
    }

    @Test
    fun `custom with a dark ground resolves to the night look`() {
        val theme = resolveSyncedTheme(CUSTOM_THEME_NAME, backgroundExtra = 0xFF102030L)
        assertEquals(SyncedTheme(0xFF102030L, isDark = true, presetKey = null), theme)
    }

    @Test
    fun `custom with a light ground resolves to the day look`() {
        val theme = resolveSyncedTheme("custom", backgroundExtra = 0xFFF0F0F0L)
        assertEquals(SyncedTheme(0xFFF0F0F0L, isDark = false, presetKey = null), theme)
    }

    @Test
    fun `custom without its background extra is ignored`() {
        assertNull(resolveSyncedTheme(CUSTOM_THEME_NAME, backgroundExtra = null))
    }

    @Test
    fun `unknown or missing names are ignored`() {
        assertNull(resolveSyncedTheme("Solarized", backgroundExtra = 0xFF000000L))
        assertNull(resolveSyncedTheme(null, backgroundExtra = 0xFF000000L))
    }

    // ---- in-app manual picks (the family switcher) ----

    @Test
    fun `the switcher offers the eight family presets in display order`() {
        assertEquals(
            listOf(
                "amoled-night", "graphite", "forest-night", "ocean-drift",
                "burgundy", "paper", "mist", CUSTOM_PRESET_KEY,
            ),
            manualPresetKeys(),
        )
    }

    @Test
    fun `a manual named pick resolves to exactly what its broadcast would`() {
        for (preset in ThemePreset.entries) {
            assertEquals(
                "${preset.displayName}: manual pick and broadcast must be indistinguishable",
                resolveSyncedTheme(preset.displayName, backgroundExtra = null),
                resolveManualTheme(preset.key),
            )
        }
    }

    @Test
    fun `a manual custom pick reuses the remembered ground through the contrast rule`() {
        assertEquals(
            SyncedTheme(0xFF102030L, isDark = true, presetKey = null),
            resolveManualTheme(CUSTOM_PRESET_KEY, lastCustomBackground = 0xFF102030L),
        )
        assertEquals(
            SyncedTheme(0xFFF0F0F0L, isDark = false, presetKey = null),
            resolveManualTheme(CUSTOM_PRESET_KEY, lastCustomBackground = 0xFFF0F0F0L),
        )
    }

    @Test
    fun `a manual custom pick without a remembered ground is a no-op`() {
        assertNull(resolveManualTheme(CUSTOM_PRESET_KEY, lastCustomBackground = null))
    }

    @Test
    fun `unknown or missing manual keys resolve to null`() {
        assertNull(resolveManualTheme("neon", lastCustomBackground = 0xFF000000L))
        assertNull(resolveManualTheme(null, lastCustomBackground = 0xFF000000L))
    }
}
