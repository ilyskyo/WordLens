// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.remember

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 间隔读数的分档。
 *
 * 这一层唯一的失败模式不是崩，而是**说出人话以外的东西**：把 45 天念成「45 天」、
 * 把 800 天念成「1年+」。所以这里断言的是边界值，而不是格式化后的字符串——
 * 字符串属于资源文件，边界才属于逻辑。
 */
class IntervalFormatTest {

    @Test
    fun `zero and negative days read as now`() {
        assertEquals(IntervalUnit.Now, intervalQuantity(0).unit)
        assertEquals(IntervalUnit.Now, intervalQuantity(-3).unit)
    }

    @Test
    fun `days are used up to a month`() {
        assertEquals(IntervalQuantity(IntervalUnit.Days, 1), intervalQuantity(1))
        assertEquals(IntervalQuantity(IntervalUnit.Days, 29), intervalQuantity(29))
    }

    @Test
    fun `weeks take over where day counts stop being readable`() {
        // 31 天是 4.4 周 → 报 4 周。这里刻意不做「向上取整」：把还没到的时间说成已到，
        // 在复习界面上就是「我以为明天不用来了结果今天要复习 12 张」。
        assertEquals(IntervalQuantity(IntervalUnit.Weeks, 4), intervalQuantity(31))
        assertEquals(IntervalQuantity(IntervalUnit.Weeks, 5), intervalQuantity(35))
        assertEquals(IntervalQuantity(IntervalUnit.Weeks, 10), intervalQuantity(73))
    }

    @Test
    fun `months are rounded down to avoid overstating progress`() {
        // 74 天是 2.43 个月 → 报 2 而不是 3：宁可少报，也不把还没到的时间说成已到。
        assertEquals(IntervalQuantity(IntervalUnit.Months, 2), intervalQuantity(74))
        assertEquals(IntervalQuantity(IntervalUnit.Months, 4), intervalQuantity(120))
        assertEquals(IntervalQuantity(IntervalUnit.Months, 12), intervalQuantity(365))
    }

    @Test
    fun `a year is a year and anything beyond reports the real number`() {
        assertEquals(IntervalQuantity(IntervalUnit.Year, 1), intervalQuantity(800))
        // 1096 天起进「年」这一档，并且报真实的年数。
        assertEquals(IntervalUnit.YearsPlus, intervalQuantity(1096).unit)
        assertEquals(3, intervalQuantity(1096).value)
        assertEquals(4, intervalQuantity(1461).value)
        assertEquals(IntervalUnit.YearsPlus, intervalQuantity(3650).unit)
        assertTrue("十年不该被截成「1年+」", intervalQuantity(3650).value >= 10)
    }

    @Test
    fun `bucketing never produces a zero count`() {
        // 「0 周」「0 个月」在界面上比「现在」更让人困惑，coerceAtLeast(1) 是这条底线。
        listOf(30, 40, 74, 100, 200, 400, 700, 1200, 5000).forEach { days ->
            val quantity = intervalQuantity(days)
            when (quantity.unit) {
                IntervalUnit.Now, IntervalUnit.Year -> Unit
                else -> assertTrue("$days -> $quantity", quantity.value >= 1)
            }
        }
    }

    @Test
    fun `durations collapse to seconds only under a minute`() {
        assertEquals("0s", formatDuration(0))
        assertEquals("59s", formatDuration(59))
        assertEquals("1m 0s", formatDuration(60))
        assertEquals("3m 34s", formatDuration(214))
    }
}
