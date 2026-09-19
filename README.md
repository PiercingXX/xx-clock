# XX-Clock

> It wakes you up and then shuts up.

Clock, alarms, timers. Offline. No Play Services, no analytics, no `INTERNET`.
Every other clock app wants an account, a subscription, or a network
connection to tell you what time it is.

<img src="docs/images/screenshot.png" width="270" alt="XX Clock on a Pixel 6 — AMOLED Night">

```
package: com.piercingxx.xxclock    minSdk 29
```

- Alarms: weekly, labels, vibrate, snooze, volume ramp, auto-silence at ten
  minutes. `setAlarmClock()` — fires through Doze, re-registers on boot.
- Per-alarm ringtone. If that URI is gone, it falls through. An alarm that
  makes no noise is not an alarm.
- Timers: several at once. Deadlines survive process death.
- Widget: clock, date, next alarm. No ticking service.
- Rings through Do Not Disturb on `STREAM_ALARM`.

The ground is a choice, never an observation. Set Paper at noon and it is
still Paper at midnight. The full-screen alert is always ink. 3 a.m. is not
the moment for a Paper screen.

**Do not put this package in Nope-Mode.** A suspended app cannot ring.

## Build

```sh
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew assembleRelease
./gradlew testDebugUnitTest
```

[CONTRACT.md](CONTRACT.md) is the rest. Cleanroom — Google Clock, AOSP
DeskClock, Fossify, Alarmio as public docs. No source copied.
