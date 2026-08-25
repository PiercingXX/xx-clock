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
