package com.piercingxx.xxclock.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.piercingxx.xxclock.R
import com.piercingxx.xxclock.model.TimerItem
import com.piercingxx.xxclock.repo.TimerRepository
import com.piercingxx.xxclock.time.TimerMath

class TimersFragment : Fragment(R.layout.fragment_timers) {

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            rebuildCards()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private var lastSignature: String? = null
    private var minutesInputLayout: TextInputLayout? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()

        val presets = listOf(
            R.id.chip_preset_1 to 1,
            R.id.chip_preset_3 to 3,
            R.id.chip_preset_5 to 5,
            R.id.chip_preset_10 to 10,
        )
        for ((chipId, minutes) in presets) {
            val chip = view.findViewById<Chip>(chipId)
            chip.text = getString(R.string.timer_preset_minutes, minutes)
            chip.setOnClickListener { TimerRepository.start(ctx, minutes * 60_000L) }
        }

        val input = view.findViewById<TextInputEditText>(R.id.et_timer_minutes)
        minutesInputLayout = view.findViewById(R.id.til_timer_minutes)
        view.findViewById<View>(R.id.btn_timer_start).setOnClickListener {
            startCustom(input)
        }

        rebuildCards()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private fun startCustom(input: EditText) {
        val ctx = context ?: return
        val minutes = input.text?.toString()?.trim()?.toIntOrNull() ?: 0
        val layout = minutesInputLayout
        if (minutes < MIN_MINUTES || minutes > MAX_MINUTES) {
            layout?.error = getString(R.string.timer_invalid_minutes)
            return
        }
        layout?.error = null
        TimerRepository.start(ctx, minutes * 60_000L)
        input.setText("")
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
        lastSignature = null
        if (isAdded) rebuildCards()
    }

    private fun rebuildCards() {
        val ctx = context ?: return
        val rootView = view ?: return
        val timers = TimerRepository.getAll(ctx)

        val signature = timers.joinToString("|") { timer ->
            "${timer.id}:${timer.state}:${timer.label}:" +
                TimerMath.display(TimerRepository.remainingMs(ctx, timer))
        }
        if (signature == lastSignature) return
        lastSignature = signature

        val scroll = rootView.findViewById<ScrollView>(R.id.timers_scroll)
        val container = rootView.findViewById<LinearLayout>(R.id.timers_container)
        val scrollY = scroll.scrollY
        container.removeAllViews()
        for (timer in timers) {
            container.addView(buildTimerCard(timer))
        }
        scroll.scrollTo(0, scrollY)

        rootView.findViewById<View>(R.id.tv_timers_empty).visibility =
            if (timers.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun buildTimerCard(timer: TimerItem): View {
        val ctx = requireContext()
        val remaining = TimerMath.display(TimerRepository.remainingMs(ctx, timer))

        val card = MaterialCardView(ctx)
        card.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }
        card.radius = dp(14).toFloat()
        card.setContentPadding(dp(16), dp(14), dp(16), dp(14))

        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(ctx).apply {
                text = remaining
                textSize = 32f
                setTypeface(typeface, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val badgeText = when (timer.state) {
            TimerItem.STATE_FINISHED -> getString(R.string.timer_done)
            TimerItem.STATE_PAUSED -> getString(R.string.timer_paused_badge)
            else -> ""
        }
        if (badgeText.isNotEmpty()) {
            header.addView(
                View(ctx),
                LinearLayout.LayoutParams(0, 1, 1f),
            )
            header.addView(
                TextView(ctx).apply {
                    text = badgeText
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary))
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        column.addView(header)

        column.addView(
            TextView(ctx).apply {
                text = timer.label.ifBlank { getString(R.string.timer_default_label) }
                textSize = 14f
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            },
        )

        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun action(textRes: Int, outlined: Boolean, command: (Context) -> Unit) {
            val button = MaterialButton(
                ctx,
                null,
                if (outlined) com.google.android.material.R.attr.materialButtonOutlinedStyle
                else com.google.android.material.R.attr.materialButtonStyle,
            )
            button.text = getString(textRes)
            button.isAllCaps = false
            button.setOnClickListener {
                val c = context ?: return@setOnClickListener
                command(c)
                lastSignature = null
                if (isAdded) rebuildCards()
            }
            actions.addView(
                button,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(8) },
            )
        }

        when (timer.state) {
            TimerItem.STATE_RUNNING -> {
                action(R.string.timer_pause, false) { c -> TimerRepository.pause(c, timer.id) }
                action(R.string.timer_reset, true) { c -> TimerRepository.reset(c, timer.id) }
                action(R.string.action_add_minute, true) { c ->
                    TimerRepository.addMinute(c, timer.id)
                }
                action(R.string.timer_delete, true) { c -> TimerRepository.delete(c, timer.id) }
            }
            TimerItem.STATE_PAUSED -> {
                action(R.string.timer_resume, false) { c -> TimerRepository.resume(c, timer.id) }
                action(R.string.timer_reset, true) { c -> TimerRepository.reset(c, timer.id) }
                action(R.string.action_add_minute, true) { c ->
                    TimerRepository.addMinute(c, timer.id)
                }
                action(R.string.timer_delete, true) { c -> TimerRepository.delete(c, timer.id) }
            }
            TimerItem.STATE_FINISHED -> {
                action(R.string.action_stop, false) { c ->
                    TimerRepository.stopRinging(c, timer.id)
                }
                action(R.string.timer_delete, true) { c -> TimerRepository.delete(c, timer.id) }
            }
            else -> {
                action(R.string.timer_delete, true) { c -> TimerRepository.delete(c, timer.id) }
            }
        }

        val actionsParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        actionsParams.topMargin = dp(10)
        column.addView(actions, actionsParams)

        card.addView(column)
        return card
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val TICK_MS = 500L
        private const val MIN_MINUTES = 1
        private const val MAX_MINUTES = 999
    }
}
