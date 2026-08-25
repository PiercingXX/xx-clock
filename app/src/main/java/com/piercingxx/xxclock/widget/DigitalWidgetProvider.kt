package com.piercingxx.xxclock.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.piercingxx.xxclock.Actions
import com.piercingxx.xxclock.R
import com.piercingxx.xxclock.repo.AlarmRepository
import com.piercingxx.xxclock.util.Fmt

/**
 * Home-screen digital clock widget.
 *
 * Time/date are self-updating TextClocks; this provider only pushes the
 * next-alarm line (and re-renders on time/timezone changes). The core engine
 * calls [refreshAll] after every alarm mutation.
 */
class DigitalWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = buildViews(context)
        for (appWidgetId in appWidgetIds) {
            manager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AppWidgetManager.ACTION_APPWIDGET_UPDATE,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Actions.REFRESH_WIDGET,
            -> renderAll(context)

            else -> super.onReceive(context, intent)
        }
    }

    private fun renderAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = assignedIds(context)
        if (ids.isEmpty()) return
        manager.updateAppWidget(ids, buildViews(context))
    }

    private fun buildViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.clock_widget)
        views.setOnClickPendingIntent(R.id.widget_root, mainActivityPendingIntent(context))

        val next = AlarmRepository.nextArmed(context)
        if (next == null) {
            views.setViewVisibility(R.id.widget_next_alarm, View.GONE)
        } else {
            val (alarm, firesAt) = next
            val label = alarm.label.trim()
            val time = Fmt.time(context, firesAt)
            val line =
                if (label.isEmpty()) time
                else context.getString(R.string.widget_next_alarm_line, label, time)
            views.setTextViewText(R.id.widget_next_alarm, line)
            views.setViewVisibility(R.id.widget_next_alarm, View.VISIBLE)
        }
        return views
    }

    private fun mainActivityPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent().setClassName(context, MAIN_ACTIVITY_CLASS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        /** Referenced by class name so the widget compiles independently of UI sources. */
        private const val MAIN_ACTIVITY_CLASS = "com.piercingxx.xxclock.ui.MainActivity"

        /**
         * Re-renders every assigned widget via an explicit self-broadcast;
         * called by the core engine after mutations.
         */
        @JvmStatic
        fun refreshAll(context: Context) {
            val update = Intent(context, DigitalWidgetProvider::class.java)
                .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, assignedIds(context))
            context.sendBroadcast(update)
        }

        private fun assignedIds(context: Context): IntArray =
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, DigitalWidgetProvider::class.java))
    }
}
