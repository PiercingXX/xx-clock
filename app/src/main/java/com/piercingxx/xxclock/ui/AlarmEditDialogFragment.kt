package com.piercingxx.xxclock.ui

import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import android.widget.TimePicker
import androidx.fragment.app.DialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.piercingxx.xxclock.R
import com.piercingxx.xxclock.model.Alarm
import com.piercingxx.xxclock.repo.AlarmRepository
import java.util.Calendar

class AlarmEditDialogFragment : DialogFragment() {

    interface Listener {
        fun onAlarmSaved(alarm: Alarm)
    }

    private lateinit var original: Alarm

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        original = Alarm(
            id = requireArguments().getLong(ARG_ID),
            hour = requireArguments().getInt(ARG_HOUR),
            minute = requireArguments().getInt(ARG_MINUTE),
            daysMask = requireArguments().getInt(ARG_DAYS),
            label = requireArguments().getString(ARG_LABEL).orEmpty(),
            enabled = requireArguments().getBoolean(ARG_ENABLED),
            vibrate = requireArguments().getBoolean(ARG_VIBRATE),
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val view = inflater.inflate(R.layout.dialog_alarm_edit, container, false)

        @Suppress("DEPRECATION") // ADJUST_RESIZE is deprecated on API 30+; harmless inside dialogs.
        dialog?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )

        val title = view.findViewById<TextView>(R.id.tv_alarm_edit_title)
        title.setText(
            if (requireArguments().getBoolean(ARG_IS_NEW)) R.string.alarm_edit_new else R.string.alarm_edit_existing,
        )

        val picker = view.findViewById<TimePicker>(R.id.tp_alarm_time)
        picker.setIs24HourView(DateFormat.is24HourFormat(requireContext()))
        picker.hour = original.hour
        picker.minute = original.minute

        val dayChipIds = listOf(
            R.id.chip_day_mon to Calendar.MONDAY,
            R.id.chip_day_tue to Calendar.TUESDAY,
            R.id.chip_day_wed to Calendar.WEDNESDAY,
            R.id.chip_day_thu to Calendar.THURSDAY,
            R.id.chip_day_fri to Calendar.FRIDAY,
            R.id.chip_day_sat to Calendar.SATURDAY,
            R.id.chip_day_sun to Calendar.SUNDAY,
        )
        for ((chipId, calendarDay) in dayChipIds) {
            val chip = view.findViewById<Chip>(chipId)
            chip.isChecked = original.daysMask and Alarm.bitForCalendarDay(calendarDay) != 0
        }

        val labelInput = view.findViewById<TextInputEditText>(R.id.et_alarm_label)
        labelInput.setText(original.label)

        val vibrateSwitch = view.findViewById<MaterialSwitch>(R.id.sw_alarm_vibrate)
        vibrateSwitch.isChecked = original.vibrate

        view.findViewById<View>(R.id.btn_cancel).setOnClickListener { dismiss() }
        view.findViewById<View>(R.id.btn_save).setOnClickListener { save(picker, dayChipIds, labelInput, vibrateSwitch) }

        return view
    }

    private fun save(
        picker: TimePicker,
        dayChips: List<Pair<Int, Int>>,
        labelInput: TextInputEditText,
        vibrateSwitch: MaterialSwitch,
    ) {
        var mask = 0
        for ((chipId, calendarDay) in dayChips) {
            if ((requireView().findViewById<Chip>(chipId)).isChecked) {
                mask = mask or Alarm.bitForCalendarDay(calendarDay)
            }
        }
        val updated = original.copy(
            hour = picker.hour,
            minute = picker.minute,
            daysMask = mask,
            label = labelInput.text?.toString()?.trim().orEmpty(),
            vibrate = vibrateSwitch.isChecked,
        )
        AlarmRepository.save(requireContext(), updated)
        (parentFragment as? Listener)?.onAlarmSaved(updated)
        dismiss()
    }

    companion object {
        const val TAG = "AlarmEditDialog"

        private const val ARG_ID = "id"
        private const val ARG_HOUR = "hour"
        private const val ARG_MINUTE = "minute"
        private const val ARG_DAYS = "days"
        private const val ARG_LABEL = "label"
        private const val ARG_ENABLED = "enabled"
        private const val ARG_VIBRATE = "vibrate"
        private const val ARG_IS_NEW = "isNew"

        fun newInstance(alarm: Alarm): AlarmEditDialogFragment =
            AlarmEditDialogFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_ID, alarm.id)
                    putInt(ARG_HOUR, alarm.hour)
                    putInt(ARG_MINUTE, alarm.minute)
                    putInt(ARG_DAYS, alarm.daysMask)
                    putString(ARG_LABEL, alarm.label)
                    putBoolean(ARG_ENABLED, alarm.enabled)
                    putBoolean(ARG_VIBRATE, alarm.vibrate)
                }
            }
    }
}
