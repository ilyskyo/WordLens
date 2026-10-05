// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision

import android.content.Context
import android.graphics.Bitmap
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
            SubjectSegmenter.Result(
                mask = mask,
                maskWidth = width,
                maskHeight = height,
                cutout = null,
                bounds = bounds,
            )
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
