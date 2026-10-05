// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapExtractor
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenterOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.Stroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tap-to-segment via MediaPipe's `magic_touch` model, shipped in `assets/models`.
 *
 * ## Why this is the primary backend, not the fallback
 *
 * 5.9 MB, no Google Play services, no network. That means the object flow — the app's signature
 * interaction — behaves identically on every device, which is also the only way the "photos stay
 * on your phone" claim can be true, and the only way this could ship to F-Droid.
 *
 * The cost is one tap. That is a fine trade for the object flow, which wants the user to say
 * "this bit" anyway, and a poor trade for the shot classifier, which wants to fire the instant
 * the shutter does. Hence [SubjectSegmenter.automatic]: with Play services present
 * [MlKitSubjectSegmenter] classifies automatically and this backend only refines the tap.
 *
 * ## One model run, two products
 *
 * `segment()` returns an RGBA image with the background transparent, which means its alpha
 * channel *is* the foreground mask. So a single run yields both the cut-out the sticker flow
 * draws and the mask [ShotClassifier] needs to decide object-versus-scene — no second pass, and
 * no risk of the two disagreeing because they came from different inferences.
 *
 * ## Resolution
 *
 * The work runs on a bitmap downscaled to [WORK_EDGE]. The model needs a subject large enough
 * to resolve, and the sticker is displayed at a few hundred pixels, so a 12-megapixel input
 * would cost memory and time for no visible gain.
 */
class MagicTouchSegmenter(private val context: Context) : SubjectSegmenter {

    override val displayName = "Tap to select (on-device)"

    override val automatic = false

    private var segmenter: InteractiveSegmenter? = null

    private var loadError: String? = null

