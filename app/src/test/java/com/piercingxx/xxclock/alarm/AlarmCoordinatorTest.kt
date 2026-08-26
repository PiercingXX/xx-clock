package com.piercingxx.xxclock.alarm

import android.content.Context
import com.piercingxx.xxclock.data.ClockStoreBackend
import com.piercingxx.xxclock.model.Alarm
import com.piercingxx.xxclock.model.TimerItem
import com.piercingxx.xxclock.repo.AlarmRepository
import com.piercingxx.xxclock.repo.TimerRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.TimeZone

/** In-memory [ClockStoreBackend]: definitions plus scheduled/snoozed/ringing runtime. */
private class FakeBackend : ClockStoreBackend {
    val alarmRows = LinkedHashMap<Long, Alarm>()
    val timerRows = LinkedHashMap<Long, TimerItem>()
    private val scheduledFire = mutableMapOf<Long, Long>()
    private val snoozedUntil = mutableMapOf<Long, Long>()
    private val ringing = mutableMapOf<Long, Boolean>()

    override fun alarms(): List<Alarm> = alarmRows.values.sortedWith(compareBy({ it.hour }, { it.minute }))
    override fun getAlarm(id: Long): Alarm? = alarmRows[id]
    override fun saveAlarm(alarm: Alarm) {
        alarmRows[alarm.id] = alarm
    }

    override fun deleteAlarm(id: Long) {
        alarmRows.remove(id)
    }

    override fun timers(): List<TimerItem> = timerRows.values.toList()
    override fun getTimer(id: Long): TimerItem? = timerRows[id]
    override fun saveTimer(timer: TimerItem) {
        timerRows[timer.id] = timer
    }

    override fun deleteTimer(id: Long) {
        timerRows.remove(id)
    }

    override fun scheduledFire(id: Long): Long = scheduledFire[id] ?: 0L
    override fun setScheduledFire(id: Long, atMs: Long) {
        scheduledFire[id] = atMs
    }

    override fun clearScheduledFire(id: Long) {
        scheduledFire.remove(id)
    }

    override fun snoozedUntil(id: Long): Long = snoozedUntil[id] ?: 0L
    override fun setSnoozedUntil(id: Long, untilMs: Long) {
        snoozedUntil[id] = untilMs
    }

    override fun isRinging(id: Long): Boolean = ringing[id] == true
    override fun setRinging(id: Long, ringing: Boolean) {
        this.ringing[id] = ringing
    }

    override fun ringingId(): Long? = ringing.entries.firstOrNull { it.value }?.key
    override fun clearRuntime(id: Long) {
        scheduledFire.remove(id)
        snoozedUntil.remove(id)
        ringing.remove(id)
    }
}

/**
 * Drives the real fire/snooze/reset lifecycles on the JVM through the
 * coordinator's collaborator seams (in-memory store, recorder scheduler,
 * recorded ring-service stops) — no Robolectric, no Android mocks.
 *
 * Covers TODO P0.3 (one-shot disable-at-fire + snoozed one-shot stays armed),
 * P0.4 (resetting a timer must not kill a ringing alarm) and P2.12 (recurring
 * reschedule strictly after now).
 */
class AlarmCoordinatorTest {

    /**
     * A throwaway Context instance: the stub constructor chain is inert under
     * `isReturnDefaultValues` (same mechanism that lets ThemeSyncReceiverTest
     * instantiate a BroadcastReceiver), and its members are never touched
     * because every collaborator is seamed.
     */
    private val ctx: Context = android.content.ContextWrapper(null)

    private lateinit var store: FakeBackend
    private val ringStops = mutableListOf<Int>()
    private val ringStarts = mutableListOf<Long>()
    private val alarmSchedules = mutableListOf<Pair<Long, Long>>()
    private var scheduleTimerCalls = 0
    private var nowMs = NOW

    // Production seam wiring, captured before overriding and restored after.
    private lateinit var production: AlarmCoordinatorSeams

