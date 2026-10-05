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

/*
 * 中日韩字形怎么办。
 *
 * Nunito 与 Inter 都不含汉字与假名，中日韩文本按字回退到系统字体——这是**刻意不去管它**的
 * 一件事：想自己控制中文观感，就得随包带一款 CJK 字体，而 Noto Sans SC 哪怕按字重子集化
 * 也要 8MB 以上。这个体积不是「稍微大一点」，它比整个 App 的其余部分都大，而且我们是
 * arm64-only、还要随包带两个推理模型。
 *
 * 所以现在的策略是让系统挑它自己那套中日韩字体（小米是 MiSans、华为是 HarmonyOS Sans、
 * AOSP 是 Noto Sans CJK），代价是各家的字重表现会有细微差别，好处是用户在系统设置里选的
 * 字体偏好被尊重，而 APK 不需要为了一个字重背 8MB。
 */

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

/**
 * 字号层级：按 iOS 的 Text Style 尺度重排，但保留 M3 的角色名。
 *
 * 为什么不新造一套 `largeTitle / headline / body` 的名字：M3 的每个组件内部都在取
 * `titleMedium`、`labelLarge` 这些角色，改了名就等于每个屏幕都要手写样式，
 * 而「屏幕只准引用 Token」这条红线靠的是**层级只有一个来源**，不是名字好听。
 *
 * 关键差异（相对上一版 Material 尺度）：
 *
 * - **正文 16 → 17sp**。iOS 的 body 是 17pt，Material 是 16sp。这 1sp 是整个「苹果感」里
 *   最容易被读出、也最难被解释的一项：16sp 读起来像安卓，17sp 读起来像 iOS。
 * - **大标题收紧字距**（34sp 上 -0.4sp）。大号无衬线不收紧会散，尤其 Inter 这种中性骨架。
 * - **小字反而给正字距**（13sp 起 +0.1、12sp +0.2）。小号字挤在一起最难读，iOS 也是这么做的。
 * - **标题仍用 Nunito，正文与控件用 Inter**。规范要的是 Inter 的骨架，但产品识别度来自
 *   Nunito 那圈开阔的字腔；把标题也换成 Inter 会省一个字体族，代价是整套品牌失去表情，
 *   不值。行高与基线由同一套 [wordLensTextStyle] 保证，两个族混排不会出现台阶。
 */
val WordLensTypography = Typography(
    // largeTitle 34 / W700：复习卡正面的单词本体，全屏最大的一行字。
    displayLarge = wordLensTextStyle(Nunito, 34, 40, FontWeight.Bold, -0.4),
    displayMedium = wordLensTextStyle(Nunito, 28, 34, FontWeight.Bold, -0.3),
    displaySmall = wordLensTextStyle(Nunito, 24, 30, FontWeight.SemiBold, -0.2),

    // title2 / title3：页面标题
    headlineLarge = wordLensTextStyle(Nunito, 24, 30, FontWeight.SemiBold, -0.2),
    headlineMedium = wordLensTextStyle(Nunito, 22, 28, FontWeight.SemiBold, -0.2),
    headlineSmall = wordLensTextStyle(Nunito, 20, 26, FontWeight.SemiBold, -0.1),

    // title：卡片标题
    titleLarge = wordLensTextStyle(Nunito, 20, 26, FontWeight.SemiBold, -0.1),
    // iOS 的 headline：17 半粗，页签、区块标题、卡片词头都用它。
    titleMedium = wordLensTextStyle(Nunito, 17, 23, FontWeight.SemiBold, -0.1),
    titleSmall = wordLensTextStyle(Nunito, 16, 22, FontWeight.SemiBold, -0.1),

    // body 17 / callout 16 / footnote 13——正文三档全部 Inter，长读不累。
    bodyLarge = wordLensTextStyle(Inter, 17, 25, FontWeight.Normal, -0.1),
    bodyMedium = wordLensTextStyle(Inter, 16, 23, FontWeight.Normal, -0.1),
    bodySmall = wordLensTextStyle(Inter, 13, 18, FontWeight.Normal, 0.1),

    // 按钮与标签
    labelLarge = wordLensTextStyle(Inter, 15, 20, FontWeight.SemiBold, -0.1),
    labelMedium = wordLensTextStyle(Inter, 12, 16, FontWeight.Medium, 0.2),
    labelSmall = wordLensTextStyle(Inter, 11, 15, FontWeight.Medium, 0.2),
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
