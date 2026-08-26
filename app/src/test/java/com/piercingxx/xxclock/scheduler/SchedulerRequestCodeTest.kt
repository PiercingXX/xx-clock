package com.piercingxx.xxclock.scheduler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2.11: PendingIntent request codes pack the kind into the high bits and a
 * hi-xor-lo fold of the long id into the low bits, so (kind, id) pairs whose
 * ids collide under toInt() still map to distinct codes — and every pair is
 * stable across repeated calls, which FLAG_UPDATE_CURRENT matching requires.
 */
class SchedulerRequestCodeTest {

    /**
     * Realistic id space (legacy timestamps and counter values above them),
     * including truncation twins spaced exactly 2^32 apart.
     */
    private val ids = listOf(
        1_750_000_000_123L,
        1_750_000_000_123L + (1L shl 32),
        1_750_000_000_123L + (2L shl 32),
        1_750_000_000_123L + (3L shl 32),
        5_000_000_000_000L,
        5_000_000_000_001L,
    )

    private val kinds = listOf(
        ExactScheduler.REQ_KIND_ALARM,
        ExactScheduler.REQ_KIND_TIMER,
        ExactScheduler.REQ_KIND_SHOW,
    )

    @Test
    fun `spread members really do truncate identically via toInt - sanity`() {
        assertEquals(1, ids.take(4).map { it.toInt() }.toSet().size)
    }

    @Test
    fun `ids that truncate identically still get distinct request codes per kind`() {
        for (kind in kinds) {
            val codes = ids.map { ExactScheduler.requestCode(kind, it) }
            assertEquals(
                "kind $kind must distinguish ids 2^32 apart",
                ids.size,
                codes.toSet().size,
            )
        }
    }

    @Test
    fun `same kind and id always yield the same code`() {
        for (id in ids) {
            for (kind in kinds) {
                assertEquals(ExactScheduler.requestCode(kind, id), ExactScheduler.requestCode(kind, id))
            }
        }
    }

    @Test
    fun `kinds never share a code even for identical ids`() {
        for (id in ids) {
            val codes = kinds.map { ExactScheduler.requestCode(it, id) }
            assertEquals(3, codes.toSet().size)
            assertNotEquals(codes[0], codes[1])
        }
    }

    @Test
    fun `codes stay non-negative so no platform layer can misread them as errors`() {
        for (id in ids) {
            for (kind in kinds) {
                assertTrue(ExactScheduler.requestCode(kind, id) >= 0)
            }
        }
    }
}
