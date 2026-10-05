// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 本项目自绘的图标集。
 *
 * ## 为什么不直接用 Material Symbols
 *
 * `androidx.compose.material:material-icons-extended` 里装着两千多个图标，
 * 即使 R8 裁剪后仍要给 APK 加上可观的 dex 体积——而本应用全站只用到 7 个图标。
 * 所以这里用 Compose 的 `ImageVector` DSL 手写几个基础几何形，既省体积，
 * 也让图标的圆角粗细与本项目「温暖手账」的调性一致（比 Material 默认的 2dp 略粗一点）。
 *
 * 几何形全部由圆角矩形、圆和折线构成，不含任何第三方图标路径。
 */
object WordLensIcons {

    /** 相机：机身圆角矩形 + 取景圆 + 顶部小方块。 */
    val Camera: ImageVector by lazy {
        ImageVector.Builder(
            name = "Camera",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // 顶部取景凸起
            path(fill = SolidColor(Color.Black)) {
                moveTo(9f, 3f)
                horizontalLineTo(15f)
                verticalLineTo(5f)
                horizontalLineTo(9f)
                close()
            }
            // 机身
            path(fill = SolidColor(Color.Black)) {
                moveTo(5f, 5f)
                horizontalLineTo(19f)
                arcToRelative(2f, 2f, 0f, true, false, 2f, 2f)
                verticalLineTo(17f)
                arcToRelative(2f, 2f, 0f, false, false, -2f, 2f)
                horizontalLineTo(5f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                verticalLineTo(7f)
                arcToRelative(2f, 2f, 0f, false, false, 2f, -2f)
                close()
            }
            // 镜头（挖空：用白色不成立，改用描边式圆环，两段弧）
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
                moveTo(16.5f, 12f)
                arcToRelative(3.5f, 3.5f, 0f, true, false, -7f, 0f)
                arcToRelative(3.5f, 3.5f, 0f, true, false, 7f, 0f)
                close()
            }
        }.build()
    }

    /** 网格：四个圆角方块，2x2。 */
    val Grid: ImageVector by lazy {
        ImageVector.Builder(
            name = "Grid",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 4f); horizontalLineTo(10f); verticalLineTo(10f)
                horizontalLineTo(4f); close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(14f, 4f); horizontalLineTo(20f); verticalLineTo(10f)
                horizontalLineTo(14f); close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 14f); horizontalLineTo(10f); verticalLineTo(20f)
                horizontalLineTo(4f); close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(14f, 14f); horizontalLineTo(20f); verticalLineTo(20f)
                horizontalLineTo(14f); close()
            }
        }.build()
    }

    /** 卡片叠放：后面两张偏移的圆角矩形 + 前面一张。表示「一组卡片」。 */
    val StackedCards: ImageVector by lazy {
        ImageVector.Builder(
            name = "StackedCards",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // 后两张用半透明，制造层次而不增加颜色
            path(fill = SolidColor(Color(0x66000000))) {
                moveTo(7f, 3f); horizontalLineTo(19f); verticalLineTo(9f)
                horizontalLineTo(7f); close()
            }
            path(fill = SolidColor(Color(0x99000000))) {
                moveTo(5f, 7f); horizontalLineTo(17f); verticalLineTo(13f)
                horizontalLineTo(5f); close()
            }
            // 主卡片，带中间分隔线（呼应「翻卡复习」）
            path(fill = SolidColor(Color.Black)) {
                moveTo(3f, 11f)
                horizontalLineTo(15f)
                verticalLineTo(13f)
                horizontalLineTo(3f)
                close()
            }
            path(fill = SolidColor(Color(0x33000000))) {
                moveTo(3f, 15f)
                horizontalLineTo(15f)
                verticalLineTo(17f)
                horizontalLineTo(3f)
                close()
            }
            path(fill = SolidColor(Color(0x55000000))) {
                moveTo(3f, 19f)
                horizontalLineTo(15f)
                verticalLineTo(21f)
                horizontalLineTo(3f)
                close()
            }
        }.build()
    }

    /** 喇叭发音：梯形喇叭 + 两段声波弧。 */
    val Speaker: ImageVector by lazy {
        ImageVector.Builder(
            name = "Speaker",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // 喇叭主体
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 9f)
                horizontalLineTo(8f)
                lineTo(13f, 4f)
                verticalLineTo(20f)
                lineTo(8f, 15f)
                horizontalLineTo(4f)
                close()
            }
            // 两段声波
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
                moveTo(16f, 9f)
                arcToRelative(3.5f, 3.5f, 0f, false, true, 0f, 6f)
            }
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
                moveTo(18.8f, 6.2f)
                arcToRelative(6.5f, 6.5f, 0f, false, true, 0f, 11.6f)
            }
        }.build()
    }

    /** 放大镜：圆 + 斜柄。 */
    val Search: ImageVector by lazy {
        ImageVector.Builder(
            name = "Search",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f) {
                moveTo(20f, 11f)
                arcToRelative(6.5f, 6.5f, 0f, true, false, -13f, 0f)
                arcToRelative(6.5f, 6.5f, 0f, true, false, 13f, 0f)
                close()
            }
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 2.2f) {
                moveTo(15.5f, 15.5f)
                lineTo(20.5f, 20.5f)
            }
        }.build()
    }

    /** 关闭：两条对角线。 */
    val Close: ImageVector by lazy {
        ImageVector.Builder(
            name = "Close",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 2.2f, strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round) {
                moveTo(6f, 6f); lineTo(18f, 18f)
            }
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 2.2f, strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round) {
                moveTo(18f, 6f); lineTo(6f, 18f)
            }
        }.build()
    }

    /** 齿轮：外圈圆 + 中心圆孔 + 四枚齿。用简化造型，避免十几个小矩形的路径噪声。 */
    val Settings: ImageVector by lazy {
        ImageVector.Builder(
            name = "Settings",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // 齿：上下左右四枚梯形
            path(fill = SolidColor(Color.Black)) {
                moveTo(10f, 2f); horizontalLineTo(14f); verticalLineTo(6f)
                horizontalLineTo(10f); close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(10f, 18f); horizontalLineTo(14f); verticalLineTo(22f)
                horizontalLineTo(10f); close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(2f, 10f); horizontalLineTo(6f); verticalLineTo(14f)
                horizontalLineTo(2f); close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(18f, 10f); horizontalLineTo(22f); verticalLineTo(14f)
                horizontalLineTo(18f); close()
            }
            // 环（描边）
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 2.4f) {
                moveTo(17f, 12f)
                arcToRelative(5f, 5f, 0f, true, false, -10f, 0f)
                arcToRelative(5f, 5f, 0f, true, false, 10f, 0f)
                close()
            }
            // 中心孔（填背景色由调用方通过 alpha 处理，这里用镂空语义：再画一个小黑圆）
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 10f)
                arcToRelative(2f, 2f, 0f, true, false, 0f, 4f)
                arcToRelative(2f, 2f, 0f, true, false, 0f, -4f)
                close()
            }
        }.build()
    }
}
