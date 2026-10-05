// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.lookback

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** 月历里的一天。[dayKey] 与条目的 `dayKey` 同一个格式（ISO 日期），所以它可以直接当筛选值用。 */
data class DayCell(val dayKey: String, val dayOfMonth: Int, val hasEntries: Boolean)

/**
 * 月历的格子算术。纯函数，因为这类代码的错全在边界上：
 * 月初不是周一时前面补几格、闰年二月有几天、周起点是周一还是周日。
 * 这些在屏幕上很好验证，但**只在特定的月份**才会暴露，而一个月只来一次。
 */
object MonthGrid {

    /**
     * 这个月的格子，按 [firstDayOfWeek] 起始，前面用 null 补齐。
     *
     * 补空格而不是裁成 7 的倍数再对齐：UI 需要知道哪一格是空的才能画得整齐，
     * 而让 UI 自己去算「10 月 1 日是星期几」等于把这段算术复制一遍——
     * 两处各算一次，早晚会在月末那一格对不上。
     */
    fun days(
        yearMonth: YearMonth,
        daysWithEntries: Set<String>,
        firstDayOfWeek: DayOfWeek = defaultFirstDayOfWeek(),
    ): List<DayCell?> {
        val leading = leadingBlanks(yearMonth.atDay(1).dayOfWeek, firstDayOfWeek)
        val cells = (1..yearMonth.lengthOfMonth()).map { day ->
            val date = yearMonth.atDay(day)
            val key = date.toString()
            DayCell(dayKey = key, dayOfMonth = day, hasEntries = key in daysWithEntries)
        }
        return List(leading) { null } + cells
    }

    /** 切成整周，尾部补 null 到 7 的倍数（不补的话最后一行会短一截，网格会歪）。 */
    fun weeks(cells: List<DayCell?>): List<List<DayCell?>> {
        val remainder = cells.size % DAYS_IN_WEEK
        val padded = if (remainder == 0) cells else cells + List(DAYS_IN_WEEK - remainder) { null }
        return padded.chunked(DAYS_IN_WEEK)
    }

    /**
     * 星期表头，随系统语言。
     *
     * 用 java.time 的 SHORT 形式而不是自己写七个字符串：这样四套语言（以及用户系统其实是
     * 第五种语言时）都能拿到正确的本地化缩写，不需要为它维护七条 × 四份字符串。
     */
    fun weekdayNames(
        firstDayOfWeek: DayOfWeek = defaultFirstDayOfWeek(),
        locale: Locale = Locale.getDefault(),
    ): List<String> {
        return (0 until DAYS_IN_WEEK).map { offset ->
            firstDayOfWeek.plus(offset.toLong()).getDisplayName(TextStyle.SHORT, locale)
        }
    }

    /** 月初前面空几格。周日起始的 locale（美国等）与周一起始的（绝大多数）都走这一条。 */
    fun leadingBlanks(firstOfMonth: DayOfWeek, firstDayOfWeek: DayOfWeek): Int =
        ((firstOfMonth.value - firstDayOfWeek.value + DAYS_IN_WEEK) % DAYS_IN_WEEK)

    /** 本周第一天是周几，跟着用户的地区设置走；拿不到时退回 ISO 的周一。 */
    fun defaultFirstDayOfWeek(): DayOfWeek = runCatching {
        WeekFields.of(Locale.getDefault()).firstDayOfWeek
    }.getOrDefault(DayOfWeek.MONDAY)

    private const val DAYS_IN_WEEK = 7
}

/** 今天所在的月份。月历打开时停在这里，而不是停在数据里最新的那个月。 */
fun currentMonth(today: LocalDate = LocalDate.now()): YearMonth = YearMonth.from(today)