    private class AlarmCoordinatorSeams(
        val storeOf: (Context) -> ClockStoreBackend,
        val clock: () -> Long,
        val ringStart: (Context, String, Long) -> Boolean,
        val ringStop: (Context) -> Unit,
        val scheduleAlarm: (Context, Alarm, Long) -> Unit,
        val scheduleTimers: (Context) -> Unit,
        val refreshWidgets: (Context) -> Unit,
    )

    @Before
    fun installFakes() {
        production = AlarmCoordinatorSeams(
            AlarmCoordinator.storeOf,
            AlarmCoordinator.clock,
            AlarmCoordinator.ringStart,
            AlarmCoordinator.ringStop,
            AlarmCoordinator.scheduleAlarm,
            AlarmCoordinator.scheduleTimers,
            AlarmCoordinator.refreshWidgets,
        )
        store = FakeBackend()
        AlarmCoordinator.storeOf = { store }
        AlarmCoordinator.clock = { nowMs }
        AlarmCoordinator.ringStart = { _, _, id -> ringStarts += id; true }
        AlarmCoordinator.ringStop = { _ -> ringStops += 1 }
        AlarmCoordinator.scheduleAlarm = { _, alarm, at ->
            // Mirrors ExactScheduler.scheduleAlarmAt's persisted bookkeeping.
            alarmSchedules += alarm.id to at
            store.setScheduledFire(alarm.id, at)
        }
        AlarmCoordinator.scheduleTimers = { _ -> scheduleTimerCalls += 1 }
        AlarmCoordinator.refreshWidgets = { _ -> }
    }

    @After
    fun restoreProductionWiring() {
        AlarmCoordinator.storeOf = production.storeOf
        AlarmCoordinator.clock = production.clock
        AlarmCoordinator.ringStart = production.ringStart
        AlarmCoordinator.ringStop = production.ringStop
        AlarmCoordinator.scheduleAlarm = production.scheduleAlarm
        AlarmCoordinator.scheduleTimers = production.scheduleTimers
        AlarmCoordinator.refreshWidgets = production.refreshWidgets
    }

    private fun oneShot(id: Long) =
        Alarm(id = id, hour = 7, minute = 30, daysMask = 0, label = "", enabled = true, vibrate = true)

    // ---- P0.3: one-shot disable-at-fire, snoozed one-shot still armed ----

    @Test
    fun `a one-shot alarm disables itself at fire`() {
        store.saveAlarm(oneShot(101))

        AlarmCoordinator.fireAlarm(ctx, 101)

        assertFalse("the fired one-shot must disable itself", store.getAlarm(101)!!.enabled)
        assertTrue(store.isRinging(101))
        assertEquals(listOf(101L), ringStarts)
        assertTrue("a one-shot must not re-arm a next occurrence", alarmSchedules.isEmpty())
    }

    @Test
    fun `a snoozed one-shot is still armed although disabled`() {
        store.saveAlarm(oneShot(101))
        nowMs = FIRE_AT
        AlarmCoordinator.fireAlarm(ctx, 101)

        nowMs = FIRE_AT + 30_000
        AlarmCoordinator.snooze(ctx, 101, minutes = 10)

        assertFalse(store.getAlarm(101)!!.enabled)
        val next = AlarmRepository.nextArmed(
            alarms = store.alarms(),
            snoozedUntil = store::snoozedUntil,
            scheduledFire = store::scheduledFire,
            nowMs = nowMs,
        )
        assertEquals(101L, next?.first?.id)
        assertEquals(FIRE_AT + 30_000 + 10 * 60_000L, next?.second)
    }

