package com.piercingxx.xxclock.audio

import com.piercingxx.xxclock.model.Alarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the per-alarm ringtone fallback order ([ringCandidates])
 * and the model-side default (null soundUri = system default alarm sound).
 * The Android edge (KlaxonPlayer walking this list, the picker UI) stays thin
 * on top of this rule.
 */
class RingCandidatesTest {

    private val chosen = "content://media/external/audio/media/42"
    private val alarmDefault = "content://media/internal/audio/media/7"
    private val notifDefault = "content://settings/system/notification_sound"

    @Test
    fun `chosen tone is tried first, defaults follow in order`() {
        assertEquals(
            listOf(chosen, alarmDefault, notifDefault),
            ringCandidates(chosen, alarmDefault, notifDefault),
        )
    }

    @Test
    fun `no chosen tone falls straight to the system alarm default`() {
        assertEquals(
            listOf(alarmDefault, notifDefault),
            ringCandidates(null, alarmDefault, notifDefault),
        )
    }

    @Test
    fun `a chosen tone that IS the default is not attempted twice`() {
        assertEquals(
            listOf(alarmDefault, notifDefault),
            ringCandidates(alarmDefault, alarmDefault, notifDefault),
        )
    }

    @Test
    fun `blank and null entries are dropped`() {
        assertEquals(listOf(notifDefault), ringCandidates("", null, notifDefault))
        assertEquals(listOf(chosen), ringCandidates(chosen, "  ", null))
    }

    @Test
    fun `everything missing yields an empty list - audio gives up, vibration continues`() {
        assertTrue(ringCandidates(null, null, null).isEmpty())
    }

    // ---- model default ----

    @Test
    fun `new alarms follow the system default alarm sound`() {
        assertNull(Alarm.newAlarm(7, 30).soundUri)
    }

    @Test
    fun `a chosen tone survives copy-based edits of other fields`() {
        val alarm = Alarm.newAlarm(7, 30).copy(soundUri = chosen)
        assertEquals(chosen, alarm.copy(minute = 45, vibrate = false).soundUri)
    }
}
