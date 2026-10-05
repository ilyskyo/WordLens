// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.core.reminder

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 每日复习提醒的排期。
 *
 * ## 为什么用 WorkManager 而不是 AlarmManager 的精确闹钟
 *
 * 想要「准点 20:00」只有 `setExactAndAllowWhileIdle` 加 `SCHEDULE_EXACT_ALARM` /
 * `USE_EXACT_ALARM` 这条路。为一个「今天想起来复习一下」的提醒去索要一个可以打断系统
 * 调度、并且是滥用重灾区的能力，与这个 App「隐私优先、不无故索权」的定位相反。
 *
 * 所以这里的承诺是诚实的另一句话：**每天在用户选的时间附近送达，可能早或晚十几到几十分钟**
 * （低电、待机、厂商省电策略会更晚）。设置页里也把这句话写给用户看，而不是假装能准点。
 *
 * ## 为什么要让 Worker 自己重新排一次
 *
 * `PeriodicWorkRequest` 的下一次是从**这一次实际执行完**的时间往后推一个间隔。如果执行晚了
 * 20 分钟，锚点就会每天漂移 20 分钟，一周之后 20:00 就变成了 23:20。Worker 在发完通知之后
 * 调 [sync] 重排，等于是「重新对着用户选的那个分钟对齐一次」，把漂移掐掉。
 */
class ReminderScheduler(private val context: Context) {

    /**
     * 让排期与设置一致。开关、时间任意一项变化都要走这里；启动时也走一次，
     * 因为用户可能几天没打开 App，而工作可能被系统清掉了。
     */
    fun sync(enabled: Boolean, minuteOfDay: Int) {
        ReminderNotifications.ensureChannel(context)
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(UNIQUE_NAME)
            return
        }
        val delay = ReminderPlan.delayUntilNextOccurrence(
            nowMillis = System.currentTimeMillis(),
            minuteOfDay = minuteOfDay,
        )
        val request = PeriodicWorkRequestBuilder<DueReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        // UPDATE 而不是 KEEP：用户改了分钟数必须立刻生效，
        // 而 KEEP 会让新设置看起来「拨了没用」——那是这个项目里最不能出现的一种 bug 形状。
        workManager.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private companion object {
        /** 唯一名。整个 App 只该有一个复习提醒在排。 */
        const val UNIQUE_NAME = "wordlens_due_reminder"
    }
}
