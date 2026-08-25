package com.piercingxx.xxclock

/** Behavior constants with sensible defaults; no settings UI in v1. */
object Prefs {
    const val SNOOZE_MINUTES_DEFAULT = 10
    const val AUTO_SILENCE_MINUTES = 10L
    const val GRADUAL_VOLUME_DEFAULT = true

    /** A timer whose deadline passed while the process was dead still fires within this window. */
    const val TIMER_BOOT_GRACE_MS = 15 * 60 * 1000L

    /** Partial wakelock held while ringing. */
    const val RING_WAKELOCK_MS = 12 * 60 * 1000L

    /** Volume ramp duration for gradual-increase-volume. */
    const val VOLUME_RAMP_MS = 60_000L
}
