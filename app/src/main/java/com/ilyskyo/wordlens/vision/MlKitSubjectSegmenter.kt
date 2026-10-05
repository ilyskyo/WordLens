// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ML Kit's subject segmentation: the best cut-out available on Android, and the only one that
 * runs without the user tapping.
 *
 * ## It is optional by construction
 *
 * The model ships through Google Play services, so this class simply does not exist on devices
 * without it. That is not a degraded mode to apologise for — it is the reason
 * [MagicTouchSegmenter] carries the object flow — but it does mean the *automatic* shot
 * classification is unavailable there, and the UI has to ask for a tap first. Keeping the two
 * behind one interface is what stops that difference from leaking into the rest of the app.
 *
 * ## Memory
 *
 * As with the MediaPipe path, segmentation runs on a bitmap downscaled to
 * [SubjectSegmenter.MASK_EDGE]. ML Kit's confidence mask is the size of its input, so a 12 MP
 * source would mean a 48 MB float buffer.
 */
class MlKitSubjectSegmenter(context: Context) : SubjectSegmenter {

    override val displayName = "Automatic (Google ML Kit)"

    override val automatic = true

    private val appContext = context.applicationContext

    private var segmenter: com.google.mlkit.vision.segmentation.subject.SubjectSegmenter? = null

    private var unavailable: String? = null

    override fun unavailableReason(): String? = unavailable

    private fun ensure(): com.google.mlkit.vision.segmentation.subject.SubjectSegmenter? {
        segmenter?.let { return it }
        if (unavailable != null) return null
        return try {
            val options = SubjectSegmenterOptions.Builder()
                .enableForegroundConfidenceMask()
                .build()
            SubjectSegmentation.getClient(options).also { segmenter = it }
        } catch (e: Exception) {
            // Thrown when Play services is missing or too old.
            Log.i(TAG, "Subject segmentation unavailable: ${e.message}")
            unavailable = "Google Play services is not available on this device"
            null
        }
    }

    override suspend fun segment(bitmap: Bitmap, tap: Pair<Float, Float>?): SubjectSegmenter.Result? =
        withContext(Dispatchers.Default) {
            val engine = ensure() ?: return@withContext null
            val small = downscale(bitmap, SubjectSegmenter.MASK_EDGE)

            val mask: FloatArray
            val width: Int
            val height: Int
            try {
                val result = engine.process(InputImage.fromBitmap(small, 0)).await()
                val buffer = result.foregroundConfidenceMask
                    ?: return@withContext null
                // The mask matches its input's dimensions, which we control.
                width = small.width
                height = small.height
                mask = FloatArray(width * height)
                buffer.rewind()
                buffer.get(mask)
            } catch (e: Exception) {
                Log.w(TAG, "segment failed", e)
                return@withContext null
            }

            val bounds = tightBounds(mask, width, height, bitmap.width, bitmap.height)
            if (small !== bitmap) small.recycle()
            SubjectSegmenter.Result(
                mask = mask,
                maskWidth = width,
                maskHeight = height,
                // 以前这里是硬编码的 null，而取景页优先选 automatic —— 装了 Play 服务的机器
                // 反而一张贴纸都拿不到。mask 本身就够用，剩下的只是把它变成 alpha 通道。
                cutout = sticker(bitmap, mask, width, height, bounds),
                bounds = bounds,
            )
        }

    /**
     * 由 160 尺度的置信度 mask 合成一张裁好、背景透明的贴纸。
     *
     * 顺序很重要：**先算输出尺寸，再一次 crop+scale**。先按原分辨率裁出一块 2560×1920 再缩放，
     * 峰值内存是 19MB；`createBitmap(src, x, y, w, h, matrix, filter)` 只分配最终那张
     * （长边 [MAX_STICKER_EDGE]），峰值约 4MB。
     */
    private fun sticker(
        source: Bitmap,
        mask: FloatArray,
        maskWidth: Int,
        maskHeight: Int,
        bounds: IntArray,
    ): Bitmap? {
        val rect = CutoutGeometry.cropRect(
            bounds = bounds,
            sourceWidth = source.width,
            sourceHeight = source.height,
            cutoutWidth = source.width,
            cutoutHeight = source.height,
            padCutoutPx = STICKER_PAD_PX,
        ) ?: return null
        val size = CutoutGeometry.capRegion(rect, MAX_STICKER_EDGE)
        val outWidth = size[2]
        val outHeight = size[3]

        val scale = Matrix().apply {
            postScale(outWidth.toFloat() / rect[2], outHeight.toFloat() / rect[3])
        }
        val cropped = runCatching {
            Bitmap.createBitmap(source, rect[0], rect[1], rect[2], rect[3], scale, true)
        }.getOrNull() ?: return null

        val pixels = IntArray(outWidth * outHeight)
        cropped.getPixels(pixels, 0, outWidth, 0, 0, outWidth, outHeight)
        val alpha = AlphaMatte.alphaForRegion(
            mask = mask,
            maskWidth = maskWidth,
            maskHeight = maskHeight,
            sourceWidth = source.width,
            sourceHeight = source.height,
            region = rect,
            outWidth = outWidth,
            outHeight = outHeight,
        )
        cropped.setPixels(AlphaMatte.applyAlpha(pixels, alpha), 0, outWidth, 0, 0, outWidth, outHeight)
        return cropped
    }

    override fun close() {
        runCatching { segmenter?.close() }
        segmenter = null
    }

    private fun tightBounds(
        mask: FloatArray,
        maskW: Int,
        maskH: Int,
        srcW: Int,
        srcH: Int,
    ): IntArray {
        var minX = maskW
        var minY = maskH
        var maxX = -1
        var maxY = -1
        for (y in 0 until maskH) {
            val row = y * maskW
            for (x in 0 until maskW) {
                if (mask[row + x] >= CUTOFF) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return intArrayOf(0, 0, srcW, srcH)
        val sx = srcW.toFloat() / maskW
        val sy = srcH.toFloat() / maskH
        return intArrayOf(
            (minX * sx).toInt().coerceIn(0, srcW - 1),
            (minY * sy).toInt().coerceIn(0, srcH - 1),
            ((maxX + 1) * sx).toInt().coerceIn(1, srcW),
            ((maxY + 1) * sy).toInt().coerceIn(1, srcH),
        )
    }

    private companion object {
        const val TAG = "MlKitSegmenter"
        const val CUTOFF = 0.5f

        /** 贴纸长边上限。界面最大也就 300dp，再细只是多占内存。 */
        const val MAX_STICKER_EDGE = 1024

        /** 紧框四周留的**原图像素**边距，让 4dp 白描边不至于切到物体边缘。 */
        const val STICKER_PAD_PX = 12

        fun downscale(source: Bitmap, edge: Int): Bitmap {
            val longEdge = maxOf(source.width, source.height)
            if (longEdge <= edge) return source
            val ratio = edge.toFloat() / longEdge
            return Bitmap.createScaledBitmap(
                source,
                (source.width * ratio).toInt().coerceAtLeast(1),
                (source.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        }
    }
}
