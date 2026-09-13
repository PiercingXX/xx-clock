package com.piercingxx.xxclock.theme

/**
 * The seven named background presets of the PiercingXX family theme set,
 * mirroring the xx-launcher's themes (and TxxT's ThemePreset). Pure Kotlin —
 * no `android.*` imports — so the model and the sync-resolution rule are
 * JVM-testable without a device.
 *
 * Names and background values are the family's own, reused verbatim so theme
 * auto-sync with the launcher can match by display name.
 */
enum class ThemePreset(
    /** Stable identifier used in persisted settings. */
    val key: String,
    /** Display name carried in the launcher broadcast, e.g. "AMOLED Night". */
    val displayName: String,
    /** Background (ground) color as a 0xAARRGGBB long. */
    val background: Long,
    /** Whether the preset is a dark theme (white foreground). */
    val isDark: Boolean,
) {
    AMOLED_NIGHT("amoled-night", "AMOLED Night", 0xFF000000, true),
    GRAPHITE("graphite", "Graphite", 0xFF131316, true),
    FOREST_NIGHT("forest-night", "Forest Night", 0xFF10261B, true),
    OCEAN_DRIFT("ocean-drift", "Ocean Drift", 0xFF0F1C2E, true),
    BURGUNDY("burgundy", "Burgundy", 0xFF2A1018, true),
    PAPER("paper", "Paper", 0xFFF3EEE2, false),
    MIST("mist", "Mist", 0xFFE6EDF5, false);

    companion object {
        /**
         * Resolve a preset by its stable [key]. Returns null for an unknown
         * key so callers can fall back without throwing.
         */
        fun fromKey(key: String?): ThemePreset? =
            entries.firstOrNull { it.key == key }

        /** Resolve a preset by its display name (case-insensitive). */
        fun fromDisplayName(name: String?): ThemePreset? =
            entries.firstOrNull { it.displayName.equals(name, ignoreCase = true) }
    }
}

/** Display name the launcher broadcasts for a user-picked custom ground. */
const val CUSTOM_THEME_NAME = "Custom"

/**
 * Stable key for the family's eighth preset, "Custom", in the in-app switcher.
 * Deliberately NOT a [ThemePreset] entry: Custom has no ground of its own — it
 * always resolves through a remembered background (the launcher's last custom
 * broadcast) plus the contrast rule, exactly like the broadcast path.
 */
const val CUSTOM_PRESET_KEY = "custom"

/** Dark (near-black) foreground used over light grounds — family contrast rule. */
const val FOREGROUND_INK: Long = 0xFF1A1A1A

/** White foreground used over dark grounds. */
const val FOREGROUND_WHITE: Long = 0xFFFFFFFF

/**
 * Perceived luminance of a 0xAARRGGBB color per the family-wide contrast rule:
 * `0.299 r + 0.587 g + 0.114 b` (0..255).
 */
fun luminance(argb: Long): Double {
    val r = ((argb ushr 16) and 0xFF).toDouble()
    val g = ((argb ushr 8) and 0xFF).toDouble()
    val b = (argb and 0xFF).toDouble()
    return 0.299 * r + 0.587 * g + 0.114 * b
}

/**
 * Family-wide contrast rule: a ground with luminance above 182 takes the dark
 * (near-black) foreground; anything darker takes white.
 */
fun prefersDarkForeground(background: Long): Boolean = luminance(background) > 182.0

/** Foreground color the contrast rule picks for [background]. */
fun foregroundFor(background: Long): Long =
    if (prefersDarkForeground(background)) FOREGROUND_INK else FOREGROUND_WHITE

/**
 * RemoteViews colors for the home widget, derived from the last synced theme.
 * Secondary is the contrast-rule foreground at 50% alpha (date / next-alarm).
 */
data class WidgetColors(val background: Int, val primary: Int, val secondary: Int)

fun widgetColors(theme: SyncedTheme): WidgetColors {
    val primary = foregroundFor(theme.background).toInt()
    return WidgetColors(
        background = theme.background.toInt(),
        primary = primary,
        secondary = (primary and 0x00FFFFFF) or (0x80 shl 24),
    )
}

/**
 * The theme state a launcher broadcast resolves to: the exact ground color to
 * paint and whether the app should wear its night look (white foreground) or
 * its day look (ink foreground).
 */
data class SyncedTheme(
    /** Ground color as a 0xAARRGGBB long. */
    val background: Long,
    /** True → night look (values-night); false → day look (Paper/Mist values). */
    val isDark: Boolean,
    /** Stable preset key, or null for a custom ground. */
    val presetKey: String? = null,
)

