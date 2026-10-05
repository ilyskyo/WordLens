// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * 「温暖手账 × 现代数字贴纸」色板。
 *
 * 设计意图：暖珊瑚橙做主色，模拟贴纸边缘那一点 Marker 橙；蓝绿做辅色，压住整体的暖燥；
 * 奶油白打底，让透明背景的贴纸浮起来时不会显得脏。
 *
 * 这套色板是为本项目单独推导的，不来自任何现有应用的品牌色。
 *
 * ## 三条硬规则（改动这里之前先读）
 *
 * 1. **没有纯白、没有纯黑**。背景 `#FDF8F3`、卡面 `#FFFDFB`、正文 `#241C17`。纯白卡面在
 *    暖底上会泛蓝（屏幕白点与环境的色温差被放大），纯黑字在暖纸上是脏的。
 * 2. 卡面与底色只差 5 个单位（约 1% 明度）。这点差**不足以**独自把卡片分出来，必须配
 *    [SoftShadow] 的三层阴影一起用；反过来，只要阴影在，就不需要把卡面调得更白。
 * 3. 语义色（评级四档）**不直接当文字色**：饱和色上放白字最多 3.6:1，读不动。
 *    一律走 [RatingHues.tones] 推导出「淡底 + 同色相深墨」，实测 6.6–9.1:1。
 */

// ── 品牌主色：暖珊瑚橙 ───────────────────────────────────────────
val Coral = Color(0xFFFF8A65)
val CoralDeep = Color(0xFFC74E2E)   // 浅色主题下的按下态与深色主题主色
val CoralSoft = Color(0xFFFFE0D6)   // PrimaryContainer
val CoralInk = Color(0xFF3E1A0F)    // OnPrimaryContainer

// ── 辅色：柔和蓝绿 ─────────────────────────────────────────────
val Seafoam = Color(0xFF4DB6AC)
val SeafoamDeep = Color(0xFF1F6E66)
val SeafoamSoft = Color(0xFFC8F0EB)
val SeafoamInk = Color(0xFF003731)

// ── 第三色：温暖黄，仅用于「今天/待复习」这类提醒语义 ──────────
val Amber = Color(0xFFFFD54F)
val AmberInk = Color(0xFF3D2E00)

// ── 中性色：奶油白 + 深棕灰 ────────────────────────────────────
/** 窗口底色。比卡面暖一档，是整套「纸感」的地基。 */
val Cream = Color(0xFFFDF8F3)

/** 卡面。#FFFDFB 而不是 #FFFFFF：保留一点暖，避免与 Cream 之间出现蓝味对比。 */
val Paper = Color(0xFFFFFDFB)

/** 按下去的底色增量。波纹被关掉之后，「按到了」这件事靠它和缩放各说一半。 */
val Pressed = Color(0xFFEFE6DC)

val PaperDim = Color(0xFFF5EDE6)   // SurfaceVariant
/** 正文墨色。16.5:1 对卡面，比原来的 #3E2C23（13:1）更接近 iOS 的正文观感，仍不是纯黑。 */
val Cocoa = Color(0xFF241C17)      // OnSurface
val CocoaSoft = Color(0xFF5C4A40)  // OnSurfaceVariant，8.3:1
val Sandalwood = Color(0xFFD7C4B8) // Outline
val Petal = Color(0xFFE57373)      // Error

/** 分隔线：10% 黑。用透明度而不是一个固定灰，是因为它要同时活在卡面、底色和贴纸上。 */
val Separator = Color(0x1A000000)

/** 深色主题下的分隔线，同一个思路反向：12% 白。 */
val NightSeparator = Color(0x1FFFFFFF)

// ── 深色主题 ───────────────────────────────────────────────────
val NightBase = Color(0xFF1A1512)
val NightPaper = Color(0xFF241E1A)
val NightPaperHigh = Color(0xFF322A25)
val NightInk = Color(0xFFF2E7DF)
val NightInkSoft = Color(0xFFD3C2B8)
val NightOutline = Color(0xFF574A42)

