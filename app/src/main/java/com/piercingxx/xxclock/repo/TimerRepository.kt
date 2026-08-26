package com.piercingxx.xxclock.repo

import android.content.Context
import com.piercingxx.xxclock.alarm.AlarmCoordinator
import com.piercingxx.xxclock.data.ClockStore
import com.piercingxx.xxclock.model.TimerItem
import com.piercingxx.xxclock.scheduler.ExactScheduler
import com.piercingxx.xxclock.time.TimerMath

/** UI-facing facade for timers. Same contract rule as [AlarmRepository]. */
object TimerRepository {

    fun getAll(context: Context): List<TimerItem> = ClockStore.get(context).timers()

    fun get(context: Context, id: Long): TimerItem? = ClockStore.get(context).getTimer(id)

    /**
     * Starts [durationMs] labeled [label]. Reuses an idle timer with the same
     * duration and label when one exists (AlarmClock SET_TIMER identical-reuse).
     */
    fun startOrReuse(context: Context, durationMs: Long, label: String = ""): TimerItem {
        val idle = getAll(context).firstOrNull {
            it.state == TimerItem.STATE_IDLE &&
                it.durationMs == durationMs &&
                it.label == label
        }
        if (idle != null) {
            val running = idle.copy(
                state = TimerItem.STATE_RUNNING,
                endsAtEpochMs = System.currentTimeMillis() + durationMs,
                remainingMs = 0L,
            )
            ClockStore.get(context).saveTimer(running)
            ExactScheduler.scheduleSoonestTimer(context)
            return running
        }
        return start(context, durationMs, label)
    }

    /** Starts a fresh countdown and returns it. */
    fun start(context: Context, durationMs: Long, label: String = ""): TimerItem {
        val timer = TimerItem.newTimer(durationMs, label).copy(
            state = TimerItem.STATE_RUNNING,
            endsAtEpochMs = System.currentTimeMillis() + durationMs,
            remainingMs = 0L,
        )
        ClockStore.get(context).saveTimer(timer)
        ExactScheduler.scheduleSoonestTimer(context)
        return timer
    }

    fun pause(context: Context, id: Long) {
        val store = ClockStore.get(context)
        val timer = store.getTimer(id) ?: return
        if (timer.state != TimerItem.STATE_RUNNING) return
        val remaining = TimerMath.remainingMs(timer, System.currentTimeMillis())
        store.saveTimer(timer.copy(state = TimerItem.STATE_PAUSED, remainingMs = remaining, endsAtEpochMs = 0L))
        ExactScheduler.scheduleSoonestTimer(context)
    }

    fun resume(context: Context, id: Long) {
        val store = ClockStore.get(context)
        val timer = store.getTimer(id) ?: return
        if (timer.state != TimerItem.STATE_PAUSED) return
        store.saveTimer(
            timer.copy(
                state = TimerItem.STATE_RUNNING,
                endsAtEpochMs = System.currentTimeMillis() + timer.remainingMs,
                remainingMs = 0L,
            ),
        )
        ExactScheduler.scheduleSoonestTimer(context)
    }

    fun reset(context: Context, id: Long) {
        AlarmCoordinator.stopTimer(context, id)
    }

    fun addMinute(context: Context, id: Long) {
        val store = ClockStore.get(context)
        val timer = store.getTimer(id) ?: return
        when (timer.state) {
            TimerItem.STATE_RUNNING -> {
                store.saveTimer(timer.copy(endsAtEpochMs = timer.endsAtEpochMs + 60_000L))
                ExactScheduler.scheduleSoonestTimer(context)
            }
            TimerItem.STATE_PAUSED -> {
                store.saveTimer(timer.copy(remainingMs = timer.remainingMs + 60_000L))
            }
            else -> Unit
        }
    }

    fun delete(context: Context, id: Long) {
        RingingGuard.stopIfRinging(context, id)
        ClockStore.get(context).deleteTimer(id)
        ExactScheduler.scheduleSoonestTimer(context)
    }

    fun stopRinging(context: Context, id: Long) {
        AlarmCoordinator.stopTimer(context, id)
    }

    @Suppress("UNUSED_PARAMETER")
    fun remainingMs(context: Context, timer: TimerItem): Long =
        TimerMath.remainingMs(timer, System.currentTimeMillis())
}

/** Small helper so both repositories can silence a ringer before mutating state. */
private object RingingGuard {
    fun stopIfRinging(context: Context, id: Long) {
        if (ClockStore.get(context).isRinging(id)) {
            AlarmCoordinator.stopTimer(context, id)
        }
    }
}
