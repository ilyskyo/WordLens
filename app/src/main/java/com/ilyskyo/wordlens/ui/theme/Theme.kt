// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.theme

import android.os.Build
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 语义色。
 *
 * 规范只给了 M3 的标准角色，但产品里有一批语义它们覆盖不了，放进 ColorScheme 会污染
 * Material 语义，所以单独用一个 CompositionLocal 承载：
 *
 * - [stickerStroke] 贴纸的白色描边。贴纸浮在奶油白背景上，没有描边会「化开」。
 * - [dueAccent] 今天到期的强调色。用 Tertiary（暖黄）会让人以为可以点，
 *   复习入口用主色更符合直觉。
 * - [shadowTint] 阴影色。Android 的 elevation 阴影硬编码为黑色，无法换色；
 *   需要暖灰阴影的地方改用本色 + [softShadow]。
 * - [separator] 分隔线。透明黑/白，能同时活在卡面、底色和贴纸上；
 *   Material 的 `outline` 是一个实心灰，铺在贴纸上会露出色块边。
 * - [pressedSurface] 按下时的底色增量。波纹被 [NoIndication] 关掉之后，
 *   「按到了」这件事由它和缩放各说一半。
 * - [ratings] 评级四档的成套配色，跟着色系选择与深浅主题一起变。
 */
@Immutable
data class WordLensAccents(
    val stickerStroke: Color,
    val stickerStrokeWidth: Dp,
    val dueAccent: Color,
    val shadowTint: Color,
    val confidenceTrack: Color,
    val separator: Color,
    val pressedSurface: Color,
    val ratings: RatingColors,
)

private val LightAccents = WordLensAccents(
    stickerStroke = Paper,
    stickerStrokeWidth = 4.dp,
    dueAccent = Coral,
    shadowTint = Color(0x1A3E2C23), // 约 0.10 alpha 的暖灰，不用纯黑
    confidenceTrack = PaperDim,
    separator = Separator,
    pressedSurface = Pressed,
    ratings = RatingSchemes.Warm.tones(dark = false),
)

private val DarkAccents = WordLensAccents(
    stickerStroke = Color(0xFFF7F1EC),
    stickerStrokeWidth = 4.dp,
    dueAccent = Coral,
    shadowTint = Color(0x66000000),
    confidenceTrack = NightPaperHigh,
    separator = NightSeparator,
    pressedSurface = NightPaperHigh,
    ratings = RatingSchemes.Warm.tones(dark = true),
)

val LocalWordLensAccents: ProvidableCompositionLocal<WordLensAccents> =
    staticCompositionLocalOf { LightAccents }

private val LightScheme = lightColorScheme(
    primary = Coral,
    onPrimary = Color.White,
    primaryContainer = CoralSoft,
    onPrimaryContainer = CoralInk,
    inversePrimary = CoralDeep,

    secondary = Seafoam,
    onSecondary = Color.White,
    secondaryContainer = SeafoamSoft,
    onSecondaryContainer = SeafoamInk,

    tertiary = Amber,
    onTertiary = AmberInk,
    tertiaryContainer = Amber,
    onTertiaryContainer = AmberInk,

    background = Cream,
    onBackground = Cocoa,
    surface = Paper,
    onSurface = Cocoa,
    surfaceVariant = PaperDim,
    onSurfaceVariant = CocoaSoft,
    surfaceTint = Coral,
    outline = Sandalwood,
    outlineVariant = PaperDim,
    scrim = Color(0x99000000),
    error = Petal,
    onError = Color.White,
    errorContainer = CoralSoft,
    onErrorContainer = CoralInk,
)

private val DarkScheme = darkColorScheme(
    primary = CoralDeep,
    onPrimary = Color(0xFFFFF3EE),
    primaryContainer = Color(0xFF5A2415),
    onPrimaryContainer = CoralSoft,
    inversePrimary = Coral,

    secondary = Seafoam,
    onSecondary = Color(0xFF003731),
    secondaryContainer = SeafoamInk,
    onSecondaryContainer = SeafoamSoft,

    tertiary = Amber,
    onTertiary = AmberInk,
    tertiaryContainer = Color(0xFF5C4400),
    onTertiaryContainer = Amber,

    background = NightBase,
    onBackground = NightInk,
    surface = NightPaper,
    onSurface = NightInk,
    surfaceVariant = NightPaperHigh,
    onSurfaceVariant = NightInkSoft,
    surfaceTint = Coral,
    outline = NightOutline,
    outlineVariant = Color(0xFF3A312B),
    scrim = Color(0xCC000000),
    error = Petal,
    onError = Color(0xFF3B0906),
    errorContainer = Color(0xFF5C1A16),
    onErrorContainer = CoralSoft,
)

/**
 * 应用主题。
 *
 * @param dynamicColor 是否跟随系统取色。默认**关闭**：本项目的视觉识别度很大程度来自
 *   「奶油白 + 珊瑚橙 + 蓝绿」这套关系，跟随壁纸会让贴纸的白色描边有时对比不足，
 *   也会让品牌色随设备漂移。设置页可以单独打开。
 */
@Composable
fun WordLensTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    ratingScheme: RatingHues = RatingSchemes.Warm,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkScheme
        else -> LightScheme
    }
    // 评级四档是「一套」颜色，不能只换其中两档：底色、墨色、色相三者必须同时来自同一个
    // 色系与同一个深浅主题，否则切了色系之后有一半的按钮对比度直接掉到 AA 线下。
    val accents = (if (darkTheme) DarkAccents else LightAccents)
        .copy(ratings = ratingScheme.tones(dark = darkTheme))

    CompositionLocalProvider(
        LocalWordLensAccents provides accents,
        // 全局消灭波纹。Material 组件（Button / Surface / FAB / Tab）内部都读这个 Local，
        // 所以一处替换覆盖全 App，而不是每个组件签名里加一个 indication 参数。
        LocalIndication provides NoIndication,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = WordLensTypography,
            shapes = WordLensShapes,
            content = content,
        )
    }
}

/** 便捷访问器，避免每个组件都写 `LocalWordLensAccents.current`。 */
object WordLensTheme {
    val accents: WordLensAccents
        @Composable get() = LocalWordLensAccents.current
}
