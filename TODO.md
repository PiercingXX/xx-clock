# TODO — XX Clock 1.0

Do not ship until every **P0** item is checked. P1 should land in the same
release if you can: they are still missed-alarm / crash-on-ring. P2/P3 can
follow. CONTRACT.md known-limitations at the bottom are **not** bugs unless
we decide they are.

Target: GrapheneOS, sideload, no INTERNET. An alarm that does not ring is a
failed 1.0.

> **Status (2026-08-26):** All P0–P3 code items, unit tests, and doc updates
> are landed (`./gradlew testDebugUnitTest` = 140 tests, lint clean). Direct
> boot also skips CE theme prefs while locked and migrates CE→DE on
> `USER_UNLOCKED` (process started locked). The **Device smoke** section
> below is the only thing left, and it needs the Pixel.

```
./gradlew testDebugUnitTest
./gradlew lint
```

---

## P0 — blocks 1.0

These fail in the product a user actually sees: unlabeled dismiss, a switch
that lies, a snooze that vanishes from the UI, a timer reset that kills an
alarm, and a reboot that eats the morning ring.

### 1. Label the full-screen Dismiss / Stop button

The lock-screen alert never sets text on `alert_stop`. Snooze / +1 min is
labeled; the control that stops the noise is a blank Material button.
TalkBack announces an unlabeled button. `action_dismiss` and `action_stop`
already exist and are used on the notification.

- [x] Alarm branch: `stopButton.setText(R.string.action_dismiss)`
- [x] Timer branch: `stopButton.setText(R.string.action_stop)`
- [ ] Or set `android:text` in the layout and override per branch

**Files:** `ui/AlarmAlertActivity.kt` (`onCreate`, around the `stopButton`
listener), `res/layout/activity_alarm_alert.xml` (`@id/alert_stop`),
`res/values/strings_core.xml`

**Done when:** alarm alert shows Dismiss, timer alert shows Stop, TalkBack
reads the same words.

### 2. Enable switch and editor stay in sync with the store

`AlarmsAdapter` compares the switch against a stale `current.enabled`.
`AlarmsFragment.onToggle` calls `AlarmRepository.toggle()` and never
`reload()`, so the bound `Alarm` keeps the old flag.

- Second tap: `alarm.enabled != checked` is false → `onToggle` is skipped,
  the switch lies.
- Disable → open editor → save writes `original.enabled` (the dialog has no
  enable control) and **re-enables** the alarm.

- [x] After toggle: `reload()` the list **or** `current = alarm.copy(enabled = checked)` before the callback
- [x] Editor save: persist `enabled` from the live store (or pass it through), not the row snapshot
- [x] Keep the mute-toggle guard so `bind()` does not re-fire `onToggle`

**Files:** `ui/AlarmsAdapter.kt` (`setOnCheckedChangeListener`),
`ui/AlarmsFragment.kt` (`onToggle` at line 24),
`ui/AlarmEditDialogFragment.kt` (`save()` copies `original` including
`enabled`)

**Done when:** toggle off, toggle on, toggle off again — all three persist.
Disable, change the label, save — alarm stays disabled.

### 3. Snoozed one-shot still counts as next alarm

`nextArmed` filters `it.enabled`. One-shots disable themselves at fire
(`AlarmCoordinator.fireAlarm`); snooze then `setAlarmClock`s a **disabled**
alarm. Recurring snooze is fine (stays enabled). Widget and Clock next-alarm
card hide a snoozed one-shot while the system next-alarm chip still shows it.
KDoc and CONTRACT already say snooze counts.

- [x] Treat an alarm as armed if `enabled || snoozedUntil > now`
- [x] Keep `maxOf(snoozedUntil, scheduledFire)` for the timestamp
- [x] Clock card and widget both go through `AlarmRepository.nextArmed()` — one fix covers both

**Files:** `repo/AlarmRepository.kt` (`nextArmed`),
`alarm/AlarmCoordinator.kt` (`fireAlarm` one-shot disable, `snooze`)

**Done when:** fire a one-shot, snooze it, Clock card and widget show that
snooze. Recurring snooze unchanged.

### 4. Resetting a timer must not kill a ringing alarm

`TimerRepository.reset()` always calls `AlarmCoordinator.stopTimer()`, and
`stopTimer` always `RingService.stop()` **before** looking at that timer.
Reset of any idle/paused/running timer while an alarm (or a different timer)
is ringing tears down the global ringer. Store state for the actual ringing
id stays `ringing=true`, so the alert poller will not finish — silent,
undismissed-looking full-screen.

`delete()` already does the right thing via `RingingGuard.stopIfRinging`.

- [x] Reset of a **non-ringing** timer: store update + `scheduleSoonestTimer` only
- [x] Only stop `RingService` when `ClockStore.isRinging(id)` for **this** id
- [x] Do not leave `ringing=true` on a different id

