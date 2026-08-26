package com.piercingxx.xxclock.scheduler

import android.content.Context
import com.piercingxx.xxclock.Actions
import com.piercingxx.xxclock.alarm.AlarmCoordinator
import com.piercingxx.xxclock.data.ClockStoreBackend
import com.piercingxx.xxclock.model.Alarm
import com.piercingxx.xxclock.model.TimerItem
import com.piercingxx.xxclock.receiver.ExactAlarmPermissionReceiver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
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

    override fun alarms(): List<Alarm> = alarmRows.values.toList()
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

/** In-memory [DegradedAlarmStore]. */
private class InMemoryDegradedStore : DegradedAlarmStore {
    var rows: Set<String> = emptySet()

    override fun ids(): Set<String> = rows
    override fun save(ids: Set<String>) {
        rows = ids
    }
}

/**
 * Drives ExactScheduler's exact-vs-window decision on the JVM through its
 * collaborator seams (in-memory store, recorder AlarmManager operations) —
 * no Robolectric, no Android mocks.
 *
 * Covers P1.8: revoked exact-alarm access must degrade an alarm to an inexact
 * window instead of throwing (a SecurityException on the alarm-event worker
 * thread kills the whole process and aborts the rest of reconcile), the
 * degradation must be observable, and cancelling clears it again.
 */
class ExactSchedulerTest {

    /**
     * A throwaway Context instance: the stub constructor chain is inert under
     * `isReturnDefaultValues`, and its members are never touched because every
     * collaborator is seamed (same mechanism as AlarmCoordinatorTest).
     */
    private val ctx: Context = android.content.ContextWrapper(null)

    private lateinit var store: FakeBackend
    private lateinit var degraded: InMemoryDegradedStore
    private val exactCalls = mutableListOf<Pair<Long, Long>>()
    private val windowCalls = mutableListOf<Pair<Long, Long>>()
    private val cancels = mutableListOf<Long>()

    // Production seam wiring, captured before overriding and restored after.
    private lateinit var production: SchedulerSeams
    private lateinit var coordinatorProduction: CoordinatorSeams
    private var nowMs = REBOOT_AT

    private class SchedulerSeams(
        val storeOf: (Context) -> ClockStoreBackend,
        val canScheduleExact: (Context) -> Boolean,
        val scheduleExact: (Context, Alarm, Long) -> Unit,
        val scheduleWindow: (Context, Alarm, Long) -> Unit,
        val cancelSystemAlarm: (Context, Long) -> Unit,
        val degradedStoreOf: (Context) -> DegradedAlarmStore,
    )

    private class CoordinatorSeams(
        val storeOf: (Context) -> ClockStoreBackend,
        val clock: () -> Long,
        val scheduleTimers: (Context) -> Unit,
        val refreshWidgets: (Context) -> Unit,
    )

    @Before
    fun installFakes() {
        production = SchedulerSeams(
            ExactScheduler.storeOf,
            ExactScheduler.canScheduleExact,
            ExactScheduler.scheduleExact,
            ExactScheduler.scheduleWindow,
            ExactScheduler.cancelSystemAlarm,
            ExactScheduler.degradedStoreOf,
        )
        coordinatorProduction = CoordinatorSeams(
            AlarmCoordinator.storeOf,
            AlarmCoordinator.clock,
            AlarmCoordinator.scheduleTimers,
            AlarmCoordinator.refreshWidgets,
        )
        store = FakeBackend()
        degraded = InMemoryDegradedStore()
        ExactScheduler.storeOf = { store }
        ExactScheduler.degradedStoreOf = { degraded }
        ExactScheduler.canScheduleExact = { true }
        ExactScheduler.scheduleExact = { _, alarm, at -> exactCalls += alarm.id to at }
        ExactScheduler.scheduleWindow = { _, alarm, at -> windowCalls += alarm.id to at }
        ExactScheduler.cancelSystemAlarm = { _, id -> cancels += id }
    }

    @After
    fun restoreProductionWiring() {
        ExactScheduler.storeOf = production.storeOf
        ExactScheduler.canScheduleExact = production.canScheduleExact
        ExactScheduler.scheduleExact = production.scheduleExact
        ExactScheduler.scheduleWindow = production.scheduleWindow
        ExactScheduler.cancelSystemAlarm = production.cancelSystemAlarm
        ExactScheduler.degradedStoreOf = production.degradedStoreOf
        AlarmCoordinator.storeOf = coordinatorProduction.storeOf
        AlarmCoordinator.clock = coordinatorProduction.clock
        AlarmCoordinator.scheduleTimers = coordinatorProduction.scheduleTimers
        AlarmCoordinator.refreshWidgets = coordinatorProduction.refreshWidgets
    }

    private fun alarm(id: Long) =
        Alarm(id = id, hour = 7, minute = 30, daysMask = 0, label = "", enabled = true, vibrate = true)

