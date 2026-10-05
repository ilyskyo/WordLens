// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The default recogniser: ML Kit's bundled image labeller.
 *
 * ## What this can and cannot do
 *
 * The bundled base model knows roughly four hundred everyday concepts. That is enough for
 * "what is this mug called" and nowhere near enough for "what is this dish of soba called",
 * and the difference from CapWords — a frontier vision model — is the single biggest gap in
 * this app. We do not paper over it: the UI always shows the raw model labels next to the
 * dictionary entry it resolved to, and when nothing resolves it offers to let the user name
 * the thing rather than guessing.
 *
 * ## Why bundled rather than downloaded
 *
 * The unbundled variant needs Google Play services and a first-run model download, which would
 * mean no offline first launch, a dead feature on de-Googled devices, and an F-Droid-hostile
 * dependency graph. The bundled model adds ~5.7 MB and works immediately, everywhere.
 */
class MlKitOnDeviceEngine : RecognitionEngine {

    override val id = RecognitionEngineId.ON_DEVICE

    override val requiresNetwork = false

    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
            // Below ~0.3 the base model starts inventing labels for texture, and a wrong word
            // is worse than no word: it gets saved to a deck and reviewed for weeks.
            .setConfidenceThreshold(MIN_LABEL_CONFIDENCE)
            .build(),
    )

    override fun unavailableReason(): String? = null

    override suspend fun label(bitmap: Bitmap): List<RawLabel> = withContext(Dispatchers.Default) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val labelled: List<ImageLabel> = try {
            labeler.process(image).await().sortedByDescending { it.confidence }
        } catch (e: Exception) {
            // A single failure must not take the camera screen down with it.
            Log.w(TAG, "image labelling failed", e)
            return@withContext emptyList()
        }

        labelled
            .asSequence()
            .filter { it.confidence >= MIN_LABEL_CONFIDENCE }
            .map { RawLabel(it.text.trim(), it.confidence) }
            .filter { it.text.isNotEmpty() }
            // Distinct on the normalised form: the base model happily returns "Food" and
            // "food" as separate labels for the same thing.
            .distinctBy { it.text.lowercase() }
            .take(MAX_LABELS)
            .toList()
    }

    override fun close() {
        runCatching { labeler.close() }
    }

    companion object {
        private const val TAG = "OnDeviceEngine"

        /** ML Kit's own default is 0.3; stated here so the intent is visible at the call site. */
        const val MIN_LABEL_CONFIDENCE = 0.30f

        /** Enough for the matcher to work with; more only adds noise and UI clutter. */
        const val MAX_LABELS = 12
    }
}