    private fun ensureLoaded(): InteractiveSegmenter? {
        segmenter?.let { return it }
        if (loadError != null) return null
        return try {
            val options = InteractiveSegmenterOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(MODEL_PATH)
                        .build(),
                )
                .build()
            InteractiveSegmenter.createFromOptions(context, options).also { segmenter = it }
        } catch (e: Exception) {
            Log.e(TAG, "could not load $MODEL_PATH", e)
            loadError = e.message ?: "the segmentation model could not be loaded"
            null
        }
    }

    override fun unavailableReason(): String? = loadError

    override suspend fun segment(bitmap: Bitmap, tap: Pair<Float, Float>?): SubjectSegmenter.Result? =
        withContext(Dispatchers.Default) {
            // Without a tap this backend has nothing to segment around; that is the whole
            // contract of [automatic] being false.
            val point = tap ?: return@withContext null
            val engine = ensureLoaded() ?: return@withContext null
            val work = downscale(bitmap, WORK_EDGE)

            val rgba = runSegment(engine, work, point) ?: return@withContext null
            if (work !== bitmap) work.recycle()

            // Alpha is the mask. Reading it once and deriving everything from it keeps the mask
            // and the cut-out provably consistent.
            val width = rgba.width
            val height = rgba.height
            val pixels = IntArray(width * height)
            rgba.getPixels(pixels, 0, width, 0, 0, width, height)

            val mask = FloatArray(width * height)
            for (i in 0 until pixels.size) {
                mask[i] = (pixels[i] ushr 24) / 255f
            }

            // Downscale the mask for the geometry pass; 160px is plenty and keeps
            // `analyseMask` cheap enough to run inline.
            val (maskW, maskH, scaledMask) = downscaleMask(mask, width, height, SubjectSegmenter.MASK_EDGE)
            val bounds = tightBounds(scaledMask, maskW, maskH, bitmap.width, bitmap.height)

            // 贴纸在 rgba 自己的尺度上裁。bounds 是**原图像素**尺度，两者不同——直接把
            // bounds 的数值拿去 createBitmap 就是那个静默失效的 bug，所以必须过 CutoutGeometry。
            val sticker = CutoutGeometry.cropRect(
                bounds = bounds,
                sourceWidth = bitmap.width,
                sourceHeight = bitmap.height,
                cutoutWidth = width,
                cutoutHeight = height,
                padCutoutPx = STICKER_PAD_PX,
            )?.let { rect -> Bitmap.createBitmap(rgba, rect[0], rect[1], rect[2], rect[3]) }
            if (sticker == null) {
                Log.w(TAG, "segmentation produced a degenerate foreground box; no sticker")
            }
            if (sticker !== rgba) rgba.recycle()

            SubjectSegmenter.Result(
                mask = scaledMask,
                maskWidth = maskW,
                maskHeight = maskH,
                cutout = sticker,
                bounds = bounds,
            )
        }

    /**
     * Run the model on a single positive point and extract the resulting bitmap.
     *
     * [point] is normalised to `[0,1]` in image coordinates, which is what
     * [NormalizedKeypoint] expects — no pixel-space conversion needed.
     */
    private fun runSegment(
        engine: InteractiveSegmenter,
        work: Bitmap,
        point: Pair<Float, Float>,
    ): Bitmap? {
        var inputImage: MPImage? = null
        var outputImage: MPImage? = null
        return try {
            inputImage = BitmapImageBuilder(work).build()
            engine.setImage(inputImage)

            val stroke = Stroke.builder()
                .setPoints(
                    listOf(
                        NormalizedKeypoint.create(
                            point.first.coerceIn(0f, 1f),
                            point.second.coerceIn(0f, 1f),
                        ),
                    ),
                )
                .setBrushMode(Stroke.BrushMode.POSITIVE)
                // A finished stroke, not one still being drawn: the interactive segmenter
                // treats an in-progress stroke as a live editing session.
                .setCompleted(true)
                .build()

            outputImage = engine.segment(listOf(stroke))
            BitmapExtractor.extract(outputImage)
        } catch (e: Exception) {
            Log.w(TAG, "segment failed", e)
            null
        } finally {
            runCatching { outputImage?.close() }
            runCatching { inputImage?.close() }
        }
    }

    /** Box-area downscale of a float mask, written out rather than resampled. */
    private fun downscaleMask(
        mask: FloatArray,
        width: Int,
        height: Int,
        edge: Int,
    ): Triple<Int, Int, FloatArray> {
        val longEdge = maxOf(width, height)
        if (longEdge <= edge) return Triple(width, height, mask)
        val ratio = edge.toFloat() / longEdge
        val w = (width * ratio).toInt().coerceAtLeast(1)
        val h = (height * ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val sy = ((y.toFloat() / h) * height).toInt().coerceIn(0, height - 1)
            for (x in 0 until w) {
                val sx = ((x.toFloat() / w) * width).toInt().coerceIn(0, width - 1)
                out[y * w + x] = mask[sy * width + sx]
            }
        }
        return Triple(w, h, out)
    }

    /**
     * Tightest box containing the mask above [ALPHA_CUTOFF], in the *original* image's
     * coordinates. Returns the whole image when the mask covers everything, which is a
     * legitimate answer and avoids a zero-area crop.
     */
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
                if (mask[row + x] >= ALPHA_CUTOFF) {
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

    override fun close() {
        runCatching { segmenter?.close() }
        segmenter = null
    }

    companion object {
        private const val TAG = "MagicTouch"

        /** Must match the file committed at `app/src/main/assets/models/`. */
        const val MODEL_PATH = "models/magic_touch.tflite"

        /** Alpha at or above this counts as foreground. */
        const val ALPHA_CUTOFF = 0.5f

        /** Long edge the model runs at. A sticker never needs more, and this bounds memory. */
        const val WORK_EDGE = 1024

        /**
         * 贴纸四周留的工作尺度像素边距。紧框是按 ALPHA_CUTOFF 算的，不留这一点余量，
         * 界面上那圈 4dp 白描边就会切到物体边缘（毛发、杯柄这类最先到）。
         */
        const val STICKER_PAD_PX = 8

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
