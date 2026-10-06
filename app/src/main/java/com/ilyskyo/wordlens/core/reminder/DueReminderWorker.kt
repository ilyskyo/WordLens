// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.core.reminder

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ilyskyo.wordlens.WordLensApplication
import com.ilyskyo.wordlens.core.AppContainer
import kotlinx.coroutines.flow.first

/**
 * 到点之后真正做事的那一步：数一遍今天还剩几张，发一条通知。
 *
 * 读的是 [AppContainer.dueCount]，与桌面小组件同一个入口。两处各数一遍的话，
 * 「通知说还剩 3 张、桌面上写着 5 张」这种不一致迟早会出现，而用户会当成 bug 报。
 */
class DueReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? WordLensApplication)?.container
            ?: return Result.success()

        val settings = container.settings.settings.first()
        // 用户已经关掉了：什么都不发，也**不再重排**。
        // 关掉开关之后还继续每天被通知，是这个功能最常见也最招人恨的实现错误。
        if (!settings.reminderEnabled) return Result.success()

        // 读不到数就**重试**，而不是按 0 处理。按 0 走的话 `showDue` 会安静地跳过，
        // 于是「今天没来提醒」既没有通知也没有任何痕迹，而 DataStore 偶发的读失败是 transient 的：
        // WorkManager 隔一会儿再试一次就能补上，用户根本不会知道中间失败过——这正是它该做的事。
        // （反过来，上面那个 container 为 null 的分支仍然返回 success：那是永久性状况，
        // 重试只会一直耗到运行次数上限，什么也换不来。）
        val due = runCatching { container.dueCount() }.getOrElse {
            Log.w(TAG, "due count failed; retrying", it)
            return Result.retry()
        }

        // 队列为空时 showDue 内部自己会跳过：一条「今天没有要复习的」在每天 20:00 出现，
        // 读起来像打卡机而不是像助手。
        ReminderNotifications.showDue(applicationContext, due)

        // 重新对齐到用户选的那个分钟，把系统调度带来的漂移吃掉。见 ReminderScheduler 的说明。
        ReminderScheduler(applicationContext).sync(
            enabled = true,
            minuteOfDay = settings.reminderMinuteOfDay,
        )
        return Result.success()
    }

    private companion object {
        const val TAG = "DueReminderWorker"
    }
}
