// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.theme

import android.os.Build
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

/**
 * 语义色。
 *
 * 规范只给了 M3 的标准角色，但产品里有三类语义它们覆盖不了，放进 ColorScheme 会污染
 * Material 语义，所以单独用一个 CompositionLocal 承载：
 *
 * - [stickerStroke] 贴纸的白色描边。贴纸浮在奶油白背景上，没有描边会「化开」。
 * - [dueAccent] 今天到期的强调色。用 Tertiary（暖黄）会让人以为可以点，
 *   复习入口用主色更符合直觉。
 * - [shadowTint] 阴影色。Android 的 elevation 阴影硬编码为黑色，无法换色；
 *   需要暖灰阴影的地方改用本色 + Modifier.shadow(shadowTint)。
 */
@Immutable
data class WordLensAccents(
    val stickerStroke: Color,
    val stickerStrokeWidth: androidx.compose.ui.unit.Dp,
    val dueAccent: Color,
    val shadowTint: Color,
    val confidenceTrack: Color,
)

private val LightAccents = WordLensAccents(
    stickerStroke = Color(0xFFFFFFFF),
    stickerStrokeWidth = androidx.compose.ui.unit.Dp(4f),
    dueAccent = Coral,
    shadowTint = Color(0x1A3E2C23), // 约 0.12 alpha 的暖灰，不用纯黑
    confidenceTrack = PaperDim,
)

private val DarkAccents = WordLensAccents(
    stickerStroke = Color(0xFFF7F1EC),
    stickerStrokeWidth = androidx.compose.ui.unit.Dp(4f),
    dueAccent = Coral,
    shadowTint = Color(0x66000000),
    confidenceTrack = NightPaperHigh,
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
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkScheme
        else -> LightScheme
    }

    CompositionLocalProvider(
        LocalWordLensAccents provides if (darkTheme) DarkAccents else LightAccents,
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