/**
 * 评级四档的**色相**。
 *
 * 只存色相，不存「底色」和「墨色」——那两个是从色相推出来的（见 [RatingHues.tones]）。
 * 手写三套 × 四档 × 三种角色 = 三十六个颜色，一定会写出某两套里对比不合格的组合；
 * 而推导公式让「新增一套色系」变成只填四个数。
 *
 * 四档的色相顺序固定是「红 → 橙 → 绿 → 蓝」，对应 FSRS 的 Again/Hard/Good/Easy：
 * 这条映射同时是 Anki 与绝大多数 SRS 的惯例，用户不需要重新学。
 * 但**颜色不是唯一信息渠道**：按钮上永远同时写着「忘了 / 困难 / 好 / 简单」四个字。
 */
@Immutable
data class RatingHues(
    val again: Color,
    val hard: Color,
    val good: Color,
    val easy: Color,
)

/** 一套色系的四个色相。名字会出现在设置页的选择器上。 */
object RatingSchemes {

    /** 默认：与珊瑚橙同一色温，绿偏草绿、蓝偏雾蓝，饱和度中等。 */
    val Warm = RatingHues(
        again = Color(0xFFE05B4B),
        hard = Color(0xFFDE9340),
        good = Color(0xFF4E9E6B),
        easy = Color(0xFF5A8CC0),
    )

    /** 冷调：把「暖燥」压下去，给深色主题与喜欢蓝绿的人用。 */
    val Cool = RatingHues(
        again = Color(0xFFD9556B),
        hard = Color(0xFFC98A2E),
        good = Color(0xFF3E9BA6),
        easy = Color(0xFF5C7BD0),
    )

    /** 低饱和：四档之间明度更接近，适合对强色刺激敏感的用户；靠字与图标区分，不靠颜色。 */
    val Muted = RatingHues(
        again = Color(0xFFC4557A),
        hard = Color(0xFFB07A3C),
        good = Color(0xFF5F9E7A),
        easy = Color(0xFF7C6BC4),
    )

    val entries: List<RatingHues> = listOf(Warm, Cool, Muted)
}

/**
 * 一档评级的三种角色色：
 * [hue] 给滑动光晕、图标描边这类「大面积强色」用；
 * [container] 是按钮底色；[ink] 是按钮上的字。
 */
@Immutable
data class RatingTone(val hue: Color, val container: Color, val ink: Color)

/** 四档的成套配色，挂在主题的 accents 上，屏幕只读不造。 */
@Immutable
data class RatingColors(
    val again: RatingTone,
    val hard: RatingTone,
    val good: RatingTone,
    val easy: RatingTone,
)

/**
 * 由色相推导出成套的底色与墨色。
 *
 * 公式的两个方向不同，是因为浅色与深色的任务不一样：
 *
 * - 浅色：底色 = 奶油白里掺 18% 色相（够读出「这是绿的」，又不至于把白卡染脏）；
 *   墨色 = 色相往近黑压 62%（保住色相辨认度，同时把对比推到 7:1 以上）。
 * - 深色：底色必须掺得更多（30%），否则在 `#241E1A` 上根本看不见；
 *   墨色改成**往白提亮** 60%——深色主题里压暗只会糊成一团黑。
 *
 * 三套色系在两种主题下实测 6.6–9.1:1，全部过 WCAG AA 正文线。
 */
fun RatingHues.tones(dark: Boolean): RatingColors {
    val base = if (dark) NightPaper else Cream
    val containerMix = if (dark) 0.30f else 0.18f
    val inkTarget = if (dark) Color.White else Color(0xFF1A0E08)
    val inkMix = if (dark) 0.60f else 0.62f

    fun tone(hue: Color) = RatingTone(
        hue = hue,
        container = lerp(base, hue, containerMix),
        ink = lerp(hue, inkTarget, inkMix),
    )

    return RatingColors(
        again = tone(again),
        hard = tone(hard),
        good = tone(good),
        easy = tone(easy),
    )
}
