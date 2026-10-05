// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision

import kotlin.math.roundToInt

/**
 * 把「原图尺度的前景框」换算到「贴纸位图自己的像素尺度」，并夹紧到位图内。
 *
 * ## 为什么单独一个文件
 *
 * 这里修的是一个**形状很典型的静默失败**：分割模型输出的位图是工作尺度（长边 1024），
 * 而前景紧框 `bounds` 是原图像素尺度（长边可达 2560）。拿后者的数值去 `Bitmap.createBitmap`
 * 裁前者会越界抛异常，而那个异常被 `runCatching {}.getOrNull()` 吞掉之后的表现是
 * 「贴纸一直是空的、界面不报错、日志也没有」。产品最招牌的那个视觉就这样消失了三个版本。
 *
 * 所以这个 helper 只接受尺度无关的输入，输出前强制夹紧，退化时返回 `null` 而不是抛——
 * 让调用方必须显式处理失败，而不是靠异常传播。
 *
 * 纯 Kotlin，不 import `android.graphics`（说明书 §11 的架构约束）：这样逐像素边界能在 JVM
 * 单测里验证，而真机上验证一次要几分钟且看不到内部状态。
 */
object CutoutGeometry {

    /**
     * @param bounds 前景紧框 `[left, top, right, bottom]`，**原图像素**尺度。
     * @param sourceWidth 与 [sourceHeight] 是 [bounds] 所相对的那张图尺寸。
     * @param cutoutWidth 与 [cutoutHeight] 是实际要裁的位图尺寸（模型工作尺度）。
     * @param padCutoutPx 在裁切位图尺度上外加的边距，让 die-cut 的白描边不吃到词。
     * @return `intArrayOf(x, y, width, height)`，保证 `0 <= x`、`x + width <= cutoutWidth`
     *   （高同理）。位图或原图尺寸为零、或夹紧后没有面积时返回 `null`。
     */
    fun cropRect(
        bounds: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
        cutoutWidth: Int,
        cutoutHeight: Int,
        padCutoutPx: Int = 0,
    ): IntArray? {
        if (bounds.size != 4) return null
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        if (cutoutWidth <= 0 || cutoutHeight <= 0) return null

        val sx = cutoutWidth.toFloat() / sourceWidth
        val sy = cutoutHeight.toFloat() / sourceHeight

        val left = (bounds[0] * sx).toInt() - padCutoutPx.coerceAtLeast(0)
        val top = (bounds[1] * sy).toInt() - padCutoutPx.coerceAtLeast(0)
        val right = (bounds[2] * sx).roundToInt() + padCutoutPx.coerceAtLeast(0)
        val bottom = (bounds[3] * sy).roundToInt() + padCutoutPx.coerceAtLeast(0)

        var x = left.coerceIn(0, cutoutWidth)
        var y = top.coerceIn(0, cutoutHeight)
        val x1 = right.coerceIn(0, cutoutWidth)
        val y1 = bottom.coerceIn(0, cutoutHeight)
        // 完全落在图外：没有可裁的东西。
        if (x >= cutoutWidth || y >= cutoutHeight) return null

        // 极小的前景框缩到工作尺度后可能不足 1 像素。这里保证至少 1×1 —— 一张 1 像素的贴纸
        // 是丑陋但正确的结果，而 null 会让调用方误判成「后端没出图」并放弃整条抠图路径。
        var width = x1 - x
        var height = y1 - y
        if (width < 1) {
            width = 1
            if (x + width > cutoutWidth) x = cutoutWidth - 1
        }
        if (height < 1) {
            height = 1
            if (y + height > cutoutHeight) y = cutoutHeight - 1
        }
        return intArrayOf(x, y, width, height)
    }

    /**
     * 把源空间矩形按「长边不超过 [maxEdge]」缩到一个小一点的区域。
     *
     * 贴纸最终只在几百像素上显示，但前景可能占满整张 12MP 照片——不加这道限时，
     * 一张极端抠图会要掉 20MB 位图内存。返回的仍是**源空间**的矩形，所以采样坐标
     * 不会因为缩放而错位；输出尺寸由调用方按同一比例算。
     */
    fun capRegion(rect: IntArray, maxEdge: Int): IntArray {
        val longest = maxOf(rect[2], rect[3])
        if (maxEdge <= 0 || longest <= maxEdge) return rect
        val ratio = maxEdge.toFloat() / longest
        return intArrayOf(
            rect[0],
            rect[1],
            (rect[2] * ratio).roundToInt().coerceAtLeast(1),
            (rect[3] * ratio).roundToInt().coerceAtLeast(1),
        )
    }
}
