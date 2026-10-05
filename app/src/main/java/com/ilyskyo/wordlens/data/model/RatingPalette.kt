// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.data.model

/**
 * 复习评级四档按钮的色系。
 *
 * 这里只存**选择**，不存颜色：颜色属于表现层（`ui.theme.RatingHues`），而这一层要负责落盘与
 * 跨版本兼容。让 data 去 import Compose 的 `Color` 会让「换一套 UI」变成数据层的破坏性改动，
 * 而且枚举名是要写进 JSON/Preferences 的，它必须是一个与渲染无关的稳定标识。
 *
 * 三档的差别只在色相安排，语义顺序永远是「红 → 橙 → 绿 → 蓝」对应
 * 「忘了 → 困难 → 好 → 简单」——这是 SRS 的通用惯例，不该被主题化掉。
 * 按钮上同时写汉字，颜色永远不是唯一的信息渠道（色觉障碍与深色模式对比度都靠这条）。
 */
enum class RatingPalette {
    /** 默认：与珊瑚橙同一色温。 */
    WARM,

    /** 冷调：把暖燥压住，适合深色主题与偏好蓝绿的人。 */
    COOL,

    /** 低饱和：四档明度更接近，主要靠字与图标区分。 */
    MUTED,
    ;

    companion object {
        /** 落盘的是名字，因此改名会读不回来——未知值一律回落默认，而不是抛异常。 */
        fun fromName(name: String?): RatingPalette =
            entries.firstOrNull { it.name == name } ?: WARM
    }
}
