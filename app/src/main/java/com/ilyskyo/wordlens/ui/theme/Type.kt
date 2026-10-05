// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.ilyskyo.wordlens.R

/**
 * 字体族。
 *
 * 用可变字体（variable font）而不是一堆静态字重文件：两个 TTF 就覆盖全字重，
 * APK 少一半体积，而且 Android 8.0+ 原生支持可变字体的 `wght` 轴。
 *
 * 两款字体都是 SIL Open Font License 1.1，允许嵌入与再分发，
 * 许可证原文随包放在 res/font/ 下的 OFL-*.txt。
 */
private fun variableFamily(resId: Int, vararg weights: Int): FontFamily = FontFamily(
    weights.map { weight ->
        Font(
            resId = resId,
            weight = FontWeight(weight),
            // 可变字体必须显式给出坐标轴取值，否则 Android 会渲染默认实例（Regular）。
            variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
        )
    },
)

/** 标题：Nunito，圆润、字腔开阔，标题看起来不严肃，适合鼓励式学习产品。 */
val Nunito = variableFamily(
    R.font.nunito_variable,
    400, 500, 600, 700, 800,
)

/** 正文：Inter，字形中性、字距均匀，小字号长时间阅读不累。 */
val Inter = variableFamily(
    R.font.inter_variable,
    400, 500, 600, 700,
)

/**
 * 中日韩字形回退。
 *
 * Nunito 与 Inter 都不含汉字与假名，若不指定回退字体，系统会在中文/日文文本上
 * 逐字回退到默认字体，行高与基线会跳。这里显式交给平台挑选合适的 CJK 字体，
 * 保证同一段文本里混排英文与汉字时行高一致。
 */
private val CjkFallback = FontFamily.Default

/**
 * 中英混排的行高修正。
 *
 * `LineHeightStyle` 的 trim 会把首尾行多余的行高裁掉。含音标与汉字的行首行尾
 * 上方有大量留白（音标的撇号、汉字的顶部空间），不裁会让段落之间出现明显的空洞，
 * 所以这里统一 `trim = Trim.None` 保留字体的自然行高。
 */
private val mixedLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun wordLensTextStyle(
    family: FontFamily,
    size: Int,
    lineHeight: Int,
    weight: FontWeight,
    letterSpacing: Double = 0.0,
): TextStyle = TextStyle(
    fontFamily = family,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight,
    letterSpacing = letterSpacing.sp,
    lineHeightStyle = mixedLineHeight,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/** 按设计规范定义的字号层级。 */
val WordLensTypography = Typography(
    // 32sp Bold —— 单词本体的展示级字号，收藏页大卡片用
    displayLarge = wordLensTextStyle(Nunito, 32, 38, FontWeight.Bold, -0.5),
    displayMedium = wordLensTextStyle(Nunito, 28, 34, FontWeight.Bold, -0.4),
    displaySmall = wordLensTextStyle(Nunito, 24, 30, FontWeight.SemiBold, -0.2),

    // 24sp SemiBold —— 页面标题
    headlineLarge = wordLensTextStyle(Nunito, 24, 30, FontWeight.SemiBold),
    headlineMedium = wordLensTextStyle(Nunito, 22, 28, FontWeight.SemiBold),
    headlineSmall = wordLensTextStyle(Nunito, 20, 26, FontWeight.SemiBold),

    // 20sp Medium —— 卡片标题
    titleLarge = wordLensTextStyle(Nunito, 20, 26, FontWeight.Medium),
    titleMedium = wordLensTextStyle(Nunito, 17, 23, FontWeight.Medium),
    titleSmall = wordLensTextStyle(Nunito, 15, 21, FontWeight.Medium),

    // 16sp Regular —— 正文
    bodyLarge = wordLensTextStyle(Inter, 16, 25, FontWeight.Normal),
    bodyMedium = wordLensTextStyle(Inter, 14, 22, FontWeight.Normal),
    bodySmall = wordLensTextStyle(Inter, 13, 19, FontWeight.Normal),

    // 14sp Medium —— 按钮与标签
    labelLarge = wordLensTextStyle(Inter, 14, 18, FontWeight.Medium),
    labelMedium = wordLensTextStyle(Inter, 12, 16, FontWeight.Medium),
    labelSmall = wordLensTextStyle(Inter, 11, 15, FontWeight.Medium),
)

/**
 * 音标专用样式。
 *
 * 音标必须斜体（IPA 的惯例），并且用等宽字体对齐，否则 `/ˈkʌp/` 这类带重音符的
 * 符号在比例字体里会高低不齐。这里用系统等宽族而不是再引一款字体：
 * 音标只出现在小标签上，等宽族足够且省一个字体文件。
 */
val IpaTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 14.sp,
    lineHeight = 19.sp,
    fontWeight = FontWeight.Normal,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = mixedLineHeight,
)

/** 汉字/假名/韩文正文样式，保证与英文正文同一行高。 */
val CjkBodyStyle = TextStyle(
    fontFamily = CjkFallback,
    fontSize = 16.sp,
    lineHeight = 25.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = mixedLineHeight,
)
