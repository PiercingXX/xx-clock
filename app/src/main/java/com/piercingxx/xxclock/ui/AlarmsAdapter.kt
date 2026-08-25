package com.piercingxx.xxclock.ui

import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.piercingxx.xxclock.R
import com.piercingxx.xxclock.model.Alarm
import java.util.Calendar

class AlarmsAdapter(
    private val onRowClick: (Alarm) -> Unit,
    private val onToggle: (id: Long, enabled: Boolean) -> Unit,
    private val onRowLongClick: (Alarm) -> Unit,
) : ListAdapter<Alarm, AlarmsAdapter.Holder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_alarm, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val time: TextView = itemView.findViewById(R.id.tv_alarm_time)
        private val summary: TextView = itemView.findViewById(R.id.tv_alarm_summary)
        private val label: TextView = itemView.findViewById(R.id.tv_alarm_label)
        private val vibrateIcon: ImageView = itemView.findViewById(R.id.iv_alarm_vibrate)
        private val toggle: SwitchMaterial = itemView.findViewById(R.id.sw_alarm_enabled)

        private var current: Alarm? = null
        private var muteToggle = false

        init {
            itemView.setOnClickListener { current?.let(onRowClick) }
            itemView.setOnLongClickListener {
                current?.let(onRowLongClick)
                true
            }
            toggle.setOnCheckedChangeListener { _, checked ->
                if (muteToggle) return@setOnCheckedChangeListener
                val alarm = current ?: return@setOnCheckedChangeListener
                if (alarm.enabled != checked) onToggle(alarm.id, checked)
            }
        }

        fun bind(alarm: Alarm) {
            current = alarm
            muteToggle = true
            toggle.isChecked = alarm.enabled
            muteToggle = false

            time.text = formatTime(alarm)
            summary.text = Alarm.summary(alarm.daysMask)
            if (alarm.label.isBlank()) {
                label.visibility = View.GONE
            } else {
                label.text = alarm.label
                label.visibility = View.VISIBLE
            }
            vibrateIcon.visibility = if (alarm.vibrate) View.VISIBLE else View.GONE
        }

        private fun formatTime(alarm: Alarm): String {
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, alarm.hour)
            cal.set(Calendar.MINUTE, alarm.minute)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return DateFormat.getTimeFormat(itemView.context).format(cal.time)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Alarm>() {
            override fun areItemsTheSame(oldItem: Alarm, newItem: Alarm): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: Alarm, newItem: Alarm): Boolean =
                oldItem == newItem
        }
    }
}