    // ---- P1.8: revoked permission degrades instead of throwing ----

    @Test
    fun `revoked exact permission schedules the window fallback instead of throwing`() {
        ExactScheduler.canScheduleExact = { false }

        val exact = ExactScheduler.scheduleAlarmAt(ctx, alarm(101), NOW + 60_000)

        assertFalse("the alarm must report itself as degraded", exact)
        assertTrue(exactCalls.isEmpty())
        assertEquals(listOf(101L to NOW + 60_000), windowCalls)
        assertEquals(NOW + 60_000, store.scheduledFire(101))
        assertEquals(setOf(101L), ExactScheduler.degradedAlarmIds(ctx))
        assertTrue(ExactScheduler.hasDegradedAlarm(ctx))
    }

    @Test
    fun `a security exception from the exact call falls back to the window without propagating`() {
        ExactScheduler.scheduleExact = { _, _, _ -> throw SecurityException("SCHEDULE_EXACT_ALARM revoked") }

        val exact = ExactScheduler.scheduleAlarmAt(ctx, alarm(101), NOW + 60_000)

        assertFalse(exact)
        assertEquals(listOf(101L to NOW + 60_000), windowCalls)
        assertEquals(setOf(101L), ExactScheduler.degradedAlarmIds(ctx))
    }

    @Test
    fun `granted exact permission registers exactly and reports no degradation`() {
        val exact = ExactScheduler.scheduleAlarmAt(ctx, alarm(101), NOW + 60_000)

        assertTrue(exact)
        assertEquals(listOf(101L to NOW + 60_000), exactCalls)
        assertTrue(windowCalls.isEmpty())
        assertTrue(degraded.rows.isEmpty())
    }

    @Test
    fun `one bad alarm cannot abort the remaining alarms from registering`() {
        ExactScheduler.scheduleExact = { _, alarm, at ->
            if (alarm.id == 101L) throw SecurityException("revoked")
            exactCalls += alarm.id to at
        }

        val first = ExactScheduler.scheduleAlarmAt(ctx, alarm(101), NOW + 60_000)
        val second = ExactScheduler.scheduleAlarmAt(ctx, alarm(202), NOW + 120_000)

        assertFalse(first)
        assertTrue("the next alarm must still register exactly", second)
        assertEquals(listOf(101L to NOW + 60_000), windowCalls)
        assertEquals(listOf(202L to NOW + 120_000), exactCalls)
        assertEquals(setOf(101L), ExactScheduler.degradedAlarmIds(ctx))
    }

    // ---- P1.8: full reconcile round-trip through the coordinator ----

