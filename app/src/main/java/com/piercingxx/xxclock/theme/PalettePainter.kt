package com.piercingxx.xxclock.theme

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.AnalogClock
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.TimePicker
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputLayout
import com.piercingxx.xxclock.R

/**
 * Paints a view tree from a [ClockPalette] so a family preset restyles cards,
 * type, chips, nav, and buttons — not only the window ground.
 */
@Suppress("DEPRECATION")
object PalettePainter {

    const val TAG_KEEP = "theme_keep"

    fun apply(root: View?, palette: ClockPalette) {
        if (root == null) return
        walk(root, palette)
    }

    private fun walk(view: View, palette: ClockPalette) {
        if (view.tag == TAG_KEEP || view.id == R.id.theme_row_swatch) return
        if (view is AnalogClock || view is TimePicker) return

        when (view) {
            is BottomNavigationView -> {
                paintNav(view, palette)
                return
            }
            is FloatingActionButton -> paintFab(view, palette)
            is MaterialCardView -> {
                view.setCardBackgroundColor(palette.surfaceMid.toInt())
                view.strokeColor = palette.outlineFaint.toInt()
            }
            is Chip -> paintChip(view, palette)
            is CompoundButton -> paintSwitch(view, palette)
            is MaterialButton -> paintButton(view, palette)
            is TextInputLayout -> paintInput(view, palette)
            is EditText -> {
                view.setTextColor(palette.onSurface.toInt())
                view.setHintTextColor(palette.onSurfaceMuted.toInt())
            }
            is TextView -> paintText(view, palette)
            is ImageView -> {
                view.imageTintList = ColorStateList.valueOf(palette.onSurfaceMuted.toInt())
            }
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                walk(view.getChildAt(i), palette)
            }
        }
    }

    private fun paintText(view: TextView, palette: ClockPalette) {
        val accentIds = setOf(R.id.clock_time, R.id.theme_row_check)
        val color = when {
            view.id in accentIds -> palette.accent
            view.textSize < 16f * view.resources.displayMetrics.scaledDensity -> palette.onSurfaceMuted
            else -> palette.onSurface
        }
        view.setTextColor(color.toInt())
    }

    private fun paintButton(view: MaterialButton, palette: ClockPalette) {
        if (view.strokeWidth > 0) {
            view.strokeColor = ColorStateList.valueOf(palette.outline.toInt())
            view.backgroundTintList = ColorStateList.valueOf(palette.surfaceMid.toInt())
            view.setTextColor(palette.onSurface.toInt())
        } else {
            view.backgroundTintList = ColorStateList.valueOf(palette.accent.toInt())
            view.setTextColor(palette.onAccent.toInt())
        }
    }

    private fun paintChip(view: Chip, palette: ClockPalette) {
        val bg = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(palette.accent.toInt(), palette.surfaceHigh.toInt()),
        )
        val fg = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(palette.onAccent.toInt(), palette.onSurface.toInt()),
        )
        view.chipBackgroundColor = bg
        view.setTextColor(fg)
    }

    private fun paintSwitch(view: CompoundButton, palette: ClockPalette) {
        val thumb = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(palette.accent.toInt(), palette.onSurfaceMuted.toInt()),
        )
        val track = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(palette.outline.toInt(), palette.outlineFaint.toInt()),
        )
        view.buttonTintList = thumb
        view.backgroundTintList = track
    }

    private fun paintFab(view: FloatingActionButton, palette: ClockPalette) {
        view.backgroundTintList = ColorStateList.valueOf(palette.accent.toInt())
        view.imageTintList = ColorStateList.valueOf(palette.onAccent.toInt())
    }

    private fun paintNav(view: BottomNavigationView, palette: ClockPalette) {
        view.setBackgroundColor(palette.background.toInt())
        val colors = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(palette.accent.toInt(), palette.onSurfaceMuted.toInt()),
        )
        view.itemIconTintList = colors
        view.itemTextColor = colors
    }

    private fun paintInput(view: TextInputLayout, palette: ClockPalette) {
        view.setBoxStrokeColorStateList(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(palette.accent.toInt(), palette.outline.toInt()),
            ),
        )
        view.hintTextColor = ColorStateList.valueOf(palette.onSurfaceMuted.toInt())
        view.defaultHintTextColor = ColorStateList.valueOf(palette.onSurfaceMuted.toInt())
    }
}
