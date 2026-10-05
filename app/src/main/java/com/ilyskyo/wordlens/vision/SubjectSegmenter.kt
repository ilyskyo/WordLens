// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision

import android.content.Context
import android.graphics.Bitmap

/**
 * Something that can find the subject in a photo.
 *
 * ## Two backends, on purpose
 *
 * There is no single segmentation story on Android:
 *
 * - **ML Kit Subject Segmentation** gives the best quality and needs no tap, but it is
 *   unbundled: it only exists if Google Play services is installed, which excludes de-Googled
 *   devices, most Chinese ROMs without Play, and F-Droid-style reproducible builds.
 * - **MediaPipe InteractiveSegmenter** (`magic_touch`) is a 5.9 MB model we ship ourselves, so
 *   it works everywhere, but it needs a tap: you point at the thing.
 *
 * Rather than pretend one covers everyone, both are exposed and the UI adapts. A device with
 * Play services classifies the shot automatically the moment the shutter fires; a device
 * without it asks the user to tap the subject — and that tap is the interaction the object flow
 * wants anyway, because it is also how you say "this bit, not all of it".
 */
interface SubjectSegmenter {

    /** Shown in the UI so the user knows why they are being asked to tap. */
    val displayName: String

    /** True when this backend runs without a tap. */
    val automatic: Boolean

    /** Null when usable; otherwise the reason it is not (missing Play services, no model). */
    fun unavailableReason(): String? = null

    /**
     * Produce a foreground mask and a cut-out.
     *
     * @param bitmap source image.
     * @param tap normalised tap point in [0,1] image coordinates, required when [automatic] is
     *   false. Ignored otherwise.
     * @return the result, or null when segmentation failed.
     */
    suspend fun segment(bitmap: Bitmap, tap: Pair<Float, Float>?): Result?

    fun close() = Unit

    /** Everything the rest of the app needs from one segmentation pass. */
    data class Result(
        /** Foreground confidence, 0..1 per pixel, row-major. Downscale before calling. */
        val mask: FloatArray,
        val maskWidth: Int,
        val maskHeight: Int,
        /** The same source image with the background removed, or null if not produced. */
        val cutout: Bitmap?,
        /** Tight bounding box of the foreground in *source image* pixels. */
        val bounds: IntArray,
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    companion object {
        /** Long edge of the mask. Geometry does not change with resolution and 160 keeps the
         *  connected-component pass cheap enough to run on the main thread. */
        const val MASK_EDGE = 160

        /**
         * The automatic backend, if this device can run it. Null on devices without Play
         * services, which is not an error — the tap-to-select fallback covers them.
         */
        fun available(context: Context): SubjectSegmenter? =
            runCatching { MlKitSubjectSegmenter(context) }
                .getOrNull()
                ?.takeIf { it.unavailableReason() == null }
    }
}
