// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision.camera

import android.graphics.Bitmap

/**
 * YUV_420_888 → 像素数组 → Bitmap。
 *
 * ## 为什么不用 RenderScript
 *
 * 官方样例里的 `YuvToRgbConverter` 依赖 RenderScript，而它在 API 31 被标记废弃、后续版本
 * 只是靠兼容层续命。这个项目要跑得比兼容层久，所以自己写这段转换——它本来就只是一次
 * BT.601 色彩空间换算，没有性能魔法。
 *
 * ## 为什么拆成纯 Kotlin 核心
 *
 * [toPixels] 只吃数组与步长，不碰任何 Android 类型，因此可以在 JVM 单测里逐像素验证
 * （取景页词片对不上物体，九成是这里的 stride 处理错了——而那种错误在预览里根本查不出
 * 是哪一步的锅）。[toBitmap] 只是薄封装。
 *
 * ## stride 的坑
 *
 * `ImageProxy` 的三个平面各自有 rowStride 与 pixelStride：行末可能有填充字节，色度平面
 * 可能隔一个采样点才有一个值（半分辨率）。直接按 `width` 连续读会整体错位。
 */
object YuvFrames {

    /**
     * @param yRowStride 亮度平面行距（字节），>= width。
     * @param yPixelStride 亮度平面像素间距，通常为 1。
     * @param uvRowStride 色度平面行距，通常为 width（或 width 的向上取整倍数）。
     * @param uvPixelStride 色度平面像素间距：1 = 422（全分辨率色度），2 = 420（半分辨率，最常见）。
     */
    fun toPixels(
        y: ByteArray,
        yRowStride: Int,
        yPixelStride: Int,
        u: ByteArray,
        v: ByteArray,
        uvRowStride: Int,
        uvPixelStride: Int,
        width: Int,
        height: Int,
    ): IntArray {
        val pixels = IntArray(width * height)
        val chromaMax = (width + 1) / 2
        var out = 0
        for (row in 0 until height) {
            val yRow = row * yRowStride
            val chromaRow = row / 2
            for (col in 0 until width) {
                // 夹紧下标：个别驱动给出的 stride 比实际数据大，越界读会抛 ArrayIndexOutOfBounds
                // 并让整个取景页失去词片——宁可取最后一个采样点。
                val yv = (y[safe(yRow + col * yPixelStride, y)].toInt() and 0xFF)
                val chromaCol = (col / 2).coerceAtMost(chromaMax - 1)
                val cIdx = safe(chromaRow * uvRowStride + chromaCol * uvPixelStride, u)
                val cIdxV = safe(chromaRow * uvRowStride + chromaCol * uvPixelStride, v)
                val uv = (u[cIdx].toInt() and 0xFF) - 128
                val vv = (v[cIdxV].toInt() and 0xFF) - 128

                // BT.601 视频量程（Y 16..235，UV ±128）。定点化：系数放大 256 倍取整。
                val c = (yv - 16) * 298 // 298/256 ≈ 1.164
                val r = (c + 409 * vv) shr 8 // 1.596
                val g = (c - 100 * uv - 208 * vv) shr 8 // 0.391 / 0.813
                val b = (c + 517 * uv) shr 8 // 2.018

                pixels[out++] = (0xFF shl 24) or
                    (r.coerceIn(0, 255) shl 16) or
                    (g.coerceIn(0, 255) shl 8) or
                    b.coerceIn(0, 255)
            }
        }
        return pixels
    }

    private fun safe(index: Int, array: ByteArray): Int = index.coerceIn(0, array.size - 1)

    fun toBitmap(
        y: ByteArray,
        yRowStride: Int,
        yPixelStride: Int,
        u: ByteArray,
        v: ByteArray,
        uvRowStride: Int,
        uvPixelStride: Int,
        width: Int,
        height: Int,
    ): Bitmap = Bitmap.createBitmap(
        toPixels(y, yRowStride, yPixelStride, u, v, uvRowStride, uvPixelStride, width, height),
        width,
        height,
        Bitmap.Config.ARGB_8888,
    )

    /** 平均亮度 0..1 与冷暖倾向 -1..1（正值偏暖），给氛围词评分用。 */
    data class FrameLuma(val brightness: Float, val warmth: Float)

    /**
     * 从像素数组抽样估计亮度与冷暖。
     *
     * 每 [step] 个像素取一个（默认 8）：氛围词只需要「亮/暗、暖/冷」这种粗信号，
     * 全像素扫描在 640x480@2Hz 的流上是白付的开销。
     */
    fun luma(pixels: IntArray, step: Int = 8): FrameLuma {
        if (pixels.isEmpty() || step <= 0) return FrameLuma(0f, 0f)
        var sum = 0L
        var warm = 0L
        var count = 0
        var i = 0
        while (i < pixels.size) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            // Rec.709 亮度权重，人眼对绿最敏感。
            sum += (0.2126f * r + 0.7152f * g + 0.0722f * b).toInt()
            warm += r - b
            count++
            i += step
        }
        return FrameLuma(
            brightness = (sum.toFloat() / count / 255f).coerceIn(0f, 1f),
            warmth = (warm.toFloat() / count / 255f).coerceIn(-1f, 1f),
        )
    }
}