    @Test
    fun `reconcile with revoked permission re-registers every armed alarm through the window`() {
        val originalTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            store.saveAlarm(alarm(701).copy(hour = 6, minute = 0))
            store.saveAlarm(alarm(702).copy(hour = 9, minute = 15))
            AlarmCoordinator.storeOf = { store }
            AlarmCoordinator.clock = { nowMs }
            AlarmCoordinator.scheduleTimers = { }
            AlarmCoordinator.refreshWidgets = { }
            ExactScheduler.canScheduleExact = { false }
            val sixAm = ZonedDateTime.of(2033, 3, 17, 6, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
            val nineFifteen = ZonedDateTime.of(2033, 3, 17, 9, 15, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
            windowCalls.clear()

            AlarmCoordinator.reconcile(ctx, force = true, recoverRinging = false)

            assertEquals(
                "every armed alarm must be re-registered despite revoked exact access",
                setOf(701L to sixAm, 702L to nineFifteen),
                windowCalls.toSet(),
            )
            assertEquals(setOf(701L, 702L), ExactScheduler.degradedAlarmIds(ctx))
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `re-granting permission and reconciling restores exact slots and clears the degraded markers`() {
        val originalTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            store.saveAlarm(alarm(701).copy(hour = 6, minute = 0))
            AlarmCoordinator.storeOf = { store }
            AlarmCoordinator.clock = { nowMs }
            AlarmCoordinator.scheduleTimers = { }
            AlarmCoordinator.refreshWidgets = { }
            ExactScheduler.canScheduleExact = { false }
            AlarmCoordinator.reconcile(ctx, force = true, recoverRinging = false)
            assertTrue(ExactScheduler.hasDegradedAlarm(ctx))

            ExactScheduler.canScheduleExact = { true }
            exactCalls.clear()
            windowCalls.clear()

            AlarmCoordinator.reconcile(ctx, force = true, recoverRinging = false)

            assertEquals(1, exactCalls.size)
            assertTrue(windowCalls.isEmpty())
            assertTrue("the degraded markers must drain once exact scheduling returns", degraded.rows.isEmpty())
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun `cancelling an alarm clears its degraded marker`() {
        ExactScheduler.canScheduleExact = { false }
        ExactScheduler.scheduleAlarmAt(ctx, alarm(101), NOW + 60_000)
        assertTrue(ExactScheduler.hasDegradedAlarm(ctx))

        ExactScheduler.cancelAlarm(ctx, 101)

        assertTrue(cancels.contains(101L))
        assertEquals(0L, store.scheduledFire(101))
        assertTrue(degraded.rows.isEmpty())
        assertFalse(ExactScheduler.hasDegradedAlarm(ctx))
    }

    // ---- P1.8: the permission-change broadcast reaches reconcile ----

    @Test
    fun `the exact-alarm permission broadcast routes into a forced reconcile`() {
        val reconciled = mutableListOf<Context>()
        val receiver = ExactAlarmPermissionReceiver(
            extractAction = { Actions.EXACT_ALARM_PERMISSION_CHANGED },
            onPermissionChanged = { c -> reconciled += c },
        )

        receiver.onReceive(ctx, null)

        assertEquals(listOf(ctx), reconciled)
    }

    @Test
    fun `an unrelated or missing action routes nowhere`() {
        val reconciled = mutableListOf<Context>()
        val receiver = ExactAlarmPermissionReceiver(
            extractAction = { "android.intent.action.TIME_SET" },
            onPermissionChanged = { c -> reconciled += c },
        )
        val nullAction = ExactAlarmPermissionReceiver(
            extractAction = { null },
            onPermissionChanged = { c -> reconciled += c },
        )

        receiver.onReceive(ctx, null)
        nullAction.onReceive(null, null)

        assertTrue(reconciled.isEmpty())
    }

    // ---- manifest wiring (locks what the OS needs to deliver the broadcast) ----

    private val manifestText: String =
        sequenceOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first { it.exists() }.readText()

    @Test
    fun `manifest declares the exact-alarm permission receiver component`() {
        assertTrue(
            "AndroidManifest.xml must declare the .receiver.ExactAlarmPermissionReceiver component",
            manifestText.contains(".receiver.ExactAlarmPermissionReceiver"),
        )
    }

    @Test
    fun `manifest keeps the permission receiver unexported`() {
        val block = manifestText
            .substringAfter(".receiver.ExactAlarmPermissionReceiver")
            .substringBefore("</receiver>")
        assertTrue(
            "ExactAlarmPermissionReceiver must not be exported",
            block.contains("android:exported=\"false\""),
        )
    }

    @Test
    fun `manifest registers the system exact-alarm permission-changed action`() {
        assertTrue(
            "AndroidManifest.xml must register ${Actions.EXACT_ALARM_PERMISSION_CHANGED}",
            manifestText.contains(Actions.EXACT_ALARM_PERMISSION_CHANGED),
        )
    }

    @Test
    fun `declared exact-alarm permission receiver name resolves to a class`() {
        Class.forName("com.piercingxx.xxclock.receiver.ExactAlarmPermissionReceiver")
    }

    // ---- direct-boot wiring (locked-boot path needs these components pre-unlock) ----

    private fun componentBlock(name: String, endTag: String): String =
        manifestText.substringAfter(name).substringBefore(endTag)

    @Test
    fun `manifest keeps AlarmEventReceiver direct boot aware`() {
        val block = componentBlock(".receiver.AlarmEventReceiver", "</receiver>")
        assertTrue(
            "AlarmEventReceiver must declare android:directBootAware=\"true\"",
            block.contains("android:directBootAware=\"true\""),
        )
    }

    @Test
    fun `manifest registers USER_UNLOCKED so CE-to-DE migration runs after a locked start`() {
        val block = componentBlock(".receiver.AlarmEventReceiver", "</receiver>")
        assertTrue(
            "AlarmEventReceiver must listen for android.intent.action.USER_UNLOCKED",
            block.contains("android.intent.action.USER_UNLOCKED"),
        )
    }

    @Test
    fun `manifest keeps RingService direct boot aware`() {
        val block = componentBlock(".service.RingService", "</service>")
        assertTrue(
            "RingService must declare android:directBootAware=\"true\"",
            block.contains("android:directBootAware=\"true\""),
        )
    }

    @Test
    fun `manifest keeps AlarmAlertActivity direct boot aware`() {
        val block = componentBlock(".ui.AlarmAlertActivity", "/>")
        assertTrue(
            "AlarmAlertActivity must declare android:directBootAware=\"true\"",
            block.contains("android:directBootAware=\"true\""),
        )
    }

    companion object {
        /** Fixed "just rebooted" instant: 2033-03-17T04:00Z, before both wall-clock slots. */
        private const val REBOOT_AT = 1_994_644_800_000L

        /** Fixed instant far from any plausible wall clock. */
        private const val NOW = 1_750_000_000_000L
    }
}
