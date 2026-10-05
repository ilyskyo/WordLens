// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision.detection

import android.graphics.RectF
import androidx.compose.ui.geometry.Offset

/**
 * 一个被检测到的物体：它在哪、是什么、有多确定。
 *
 * ## 为什么必须有位置
 *
 * 只有类别没有位置，界面就只能给出一列词，用户得自己在画面里找哪个是哪个。一旦有了
 * 位置，词就可以**长在物体上**，点一下就是那个东西——这正是「看画面自动出词、点物品
 * 聚焦保存」这条交互成立的前提。
 *
 * 坐标一律用**归一化的 `[0,1]`**，因为它要同时喂给：Compose 覆盖层（屏幕坐标系）、
 * CameraX 的对焦/变焦计算（传感器归一化坐标）、以及词典与贴纸的裁切几何。
 * 在这几层之间转来转去是这类功能最常见的 off-by-one 来源，所以统一在源头定死。
 *
 * 注意换算发生在 [EfficientDetector]：MediaPipe 交出来的是**像素**框，必须在那里除以源图
 * 尺寸再进这个契约，别指望它已经是归一化的。
 */
data class DetectedObject(
    /** 模型给出的类别名，可能是复数或复合词（"dining table"、"sports ball"）。 */
    val categoryName: String,

    /** 模型置信度，0..1。 */
    val score: Float,

    /** 归一化的外接框，(left, top, right, bottom)，取值 0..1。 */
    val box: RectF,

    /** 原始像素尺寸，用于把归一化框换算回像素。 */
    val sourceWidth: Int,
    val sourceHeight: Int,

    /**
     * 这个框对应的词典条目 id。留空表示检测到了但词典里没有——界面应当把它标成
     * 「不认识」而不是悄悄丢掉，否则用户会觉得 App 在漏看东西。
     */
    val lexiconEntryId: String? = null,

    /** 展示用的词：词典里有就用词条里的词，否则退回类别名。 */
    val displayWord: String? = null,
) {
    /** 中心点，归一化。 */
    val center: Offset get() = Offset(box.centerX(), box.centerY())

    /** 框的面积占比，0..1。 */
    val areaFraction: Float get() = box.width() * box.height()

    /** 短边占比，用于判断「这个东西小不小」。 */
    val minEdgeFraction: Float get() = minOf(box.width(), box.height())

    val pixelRect: RectF
        get() = RectF(
            box.left * sourceWidth,
            box.top * sourceHeight,
            box.right * sourceWidth,
            box.bottom * sourceHeight,
        )

    /**
     * 两个框的交并比。用于同一次检测里合并重叠的重复框——EfficientDet 在物体堆叠时
     * 常给出几个高度重叠的框，全画出来会让覆盖层变成一团乱麻。
     */
    fun iouWith(other: DetectedObject): Float {
        val left = maxOf(box.left, other.box.left)
        val top = maxOf(box.top, other.box.top)
        val right = minOf(box.right, other.box.right)
        val bottom = minOf(box.bottom, other.box.bottom)
        val intersection = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
        if (intersection <= 0f) return 0f
        val union = box.width() * box.height() + other.box.width() * other.box.height() - intersection
        return if (union <= 0f) 0f else intersection / union
    }
}

/** 覆盖层布局用的一层。语义定义在数据层，见 `com.ilyskyo.wordlens.data.model.OverlayLayer`。 */
typealias OverlayLayer = com.ilyskyo.wordlens.data.model.OverlayLayer