/**
 * Resolve the launcher broadcast's payload to a [SyncedTheme].
 *
 * A named preset resolves to its own ground and dark/light classification.
 * [CUSTOM_THEME_NAME] resolves through [backgroundExtra] plus the contrast
 * rule (white foreground → night look). An unknown name, or a Custom
 * broadcast missing its background, resolves to null and is ignored.
 */
fun resolveSyncedTheme(displayName: String?, backgroundExtra: Long?): SyncedTheme? {
    val preset = ThemePreset.fromDisplayName(displayName)
    if (preset != null) {
        return SyncedTheme(preset.background, preset.isDark, preset.key)
    }
    if (CUSTOM_THEME_NAME.equals(displayName, ignoreCase = true) && backgroundExtra != null) {
        return SyncedTheme(backgroundExtra, isDark = !prefersDarkForeground(backgroundExtra))
    }
    return null
}

/**
 * The full family switcher set, in display order: the seven named presets plus
 * [CUSTOM_PRESET_KEY]. The in-app picker builds its rows from this list so the
 * enum stays the single source of truth and the eight-preset family contract
 * is testable on the JVM.
 */
fun manualPresetKeys(): List<String> = ThemePreset.entries.map { it.key } + CUSTOM_PRESET_KEY

/** Channel-wise mix so raised surfaces keep the preset's hue. */
fun mix(a: Long, b: Long, fraction: Double): Long {
    fun channel(shift: Int): Long {
        val from = (a ushr shift) and 0xFF
        val to = (b ushr shift) and 0xFF
        return ((from + (to - from) * fraction) + 0.5).toLong() and 0xFF
    }
    return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

fun withAlpha(argb: Long, alpha: Int): Long =
    (alpha.toLong() shl 24) or (argb and 0x00FFFFFF)

const val AMOLED_SURFACE_LOW: Long = 0xFF09090B
const val AMOLED_SURFACE_MID: Long = 0xFF131316
const val AMOLED_SURFACE_HIGH: Long = 0xFF18181B

/**
 * Every painted surface in the clock UI. Derived from the chosen ground so
 * Forest/Ocean/Paper change cards, type, and chrome — not only the window.
 */
data class ClockPalette(
    val background: Long,
    val foreground: Long,
    val surfaceLow: Long,
    val surfaceMid: Long,
    val surfaceHigh: Long,
    val onSurface: Long,
    val onSurfaceMuted: Long,
    val outline: Long,
    val outlineFaint: Long,
    val accent: Long,
    val onAccent: Long,
)

fun paletteFor(theme: SyncedTheme): ClockPalette {
    val foreground = foregroundFor(theme.background)
    val amoled = theme.background == ThemePreset.AMOLED_NIGHT.background && theme.isDark
    return ClockPalette(
        background = theme.background,
        foreground = foreground,
        surfaceLow = if (amoled) AMOLED_SURFACE_LOW else mix(theme.background, foreground, 0.04),
        surfaceMid = if (amoled) AMOLED_SURFACE_MID else mix(theme.background, foreground, 0.07),
        surfaceHigh = if (amoled) AMOLED_SURFACE_HIGH else mix(theme.background, foreground, 0.10),
        onSurface = withAlpha(foreground, 0xE6),
        onSurfaceMuted = withAlpha(foreground, 0x80),
        outline = withAlpha(foreground, 0x40),
        outlineFaint = withAlpha(foreground, 0x1A),
        accent = foreground,
        onAccent = theme.background,
    )
}

/**
 * Resolve an in-app manual pick to the SAME [SyncedTheme] the corresponding
 * launcher broadcast would produce — that identity is what makes a manual pick
 * indistinguishable downstream (ThemeStore + ThemeSyncApplier see one shape).
 *
 * A named preset resolves to its own ground and classification. Custom reuses
 * [lastCustomBackground] (the launcher's most recent custom ground, remembered
 * by ThemeStore) through the contrast rule; with no remembered ground there is
 * nothing sensible to paint, so Custom — like an unknown key — resolves to
 * null and the pick is a no-op.
 */
fun resolveManualTheme(presetKey: String?, lastCustomBackground: Long? = null): SyncedTheme? {
    val preset = ThemePreset.fromKey(presetKey)
    if (preset != null) {
        return SyncedTheme(preset.background, preset.isDark, preset.key)
    }
    if (presetKey == CUSTOM_PRESET_KEY && lastCustomBackground != null) {
        return SyncedTheme(lastCustomBackground, isDark = !prefersDarkForeground(lastCustomBackground))
    }
    return null
}
