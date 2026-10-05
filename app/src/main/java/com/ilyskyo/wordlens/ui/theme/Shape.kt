// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 圆角尺度。
 *
 * 全局只有一个圆角梯度：所有值都是 8 的倍数（28 是 8*3.5，为对齐视觉重量取整到 4 的倍数）。
 * 统一梯度的意义是界面看起来像一套东西，而不是十几个各自为政的圆角。
 *
 * 全部换成 [ContinuousCornerShape]（超椭圆）而不是 [RoundedCornerShape]：圆弧与直边的切点处
 * 曲率是**突跳**的，大圆角配柔和阴影时会在交界出一道说不出来的「折」。M3 的 `Shapes` 收的是
 * [androidx.compose.ui.graphics.Shape]，所以这一步不需要动任何调用点——这正是把形状收在
 * 主题里的回报。
 */
val WordLensShapes = Shapes(
    // 极小：贴纸图本身、KPI 数字底。8dp 时曲率连续的可见度有限，但同一套公式保证它不成异类。
    extraSmall = ContinuousCornerShape(8.dp),
    // 小：Chip、输入框
    small = ContinuousCornerShape(16.dp),
    // 中：按钮、搜索栏
    medium = ContinuousCornerShape(20.dp),
    // 大：卡片、贴纸容器
    large = ContinuousCornerShape(24.dp),
    // 特大：对话框与需要「贴纸感」的主卡片
    extraLarge = ContinuousCornerShape(28.dp),
)

/** 顶部圆角的 BottomSheet：只有上角圆，下角保持直角贴合屏幕底部。 */
val BottomSheetShape = Squircle.sheet

/** 对话框：28dp 全角超椭圆，配 8dp 网格对齐。 */
val DialogShape = Squircle.card

/**
 * 贴纸的「不规则圆角」。
 *
 * 规范里写的是「30% 圆角」，但 Shape 收的是绝对 dp，30% 需要按组件尺寸换算。为了让不同尺寸
 * 的贴纸都得到同一视觉重量，这里给一个按边长线性插值的辅助函数：边长 × 0.3，并夹在 12dp 到
 * 40dp 之间——小图算出来不至于圆成一粒纽扣，大图也不会圆到看不出原形。
 *
 * 这一档**保持普通圆角**：贴纸是被抠出来的实物轮廓，给它超椭圆会读成「一个有圆角的矩形图片」，
 * 反而压掉了「这是一张真实物体的贴纸」这件事。
 */
fun stickerCorner(radiusPx: Float): RoundedCornerShape {
    val target = (radiusPx * STICKER_CORNER_RATIO).dp
    return RoundedCornerShape(target.coerceIn(12.dp, 40.dp))
}

private const val STICKER_CORNER_RATIO = 0.30f

/** 统一间距刻度：8dp 网格。 */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp

    /** 段落之间的留白。规范定为 64，比 xxl 再大一档，用于「这是一节结束了」。 */
    val section = 64.dp

    /** 卡片内边距。16 太挤（文字贴着超椭圆的弧边），24 又让卡片显得空旷；20 是四边都留得住的读数。 */
    val cardPadding = 20.dp

    /** 屏幕左右边距。拇指自然落点在屏幕下半的中间，边距给到 20 才不会让卡片像被裁了。 */
    val screen = 20.dp

    /** 列表项间距。必须大于 [SoftShadow.reach] 的三分之一，否则阴影会互相压成一条灰带。 */
    val listGap = 12.dp

    /** 底部导航条高度，规范定为 80dp。 */
    val navBarHeight = 80.dp
}
