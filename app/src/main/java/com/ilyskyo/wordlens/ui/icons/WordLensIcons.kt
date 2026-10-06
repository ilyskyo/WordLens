// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 本项目自绘的图标集。
 *
 * ## 为什么不直接用 Material Symbols
 *
 * `androidx.compose.material:material-icons-extended` 里装着两千多个图标，
 * 即使 R8 裁剪后仍要给 APK 加上可观的 dex 体积——而本应用全站只用到十来个图标。
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

    /**
     * 卡片叠放：后面两张只露上沿，前面一张描边卡片中间带一道分隔线。
     *
     * 全部实色，这一条是重点。`Icon` 会把整枚图形 tint 成一个颜色，**连 alpha 一起换掉**——
     * 原来这里用 0x66 / 0x99 / 0x33 三档半透明去制造层次，tint 之后三档变成同一块实心，
     * 叠在一起就是一团灰（真机截图里「记住」那颗就是它）。图标的层次只能靠几何拿：
     * 错位、留缝、粗细。
     */
    val StackedCards: ImageVector by lazy {
        ImageVector.Builder(
            name = "StackedCards",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // 最后面一张：只露一条顶边，越往后露得越短
            path(fill = SolidColor(Color.Black)) {
                moveTo(8f, 3f); horizontalLineTo(20f); verticalLineTo(5f)
                horizontalLineTo(8f); close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(6f, 7f); horizontalLineTo(18f); verticalLineTo(9f)
                horizontalLineTo(6f); close()
            }
            // 前面一张走描边而不是实心：与上面那两条之间留出缝，四块才读成「叠着」而不是「一整块」
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
                moveTo(4f, 11.5f); horizontalLineTo(16f); verticalLineTo(21.5f)
                horizontalLineTo(4f); close()
            }
            // 卡中间那道线：正面与背面的分界，也就是这张卡要被翻开的方向
            path(fill = SolidColor(Color.Black)) {
                moveTo(6.5f, 16f); horizontalLineTo(13.5f); verticalLineTo(17.4f)
                horizontalLineTo(6.5f); close()
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
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round) {
                moveTo(6f, 6f); lineTo(18f, 18f)
            }
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round) {
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

    /** 日历：方框 + 两道挂环 + 一行日期点。用线段画而不是矩形图元，
     *  因为 vector 的 path{} 收到的是 PathBuilder，没有 addRect/addCircle。 */
    val Calendar: ImageVector by lazy {
        ImageVector.Builder(
            name = "Calendar",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f) {
                moveTo(3.5f, 5.5f); horizontalLineTo(20.5f); verticalLineTo(20.5f)
                horizontalLineTo(3.5f); close()
            }
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(8f, 3f); verticalLineTo(7f)
                moveTo(16f, 3f); verticalLineTo(7f)
                moveTo(3.5f, 10.5f); horizontalLineTo(20.5f)
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(7f, 13.5f); horizontalLineTo(9f); verticalLineTo(15.5f)
                horizontalLineTo(7f); close()
                moveTo(11f, 13.5f); horizontalLineTo(13f); verticalLineTo(15.5f)
                horizontalLineTo(11f); close()
                moveTo(15f, 13.5f); horizontalLineTo(17f); verticalLineTo(15.5f)
                horizontalLineTo(15f); close()
            }
        }.build()
    }

    /** 向左的尖括号：月历翻上一月。 */
    val ChevronLeft: ImageVector by lazy {
        ImageVector.Builder(
            name = "ChevronLeft",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(14.5f, 5.5f)
                lineTo(8f, 12f)
                lineTo(14.5f, 18.5f)
            }
        }.build()
    }

    /** 向右的尖括号：月历翻下一月。与左边那条只差 x 对称，仍然各写一遍——
     *  把路径抽成「解析字符串」的把戏省不下什么，只会让图标变成要读懂才敢改的东西。 */
    val ChevronRight: ImageVector by lazy {
        ImageVector.Builder(
            name = "ChevronRight",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(9.5f, 5.5f)
                lineTo(16f, 12f)
                lineTo(9.5f, 18.5f)
            }
        }.build()
    }

    /**
     * 相册：一张带山形与太阳的相框。
     *
     * 只**描边**不填色，且用与 Camera 机身同款的圆角矩形几何——填实的色块在暗取景画面上
     * 会比快门还抢眼，而这一颗只是「另一个来源」，不是主角。
     */
    val Gallery: ImageVector by lazy {
        ImageVector.Builder(
            name = "Gallery",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // 相框：顺时针一圈，四角各一段半径 2 的弧
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(5f, 4.5f)
                horizontalLineTo(19f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                verticalLineTo(17.5f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                horizontalLineTo(5f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                verticalLineTo(6.5f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                close()
            }
            // 太阳：与 Settings 中心孔同一个画法（两段半弧拼一整圈）
            path(fill = SolidColor(Color.Black)) {
                moveTo(8.4f, 8.4f)
                arcToRelative(1.5f, 1.5f, 0f, false, true, 0f, 3f)
                arcToRelative(1.5f, 1.5f, 0f, false, true, 0f, -3f)
                close()
            }
            // 山：一道折线就够了，它读起来是「一张照片」而不是「一个矩形」
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(5.5f, 16.5f)
                lineTo(9.5f, 12.5f)
                lineTo(12f, 15f)
                lineTo(14.5f, 12f)
                lineTo(18.5f, 16.5f)
            }
        }.build()
    }

    /**
     * 麦克风：振膜胶囊 + 一道托着它的弧 + 一截支架。
     *
     * 用描边而不是填实，与 [Gallery] 同一个理由：这枚图标出现在照片上（时间轴卡片左下角），
     * 填实的色块会压过照片本身，而它要说的只是「这一条还带了一段声音」。
     */
    val Mic: ImageVector by lazy {
        ImageVector.Builder(
            name = "Mic",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // 振膜：竖着的圆角胶囊，宽 6、高 12
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(9f, 6f)
                arcToRelative(3f, 3f, 0f, false, true, 6f, 0f)
                verticalLineTo(11f)
                arcToRelative(3f, 3f, 0f, false, true, -6f, 0f)
                close()
            }
            // 托着振膜的那道弧
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(5.5f, 11.5f)
                arcToRelative(6.5f, 6.5f, 0f, false, false, 13f, 0f)
            }
            // 支架与底座
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(12f, 18f)
                verticalLineTo(21f)
                moveTo(8.5f, 21f)
                horizontalLineTo(15.5f)
            }
        }.build()
    }

    /** 播放：一枚实心三角。这一枚必须填实——描边的三角形在 48dp 的圆钮里读不出方向。 */
    val Play: ImageVector by lazy {
        ImageVector.Builder(
            name = "Play",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(8f, 5.4f)
                lineTo(18.4f, 12f)
                lineTo(8f, 18.6f)
                close()
            }
        }.build()
    }

    /** 暂停：两道圆头竖条。与 Play 同一套视觉重量（实心），换图标时不会跳一下。 */
    val Pause: ImageVector by lazy {
        ImageVector.Builder(
            name = "Pause",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 3f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(9f, 6f)
                verticalLineTo(18f)
                moveTo(15f, 6f)
                verticalLineTo(18f)
            }
        }.build()
    }

}
