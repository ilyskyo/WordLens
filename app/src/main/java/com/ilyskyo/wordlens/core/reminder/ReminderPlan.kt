// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.core.reminder

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 「下一次提醒该在什么时候」的纯算术。
 *
 * 单独抽出来只有一个原因：这一类的错全在边界上——23:59、00:00、夏令时切换的那天没有这个
 * 分钟、以及「用户刚打开开关时今天的时间点已经过了」。这些在真机上验证一次要等到明天，
 * 而在 JVM 上是四行断言。
 */
object ReminderPlan {

    /**
     * 从现在起，到下一个 [minuteOfDay]（本地时区的「几点几分」）还有多少毫秒。
     *
     * 语义：如果今天这个时刻还没到，就等今天；已经过去（或正好此刻），就等**明天**。
     * 这条「不返回 0 或负数」的性质很重要：WorkManager 拿到负的 initialDelay 行为未定义，
     * 而返回 0 会让用户刚把开关拨上去就被通知轰炸一次。
     *
     * 用 ZonedDateTime 而不是 `System.currentTimeMillis() % 86400000` 那类取模算法：
     * 后者在夏令时切换的日子会算错一小时，而且对 UTC 偏移的假设在有历史时区规则的地方
     * （例如部分南美洲地区）直接是错的。
     */
    fun delayUntilNextOccurrence(
        nowMillis: Long,
        minuteOfDay: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val minute = minuteOfDay.coerceIn(0, MINUTE_OF_DAY_MAX)
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val todayTarget = now.toLocalDate().atStartOfDay(zone).plusMinutes(minute.toLong())
        val target = if (todayTarget.isAfter(now)) todayTarget else todayTarget.plusDays(1)
        return millisBetween(now, target)
    }

    /** 把「几点几分」写成给人看的文本；分钟补零，否则 20:05 会显示成「20:5」。 */
    fun formatMinuteOfDay(minuteOfDay: Int): String {
        val minute = minuteOfDay.coerceIn(0, MINUTE_OF_DAY_MAX)
        val hour = minute / 60
        val rest = minute % 60
        return "%02d:%02d".format(hour, rest)
    }

    private fun millisBetween(from: ZonedDateTime, to: ZonedDateTime): Long =
        // Duration 作用于 ZonedDateTime 时算的是**真实经过的时间**：夏令时切换那天本地时钟
        // 只有 23 小时或多一小时，而调度器要知道的正是「还要等多久」，不是「墙钟差多少」。
        // 自己用「天数 × 1440 分 + 时刻差」去拼，就会在那一天错一小时。
        java.time.Duration.between(from, to).toMillis()

    /** 一天的最后一分钟：23:59 = 1439。 */
    const val MINUTE_OF_DAY_MAX = 24 * 60 - 1
}
