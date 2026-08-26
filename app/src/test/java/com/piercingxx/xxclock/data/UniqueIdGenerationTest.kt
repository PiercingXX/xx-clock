package com.piercingxx.xxclock.data

import com.piercingxx.xxclock.model.Alarm
import com.piercingxx.xxclock.model.TimerItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P2.11: alarm and timer ids come from one persisted monotonic counter — never
 * raw wall clock. The counter is driven through [ClockStore.idSequence] with a
 * prefs-emulating slot and a frozen clock, so "same millisecond" is exact:
 * every mint below happens while the fake clock stands still, which is where
 * the old System.currentTimeMillis() generation collided.
 */
class UniqueIdGenerationTest {

    /** Prefs-emulating persistence slot for the counter. */
    private val slot = longArrayOf(0L)

    /** Fake wall clock; deliberately frozen for whole tests. */
    private var nowMs = 1_750_000_000_000L

    private lateinit var production: MonotonicIdSequence

    @Before
    fun installFakeStorage() {
        production = ClockStore.idSequence
        ClockStore.idSequence = MonotonicIdSequence(
            load = { slot[0] },
            store = { slot[0] = it },
            floorOf = { nowMs },
        )
    }

    @After
    fun restoreProductionWiring() {
        ClockStore.idSequence = production
    }

    @Test
    fun `creates within one frozen millisecond all get distinct ids`() {
        val ids = List(64) { i ->
            if (i % 2 == 0) Alarm.newAlarm(7, 30).id else TimerItem.newTimer(60_000L).id
        }
        assertEquals("same-ms creates must not share an id", ids.size, ids.toSet().size)
    }

    @Test
    fun `alarm ids and timer ids draw from one sequence and never collide`() {
        val alarms = List(16) { Alarm.newAlarm(6, 15).id }
        val timers = List(16) { TimerItem.newTimer(90_000L).id }

        assertEquals("one shared namespace means zero cross-kind collisions", 32, (alarms + timers).toSet().size)
        assertTrue("no alarm id may equal any timer id", alarms.intersect(timers.toSet()).isEmpty())
    }

    @Test
    fun `first mint seeds above the highest legacy timestamp id and the wall clock`() {
        val legacyId = 1_700_000_000_123L // pre-migration id minted from wall clock
        ClockStore.idSequence = MonotonicIdSequence(
            load = { slot[0] },
            store = { slot[0] = it },
            floorOf = { maxOf(legacyId, nowMs) }, // production floor: max(existing ids, clock)
        )

        val minted = ClockStore.nextId()

        assertTrue("minted $minted must clear legacy id $legacyId", minted > legacyId)
        assertTrue("minted $minted must clear the current wall clock", minted > nowMs)
    }

    @Test
    fun `a fresh sequence over the same storage resumes above all previous mints`() {
        val firstProcess = List(4) { ClockStore.nextId() }

        // Simulated process death: new instance, same persisted slot.
        val resumed = MonotonicIdSequence(
            load = { slot[0] },
            store = { slot[0] = it },
            floorOf = { nowMs },
        ).next()

        assertTrue(firstProcess.all { resumed > it })
    }
}
