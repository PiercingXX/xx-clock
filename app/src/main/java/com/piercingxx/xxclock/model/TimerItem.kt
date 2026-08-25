package com.piercingxx.xxclock.model

/**
 * A countdown timer persisted as a wall-clock deadline so it survives process death.
 *
 * States:
 *  IDLE     — reset, not running ([remainingMs] == [durationMs])
 *  RUNNING  — counting down toward [endsAtEpochMs]
 *  PAUSED   — frozen with [remainingMs] left
 *  FINISHED — deadline reached; ringing until stopped
 */
data class TimerItem(
    val id: Long,
    val durationMs: Long,
    val state: String,
    val endsAtEpochMs: Long,
    val remainingMs: Long,
    val label: String,
) {
    companion object {
        const val STATE_IDLE = "IDLE"
        const val STATE_RUNNING = "RUNNING"
        const val STATE_PAUSED = "PAUSED"
        const val STATE_FINISHED = "FINISHED"

        fun newTimer(durationMs: Long, label: String = ""): TimerItem = TimerItem(
            id = System.currentTimeMillis(),
            durationMs = durationMs,
            state = STATE_IDLE,
            endsAtEpochMs = 0L,
            remainingMs = durationMs,
            label = label,
        )
    }
}
