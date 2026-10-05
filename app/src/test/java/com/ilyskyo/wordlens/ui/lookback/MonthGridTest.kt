// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.lookback

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 月历的格子算术。
 *
 * 这里的错全在边界上，而边界**一个月才轮到一次**：月初恰好是周一时补几格、闰年二月是不是
 * 29 格、周日起始的 locale 会不会整行错位。等它在屏幕上被发现，用户已经看到一张歪掉的日历，
 * 而下次复现要等到下个月。所以全部在 JVM 上钉死。
 */
class MonthGridTest {

    // 2026-01-01 是星期四；2026 不是闰年，1 月 31 天。
    private val january = YearMonth.of(2026, 1)

    @Test
    fun `leading blanks follow the week start`() {
        // 周四开头，周一为一周之始 → 前面空 3 格（一、二、三）。
        assertEquals(3, MonthGrid.leadingBlanks(DayOfWeek.THURSDAY, DayOfWeek.MONDAY))
        // 同一句话，换成周日为一周之始 → 空 4 格（日、一、二、三）。
        assertEquals(4, MonthGrid.leadingBlanks(DayOfWeek.THURSDAY, DayOfWeek.SUNDAY))
    }

    @Test
    fun `a month that starts on the week start needs no blanks`() {
        // 2027-02-01 是星期一，且 2 月正好 28 天——补格逻辑最容易被跳过的那个分支。
        val february = YearMonth.of(2027, 2)
        assertEquals(0, MonthGrid.leadingBlanks(february.atDay(1).dayOfWeek, DayOfWeek.MONDAY))

        val cells = MonthGrid.days(february, emptySet(), DayOfWeek.MONDAY)
        assertEquals(28, cells.size)
        assertEquals(4, MonthGrid.weeks(cells).size)
        // 28 已是 7 的倍数，不许再补出第 5 行空白。
        assertTrue(MonthGrid.weeks(cells).all { week -> week.all { it != null } })
    }

    @Test
    fun `cells are the leading blanks plus every day of the month`() {
        val cells = MonthGrid.days(january, emptySet(), DayOfWeek.MONDAY)
        assertEquals(3 + 31, cells.size)
        assertTrue(cells.take(3).all { it == null })
        val days = cells.drop(3)
        assertEquals((1..31).toList(), days.map { it!!.dayOfMonth })
        // 月末之后不许再补 null——那是 weeks() 的活，两者混在一起就没法单独验证。
        assertTrue(days.none { it == null })
    }

    @Test
    fun `day keys are ISO dates so they can be used as filter values directly`() {
        val cells = MonthGrid.days(january, emptySet(), DayOfWeek.MONDAY)
        assertEquals("2026-01-01", cells[3]!!.dayKey)
        assertEquals("2026-01-31", cells.last()!!.dayKey)
    }

    @Test
    fun `leap february has 29 cells`() {
        // 数的是「有日期的格子」，不是整张网格——月初补的那几格不算天数。
        fun dayCount(month: YearMonth) =
            MonthGrid.days(month, emptySet(), DayOfWeek.MONDAY).count { it != null }

        // 2028 是闰年：少一天或多一天都会让月末那几格整体错位。
        assertEquals(29, dayCount(YearMonth.of(2028, 2)))
        // 2027 的二月是 28 天，放在一起是防止「闰年判断写成了能被 4 整除」这类近似。
        assertEquals(28, dayCount(YearMonth.of(2027, 2)))
        // 2100 能被 4 整除、也能被 100 整除，但除不尽 400——所以不是闰年。
        assertEquals(28, dayCount(YearMonth.of(2100, 2)))
        // 2000 能除尽 400——是闰年。这两条一起把整条规则钉住。
        assertEquals(29, dayCount(YearMonth.of(2000, 2)))
    }

    @Test
    fun `weeks pad the last row to seven`() {
        val weeks = MonthGrid.weeks(MonthGrid.days(january, emptySet(), DayOfWeek.MONDAY))
        assertEquals(5, weeks.size)
        assertTrue(weeks.all { it.size == 7 })
        // 34 格 → 补 1 格到 35。这一格是布局用的占位，必须存在，否则最后一行会短一截。
        assertNull(weeks.last().last())
    }

    @Test
    fun `entry markers land on the right day`() {
        val cells = MonthGrid.days(january, setOf("2026-01-01", "2026-01-17"), DayOfWeek.MONDAY)
        val marked = cells.filterNotNull().filter { it.hasEntries }.map { it.dayOfMonth }
        assertEquals(listOf(1, 17), marked)
    }

    @Test
    fun `weekday names start at the given first day`() {
        // 固定 Locale.US，让断言不随跑测试的机器变。
        assertEquals(
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"),
            MonthGrid.weekdayNames(DayOfWeek.MONDAY, Locale.US),
        )
        assertEquals(
            listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"),
            MonthGrid.weekdayNames(DayOfWeek.SUNDAY, Locale.US),
        )
    }

    @Test
    fun `current month comes from the given day`() {
        assertEquals(YearMonth.of(2026, 10), currentMonth(LocalDate.of(2026, 10, 5)))
        // 月末与月初属于同一个月，不会因为「离下一月更近」而偏。
        assertEquals(YearMonth.of(2026, 10), currentMonth(LocalDate.of(2026, 10, 31)))
        assertEquals(YearMonth.of(2026, 11), currentMonth(LocalDate.of(2026, 11, 1)))
    }
}