**Files:** `repo/TimerRepository.kt` (`reset`),
`alarm/AlarmCoordinator.kt` (`stopTimer` / `stopTimerInternal`)

**Done when:** alarm ringing + reset a different timer → alarm still rings.
Reset the ringing timer itself → ring stops and that timer goes IDLE.

### 5. Direct boot — ring on the lock screen after overnight reboot

The app is not `directBootAware`. `ClockStore` uses default
credential-encrypted SharedPreferences. `LOCKED_BOOT_COMPLETED` is in the
receiver filter and cannot run. AlarmManager registrations die across
reboot and only come back on `BOOT_COMPLETED` after first unlock.

A phone that reboots overnight (GrapheneOS update, dead battery) will not
ring at 6:00 on the lock screen. This is **not** in CONTRACT known
limitations. Doze is already handled (`setAlarmClock`).

- [x] Persist alarm definitions + schedule bookkeeping in
      `createDeviceProtectedStorageContext()` (`ClockStore`)
- [x] Mark `AlarmEventReceiver`, `RingService`, and `AlarmAlertActivity`
      `android:directBootAware="true"`
- [x] Reschedule from `LOCKED_BOOT_COMPLETED` (`reconcile(force=true)`)
- [x] Keep CE for UI prefs / theme if you want; alarms must not wait on unlock
- [x] Update CONTRACT.md: either document the new behavior or, if this slips,
      add it as a known limitation — do not ship the gap undocumented

**Files:** `AndroidManifest.xml` (application / receiver / service / alert
activity), `data/ClockStore.kt` (prefs context),
`receiver/AlarmEventReceiver.kt`, `alarm/AlarmCoordinator.kt` (`reconcile`)

**Done when:** reboot, leave locked, one-shot or recurring set before the
reboot fires on the lock screen at the scheduled time.

---

## P1 — still miss or crash a ring (same release if possible)

### 6. Full-screen alert handles a second fire (`onNewIntent`)

Alert is `singleTask` and never implements `onNewIntent`. Newest-wins
silences the previous id, the in-foreground poller `finish()`es because the
**old** id is no longer ringing, and the new FSI arrives as `onNewIntent`
with extras ignored. The new alarm/timer can lose its full-screen UI
(notification actions still work). Same race: timer ringing + alarm fires.

- [x] Implement `onNewIntent`: `setIntent(intent)`, rebind id / isAlarm /
      buttons, keep the poller on the **new** id
- [ ] Or drop `singleTask` and instantiate a fresh alert — pick one, don't mix

**Files:** `ui/AlarmAlertActivity.kt`, `AndroidManifest.xml`
(`launchMode="singleTask"`), `alarm/AlarmCoordinator.kt` (`silenceOtherRinger`)

**Done when:** while the alert is up, a second alarm (or a timer then an
alarm) shows the new label and the new buttons; old audio is gone.

### 7. Restart the ringer if the system kills the FGS

`RingService.onStartCommand` returns `START_NOT_STICKY`. A mid-ring kill is
not redelivered. Auto-silence lives on this service’s Handler, so a kill
leaves `isRinging=true` with no audio until the next process start, which
`reconcile(recoverRinging=true)` converts to a missed notification instead
of resuming the ring.

- [x] Return `START_REDELIVER_INTENT` after a successful `begin()`
- [x] Keep `START_NOT_STICKY` for `id <= 0` / bad action
- [x] `begin()` is already re-entrant (stops previous player) — keep that

**Files:** `service/RingService.kt` (`onStartCommand`)

**Done when:** killing the service process mid-ring either resumes audio or
still posts a missed notification — it must not sit silent with
`isRinging=true` and no UI.

### 8. Exact-alarm revoke must not crash reconcile

`scheduleAlarmAt` calls `setAlarmClock` with no `canScheduleExactAlarms()`
check and no `SecurityException` handler. Timers already degrade to
`setWindow`. If the user revokes exact-alarm access (Setup documents the
toggle), saving an alarm, recurring reschedule, or boot `reconcile` throws
on the `alarm-event` worker. That thread has no uncaught-exception handler;
one throw kills the process and aborts the rest of reconcile, so **all**
remaining alarms fail to re-register.

There is no receiver for `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`,
and Setup does not `reconcile()` after a grant.

- [x] Mirror the timer branch: `canScheduleExactAlarms()` → `setAlarmClock`,
      else inexact / `setWindow` plus a visible warning
- [x] Catch `SecurityException` so one alarm cannot abort the rest
- [x] On grant: Setup `onResume` and/or the system broadcast →
      `AlarmCoordinator.reconcile(force=true)`

**Files:** `scheduler/ExactScheduler.kt` (`scheduleAlarmAt`),
`ui/SetupActivity.kt` (`onResume`), `receiver/AlarmEventReceiver.kt` or a
new exported=false receiver for the permission-state broadcast,
`permissions/PermissionsGate.kt`

