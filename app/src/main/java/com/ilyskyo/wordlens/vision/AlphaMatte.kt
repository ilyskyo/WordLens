// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 由低分辨率前景 mask 合成贴纸的 alpha 通道。
 *
 * ## 为什么需要它
 *
 * ML Kit 的主体分割只给置信度 mask，不给抠好的图——所以此前它的 `Result.cutout` 是 `null`，
 * 而取景页恰好优先选中了这个「自动」后端（`automatic ?: manual`）。结果是：**装了 Play 服务的
 * 机器一张贴纸都拿不到**。这个文件就是把那份 mask 变成真正的透明贴纸所需要的全部数学。
 *
 * mask 是 160 尺度的（见 [SubjectSegmenter.MASK_EDGE]），贴纸可能是几百到一千多像素，
 * 所以放大必须是双线性而不是最近邻——否则边缘会出现可见的阶梯，die-cut 的白描边会沿着
 * 台阶起伏。
 *
 * 纯 Kotlin、只吃 IntArray/FloatArray：不 import `android.graphics`，因此边缘行为可以在
 * JVM 单测里逐像素钉住。
 */
object AlphaMatte {

    /**
     * @param mask 前景置信度 0..1，行优先，尺寸为 [maskWidth]×[maskHeight]。
     * @param sourceWidth 与 [sourceHeight] 是 mask 所对应的源图尺寸（模型输入的原图）。
     * @param region 源空间的裁切框 `[x, y, width, height]`。
     * @param outWidth 与 [outHeight] 是贴纸位图尺寸——[region] 被缩放后的目标大小，
     *   两者不相同时采样仍然正确（这正是 [CutoutGeometry.capRegion] 之后要做的事）。
     * @return 长度 `outWidth * outHeight` 的 alpha（0..255）。入参退化时返回全 0，
     *   调用方拿到一张全透明贴纸而不是崩溃——贴纸缺失是可降级的事，抠图数学出错不是。
     */
    fun alphaForRegion(
        mask: FloatArray,
        maskWidth: Int,
        maskHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        region: IntArray,
        outWidth: Int,
        outHeight: Int,
    ): IntArray {
        val out = IntArray(outWidth * outHeight)
        if (maskWidth <= 0 || maskHeight <= 0 || sourceWidth <= 0 || sourceHeight <= 0) return out
        if (region.size != 4 || outWidth <= 0 || outHeight <= 0) return out
        if (mask.size < maskWidth * maskHeight) return out

        // 源像素 → mask 纹素：像素中心对齐纹素中心，所以先 +0.5 再 -0.5。
        val toMaskX = maskWidth.toFloat() / sourceWidth
        val toMaskY = maskHeight.toFloat() / sourceHeight
        val toSourceX = region[2].toFloat() / outWidth
        val toSourceY = region[3].toFloat() / outHeight

        for (oy in 0 until outHeight) {
            val sourceY = region[1] + (oy + 0.5f) * toSourceY
            val gy = (sourceY + 0.5f) * toMaskY - 0.5f
            val y0 = floor(gy).toInt().coerceIn(0, maskHeight - 1)
            val y1 = (y0 + 1).coerceAtMost(maskHeight - 1)
            val fy = (gy - y0).coerceIn(0f, 1f)

            var index = oy * outWidth
            for (ox in 0 until outWidth) {
                val sourceX = region[0] + (ox + 0.5f) * toSourceX
                val gx = (sourceX + 0.5f) * toMaskX - 0.5f
                val x0 = floor(gx).toInt().coerceIn(0, maskWidth - 1)
                val x1 = (x0 + 1).coerceAtMost(maskWidth - 1)
                val fx = (gx - x0).coerceIn(0f, 1f)

                val top = mask[y0 * maskWidth + x0] * (1f - fx) + mask[y0 * maskWidth + x1] * fx
                val bottom = mask[y1 * maskWidth + x0] * (1f - fx) + mask[y1 * maskWidth + x1] * fx
                val confidence = top * (1f - fy) + bottom * fy

                out[index++] = (confidence.coerceIn(0f, 1f) * 255f).roundToInt()
            }
        }
        return out
    }

    /**
     * 把 alpha 写进 ARGB 数组，RGB 原样保留。
     *
     * 不做预乘也不清零：Android 的位图是非预乘 ARGB，把透明处的 RGB 抹平反而会让
     * 半透明的边缘发黑（沿着毛发、杯柄这种地方尤其明显）。
     */
    fun applyAlpha(argb: IntArray, alpha: IntArray): IntArray {
        val n = minOf(argb.size, alpha.size)
        for (i in 0 until n) {
            argb[i] = (alpha[i] shl 24) or (argb[i] and 0x00FF_FFFF)
        }
        return argb
    }
}
