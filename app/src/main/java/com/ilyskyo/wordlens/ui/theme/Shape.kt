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
 */
val WordLensShapes = Shapes(
    // 极小：贴纸图本身、KPI 数字底
    extraSmall = RoundedCornerShape(8.dp),
    // 小：Chip、输入框
    small = RoundedCornerShape(16.dp),
    // 中：按钮、搜索栏
    medium = RoundedCornerShape(20.dp),
    // 大：卡片、贴纸容器
    large = RoundedCornerShape(24.dp),
    // 特大：BottomSheet、对话框
    extraLarge = RoundedCornerShape(28.dp),
)

/** 顶部圆角的 BottomSheet：只有上角圆，下角保持直角贴合屏幕底部。 */
val BottomSheetShape = RoundedCornerShape(
    topStart = 28.dp,
    topEnd = 28.dp,
    bottomStart = 0.dp,
    bottomEnd = 0.dp,
)

/** 对话框：28dp 全角圆，配 8dp 网格对齐。 */
val DialogShape = RoundedCornerShape(28.dp)

/**
 * 贴纸的「不规则圆角」。
 *
 * 规范里写的是「30% 圆角」，但 Compose 的 RoundedCornerShape 收的是绝对 dp，
 * 30% 需要按组件尺寸换算。为了让不同尺寸的贴纸都得到同一视觉重量，
 * 这里给一个按边长线性插值的辅助函数：边长 × 0.3，并夹在 12dp 到 40dp 之间——
 * 小图算出来不至于圆成一粒纽扣，大图也不会圆到看不出原形。
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

    /** 底部导航条高度，规范定为 80dp。 */
    val navBarHeight = 80.dp
}
