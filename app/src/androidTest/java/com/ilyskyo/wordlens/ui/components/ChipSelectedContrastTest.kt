// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ilyskyo.wordlens.ui.theme.WordLensTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 选中态在**真实像素**上要跳得出来。
 *
 * ## 为什么量像素而不是量颜色
 *
 * JVM 里已经有一项 `RatingContrastTest` 检查色板算出来的对比度——它管的是「这两个色搭不搭」。
 * 但它看不见的是：一个 `colors = …` 参数没接上、被组件自己的默认值盖掉、或者压根没被应用。
 * 那时色板仍然合格，屏幕上却是一片没有选中态的胶囊。
 * 只有从渲染结果里取色，才分得开「设计上对」和「真的画上去了」。
 *
 * ## 这条测试是为一个已经发生过的回归写的
 *
 * 选中态第一版用的是 `primaryContainer`（淡桃 #FFE0D6）配深墨字，与未选中的
 * `onSurface` 7.5% 灰底（约 #EEE9E4）**只差色温不差明度**——装机截图上六颗胶囊
 * 分不出哪颗是开的，而注释当时还写着「底色与文字同时变，所以色盲下也分得出」。
 * 那两档的 WCAG 对比度只有约 1.06:1。改成实心 `primary` 之后约 1.9:1。
 * 所以 1.5:1 这条线把「坏的」和「好的」干净地分开，而且**旧实现会红**。
 *
 * ## 取样的位置
 *
 * 文字在中间，所以取**靠右边缘**那一竖条：那里一定是填充色而不是字形。
 * 取多行求平均，避开阴影与描边最重的最外一圈像素。
 */
@RunWith(AndroidJUnit4::class)
class ChipSelectedContrastTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aSelectedChipContrastsAgainstAnUnselectedOneByAtLeast150Percent() {
        compose.setContent {
            WordLensTheme {
                Row {
                    OptionChip(label = "Quiet", selected = false, onClick = {})
                    OptionChip(label = "Warm", selected = true, onClick = {})
                }
            }
        }

        // `ImageBitmap -> android.graphics.Bitmap` 在这个版本里叫 `asAndroidBitmap()`；
        // 网上一半的示例写的 `toBitmap()` 已经不存在了（编译期只会给一句
        // `Unresolved reference: toBitmap`，看不出是改名）。
        // 按**文字**找，不是 contentDescription：`OptionChip` 没有做 `clearAndSetSemantics`
        // （那是 `PillSwitch` 的事），合并树里暴露的就是 label 那个 Text。
        // 同一份代码里两个组件的语义树不一样，找节点只能看组件自己声明了什么。
        val idle = compose.onNodeWithText("Quiet").captureToImage().asAndroidBitmap()
        val picked = compose.onNodeWithText("Warm").captureToImage().asAndroidBitmap()

        val idleLuma = fillLuminance(idle)
        val pickedLuma = fillLuminance(picked)
        val ratio = (max(idleLuma, pickedLuma) + 0.05) / (min(idleLuma, pickedLuma) + 0.05)

        assertTrue(
            "选中与未选中的底色对比只有 %.2f:1 —— 一眼分不出哪颗是开的。"
                .format(ratio) + " idle=%.3f picked=%.3f".format(idleLuma, pickedLuma),
            ratio >= 1.5,
        )
    }

    /** 靠右边缘那一竖条的平均相对亮度（WCAG 定义：线性化之后按 0.2126/0.7152/0.0722 加权）。 */
    private fun fillLuminance(bitmap: android.graphics.Bitmap): Float {
        val x = (bitmap.width - SAMPLE_EDGE_INSET).coerceAtLeast(0)
        val fromY = (bitmap.height * 0.3f).toInt()
        val toY = (bitmap.height * 0.7f).toInt().coerceAtLeast(fromY)
        var red = 0f
        var green = 0f
        var blue = 0f
        var n = 0
        for (y in fromY..toY) {
            val color = bitmap.getPixel(x, y)
            val a = ((color shr 24) and 0xFF) / 255f
            // 半透明的边缘（抗锯齿、阴影）会污染平均值：取样区应当是实心填充。
            if (a < 0.9f) continue
            red += channel(color shr 16 and 0xFF)
            green += channel(color shr 8 and 0xFF)
            blue += channel(color and 0xFF)
            n++
        }
        check(n > 0) { "取样区没有一个是实心像素：这颗胶囊根本没画出来" }
        return 0.2126f * red / n + 0.7152f * green / n + 0.0722f * blue / n
    }

    /** sRGB 到线性光。不换算就平均，深色会被系统性地高估，对比度会算得偏小。 */
    private fun channel(byte: Int): Float {
        val s = byte / 255f
        return if (s <= 0.04045f) s / 12.92f else ((s + 0.055f) / 1.055f).pow(12.4f)
    }
}

/** 离右边缘多远开始取样：够躲开描边与阴影，又一定落在填充区里。 */
private const val SAMPLE_EDGE_INSET = 6
