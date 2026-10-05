// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.ilyskyo.wordlens.data.model.RatingPalette
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评级配色的对比度不变量。
 *
 * ## 为什么这套颜色需要一条测试而不是一次人工核对
 *
 * 四档按钮的字色与底色都是**推导**出来的（[tones]：底色 = 纸里掺色相，字色 = 色相往极值推），
 * 推导公式改一个数，二十四种组合（三套色系 × 四档 × 深浅两种主题）会一起动，
 * 而人眼只能看出「这一档好像淡了」，看不出淡到了 AA 线以下。
 * 一次改色相就是一次静默的无障碍回归——那种回归不会让任何一屏崩掉，只会让一部分人读不清。
 *
 * ## 阈值定在 4.5 而不是当前实测的 6.63
 *
 * 6.63 是**此刻**的最低值（深色 Warm/hard），拿它当断言等于把配色锁死，
 * 下一次为了让四档更像而微调掺色比例就会红。4.5 是 WCAG AA 的正文线，
 * 也是这套配色真正的承诺：低于它才算坏。
 *
 * 颜色算式与 [tones] 保持一致地用 sRGB 线性化，不用感知均匀空间——
 * 因为屏幕上真正渲染的就是这条路径，测的必须是产物而不是理想。
 */
class RatingContrastTest {

    private val pairs: List<Pair<String, Pair<Color, Color>>>
        get() = buildList {
            RatingPalette.entries.forEach { palette ->
                LIGHT_AND_DARK.forEach { (label, dark) ->
                    val colors = palette.hues().tones(dark)
                    listOf(
                        "again" to colors.again,
                        "hard" to colors.hard,
                        "good" to colors.good,
                        "easy" to colors.easy,
                    ).forEach { (grade, tone) ->
                        add("$label/$palette/$grade" to (tone.ink to tone.container))
                    }
                }
            }
        }

    @Test
    fun `every grade label clears WCAG AA against its own button fill`() {
        pairs.forEach { (name, pair) ->
            val (ink, container) = pair
            val ratio = contrast(ink, container)
            assertTrue(
                "$name 的对比只有 ${fmt(ratio)}（字 ${hexOf(ink)} / 底 ${hexOf(container)}）",
                ratio >= AA_BODY,
            )
        }
    }

    @Test
    fun `ink is pushed away from the paper in each theme's own direction`() {
        // 浅色主题把字往黑压，深色主题往白提——这个方向是 [tones] 的整条设计前提。
        // 写成断言是因为它一旦被写反，界面上只会「深色模式淡成一片」，没人会想到是符号反了。
        RatingPalette.entries.forEach { palette ->
            val colors = palette.hues()
            val light = colors.tones(dark = false)
            val dark = colors.tones(dark = true)
            listOf(light.again, light.hard, light.good, light.easy).forEach { tone ->
                assertTrue("浅色主题的字必须比它的底暗", luminance(tone.ink) < luminance(tone.container))
            }
            listOf(dark.again, dark.hard, dark.good, dark.easy).forEach { tone ->
                assertTrue("深色主题的字必须比它的底亮", luminance(tone.ink) > luminance(tone.container))
            }
        }
    }

    @Test
    fun `the light theme's fill stays paper, not painted`() {
        // 「掺 18% 色相」的意义是读得出「这是绿的」，但按钮底仍然是纸。
        // 掺到一半就不是配色而是色块了——那条线用亮度来表达最稳，色相怎么调都不该越过。
        RatingPalette.entries.forEach { palette ->
            palette.hues().tones(dark = false).let { colors ->
                listOf(colors.again, colors.hard, colors.good, colors.easy).forEach { tone ->
                    assertTrue(
                        "浅色底太深：${hexOf(tone.container)}",
                        luminance(tone.container) >= LIGHT_PAPER_FLOOR,
                    )
                }
            }
        }
    }

    @Test
    fun `the muted scheme really is the flatter one`() {
        // 低饱和那套的存在理由就写在它的注释里：四档之间明度更接近，给对强色刺激敏感的人，
        // 靠字与图标而不是靠颜色区分。如果哪天它比 Warm 还跳，这个「为什么存在」就没人认了。
        val spread: (RatingHues) -> Double = { hues ->
            val l = listOf(hues.again, hues.hard, hues.good, hues.easy).map { luminance(it) }
            (l.maxOf { it } - l.minOf { it }).toDouble()
        }
        val warm = spread(RatingSchemes.Warm)
        val muted = spread(RatingSchemes.Muted)
        // 只断言这一对：Muted 与 Cool 的差值实测只有 0.003，那种量级的比较不是承诺，是巧合。
        assertTrue("Muted 的四档明度跨度（$muted）本该比 Warm（$warm）更小", muted < warm)
    }

    // ── 算式 ──────────────────────────────────────────────────────────────────

    /** WCAG 的相对亮度：线性化之后按 0.2126 / 0.7152 / 0.0722 加权。 */
    private fun luminance(color: Color): Double =
        0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)

    private fun linear(channel: Float): Double {
        val c = channel.toDouble()
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** 把推导出来的颜色写成 #RRGGBB 打进失败信息里，不然「0.3 不够」这种话没人能对着改。 */
    private fun hexOf(color: Color): String = String.format("#%06X", color.toArgb() and 0xFFFFFF)

    private fun fmt(ratio: Double): String = String.format("%.2f", ratio)

    private companion object {
        const val AA_BODY = 4.5

        /**
         * 浅色的按钮底至少要保住的亮度。
         *
         * 十二种浅色底实测落在 0.749–0.811（最暗的是 Muted/again 的 `#F3DBDD`）。
         * 0.60 留了一整档掺色比例的余量：把「掺 18%」调到 30% 还过得去，
         * 调到「底就是色相本身」一定会红——那种底上放什么都读不清。
         */
        const val LIGHT_PAPER_FLOOR = 0.60

        val LIGHT_AND_DARK = listOf("浅色" to false, "深色" to true)
    }
}
