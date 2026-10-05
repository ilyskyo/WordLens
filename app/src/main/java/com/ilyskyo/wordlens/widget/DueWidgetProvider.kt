package com.ilyskyo.wordlens.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.util.Log
import android.widget.RemoteViews
import com.ilyskyo.wordlens.R

/**
 * Home-screen widget showing how many cards are due today.
 *
 * Milestone 1 keeps this deliberately dumb: it renders a placeholder. The deck repository
 * lands in milestone 3 and the count becomes real there.
 */
class DueWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_due).apply {
                setTextViewText(R.id.widget_due_count, "0")
                setTextViewText(R.id.widget_due_title, context.getString(R.string.widget_due_label))
            }
            appWidgetManager.updateAppWidget(id, views)
        }
        Log.d(TAG, "onUpdate: ${appWidgetIds.size} widget(s)")
    }

    private companion object {
        const val TAG = "DueWidget"
    }
}
