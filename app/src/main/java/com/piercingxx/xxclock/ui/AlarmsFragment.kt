package com.piercingxx.xxclock.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.piercingxx.xxclock.R
import com.piercingxx.xxclock.model.Alarm
import com.piercingxx.xxclock.repo.AlarmRepository
import com.piercingxx.xxclock.util.Fmt
import java.util.Calendar

class AlarmsFragment : Fragment(R.layout.fragment_alarms), AlarmEditDialogFragment.Listener {

    private lateinit var adapter: AlarmsAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        adapter = AlarmsAdapter(
            onRowClick = ::openEditor,
            onToggle = { id, enabled ->
                AlarmRepository.toggle(ctx, id, enabled)
                reload()
            },
            onRowLongClick = ::deleteWithUndo,
        )
        view.findViewById<RecyclerView>(R.id.alarms_list).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = this@AlarmsFragment.adapter
        }
        view.findViewById<FloatingActionButton>(R.id.fab_add_alarm)
            .setOnClickListener { openEditor(newDraftAlarm(), isNew = true) }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val items = AlarmRepository.getAll(requireContext())
        adapter.submitList(items)
        requireView().findViewById<View>(R.id.alarms_empty).visibility =
            if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun newDraftAlarm(): Alarm {
        val now = Calendar.getInstance()
        var hour = now.get(Calendar.HOUR_OF_DAY)
        val minute = now.get(Calendar.MINUTE)
        if (minute >= 30) hour += 1
        return Alarm.newAlarm(hour % 24, if (minute < 30) 30 else 0)
    }

    private fun openEditor(alarm: Alarm, isNew: Boolean = false) {
        AlarmEditDialogFragment.newInstance(alarm, isNew)
            .show(childFragmentManager, AlarmEditDialogFragment.TAG)
    }

    private fun deleteWithUndo(alarm: Alarm) {
        val ctx = requireContext()
        AlarmRepository.delete(ctx, alarm.id)
        reload()
        Snackbar.make(requireView(), R.string.alarm_deleted, Snackbar.LENGTH_LONG)
            .setAction(R.string.action_undo) {
                AlarmRepository.save(ctx, alarm)
                reload()
            }
            .show()
    }

    override fun onAlarmSaved(alarm: Alarm) {
        reload()
        if (!alarm.enabled) return
        val ctx = requireContext()
        val fireAt = AlarmRepository.scheduledFire(ctx, alarm.id)
        Snackbar.make(
            requireView(),
            getString(R.string.alarm_set_for, Fmt.until(System.currentTimeMillis(), fireAt)),
            Snackbar.LENGTH_SHORT,
        ).show()
    }
}