    @Test
    fun `a recurring alarm's snooze leaves the definition armed and books the wake-up`() {
        val weekly = Alarm(202, hour = 6, minute = 15, daysMask = Alarm.ALL_DAYS_MASK, label = "", enabled = true, vibrate = true)
        store.saveAlarm(weekly)
        store.setRinging(202, true)
        nowMs = FIRE_AT

        AlarmCoordinator.snooze(ctx, 202, minutes = 5)

        assertTrue("recurring snooze must not touch the enabled flag", store.getAlarm(202)!!.enabled)
        assertEquals(Alarm.ALL_DAYS_MASK, store.getAlarm(202)!!.daysMask)
        assertFalse(store.isRinging(202))
        assertEquals(FIRE_AT + 5 * 60_000L, store.snoozedUntil(202))
        assertEquals(listOf(202L to FIRE_AT + 5 * 60_000L), alarmSchedules)
    }

    // ---- P0.4: resetting a timer must not kill a ringing alarm ----

    @Test
    fun `resetting a non-ringing timer does not stop the ringing alarm`() {
        store.saveAlarm(oneShot(404))
        store.setRinging(404, true) // an alarm is mid-ring
        store.saveTimer(TimerItem(505, durationMs = 60_000, state = TimerItem.STATE_RUNNING, endsAtEpochMs = NOW + 30_000, remainingMs = 0, label = ""))

        TimerRepository.reset(ctx, 505)

        assertTrue("reset of an idle/paused/running timer must never stop the ringer", ringStops.isEmpty())
        assertTrue("the ringing alarm's flag must survive another timer's reset", store.isRinging(404))
        val timer = store.getTimer(505)!!
        assertEquals(TimerItem.STATE_IDLE, timer.state)
        assertEquals(60_000L, timer.remainingMs)
        assertEquals(0L, timer.endsAtEpochMs)
        assertTrue(scheduleTimerCalls > 0)
    }

    @Test
    fun `resetting the ringing timer itself stops the ring and goes idle`() {
        store.saveTimer(TimerItem(606, durationMs = 45_000, state = TimerItem.STATE_FINISHED, endsAtEpochMs = 0, remainingMs = 0, label = ""))
        store.setRinging(606, true)

        TimerRepository.reset(ctx, 606)

        assertEquals(1, ringStops.size)
        assertFalse(store.isRinging(606))
        assertEquals(TimerItem.STATE_IDLE, store.getTimer(606)!!.state)
        assertEquals(45_000L, store.getTimer(606)!!.remainingMs)
    }

    // ---- P2.12: recurring reschedule strictly after now ----

