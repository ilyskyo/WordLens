// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.remember

import kotlin.math.roundToInt

/** 间隔的量级。渲染层据此选字符串或复数，不自己判断天数。 */
enum class IntervalUnit { Now, Days, Weeks, Months, Year, YearsPlus }

/** 量级 + 该量级下的读数。`Now` 与 `Year` 的 value 没有意义（界面上不显示数字）。 */
data class IntervalQuantity(val unit: IntervalUnit, val value: Int)

/**
 * 把「多少天后复习」压成人类会说的说法。
 *
 * 分界点的理由，按「这个读数还能不能传达精度」来定：
 *
 * - **< 30 天用天**：两周内的计划人们按天想；写成「2周」反而不如「14 天」精确。
 * - **30–73 天用周**：这一段用天会变成 45 天这种既占地方又读不出量级的数；
 *   用月又太粗（45 天说成「1 个半月」是废话）。
 * - **74–730 天用月**：再往上「23 周」已经要求用户心算了，而 8 个月是直觉可数的。
 *   月数按 30.44 天折算并**四舍五入**：74 天说成 2 个月而不是 3 个月，
 *   宁可少报也不夸大已经达成的进度。
 * - **一年整单独一档**：一年以上的读数用「年」，且 2 年起给出真实数字，
 *   不再像上一版那样统一写「1年+」——一张排到 4 年后的卡说「1年+」既没信息量，
 *   也让人怀疑排期器是不是坏掉了。
 */
fun intervalQuantity(days: Int): IntervalQuantity = when {
    days <= 0 -> IntervalQuantity(IntervalUnit.Now, 0)
    days < 30 -> IntervalQuantity(IntervalUnit.Days, days)
    days < 74 -> IntervalQuantity(IntervalUnit.Weeks, (days / 7.0).roundToInt().coerceAtLeast(1))
    days < 731 -> IntervalQuantity(IntervalUnit.Months, (days / DAYS_PER_MONTH).roundToInt().coerceAtLeast(1))
    days < 1096 -> IntervalQuantity(IntervalUnit.Year, 1)
    else -> IntervalQuantity(IntervalUnit.YearsPlus, (days / DAYS_PER_YEAR).roundToInt().coerceAtLeast(2))
}

private const val DAYS_PER_MONTH = 30.44
private const val DAYS_PER_YEAR = 365.25
