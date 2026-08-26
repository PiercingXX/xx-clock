# XX Clock

> It wakes you up and then shuts up.

Clock, alarms, timers. Cleanroom, offline, sideloaded onto **GrapheneOS**. No
Play Services, no analytics, no `INTERNET` permission — verifiable in the
manifest and in the built APK. It exists because every other clock app wants an
account, a subscription, or a network connection to tell you what time it is.

```
package: com.piercingxx.xxclock        minSdk 29 (Android 10)
version 1.0                            target/compileSdk 35
```

<img src="docs/images/screenshot.png" width="270" alt="XX Clock on a Pixel 6 — AMOLED Night, the family default">

## What it does ⏰

- **Alarms** — weekly recurrence, labels, vibrate, snooze, volume ramp,
  auto-silence at ten minutes. Scheduled with `setAlarmClock()`, so they fire
  through Doze and re-register on boot.
- **Per-alarm ringtone** — every alarm carries its own tone. Built-in and
  MediaStore tones always resolve; a storage tone keeps its read grant across
  reboots wherever the provider offers a persistable one. If that tone's URI
  is gone or unreadable, the player falls through to the next candidate
  instead of ringing silent. An alarm that makes no noise is not an alarm.
- **Timers** — presets and custom, several at once, wall-clock deadlines that
  survive process death.
- **Widget** — clock, date, next alarm. `TextClock` in RemoteViews, so there is
  no ticking service and no battery cost.
- **Eight themes**, shared across the suite: XX-Launcher broadcasts, every app
  repaints, Setup picks the same eight locally.
- **Rings through Do Not Disturb** — alarm audio on `STREAM_ALARM`, which DND
  allows by default, plus bypass channels once you grant **Do Not Disturb
  access**. Setup walks you through that and the rest of the checklist.

**The ground is a choice, never an observation.** XX Clock never reads system
dark mode, sunrise, or the time it is displaying. Set Paper at noon and it is
still Paper at midnight. Never chosen? AMOLED Night, the family default. The
full-screen alarm alert opts out entirely — ink ground, white digits, always.
3 a.m. is not the moment for a Paper screen.

## Keep it out of Nope-Mode ⚠

[Nope-Mode](https://github.com/PiercingXX/Nope-Mode) suspends packages as device
owner, and a suspended app cannot ring, notify, or launch. Do **not** put
`com.piercingxx.xxclock` in its blocked-apps list. Setup says so permanently, in
case you forget.

## Build 🛠️

JDK 21 running Gradle 8.11.1, JVM target 17, SDK platform 35. AGP 8.9.1, Kotlin
2.1.20.

```bash
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew assembleRelease       # -> app/build/outputs/apk/release/
./gradlew testDebugUnitTest     # 140 JVM unit tests
./gradlew lint                  # 0 errors
```

Sideload the APK, allow **Install unknown apps** for whatever opened it, then
walk **Setup** (gear, top right). Widget: long-press home → Widgets → XX Clock.

Everything that could be a pure function was written as one, so the recurrence
math, timer deadlines, ringtone fallback order and theme rules are all tested
without a device. No keystore is committed — release signing, the component map,
the theme authority rule, the force-dark fight and the v1 limitations all live
in [CONTRACT.md](CONTRACT.md).

## Under the hood 🧰

Single module, classic Views, Kotlin. SharedPreferences + `org.json` — no Room,
no Compose, no coroutines, no GMS. Brand tokens from
[piercingxx-branding](https://github.com/PiercingXX/piercingxx-branding): AMOLED
ink, Signal-white accent (the clock face *is* the accent), Space Mono and
JetBrains Mono bundled in `res/font/`.

Original implementation, specified from public documentation of Google Clock,
AOSP DeskClock, Fossify Clock and Alarmio. No source or assets copied.
