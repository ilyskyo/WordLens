// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * 小组件与冷启动那批 XML 颜色的对比度。
 *
 * ## 为什么主题之外还要一条对比度测试
 *
 * App 里其余颜色都在 Kotlin 常量上，`RatingContrastTest` 那种断言够得着；小组件与 splash
 * 画的是 `res/values` 与 `res/values-night` 下的 `colors.xml`，Compose 的运行时压根不经过它们。而小组件恰好是这个 App 里
 * **唯一一处会在别人的背景下被看到**的表面：启动器可能把它画在深色壁纸上、可能强制暗色、
 * 可能给 RemoteViews 加一层淡色蒙版。这类错在开发机上看不出来——要看的是装到桌面之后那一眼。
 *
 * 所以这里不放「我看着顺眼」的结论，而是把 WCAG 的相对亮度公式自己写一遍算出来。
 * 公式只有三行，而它给的是一个能复查的数字：13sp 的标签要 4.5:1，34sp 粗体的读数按
 * 大字号算只要 3:1——两个门槛不一样，混成一个数会让标签那条永远松一档。
 *
 * 颜色是 `#AARRGGBB` 八位形式（AAPT 允许，而本项目全部这么写），所以取**后六位**。
 * 上一轮我自己在这里犯过一次：按前六位切，把 alpha 当成了红色的开始，得到一堆
 * 1.04:1 这种荒谬的数——那一版全部作废重算。这条测试里的切法有 `bad hex` 那一行守着：
 * 长度不对就当场失败，不许安静地算错。
 */
class WidgetContrastTest {

    private val res: File by lazy { findResDir() }

    private data class Pair(val name: String, val foreground: String, val background: String, val min: Double)

    @Test
    fun `the widget's day palette clears WCAG AA`() {
        assertPalette(File(res, "values/colors.xml"), "day")
    }

    @Test
    fun `the widget's night palette clears WCAG AA`() {
        assertPalette(File(res, "values-night/colors.xml"), "night")
    }

    private fun assertPalette(file: File, tag: String) {
        val colors = colorsOf(file)
        // 34sp 粗体属于 WCAG 的「大字」（≥18.66px 粗体或 ≥24px 常规），门槛 3:1；
        // 13sp 的标签是普通字号，门槛 4.5:1。写在这里而不是抄一个统一数，是为了让
        // 「哪天有人把读数改小」这件事会立刻打回，而不是悄悄降到 AA 以下。
        val pairs = listOf(
            Pair("widget_label", colors.getValue("widget_label"), colors.getValue("widget_background"), 4.5),
            Pair("widget_count", colors.getValue("widget_count"), colors.getValue("widget_background"), 3.0),
        )
        pairs.forEach { p ->
            val ratio = contrast(p.foreground, p.background)
            assertTrue(
                "$tag/${p.name} 只有 ${ratio.format2()}:1（门槛 ${p.min}）" +
                    " —— fg=${p.foreground} bg=${p.background}",
                ratio >= p.min,
            )
        }
    }

    // ── WCAG 2.1 相对亮度与对比度 ────────────────────────────────────────────

    private fun contrast(foreground: String, background: String): Double {
        val a = relativeLuminance(foreground)
        val b = relativeLuminance(background)
        val hi = maxOf(a, b)
        val lo = minOf(a, b)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun relativeLuminance(color: String): Double {
        val rgb = rgbOf(color)
        val channels = rgb.map { channel ->
            val c = channel / 255.0
            if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]
    }

    /** 取后六位；`#RGB`、`#RRGGBB`、`#AARRGGBB` 之外的长度一律当场失败。 */
    private fun rgbOf(color: String): List<Int> {
        val digits = color.removePrefix("#")
        val hex = when (digits.length) {
            3 -> digits.map { "$it$it" }.joinToString("")
            6 -> digits
            8 -> digits.takeLast(6)
            else -> throw AssertionError("认不出的颜色写法：$color")
        }
        assertTrue("切出来的十六进制长度不对：$color -> $hex", hex.length == 6)
        return listOf(0, 2, 4).map { hex.substring(it, it + 2).toInt(16) }
    }

    private fun Double.format2(): String = String.format("%.2f", this)

    // ── 读取 ────────────────────────────────────────────────────────────────

    private fun colorsOf(file: File): Map<String, String> {
        assertTrue("颜色资源不存在：$file", file.isFile)
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val nodes = root.childNodes
        val out = LinkedHashMap<String, String>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? Element ?: continue
            if (node.tagName == "color") out[node.getAttribute("name")] = node.getTextContent().trim()
        }
        assertTrue("$file 里一个 <color> 都没有：资源文件读错了？", out.isNotEmpty())
        return out
    }

    /** 与 `StringsDisciplineTest` 同一套做法：逐级往上找，不赌测试的工作目录在哪一层。 */
    private fun findResDir(): File {
        var cursor: File? = File("").absoluteFile
        while (cursor != null) {
            val candidate = File(cursor, "src/main/res")
            if (File(candidate, "values/colors.xml").isFile) return candidate
            cursor = cursor.parentFile
        }
        throw IllegalStateException("找不到 src/main/res/values/colors.xml；工作目录是 ${File("").absolutePath}")
    }

    @Test
    fun `day and night both declare the three widget colors`() {
        // 少一个 key 会安静地借用默认包的值：暗色模式下小组件变成「浅色底 + 浅色字」，
        // 而那只在装到桌面上才看得见。
        val day = colorsOf(File(res, "values/colors.xml")).keys
        val night = colorsOf(File(res, "values-night/colors.xml")).keys
        val needed = setOf("widget_background", "widget_label", "widget_count")
        assertEquals("默认包缺的小组件颜色", emptySet<String>(), needed - day)
        assertEquals("night 缺的小组件颜色（会借用默认包的浅色值）", emptySet<String>(), needed - night)
    }
}
