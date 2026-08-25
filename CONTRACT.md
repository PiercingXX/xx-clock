# xx-clock — Build Contract (v1)

Package: `com.piercingxx.xxclock` · minSdk 29 · target/compile 34 · Kotlin 1.9.24 · classic Views · Material3.
NO INTERNET permission (privacy parity with Nope-Mode). No Room, no coroutines, no Compose, no GMS.

## Architecture (already implemented — DO NOT re-implement)

Core engine is written. UI/widget/setup code plugs into these APIs:

```kotlin
// repo/AlarmRepository.kt
getAll(ctx): List<Alarm>            // sorted by hour,minute
get(ctx, id): Alarm?
save(ctx, alarm)                    // persists + reschedules + cancels snooze/ring + refreshes widget
delete(ctx, id); toggle(ctx, id, enabled: Boolean)
snoozedUntil(ctx, id): Long; scheduledFire(ctx, id): Long; isRinging(ctx, id): Boolean
nextArmed(ctx): Pair<Alarm, Long>?  // soonest upcoming ring incl. snoozed
snoozeRinging(ctx, id, minutes); dismissRinging(ctx, id)

// repo/TimerRepository.kt
getAll(ctx): List<TimerItem>; get(ctx, id): TimerItem?
start(ctx, durationMs: Long, label: String = ""): TimerItem   // starts immediately
pause(ctx, id); resume(ctx, id); reset(ctx, id)               // reset -> IDLE full duration
addMinute(ctx, id); delete(ctx, id); stopRinging(ctx, id)
remainingMs(ctx, timer): Long                                  // live remaining, never negative

// model/Alarm.kt  (id, hour, minute, daysMask, label, enabled, vibrate)
Alarm.newAlarm(hour, minute)        // enabled one-shot, vibrate=true
Alarm.bitForCalendarDay(Calendar.MONDAY..SUNDAY) -> Int   // bit0=Mon..bit6=Sun
Alarm.calendarDays(mask): List<Int>                       // Mon-first Calendar days
Alarm.summary(mask): String         // "Once"/"Weekdays"/"Weekends"/"Every day"/"Mon, Wed"
alarm.repeating: Boolean            // daysMask != 0

// model/TimerItem.kt  (id, durationMs, state, endsAtEpochMs, remainingMs, label)
states: STATE_IDLE / STATE_RUNNING / STATE_PAUSED / STATE_FINISHED

// time/TimerMath.kt
remainingMs(t, nowMs): Long; isExpired(t, nowMs): Boolean
display(ms): String                 // "MM:SS" or "H:MM:SS"

// permissions/PermissionsGate.kt
notificationsGranted/exactAlarmsGranted/dndPolicyAccessGranted/fullScreenIntentGranted/batteryExemptionGranted (ctx): Boolean
notificationsIntent(ctx)/exactAlarmsIntent(ctx)/dndIntent()/fullScreenIntentIntent(ctx)/batteryIntent(ctx): Intent

// util/Fmt.kt
time(ctx, epochMs): String          // locale short time
until(nowMs, targetMs): String      // "2 d 7 h 53 m"

// constants (Actions.kt): EXTRA_TAB on MainActivity = "clock"|"alarms"|"timers"
```

## File ownership (STRICT — touch ONLY your files)

| Owner | Files |
|---|---|
| core (done) | everything under `java/com/piercingxx/xxclock/{alarm,audio,data,model,notify,permissions,receiver,repo,scheduler,service,time,util}`, `ClockApp.kt`, `Actions.kt`, `Prefs.kt`, `ui/AlarmAlertActivity.kt`, `layout/activity_alarm_alert.xml`, `values/{strings_core,colors,themes}.xml`, `drawable/ic_stat_alarm.xml`, launcher mipmaps |
| **Agent A (UI)** | `ui/MainActivity.kt`, `ui/ClockFragment.kt`, `ui/AlarmsFragment.kt`, `ui/AlarmsAdapter.kt`, `ui/AlarmEditDialogFragment.kt` (or equivalent), `ui/TimersFragment.kt`, `layout/activity_main.xml`, `layout/fragment_clock.xml`, `layout/fragment_alarms.xml`, `layout/item_alarm.xml`, `layout/dialog_alarm_edit.xml`, `layout/fragment_timers.xml`, `menu/bottom_nav.xml`, `values/strings.xml`, any `drawable/ic_*`/`bg_*` you need |
| **Agent C (Widget+Setup)** | `widget/DigitalWidgetProvider.kt`, `layout/clock_widget.xml`, `xml/clock_widget_info.xml`, `values/strings_widget.xml`, `values/strings_setup.xml`, `drawable/widget_*`, `ui/SetupActivity.kt`, `layout/activity_setup.xml` |
| **Agent T (Tests)** | `test/java/com/piercingxx/xxclock/**` only |

**strings.xml rules:** Agent A owns `values/strings.xml` and must NOT define keys already in `strings_core.xml` (app_name, notif_*, action_snooze/dismiss/stop/add_minute, action_snooze_minutes, alert_default_label). Agent C uses ONLY `strings_widget.xml`/`strings_setup.xml`. Never edit another agent's resource files.

## Required behaviors