**Done when:** revoke exact-alarm, reboot, unlock — process stays up, every
alarm is at least inexactly scheduled, Setup shows the hole. Re-grant →
exact schedules come back without a force-stop.

---

## P2 — hardening

### 9. Theme broadcast: ignore strangers (or document it)

`ThemeSyncReceiver` is exported with no `android:permission` and no sender
check. CONTRACT intends this (xx-launcher targets the package). Any app can
send `xx.launcher.THEME_CHANGED` and flip night mode / recreate activities.
No data leaves the device; it can still drop an in-progress editor and spam
`setDefaultNightMode`.

Pick one:

- [ ] Ignore extras unless the sender is the known launcher package **or** a
      signature permission shared with xx-launcher
- [x] Or document in README/CONTRACT: any app on the device may restyle this
      clock

**Files:** `AndroidManifest.xml` (`ThemeSyncReceiver`),
`theme/ThemeSyncReceiver.kt`, CONTRACT.md family-theme section

### 10. Custom ringtone URI actually survives reboot

Picker result is stored as a raw URI string. No
`takePersistableUriPermission`, no `READ_MEDIA_AUDIO` /
`READ_EXTERNAL_STORAGE`. `KlaxonPlayer` catches `SecurityException` and
falls through, so this will not ring **silent** — but a pick from
`/sdcard/Ringtones` can quietly become the system default after reboot or
provider revocation. Weaker than the README claim that per-alarm custom
tones actually play.

- [x] If the picker grants persistable read, take it
- [x] Else copy the tone into app storage, **or** document that only
      system / MediaStore alarm tones are guaranteed

**Files:** `ui/AlarmEditDialogFragment.kt` (picker result),
`audio/KlaxonPlayer.kt`, README.md ringtone paragraph

### 11. Unique ids and PendingIntent request codes

Alarm/timer ids are `System.currentTimeMillis()`. PendingIntent request
codes are `id.toInt()` (low 32 bits). Identity also keys a single runtime
JSON map, so an alarm and a timer created in the same millisecond share
ringing / snooze / schedule state. Request-code collisions every 2^32 ms
(~49.7 days) can make `FLAG_UPDATE_CURRENT` overwrite another alarm’s fire
extras.

- [x] Monotonic counter in SharedPreferences (not wall clock)
- [x] Separate alarm vs timer namespaces, **or** different request-code high bits
- [x] PendingIntent identity unique per `(kind, id)`

**Files:** `model/Alarm.kt` (`newAlarm`), `model/TimerItem.kt` (`newTimer`),
`scheduler/ExactScheduler.kt` (`firePendingIntent`, `showIntent`),
`data/ClockStore.kt` (runtime map keyed by id)

### 12. Recurring reschedule is strictly after now

`fireAlarm` reschedules with `NextOccurrence.alarmMillis(...)` “at or after
now”. If fire runs at that exact wall instant, `setAlarmClock` is asked to
fire **now** again. The `goAsync`+worker delay usually skips this; it is
still the wrong bound for a just-fired alarm. Newly created alarms set to
the current minute should stay at-or-after.

- [x] Fire/reschedule path: `nowMillis = System.currentTimeMillis() + 1` (or
      a strictly-after API)
- [x] Create/edit path: keep at-or-after
- [x] Existing NextOccurrenceTest “candidate exactly equal to now is
      accepted” stays for the create path

**Files:** `alarm/AlarmCoordinator.kt` (`fireAlarm` recurring branch),
`time/NextOccurrence.kt`

### 13. Do not `prepare()` ringtones on the main thread

`MediaPlayer.prepare()` runs synchronously from `RingService.onStartCommand`
→ `begin()`. A slow or hung provider can ANR the ring path. Failures per
candidate are caught; a stall is not. Foreground has already started, so
this is latency/ANR, not an FGS-timeout miss by itself.

- [x] `prepareAsync()` + `OnPreparedListener`
- [x] Timeout → next `ringCandidates` entry

**Files:** `audio/KlaxonPlayer.kt`, `service/RingService.kt`

---

## P3 — polish

### 14. New-alarm editor title

Title uses `ARG_IS_NEW`, but `newInstance` never puts that extra, so FAB
creates always show “Edit alarm”. Harmless; `alarm_edit_new` is otherwise
only the FAB content description.

- [x] `putBoolean(ARG_IS_NEW, …)` from `AlarmsFragment`, or infer “new”
      (id not in the store yet)

**Files:** `ui/AlarmEditDialogFragment.kt` (`newInstance`, title at
`onCreateView`)

### 15. Cut memoir comments out of the implementation

