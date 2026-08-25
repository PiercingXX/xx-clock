package com.piercingxx.xxclock.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.TextClock
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.piercingxx.xxclock.R
import com.piercingxx.xxclock.repo.AlarmRepository
import com.piercingxx.xxclock.util.Fmt

// AnalogClock is deprecated platform-wise but remains the only zero-maintenance
// analog view that also works inside RemoteViews; intentional use for v1.
@Suppress("DEPRECATION")
class ClockFragment : Fragment(R.layout.fragment_clock) {

    private lateinit var timeText: TextClock
    private lateinit var analogClock: android.widget.AnalogClock
    private lateinit var styleButton: MaterialButton
    private lateinit var nextAlarmCard: MaterialCardView
    private lateinit var nextAlarmText: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        timeText = view.findViewById(R.id.clock_time)
        analogClock = view.findViewById(R.id.clock_analog)
        styleButton = view.findViewById(R.id.btn_clock_style)
        nextAlarmCard = view.findViewById(R.id.card_next_alarm)
        nextAlarmText = view.findViewById(R.id.tv_next_alarm)

        styleButton.setOnClickListener {
            val next = if (isAnalog()) STYLE_DIGITAL else STYLE_ANALOG
            prefs().edit().putString(KEY_STYLE, next).apply()
            applyStyle()
        }
        applyStyle()
    }

    override fun onResume() {
        super.onResume()
        applyStyle()
        refreshNextAlarm()
    }

    private fun prefs(): SharedPreferences =
        requireContext().getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)

    private fun isAnalog(): Boolean = prefs().getString(KEY_STYLE, STYLE_DIGITAL) == STYLE_ANALOG

    private fun applyStyle() {
        val analogSelected = isAnalog()
        timeText.visibility = if (analogSelected) View.GONE else View.VISIBLE
        analogClock.visibility = if (analogSelected) View.VISIBLE else View.GONE
        styleButton.setText(if (analogSelected) R.string.clock_show_digital else R.string.clock_show_analog)
    }

    private fun refreshNextAlarm() {
        val ctx = context ?: return
        val now = System.currentTimeMillis()
        val next = AlarmRepository.nextArmed(ctx)
        if (next == null) {
            nextAlarmCard.visibility = View.GONE
            return
        }
        val (alarm, fireAt) = next
        val label = alarm.label.ifBlank { getString(R.string.alert_default_label) }
        nextAlarmText.text = listOf(label, Fmt.time(ctx, fireAt), Fmt.until(now, fireAt))
            .joinToString(SEPARATOR)
        nextAlarmCard.visibility = View.VISIBLE
    }

    companion object {
        private const val UI_PREFS = "xx_clock_ui"
        private const val KEY_STYLE = "clock_style"
        private const val STYLE_DIGITAL = "digital"
        private const val STYLE_ANALOG = "analog"
        private const val SEPARATOR = " · "
    }
}