### Agent A — MainActivity + tabs
- `MainActivity`: `BottomNavigationView` switching 3 fragments via supportFragmentManager; reads `Actions.EXTRA_TAB` ("alarms" opens alarms tab — used by system next-alarm tap and widget).
- `ClockFragment`: large `TextClock` (format12Hour "h:mm", format24Hour "HH:mm"), date line below (`DateFormat.getDateFormat` or `DateUtils`), next-alarm card using `AlarmRepository.nextArmed()` showing label + `Fmt.time()` + `Fmt.until()` ("Work · Tue 07:00 · in 9 h 12 m"; hide card when null), a small analog/digital style toggle button persisting choice in SharedPreferences `"xx_clock_ui"` key `"clock_style"` ("digital"/"analog"; analog = `AnalogClock` view), updates next-alarm card in onResume.
- `AlarmsFragment`: RecyclerView of `AlarmRepository.getAll()`; rows show formatted time, `Alarm.summary(daysMask)`, label, vibrate icon, Material `SwitchMaterial` bound to enable/disable via `toggle()`; row click opens editor; long-press or swipe deletes via `delete()`; FAB creates `Alarm.newAlarm(nextRoundHalfHour)` and opens editor. Editor dialog: `TimePickerDialog` (or inline pickers), 7 day-toggle chips (Mon..Sun order, use `Alarm.bitForCalendarDay`), label `TextInputEditText`, vibrate switch, save → `AlarmRepository.save()`. Show snackbar "Alarm set for X" using `Fmt.until(now, AlarmRepository.scheduledFire(...))`.
- `TimersFragment`: preset chips 1/3/5/10 min + custom minutes via number input; running/paused timer cards showing `TimerMath.display(TimerRepository.remainingMs(...))`, label, pause/resume, reset, +1 min, delete buttons; tick with a single Handler at 500 ms while resumed, cancel in onPause; when a RUNNING timer hits 0 in UI just let the coordinator fire (no local ring logic).
- Theme: `Theme.XxClock`; dark-friendly colors already defined. Keep layouts simple ConstraintLayout/LinearLayout.

### Agent C — Widget + Setup
- `DigitalWidgetProvider` extends `AppWidgetProvider`; MUST expose `companion object { fun refreshAll(context: Context) }` that sends an explicit broadcast to itself with `AppWidgetManager.ACTION_APPWIDGET_UPDATE` (all ids) — core code calls this after every mutation.
- `onUpdate` builds RemoteViews from `layout/clock_widget.xml`: root layout bg `@color/widget_background`, corner radius via `bg_widget_rounded` drawable you create (`widget_` prefix allowed for drawables), a `TextClock` (large, format12 "h:mm", format24 "HH:mm", textColor @color/widget_text), a date `TextClock` (format12/24 "EEE, MMM d"), and a next-alarm `TextView` filled from `AlarmRepository.nextArmed()` (label + Fmt.time; GONE when none). `PendingIntent` opening MainActivity on tap. Use `android.widget.RemoteViews` only — no findViewById.
- `xml/clock_widget_info.xml`: minWidth 180dp, minHeight 60dp, resize both, updatePeriodMillis 0 (TextClock self-updates), previewLayout optional, widgetCategory home_screen.
- `SetupActivity`: checklist of PermissionsGate states as Material cards/rows, each with status dot + button launching the corresponding intent: Notifications, Exact alarms, DND access (labeled "Allow alarms through Do Not Disturb"), Full-screen intent, Battery optimization. Plus an INFO card: "Keep XX Clock OUT of Nope-Mode's blocked apps list — Nope-Mode suspends packages and would silence these alarms." Re-check states in onResume. Include a "Done" button finishing the activity.
- Manifest entries for both components ALREADY EXIST — do not modify AndroidManifest.xml.

### Agent T — Unit tests (pure JVM, JUnit4, no Robolectric, no Android classes)
- `NextOccurrenceTest`: one-shot today-future/today-past→tomorrow; weekly masks across week boundary; DST-gap safety (use ZoneId "America/New_York", 2024-03-10 spring-forward: 02:30 request resolves sanely without exception); monotonicity (result >= now); every-day mask finds tomorrow when today's time passed.
- `TimerMathTest`: remaining for all four states; expiry boundary (now == endsAt → expired); display formatting (0→"00:00", 59999→"01:00", 3600000→"1:00:00", 3661000→"1:01:01").
- `AlarmMaskTest`: bit mapping round-trip for all 7 days; summary() for Once/Weekdays/Weekends/Every day/custom.
- Construct ZonedDateTime explicitly; never call System.currentTimeMillis-dependent code without injecting nowMs (NextOccurrence.alarm takes ZonedDateTime — use it).

## Hard rules (all agents)
1. Original cleanroom code — no copied source/assets from AOSP DeskClock, Fossify, Google Clock.
2. Do NOT create/modify Gradle files, AndroidManifest.xml, themes/colors, or any file outside your ownership table.
3. No new dependencies. No coroutines/RxJava/Room/Hilt/Compose. org.json + androidx + material only.
4. Kotlin only. Compile-safe: reference ONLY APIs listed above or standard SDK/androidx/material APIs.
5. IDs referenced by core: none besides what's listed. If you need a string that exists in strings_core.xml, reuse the key.
