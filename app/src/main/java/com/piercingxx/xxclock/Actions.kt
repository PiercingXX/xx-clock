package com.piercingxx.xxclock

/** All intent actions and extras used across the app. Single source of truth. */
object Actions {
    const val FIRE_ALARM = "com.piercingxx.xxclock.action.FIRE_ALARM"
    const val SNOOZE_ALARM = "com.piercingxx.xxclock.action.SNOOZE_ALARM"
    const val DISMISS_ALARM = "com.piercingxx.xxclock.action.DISMISS_ALARM"
    const val FIRE_TIMER = "com.piercingxx.xxclock.action.FIRE_TIMER"
    const val STOP_TIMER = "com.piercingxx.xxclock.action.STOP_TIMER"
    const val ADD_MINUTE_TIMER = "com.piercingxx.xxclock.action.ADD_MINUTE_TIMER"
    const val REFRESH_WIDGET = "com.piercingxx.xxclock.action.REFRESH_WIDGET"

    /** MainActivity extra: which tab to open ("clock" | "alarms" | "timers"). */
    const val EXTRA_TAB = "com.piercingxx.xxclock.extra.TAB"

    const val EXTRA_ID = "com.piercingxx.xxclock.extra.ID"
    const val EXTRA_SNOOZE_MINUTES = "com.piercingxx.xxclock.extra.SNOOZE_MINUTES"

    /** Extra on [FIRE_ALARM]/[FIRE_TIMER] delivered to RingService/AlarmAlertActivity. */
    const val EXTRA_IS_ALARM = "com.piercingxx.xxclock.extra.IS_ALARM"
}
