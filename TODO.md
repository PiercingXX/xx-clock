# XX-Clock — Remaining work

**2026-09-04.** P0–P3 **code** is landed. The remaining work **is** the
device smoke list. Do not invent features.

Package: `com.piercingxx.xxclock`  
Offline GrapheneOS clock. No `INTERNET`. No Room. No Compose.
Keep this package **out** of Nope-Mode.

```
Status: alarms/timers/widget/direct-boot/AlarmClock intents exist.
Device smoke never run. Test count drifts (TODO 140 vs README 154).
No CI.
```

---

## Locked now (2026-09-04)

| ID | Decision |
|---|---|
| C1 | Device smoke **is** the todo. Tick only on the Pixel, not from JVM. |
| C2 | Theme sender policy stays **documented-open** (CONTRACT). Do not lock unless a real abuse shows up. |

Known v1, leave documented: one-shot missed while powered off → tomorrow;
English-only day summaries.

---

## Device smoke (GrapheneOS)

Setup granted: notifications, exact alarms, DND, full-screen, battery
exemption.

### P0 — blocks 1.0

- [ ] Full-screen alert: Dismiss labeled on alarm, Stop labeled on timer
- [ ] Alarms tab: toggle off / on / off; disable then edit label then save — stays disabled
- [ ] One-shot → fire → snooze → Clock card **and** widget still show it
- [ ] Alarm ringing → reset a different timer → alarm still rings
- [ ] Reboot, leave locked, alarm in the next 2–3 minutes fires on the lock screen

### P1 — missed-alarm / stuck ring

- [ ] Timer ringing, then an alarm fires — alert shows the alarm, not a blank / finished timer
- [ ] Force-stop mid-ring (or kill the FGS) — audio resumes **or** missed notification, never silent-and-stuck
- [ ] Revoke exact alarms in Setup, save two alarms, reboot — app comes back, both still exist. Re-grant — they go exact again

### P2 — spot

- [ ] Custom tone from `/sdcard/Ringtones` still plays after reboot
- [ ] Theme broadcast from xx-launcher still paints

**Accept:** every P0 box dated with device + OS. Then this is 1.0.

---

## Housekeeping (not ship blockers)

- [ ] Align test count in this file and README after `./gradlew testDebugUnitTest`
- [ ] Add a CI workflow (test + lint) if sibling apps keep theirs
- [ ] Signed sideload when you want it daily; unsigned `assembleRelease` without a keystore stays the contract

---

## Stop conditions

- `INTERNET` / Room / Compose / GMS → reject.
- Putting this package in Nope-Mode → reject (it cannot ring).
- Ticking P0 from a laundry-bot Deliver → reject.
- “Fixing” CONTRACT known-limitations without reopening them here → reject.
