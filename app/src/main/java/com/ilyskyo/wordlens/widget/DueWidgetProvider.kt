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
 * 写者视角，而小组件本来就活在同一个进程里。[onUpdate] 不是 suspend，所以数字要晚几十毫秒
 * 才到——那几十毫秒里桌面上留着的是上一次的数（RemoteViews 在桌面有底），不是被清零的占位。
 * 诚实和复制一份仓库之间没有冲突，闪一下 0 才是问题。
 *
 * 「上一次的数」只对**刷新**成立；刚把小部件放上桌面时没有上一次，系统画的是
 * `initialLayout`，所以那份布局里读数的初值是 `@string/widget_count_placeholder`（一个省略号）
 * 而不是数字——别改回 `0`：那是一个谎，而它出现的时机恰恰是用户第一次看这个小部件。
 */
class DueWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val container = (context.applicationContext as? WordLensApplication)?.container
        for (id in appWidgetIds) {
            // 不再先渲染一个 0。原来那样做的理由「第一帧总要显示点什么」站不住：
            // RemoteViews 在桌面里是留底的，没有新数据时 launcher 显示的就是上一次那个数。
            // 于是每复习完一张触发的刷新都会让桌上闪一下 0 再回到 6——
            // 一个会闪的读数比一个晚几十毫秒的读数更不像真的。
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
