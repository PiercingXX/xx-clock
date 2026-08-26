package com.piercingxx.xxclock.ui

import com.piercingxx.xxclock.model.Alarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-memory stand-in for ClockStore's alarm half: the same get/save surface
 * [com.piercingxx.xxclock.repo.AlarmRepository] drives, without Android.
 */
private class FakeAlarmStore {
    val rows = mutableMapOf<Long, Alarm>()

    fun get(id: Long): Alarm? = rows[id]

    fun save(alarm: Alarm) {
        rows[alarm.id] = alarm
    }

    /** Mirrors AlarmRepository.toggle: read the live row, persist its copy. */
    fun toggle(id: Long, enabled: Boolean) {
        val current = rows[id] ?: return
        save(current.copy(enabled = enabled))
    }
}

/**
 * Locks the list-toggle / editor-save contract: `enabled` belongs to the live
 * store row at save time, never to the snapshot captured when the editor
 * opened — otherwise saving an edit after a toggle-off would re-enable the
 * alarm (the dialog has no enable control of its own).
 */
class AlarmToggleThenSaveTest {

    private val store = FakeAlarmStore()

    /** The stale row state an open editor would still be holding. */
    private lateinit var dialogSnapshot: Alarm

    private fun openEditorOn(id: Long) {
        dialogSnapshot = store.get(id) ?: error("no stored alarm $id to edit")
    }

    /** The dialog's save path: session edits merged with the live store row. */
    private fun editorSaves(label: String): Alarm {
        val edited = dialogSnapshot.copy(label = label)
        val saved = AlarmEditDialogFragment.mergedForSave(edited, store.get(dialogSnapshot.id))
        store.save(saved)
        return saved
    }

    @Test
    fun `editor save after toggling off does not re-enable the alarm`() {
        val seeded = Alarm(id = 7L, hour = 7, minute = 30, daysMask = 0, label = "Wake", enabled = true, vibrate = true)
        store.save(seeded)

        openEditorOn(seeded.id)
        store.toggle(seeded.id, enabled = false)

        val saved = editorSaves(label = "Renamed")
        assertFalse("a save must not resurrect a disabled alarm", saved.enabled)
        assertEquals(false, store.get(seeded.id)?.enabled)
        assertEquals("Renamed", store.get(seeded.id)?.label)
    }

    @Test
    fun `editor save after toggling off then on keeps the alarm enabled`() {
        val seeded = Alarm(id = 7L, hour = 7, minute = 30, daysMask = 0, label = "Wake", enabled = true, vibrate = true)
        store.save(seeded)

        openEditorOn(seeded.id)
        store.toggle(seeded.id, enabled = false)
        store.toggle(seeded.id, enabled = true)

        assertTrue(editorSaves(label = "Renamed").enabled)
    }

    @Test
    fun `a draft absent from the store saves with its own enabled flag`() {
        val draft = Alarm(id = 9L, hour = 8, minute = 0, daysMask = 0, label = "", enabled = true, vibrate = true)
        dialogSnapshot = draft

        val saved = editorSaves(label = "New")

        assertTrue(saved.enabled)
    }
}
