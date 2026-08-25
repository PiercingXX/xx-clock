package com.piercingxx.xxclock.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import android.widget.TimePicker
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
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

    /**
     * The tone picked in THIS editing session (null = system default alarm
     * sound). Held outside [original] because the picker result lands between
     * view states; survives recreation via [onSaveInstanceState].
     */
    private var chosenSoundUri: String? = null

    /**
     * System ringtone picker (TYPE_ALARM): the platform-idiomatic chooser for
     * an offline app — it lists the built-in alarm tones plus /sdcard/Ringtones
     * without this app needing storage permissions of its own.
     */
    private val soundPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val picked = result.data?.let {
                IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            }
            // Picking "Default" (or Silent, which we hide) comes back as the
            // default-alarm URI: collapse it to null so the alarm keeps
            // FOLLOWING the system default instead of pinning today's tone.
            chosenSoundUri = picked
                ?.takeIf { it != RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) }
                ?.toString()
            view?.findViewById<TextView>(R.id.tv_alarm_sound)?.text = soundTitle(chosenSoundUri)
        }

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
            soundUri = requireArguments().getString(ARG_SOUND),
        )
        chosenSoundUri = if (savedInstanceState != null) {
            savedInstanceState.getString(KEY_CHOSEN_SOUND)
        } else {
            original.soundUri
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_CHOSEN_SOUND, chosenSoundUri)
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

        view.findViewById<TextView>(R.id.tv_alarm_sound).text = soundTitle(chosenSoundUri)
        view.findViewById<View>(R.id.row_alarm_sound).setOnClickListener { openSoundPicker() }

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
            soundUri = chosenSoundUri,
        )
        AlarmRepository.save(requireContext(), updated)
        (parentFragment as? Listener)?.onAlarmSaved(updated)
        dismiss()
    }

    private fun openSoundPicker() {
        val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.alarm_sound))
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            // No Silent entry: an alarm that cannot make sound is a footgun;
            // the Vibrate switch is the deliberate quiet option.
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, defaultUri)
            .putExtra(
                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                chosenSoundUri?.let(Uri::parse) ?: defaultUri,
            )
        try {
            soundPicker.launch(intent)
        } catch (_: ActivityNotFoundException) {
            // No system picker on this device: keep the current selection.
        }
    }

    /**
     * Human title for the sound row. A URI that no longer resolves (deleted
     * file, revoked permission) reads as the default — which is also exactly
     * what KlaxonPlayer will fall back to at ring time.
     */
    private fun soundTitle(uriString: String?): String {
        if (uriString == null) return getString(R.string.alarm_sound_default)
        return try {
            RingtoneManager.getRingtone(requireContext(), Uri.parse(uriString))
                ?.getTitle(requireContext())
                ?: getString(R.string.alarm_sound_default)
        } catch (_: Exception) {
            getString(R.string.alarm_sound_default)
        }
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
        private const val ARG_SOUND = "soundUri"
        private const val ARG_IS_NEW = "isNew"
        private const val KEY_CHOSEN_SOUND = "chosenSoundUri"

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
                    putString(ARG_SOUND, alarm.soundUri)
                }
            }
    }
}