Class-level KDoc on `ThemeSyncApplier` and the splash comments in
`values/themes.xml` narrate product history, rejected alternatives, and
on-device archaeology. That belongs in CONTRACT “Theme authority”. Keep
two-line WHYs in code (never `FOLLOW_SYSTEM`; revoke force-dark before the
alarm-alert early return).

- [x] Shorten `theme/ThemeSyncApplier.kt` KDoc
- [x] Shorten `res/values/themes.xml` splash comments
- [x] Leave the essay in CONTRACT.md

### 16. README test count

README says “95 JVM unit tests”. That number is mostly theme gray-ramp +
recurrence, not the fire path. After P0/P1 tests land, update the number
so it stays true.

- [x] `README.md` Build section: actual `./gradlew testDebugUnitTest` count

---

## Tests — add with the matching item, not as a pile at the end

Current coverage is good for pure functions (NextOccurrence including DST,
TimerMath, alarm mask, ring-candidate order, theme authority vs XML). There
is **no** JVM coverage of `AlarmCoordinator`, `ExactScheduler`,
`ClockStore`, boot reschedule, or the adapter toggle.

Keep the family rule: JUnit4, no Robolectric, seams instead of Android
mocks, same pattern as `ThemeSyncReceiverTest`.

- [x] **P0.2** Toggle then save does not re-enable (adapter / editor contract
      over a fake store)
- [x] **P0.3** One-shot disable-at-fire; snooze of a disabled one-shot is
      still armed in `nextArmed`; recurring snooze unchanged
- [x] **P0.4** Reset of a non-ringing timer does not stop a ringing alarm;
      reset of the ringing timer does
- [x] **P0.5** Reconcile with `force=true` after “reboot” re-registers every
      enabled / snoozed alarm (fake scheduler)
- [x] **P1.6** Newest-wins: second fire rebinds the alert id (if you can
      drive it without Robolectric; otherwise a coordinator test that the
      previous id is not ringing and the new one is)
- [x] **P1.8** `scheduleAlarmAt` without exact permission does not throw;
      remaining alarms still schedule
- [x] **P2.11** Two creates in the same millisecond get distinct ids;
      alarm id and timer id cannot collide in the runtime map
- [x] **P2.12** Fire/reschedule of a recurring alarm at the exact wall
      instant schedules the **next** occurrence, not now

---

## Device smoke (GrapheneOS)

Do this on the Pixel (or whatever is the daily driver), not the emulator.
Setup checklist granted: notifications, exact alarms, DND, full-screen,
battery exemption. Keep `com.piercingxx.xxclock` **out** of Nope-Mode.

P0

- [ ] Full-screen alert: Dismiss is labeled, Stop is labeled on a timer
- [ ] Alarms tab: toggle off / on / off; disable then edit label then save
      — stays disabled
- [ ] One-shot → fire → snooze → Clock card **and** widget still show it
- [ ] Alarm ringing → reset a different timer → alarm still rings
- [ ] Reboot, leave locked, alarm in the next 2–3 minutes fires on the lock
      screen

P1

- [ ] Timer ringing, then an alarm fires — alert shows the alarm, not a
      blank / finished timer
- [ ] Force-stop mid-ring (or kill the FGS) — audio resumes **or** missed
      notification, never silent-and-stuck
- [ ] Revoke exact alarms in Setup, save two alarms, reboot — app comes
      back, both still exist. Re-grant — they go exact again

P2 (spot)

- [ ] Custom tone from `/sdcard/Ringtones` still plays after reboot
- [ ] Theme broadcast from xx-launcher still paints; a random app should
      not (if you locked the sender)

---

## Docs that move with the code

- [x] CONTRACT.md: `nextArmed` includes snoozed one-shots (if the API
      comment is the contract) — already stated (“soonest upcoming ring incl.
      snoozed”), verified, no edit needed
- [x] CONTRACT.md: direct-boot behavior **or** known limitation (P0.5)
- [x] CONTRACT.md: ThemeSyncReceiver sender policy (P2.9)
- [x] README.md: custom ringtone guarantee (P2.10)
- [x] README.md: test count (P3.16)

---

## Known v1 — leave documented, do not “fix” in this pass

From CONTRACT.md. Not in the P0–P3 lists unless we reopen them.

- One-shot alarms whose fire window passes while the device is powered off
  are silently rescheduled to tomorrow. Recurring alarms keep the correct
  next occurrence.
- Day summaries are English-only.

Also already true, do not churn:

- No INTERNET permission. No Room, no coroutines, no Compose, no GMS.
- `isMinifyEnabled = false`. Empty `proguard-rules.pro` is unused.
- No keystore in git. `keystore.properties` is gitignored; unsigned
  `assembleRelease` without credentials is the contract.
- `AlarmAlertActivity` stays always-dark. Widget / notification colors stay
  `values/`-only.
- Force-invert colours is a **device** accessibility setting, not an app
  bug. Check that before touching `theme/`.
)
