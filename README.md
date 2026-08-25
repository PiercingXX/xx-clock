# XX Clock

> An alarm clock that answers to you, not to a cloud.

A cleanroom, offline-first clock / alarm / timer app for Android, built to be
sideloaded onto **GrapheneOS**. No Google Play Services, no INTERNET permission,
no analytics — verifiable in the manifest.

```
package: com.piercingxx.xxclock        minSdk 29 (Android 10)
version 1.0                            target/compileSdk 35
```

## Features

- **Clock tab** — large digital clock (`TextClock`), date, next-alarm card
  ("Work · Tue 07:00 · in 9 h 12 m"), analog/digital style toggle.
- **Home-screen widget** — self-updating digital clock + date + next-alarm line.
  Zero battery cost (RemoteViews `TextClock`, no ticking service).
- **Alarms** — weekly recurrence (Mon-first day chips), labels, vibrate,
  one-shot or repeating; snooze (10 min) and dismiss from the full-screen
  alert or the notification; gradual volume ramp; auto-silence after 10 min;
  "Alarm set for …" confirmation snackbar.
- **Timers** — presets (1/3/5/10 min) + custom duration; multiple simultaneous
  timers; pause/resume/+1 min/reset; wall-clock deadlines survive process death;
  "+1 min" and "Stop" on the ringing notification.
- **Reliability design**
  - Alarms scheduled via `AlarmManager.setAlarmClock()` — exact, fires through
    Doze, feeds the system next-alarm indicator, no exact-alarm permission gate.
  - Timers via `setExactAndAllowWhileIdle` with `setWindow` fallback.
  - Full re-registration on `BOOT_COMPLETED` / package update; reconciliation on
    time-set / timezone-change / app start.
  - Ringing runs in a foreground service (`specialUse`) holding a partial
    wakelock; any failure degrades to a "missed" notification instead of a crash.
- **DND survival (belt and braces)**
  1. Alarm audio plays on `USAGE_ALARM` / `STREAM_ALARM`, which Do Not Disturb
     allows through by default (same as Pixel Clock).
  2. Notification channels are created with `setBypassDnd(true)` — effective once
     you grant the app **Do Not Disturb access** (Setup screen walks you through it).

## Nope-Mode integration

[Nope-Mode](https://github.com/PiercingXX/Nope-Mode) suspends packages via device
owner (`setPackagesSuspended`) and exposes **no pause/override API** — a suspended
app cannot ring, notify, or launch. Therefore:

> **Keep XX Clock OUT of Nope-Mode's blocked-apps list.**

The Setup screen shows this warning permanently. Structurally, XX Clock is as
alarm-proof as Android allows: `setAlarmClock()` scheduling, full-screen-intent
alert over the lock screen, bypass-DND channels, and optional battery-optimization
exemption. Nope-Mode's own zen rule deliberately leaves the alarm category alone,
so the two compose cleanly when the clock isn't suspended.

## Sideload onto GrapheneOS

1. Build the APK (see below) and copy it to the phone (USB, Syncthing, etc.).
2. Open it from **Files** (or Vanadium). When prompted, allow
   **Install unknown apps** for that source — one-time per-app grant.
3. Install. First launch asks for notification permission — allow it. Then open
   **XX Clock → Setup** (gear icon, top right) and walk the checklist:
   - Notifications — allow
   - Exact alarms — should already be granted (`USE_EXACT_ALARM`)
   - **Do Not Disturb access** — grant so alarms break through DND reliably
   - Full-screen alarm display — enabled by default for sideloaded alarm apps
   - Battery optimization — recommended to exempt
4. Add the widget: long-press home screen → Widgets → XX Clock.
5. In Nope-Mode: do **not** add `com.piercingxx.xxclock` to blocked apps.

## Build from source

Toolchain: JDK 17, Android SDK platform 35, Gradle 8.11.1
(AGP 8.9.1, Kotlin 2.1.20).

```bash
./gradlew assembleRelease       # -> app/build/outputs/apk/release/
./gradlew testDebugUnitTest     # 37 JVM unit tests (recurrence/timer/mask math)
./gradlew lint                  # 0 errors, 0 warnings
```

Signing: no keystore is committed to this repo. Release builds are signed only
if you supply credentials via a gitignored `keystore.properties` at the repo
root (`storeFile` / `storePassword` / `keyAlias` / `keyPassword`) or via the
environment variables `XXCLOCK_STORE_FILE` / `XXCLOCK_STORE_PASSWORD` /
`XXCLOCK_KEY_ALIAS` / `XXCLOCK_KEY_PASSWORD`. Without either, the release APK
is built unsigned — sign it yourself with `apksigner`, or sideload the debug
build for testing.

## Architecture notes

Single module, classic Views, Kotlin. Persistence is SharedPreferences +
org.json (no Room). See `CONTRACT.md` for the component map:

- `time/NextOccurrence.kt` — pure weekly-recurrence math (DST-safe)
- `data/ClockStore.kt` — definitions + per-alarm runtime state (scheduled/snoozed/ringing)
- `scheduler/ExactScheduler.kt` — all AlarmManager access
- `alarm/AlarmCoordinator.kt` — fire/snooze/dismiss/reconcile state machine
- `service/RingService.kt` + `audio/KlaxonPlayer.kt` — CATEGORY_ALARM
  full-screen-intent notification, alarm-stream audio, vibration
- `widget/DigitalWidgetProvider.kt` — RemoteViews widget
- `ui/SetupActivity.kt` — permission checklist + Nope-Mode advisory

## Branding

Follows the [PiercingXX brand system](https://github.com/PiercingXX/piercingxx-branding):
AMOLED Ink background, Signal-white accent (the clock face *is* the accent),
white/black opacity ramps for hierarchy, Space Mono display + JetBrains Mono
body (bundled in `res/font/`), and the underlined-XX logomark as the launcher
icon. Tokens live in `res/values/colors_brand.xml` — consumed, not retyped.

## Cleanroom statement

Original implementation. Behavior was specified from public documentation of
Google Clock (Pixel), AOSP DeskClock, Fossify Clock and Alarmio; no source code
or assets were copied from any of them. Algorithms (weekday-mask recurrence,
wall-clock timer deadlines, boot reconciliation) follow publicly documented
platform patterns.

## Known limitations (v1)

- One-shot alarms whose fire window passes while powered off are silently
  rescheduled to tomorrow (recurring alarms always keep their next occurrence).
- Alarm sound picker not yet exposed — uses the system default alarm sound.
- English-only day summaries.
