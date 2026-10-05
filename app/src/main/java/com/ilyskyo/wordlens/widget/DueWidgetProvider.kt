package com.ilyskyo.wordlens.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.util.Log
import android.widget.RemoteViews
import com.ilyskyo.wordlens.R
import com.ilyskyo.wordlens.WordLensApplication
import kotlinx.coroutines.launch

/**
 * Home-screen widget showing how many cards are due today.
 *
 * The count is read through the app's [com.ilyskyo.wordlens.core.AppContainer] rather than a
 * second copy of the repositories: deck.json must have exactly one writer-process view of
 * itself, and the widget lives in that same process anyway.
 *
 * [AppWidgetProvider.onUpdate] is not suspend, so the first frame renders 0 and the real
 * number replaces it as soon as DataStore answers with the study direction. A stale-by-
 * milliseconds count is honest; a duplicate repository would not be.
 */
class DueWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val container = (context.applicationContext as? WordLensApplication)?.container
        for (id in appWidgetIds) {
            render(context, appWidgetManager, id, due = 0)
            container?.applicationScope?.launch {
                val due = runCatching { container.dueCount() }.getOrDefault(0)
                render(context, appWidgetManager, id, due)
            }
        }
        Log.d(TAG, "onUpdate: ${appWidgetIds.size} widget(s)")
    }

    private fun render(context: Context, manager: AppWidgetManager, widgetId: Int, due: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_due).apply {
            setTextViewText(R.id.widget_due_count, due.toString())
            setTextViewText(R.id.widget_due_title, context.getString(R.string.widget_due_label))
        }
        manager.updateAppWidget(widgetId, views)
    }

    private companion object {
        const val TAG = "DueWidget"
    }
}
