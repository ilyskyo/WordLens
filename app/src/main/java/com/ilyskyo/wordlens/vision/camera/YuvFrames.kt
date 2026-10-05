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
 *
 * ## 空平面
 *
 * 首帧和退订后的尾帧里，某个平面的 buffer 可能剩 0 字节。这一层的约定是**任何入参都不抛**：
 * 色度缺失按「没有颜色」处理（灰度），亮度缺失按「没有图像」处理（整张中灰）。理由是这里的
 * 异常会被每帧的 `runCatching` 吞掉，一次抛异常等于取景页从此没有词片，而且不留一行日志。
 */
object YuvFrames {

    /** U/V 的零点：这个字节表示「色度偏移为零」，也就是没有颜色。 */
    private const val NEUTRAL_CHROMA = 128

    /**
     * 没有任何图像信息时给出的像素：不透明中灰（ARGB 0xFF808080）。
     *
     * 为什么是中灰而不是纯黑：纯黑会被 [luma] 读成「这帧很暗」，氛围词因此往 night/dark 偏，
     * 等于把一个空平面解释成了负面的场景证据；中灰读成「亮度无从判断」，与真实情况一致。
     */
    private const val NEUTRAL_PIXEL = (0xFF shl 24) or (0x80 shl 16) or (0x80 shl 8) or 0x80

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
        // 尺寸退化时给空数组而不是让 IntArray(负数) 抛：调用方的 createBitmap 会显式报错，
        // 但那已经是它的责任范围了，这一层不该用异常来表达「这帧没画面」。
        if (width <= 0 || height <= 0) return IntArray(0)
        // 亮度平面为空 = 这一帧根本没有可读的图像。返回一整张中性灰、且长度正好是
        // width*height 的像素数组：长度不对，调用方紧接着的 createBitmap 会再崩一次，
        // 于是同一个静默失败换了个栈顶而已。
        if (y.isEmpty()) return IntArray(width * height) { NEUTRAL_PIXEL }
        val pixels = IntArray(width * height)
        val chromaMax = (width + 1) / 2
        var out = 0
        for (row in 0 until height) {
            val yRow = row * yRowStride
            val chromaRow = row / 2
            for (col in 0 until width) {
                // 夹紧下标：个别驱动给出的 stride 比实际数据大，越界读会抛 ArrayIndexOutOfBounds
                // 并让整个取景页失去词片——宁可取最后一个采样点。
                val yv = luminance(y, yRow + col * yPixelStride)
                val chromaCol = (col / 2).coerceAtMost(chromaMax - 1)
                val cIdx = chromaRow * uvRowStride + chromaCol * uvPixelStride
                val uv = chroma(u, cIdx) - 128
                val vv = chroma(v, cIdx) - 128

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

    /**
     * 亮度采样。空平面给 0（调用方已在入口早退，这里只是让下标运算没有死角）。
     *
     * 为什么不写 `index.coerceIn(0, size - 1)`：空数组时那个区间是 `(0, -1)`，
     * `coerceIn` 会抛 `IllegalArgumentException` —— 而这段代码每帧都跑、异常每帧被
     * 外层的 `runCatching` 吞掉，最终现象是取景页的词片**永久消失**且日志里一个字都没有。
     * 分两步夹（先取下界再取上界）永远不会构造空区间：上界 `size - 1 >= 0` 已由非空保证。
     */
    private fun luminance(plane: ByteArray, index: Int): Int =
        if (plane.isEmpty()) 0 else (plane[index.coerceAtLeast(0).coerceAtMost(plane.size - 1)].toInt() and 0xFF)

    /**
     * 色度采样：平面为空时返回 128。
     *
     * 为什么是 128：YUV 的 U/V 是**有符号偏移量**，128 那个字节表示「偏移为零」。取 128
     * 就是声明「这一帧没有颜色信息」，画面退化成灰度——帧还在、词片照出。空平面在个别
     * 机型的首帧与退订后的尾帧上确实存在（`ImageProxy.planes[1].buffer` 剩 0 字节）。
     */
    private fun chroma(plane: ByteArray, index: Int): Int =
        if (plane.isEmpty()) NEUTRAL_CHROMA
        else plane[index.coerceAtLeast(0).coerceAtMost(plane.size - 1)].toInt() and 0xFF

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