    @Test
    fun `firing a recurring alarm at its exact wall instant re-arms the next occurrence`() {
        val originalTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            // Fixed instants far from any plausible wall clock: the production
            // path must take its "now" purely from the seamed clock.
            val firedAt = ZonedDateTime.of(2033, 3, 17, 7, 7, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
            val nextMorning = ZonedDateTime.of(2033, 3, 18, 7, 7, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
            nowMs = firedAt
            store.saveAlarm(Alarm(303, hour = 7, minute = 7, daysMask = Alarm.ALL_DAYS_MASK, label = "", enabled = true, vibrate = true))

            AlarmCoordinator.fireAlarm(ctx, 303)

            assertEquals(listOf(303L to nextMorning), alarmSchedules)
            assertTrue(nextMorning > firedAt)
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    // ---- P0.5: direct boot — LOCKED_BOOT_COMPLETED re-registers everything ----

    @Test
    fun `locked-boot reconcile with force re-registers every enabled and snoozed alarm`() {
        val originalTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val daily = Alarm(701, hour = 6, minute = 0, daysMask = Alarm.ALL_DAYS_MASK, label = "", enabled = true, vibrate = true)
            val oneShotFuture = oneShot(702).copy(hour = 9, minute = 15)
            val snoozedOneShot = oneShot(703).copy(hour = 5, minute = 45, enabled = false)
            store.saveAlarm(daily)
            store.saveAlarm(oneShotFuture)
            store.saveAlarm(snoozedOneShot)

            nowMs = REBOOT_AT
            // State as a reboot leaves it: persisted bookkeeping survived (the
            // previous session wrote these), system registrations did not.
            val dailyAt = ZonedDateTime.of(2033, 3, 17, 6, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
            val oneShotAt = ZonedDateTime.of(2033, 3, 17, 9, 15, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
            val snoozeUntil = REBOOT_AT + 7 * 60_000L
            store.setScheduledFire(701, dailyAt)
            store.setScheduledFire(702, oneShotAt)
            store.setScheduledFire(703, snoozeUntil)
            store.setSnoozedUntil(703, snoozeUntil)
            alarmSchedules.clear()

            AlarmCoordinator.handleAction(ctx, android.content.Intent.ACTION_LOCKED_BOOT_COMPLETED, -1L, 0)

            assertEquals(
                "every enabled alarm AND still-pending snooze must be registered exactly once",
                setOf(
                    701L to dailyAt,
                    702L to oneShotAt,
                    703L to snoozeUntil,
                ),
                alarmSchedules.toSet(),
            )
            assertEquals(3, alarmSchedules.size)
            assertEquals(snoozeUntil, store.scheduledFire(703))
            assertTrue(scheduleTimerCalls > 0)
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `user-unlocked reconcile re-registers without treating a live ringer as missed`() {
        val originalTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val daily = Alarm(701, hour = 6, minute = 0, daysMask = Alarm.ALL_DAYS_MASK, label = "", enabled = true, vibrate = true)
            val later = oneShot(702).copy(hour = 9, minute = 15)
            store.saveAlarm(daily)
            store.saveAlarm(later)
            nowMs = REBOOT_AT
            val dailyAt = ZonedDateTime.of(2033, 3, 17, 6, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
            val laterAt = ZonedDateTime.of(2033, 3, 17, 9, 15, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
            store.setScheduledFire(701, dailyAt)
            store.setScheduledFire(702, laterAt)
            store.setRinging(701, true)
            alarmSchedules.clear()

            AlarmCoordinator.handleAction(ctx, android.content.Intent.ACTION_USER_UNLOCKED, -1L, 0)

            assertTrue("a lock-screen ringer must survive unlock", store.isRinging(701))
            assertEquals(
                "the live ringer is skipped; every other armed alarm still re-registers",
                listOf(702L to laterAt),
                alarmSchedules,
            )
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    // ---- P1.6: newest-wins takeover ----

    @Test
    fun `a firing alarm takes over from the ringer that is already up`() {
        store.saveAlarm(oneShot(111))
        store.saveAlarm(oneShot(222))
        store.setRinging(111, true) // alarm A is mid-ring

        AlarmCoordinator.fireAlarm(ctx, 222)

        assertFalse("the previous ringer must no longer be ringing", store.isRinging(111))
        assertTrue("the newest alarm must be ringing", store.isRinging(222))
        assertEquals("stop must have been invoked for the previous ringer", 1, ringStops.size)
        assertEquals(listOf(222L), ringStarts)
    }

    // ---- boot-critical: one garbage row must not abort the reconcile pass ----

    @Test
    fun `one semantically-garbage row cannot abort the boot reconcile of the healthy alarms`() {
        val originalTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            store.saveAlarm(
                Alarm(id = 901, hour = 25, minute = 0, daysMask = Alarm.ALL_DAYS_MASK, label = "", enabled = true, vibrate = true),
            )
            store.saveAlarm(
                Alarm(id = 902, hour = 6, minute = 0, daysMask = Alarm.ALL_DAYS_MASK, label = "", enabled = true, vibrate = true),
            )
            nowMs = REBOOT_AT
            alarmSchedules.clear()

            AlarmCoordinator.handleAction(ctx, android.content.Intent.ACTION_LOCKED_BOOT_COMPLETED, -1L, 0)

            val sixAm = ZonedDateTime.of(2033, 3, 17, 6, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
            assertEquals(
                "the healthy alarm after a throwing row must still register",
                listOf(902L to sixAm),
                alarmSchedules,
            )
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    companion object {
        private const val NOW = 1_750_000_000_000L
        private const val FIRE_AT = NOW + 60_000

        /** Fixed "just rebooted" instant: 2033-03-17T04:00Z, before both wall-clock slots. */
        private const val REBOOT_AT = 1_994_644_800_000L
    }
}
