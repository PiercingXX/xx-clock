# xx-clock — Build Contract (v1)

Package: `com.piercingxx.xxclock` · minSdk 29 · target/compile 35 · Kotlin 2.1.20 · classic Views · Material3.
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

## Family theme sync (post-v1 maintenance addition)

Receiver side of the family-wide theme sync driven by the xx-launcher. The
broadcast is explicitly targeted at this package:

- Action `xx.launcher.THEME_CHANGED`
- String extra `xx.launcher.extra.THEME_NAME`: "AMOLED Night" / "Graphite" /
  "Forest Night" / "Ocean Drift" / "Burgundy" (dark, white foreground),
  "Paper" / "Mist" (light, ink #FF1A1A1A foreground), or "Custom".
- Int extra `xx.launcher.extra.BACKGROUND`: resolved ground ARGB (present even
  for Custom).
- Contrast rule (identical across the family): luminance
  `0.299r + 0.587g + 0.114b` > 182 → dark foreground #FF1A1A1A, else white.

The preset drives the app's GROUND. XX Clock already renders both looks —
Paper/Mist day colors in `values/`, a dark flip in `values-night/` — so a dark
preset flips the app to its night look (`AppCompatDelegate.MODE_NIGHT_YES`)
and the exact preset ground is painted over window background, root content,
and status/nav bars; Paper/Mist flip to the day look (whose colors already ARE
Paper and Mist). "Custom" is honored via the BACKGROUND extra + contrast rule.
`AlarmAlertActivity` keeps its purpose-built always-dark look; widget and
notification surfaces are out of scope.

The night resource set is selected by the app's own pinned night mode, never by
the OS. See **Theme authority** at the end of this document — that section is a
hard rule and overrides any reading of the paragraph above.

Files (all under `theme/`):
- `ThemePreset.kt` — pure-Kotlin model: 7 presets (`fromKey`/`fromDisplayName`),
  contrast rule (`luminance`/`prefersDarkForeground`/`foregroundFor`),
  `SyncedTheme` + `resolveSyncedTheme(name, backgroundExtra)`.
- `ThemeStore.kt` — persistence over the `ThemeKeyValueStore` seam; real store
  is SharedPreferences `xx_clock_theme`.
- `ThemeSyncReceiver.kt` — manifest-declared, exported, injectable seams
  (action/name/background extractors, persist, live-apply) so JVM tests drive
  `onReceive` without mocking Android.
- `ThemeSyncApplier.kt` — night mode + ground painting on activity
  post-create/resume via `ActivityLifecycleCallbacks` registered in `ClockApp`;
  repaints visible activities immediately when a broadcast lands. Also owns the
  authority rules: `DEFAULT_THEME` (AMOLED Night), `effectiveTheme(stored)`
  (never null), `activeTheme(context)`, `nightModeFor(theme)` (YES/NO only),
  and `disableForceDark(window)` (applied to every activity and dialog window).

This addition amends hard rule 2: the manifest gained the exported
`.theme.ThemeSyncReceiver` entry and `app/build.gradle.kts` gained
`testOptions.unitTests.isReturnDefaultValues = true` (family convention, from
TxxT) so JVM tests can instantiate the `BroadcastReceiver` stub. Still no new
dependencies, no INTERNET permission (receiving carries no data out).

The theme-authority fix amends hard rule 2 further: `values/themes.xml` gained
the `Theme.XxClock.AlarmAlert` style and `android:forceDarkAllowed=false`,
`values-night/themes.xml` gained the same opt-out, and the manifest's
`AlarmAlertActivity` entry gained `android:theme`. Still no new dependencies.

Tests (JUnit4, pure JVM, seams instead of Robolectric):
- `theme/ThemePresetTest`: all 7 display names + case-insensitive + unknown/null;
  stable keys; ground values; dark/light classification vs the contrast rule;
  luminance weights + exclusive-182 threshold; `resolveSyncedTheme` for named /
  Custom-dark / Custom-light / Custom-without-background / unknown.
- `theme/ThemeSyncReceiverTest`: manifest wiring (component declared, exported,
  action filter, class resolves); persistence routing via seams (named preset,
  Paper, Custom via contrast rule, wrong action, unknown name); live-apply
  receives exactly the persisted theme; `ThemeStore` round-trips.
- `theme/ThemeSyncApplierTest`: the authority rule — unset resolves to AMOLED
  Night (directly and through an empty `ThemeStore`); a chosen theme always
  wins; a broadcast survives a store round-trip unchanged; night mode is a
  function of `isDark` alone; a light pick pins `MODE_NIGHT_NO` (the
  launcher-Paper-under-system-dark case); nothing — 7 presets, the default, and
  the full 256-step gray ramp as Custom grounds — resolves to
  `MODE_NIGHT_FOLLOW_SYSTEM`. Plus the declarations the rule leans on, asserted
  against the real XML/source: `forceDarkAllowed=false` in both theme configs,
  the alarm alert pinned to a non-DayNight style with no night override, the
  decor-view force-dark revocation reaching every activity *before* the
  alarm-alert early return, and the dialog-window guard.

## Theme authority (hard rule)

The ground is a choice, never an observation. Exactly two inputs may move it:

1. An `xx.launcher.THEME_CHANGED` broadcast.
2. An in-app pick in Setup → Theme, which resolves to the identical
   `SyncedTheme` and goes down the identical persist-and-repaint path.

Last writer wins between the two. There is no "manual beats sync" precedence
(that is TxxT's model, not this one).

The system's day/night state is NOT an input, at any point, on any surface.
Not the system dark-mode toggle, not an auto-dark sunrise/sunset schedule, not
the time the app happens to be displaying. A theme set at noon renders
identically at midnight.

Consequences any future change must preserve:

- Nothing chosen yet (fresh install, cleared data) resolves to
  `ThemeSyncApplier.DEFAULT_THEME` = **AMOLED Night**, the family default. The
  old behavior — falling through to the `DayNight` default and tracking the
  system — was the bug, reproduced on-device as Paper ground in system day mode
  and black in system night mode with no user choice involved.
- `AppCompatDelegate` is never left on `MODE_NIGHT_FOLLOW_SYSTEM`.
  `ThemeSyncApplier.nightModeFor` is total over `SyncedTheme.isDark` and has no
  null/unknown branch that could return it.
- `values-night/` stays, as the resource mechanism for a dark theme. Its
  selector is the app's own pinned night mode, i.e. the stored theme's
  `isDark`; the uiMode qualifier is an internal switch, not an OS signal.
- Force dark is revoked at the **window** level: every window this app opens
  clears `View.setForceDarkAllowed` on its decor view — every activity
  (`AlarmAlertActivity` included, before the ground-painting early return) and
  every dialog, via a recursive `FragmentLifecycleCallbacks` guard. A window
  added anywhere in this app must do the same; a `PopupWindow` or a bare
  `Dialog` would be a new, unguarded window.
- Both theme configs also set `android:forceDarkAllowed=false`. Keep both.
  The theme attribute is declaration-level intent and was NOT sufficient in
  practice: on a Pixel 6 / GrapheneOS, a correctly stored, resolved and painted
  Paper ground still rendered lightness-inverted under system dark mode with
  the attribute compiled into both style variants. Do not "fix" a future
  recurrence by adding `android:isLightTheme=false` — that attribute feeds the
  same `ViewRootImpl.updateForceDarkMode()` lookup that already failed to see
  our value. The decor-view flag works one layer lower, on the RenderNode, and
  is the enforcement.
- If the decor-view flag ever fails too, the only remaining move is to stop
  handing the platform a light window at all: drop `values-night/`, ship one
  never-`isLightTheme` resource set, and repaint every colored surface
  programmatically from the resolved theme — xx-calculator's model. That is a
  much larger change (every view in 3 fragments and 4 layouts needs a palette
  callback) and should be a deliberate, separate piece of work, not a patch.
- `AlarmAlertActivity` is pinned to `Theme.XxClock.AlarmAlert`
  (parent `Theme.Material3.Dark.NoActionBar`, no `values-night/` override) so
  its Material chrome matches its hardcoded ink ground. Constant by design,
  with no ambient input of its own.
- Widget and notification surfaces draw only from `values/`-only colors
  (`widget_background`, `widget_text`, `widget_secondary`, `background_dark`,
  `surface_dark`), so they do not vary by configuration either. Do not add
  `values-night/` overrides for those names.

### The device setting that beats all of the above

Both opt-out layers can be defeated from outside the app. Android's
accessibility **force-invert colours** (`accessibility_force_invert_color_enabled`
in the `secure` namespace) inverts light content app-wide, and because it is an
accessibility feature it deliberately outranks anything an app declares — the
theme attribute and the decor-view flag are both ignored while it is on.

Observed on the Pixel 6 test device: with a launcher-set Paper ground the app
painted `#F3EEE2` correctly and the screen still rendered `rgb(22, 18, 5)`, the
same cream inverted, with the ink text flipped to white. xx-calculator — which
already ships a single ink resource set and repaints programmatically, the
escalation described above — inverted to the identical value. Two unrelated
apps, one transform: that is the tell that the cause is the device, not the app.

The per-app exclusion list lives in the `system` namespace:

```sh
adb shell settings get system accessibility_force_invert_color_override_packages_to_disable
```

The family packages were appended to it. **This lives in device settings, not in
this repo**, so a factory reset, a new phone, or a re-flash brings the symptom
straight back and it will look like a regression in this code. Check that
setting before changing anything in `theme/`.

## Ringtone fallback — never ring silent

Each alarm carries its own tone, chosen through the system alarm picker
(built-in tones plus anything in `/sdcard/Ringtones`). At ring time
`audio/RingCandidates.kt` builds the candidate list in a fixed order — chosen
tone → system alarm default → notification default — dropping blanks and
duplicates as it goes. A tone whose file was deleted or whose provider vanished
falls through to the next candidate, so no alarm can ring silent because of a
stale pick.

`RingCandidates.kt` has no `android.*` imports on purpose: the ordering rule is
a pure function and is asserted on the JVM rather than hoped at on a device.
Keep it that way — any new candidate source goes into the list, not into
`KlaxonPlayer`.

## Release signing

No keystore is committed to this repo. Release builds are signed only if
credentials are supplied by one of two routes:

- a gitignored `keystore.properties` at the repo root, with `storeFile`,
  `storePassword`, `keyAlias`, `keyPassword`; or
- the environment variables `XXCLOCK_STORE_FILE`, `XXCLOCK_STORE_PASSWORD`,
  `XXCLOCK_KEY_ALIAS`, `XXCLOCK_KEY_PASSWORD`.

With neither, `assembleRelease` produces an **unsigned** APK. Sign it yourself
with `apksigner`, or sideload the debug build for testing. Do not add a keystore
or its passwords to the repo to make this more convenient.

## Known limitations (v1)

- One-shot alarms whose fire window passes while the device is powered off are
  silently rescheduled to tomorrow. Recurring alarms always keep their correct
  next occurrence.
- Day summaries are English-only.
