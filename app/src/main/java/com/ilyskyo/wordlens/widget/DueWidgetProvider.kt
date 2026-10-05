// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.ilyskyo.wordlens.MainActivity
import com.ilyskyo.wordlens.R
import com.ilyskyo.wordlens.WordLensApplication
import kotlinx.coroutines.launch

/**
 * 桌面小组件：今日待复习数。
 *
 * 读数走 [com.ilyskyo.wordlens.core.AppContainer] 而不是另建一份仓库：`deck.json` 只能有一个
 * 写者视角，而小组件本来就活在同一个进程里。[onUpdate] 不是 suspend，所以第一帧显示 0，
 * DataStore 答出复习方向之后立刻换成真数——晚几十毫秒是诚实，复制一份仓库不是。
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
            setOnClickPendingIntent(R.id.widget_due_root, openApp(context))
        }
        manager.updateAppWidget(widgetId, views)
    }

    /** 点小组件就是要去复习，把它落在时间轴上等于让这次点击白给。 */
    private fun openApp(context: Context): PendingIntent {
        val launch = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_REMEMBER, true)
        }
        return PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "DueWidget"

        /**
         * 复习完一张就主动推一把。
         *
         * `updatePeriodMillis` 的系统下限是 30 分钟，只靠它的话，桌面上那个数会在「还剩 7 个」
         * 上停半小时——而小组件唯一的意义就是「现在有没有得做」。
         */
        fun refresh(context: Context) {
            val ids = runCatching {
                AppWidgetManager.getInstance(context)
                    .getAppWidgetIds(ComponentName(context, DueWidgetProvider::class.java))
            }.getOrNull() ?: return
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, DueWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                },
            )
        }
    }
}
