// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision

import com.ilyskyo.wordlens.data.model.ShotKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks down the object-vs-scene decision.
 *
 * The thresholds encode a claim about how people frame photographs, so they are the kind of
 * constant that silently rots if nobody notices it changed. Every band in [ShotClassifier] has
 * a test here that would fail if the band moved.
 */
class ShotClassifierTest {

    private val w = 100
    private val h = 100

    // ── mask analysis ────────────────────────────────────────────────────────

    @Test
    fun `a centred square is one solid component`() {
        val mask = rectMask(25, 25, 50, 50) // 25% of the frame
        val stats = ShotClassifier.analyseMask(mask, w, h)

        assertEquals(0.25f, stats.coverage, 0.001f)
        assertEquals(1.0f, stats.dominantRatio, 0.001f)
        assertEquals(1, stats.components)
        assertEquals(0, stats.edgeTouch)
        assertTrue(!stats.touchesAllEdges)
    }

    @Test
    fun `a full-frame mask touches every edge`() {
        val stats = ShotClassifier.analyseMask(FloatArray(w * h) { 1f }, w, h)
        assertEquals(1.0f, stats.coverage, 0.001f)
        assertTrue(stats.touchesAllEdges)
    }

    @Test
    fun `scattered blobs are counted separately and none dominates`() {
        val mask = FloatArray(w * h)
        for (i in 0 until 5) {
            val x = 6 + i * 18
            for (y in 10..20) for (px in x until x + 6) mask[y * w + px] = 1f
        }
        val stats = ShotClassifier.analyseMask(mask, w, h)

        assertEquals(5, stats.components)
        // Five equally sized blobs: the largest is a fifth of the foreground, which is exactly
        // why a scattered frame reads as a scene rather than a subject.
        assertEquals(0.2f, stats.dominantRatio, 0.001f)
        assertTrue("coverage=${stats.coverage}", stats.coverage < ShotClassifier.COMFORTABLE)
    }

    @Test
    fun `a dominant blob next to small ones yields a low dominant ratio`() {
        val mask = rectMask(10, 10, 40, 40) // 16%
        fillRect(mask, 80, 80, 6, 6)      // one speck
        fillRect(mask, 90, 20, 5, 5)
        val stats = ShotClassifier.analyseMask(mask, w, h)

        assertEquals(3, stats.components)
        assertTrue("dominant=${stats.dominantRatio}", stats.dominantRatio < 0.99f)
        assertTrue("dominant=${stats.dominantRatio}", stats.dominantRatio > 0.95f)
    }

    @Test
    fun `an empty mask yields zero stats rather than a division by zero`() {
        val stats = ShotClassifier.analyseMask(FloatArray(w * h), w, h)
        assertEquals(0f, stats.coverage, 0f)
        assertEquals(0, stats.components)
        assertEquals(0f, stats.dominantRatio, 0f)
    }

    @Test
    fun `a degenerate mask is rejected instead of crashing`() {
        assertEquals(ShotClassifier.MaskStats.UNKNOWN, ShotClassifier.analyseMask(FloatArray(0), 0, 0))
    }

    @Test
    fun `thresholding respects the confidence threshold`() {
        // A soft image: half at 0.6 confidence, half at 0.4.
        val soft = FloatArray(w * h) { 0.4f }
        for (y in 0 until 50) for (x in 0 until 50) soft[y * w + x] = 0.6f

        // Between the two confidences: only the confident region counts.
        assertEquals(0.25f, ShotClassifier.analyseMask(soft, w, h, threshold = 0.5f).coverage, 0.001f)
        // Below both: everything counts.
        assertEquals(1.0f, ShotClassifier.analyseMask(soft, w, h, threshold = 0.3f).coverage, 0.001f)
        // Above both: nothing counts, and that must report zero rather than divide by zero.
        assertEquals(0.0f, ShotClassifier.analyseMask(soft, w, h, threshold = 0.9f).coverage, 0.001f)
    }

    // ── the decision ─────────────────────────────────────────────────────────

    @Test
    fun `a subject filling a quarter of the frame is an object`() {
        val verdict = ShotClassifier.classify(
            ShotClassifier.MaskStats(coverage = 0.25f, dominantRatio = 1.0f, components = 1, edgeTouch = 0),
        )
        assertEquals(ShotKind.OBJECT, verdict.kind)
        assertTrue("confidence=${verdict.confidence}", verdict.confidence > 0.55f)
    }

    /** The rule the whole design rests on: too small means the surroundings are the point. */
    @Test
    fun `a subject too small to be the subject is a scene`() {
        val verdict = ShotClassifier.classify(
            ShotClassifier.MaskStats(coverage = 0.012f, dominantRatio = 1.0f, components = 1, edgeTouch = 0),
        )
        assertEquals(ShotKind.SCENE, verdict.kind)
        assertTrue("confidence=${verdict.confidence}", verdict.confidence > 0.55f)
    }

    /**
 * The band from TINY to COMFORTABLE is deliberately a grey zone: at 2% of the frame the
 * "subject" is not what the photo is about, while by 5.5% it comfortably is. The test asserts
 * the call flips somewhere inside that band, at a point on each side where the answer is
 * unambiguous.
 */
    @Test
    fun `the call flips inside the tiny-to-comfortable band`() {
        val justUnder = ShotClassifier.classify(
            ShotClassifier.MaskStats(coverage = ShotClassifier.TINY - 0.002f, dominantRatio = 1f, components = 1, edgeTouch = 0),
        )
        val comfortablyOver = ShotClassifier.classify(
            ShotClassifier.MaskStats(coverage = ShotClassifier.TINY + 0.030f, dominantRatio = 1f, components = 1, edgeTouch = 0),
        )
        assertEquals(ShotKind.SCENE, justUnder.kind)
        assertEquals(ShotKind.OBJECT, comfortablyOver.kind)
    }

    /** A frame-filling close-up has no surroundings, so we must not pretend to know. */
    @Test
    fun `a frame-filling close-up abstains`() {
        val verdict = ShotClassifier.classify(
            ShotClassifier.MaskStats(coverage = 0.95f, dominantRatio = 1f, components = 1, edgeTouch = 0b1111),
        )
        assertEquals(ShotKind.UNCLEAR, verdict.kind)
        assertTrue(verdict.reason.contains("close-up"))
    }

    @Test
    fun `a big but fragmented foreground is a scene`() {
        val verdict = ShotClassifier.classify(
            ShotClassifier.MaskStats(coverage = 0.30f, dominantRatio = 0.30f, components = 7, edgeTouch = 0b1111),
        )
        assertEquals(ShotKind.SCENE, verdict.kind)
        assertTrue("confidence=${verdict.confidence}", verdict.confidence > 0.55f)
    }

    @Test
    fun `place labels and object labels pull a borderline frame opposite ways`() {
        // Geometry alone here is genuinely mixed: a 5% subject, only 55% of the foreground in
        // one blob, three components. It should be decided by the labels, which is the whole
        // reason the label signal exists.
        val stats = ShotClassifier.MaskStats(coverage = 0.05f, dominantRatio = 0.55f, components = 3, edgeTouch = 0)

        val asPlace = ShotClassifier.classify(stats, labels = listOf("Kitchen" to 0.9f, "Home good" to 0.5f))
        val asThing = ShotClassifier.classify(stats, labels = listOf("Bottle" to 0.9f, "Drink" to 0.5f))

        assertEquals(ShotKind.SCENE, asPlace.kind)
        assertEquals(ShotKind.OBJECT, asThing.kind)
        assertTrue("${asPlace.confidence} should exceed ${asThing.confidence}", asPlace.confidence > asThing.confidence)
    }

    /**
     * The sign of the label lean is the single easiest thing to get backwards, and getting it
     * backwards still produces plausible-looking numbers — it just quietly flips the verdict.
     * This pins the direction on a shot whose geometry already says OBJECT.
     */
    @Test
    fun `a strong place label flips an object shot toward scene`() {
        val cleanObject = ShotClassifier.MaskStats(coverage = 0.30f, dominantRatio = 1f, components = 1, edgeTouch = 0)

        assertEquals(ShotKind.OBJECT, ShotClassifier.classify(cleanObject).kind)
        // Geometry alone says one object, but a single object filling 30% of a beach photo is
        // still a photo of a beach, so the label must be able to overrule it.
        assertEquals(
            ShotKind.SCENE,
            ShotClassifier.classify(cleanObject, labels = listOf("Beach" to 0.95f)).kind,
        )
    }

    @Test
    fun `without segmentation and without labels the app abstains`() {
        val verdict = ShotClassifier.classify(null)
        assertEquals(ShotKind.UNCLEAR, verdict.kind)
        assertEquals(null, verdict.stats)
        assertTrue(verdict.reason.contains("Segmentation"))
    }

    @Test
    fun `without segmentation a place label still suggests scene`() {
        val verdict = ShotClassifier.classify(null, labels = listOf("Beach" to 0.95f))
        assertEquals(ShotKind.SCENE, verdict.kind)
        assertEquals(null, verdict.stats)
    }

    @Test
    fun `without segmentation a specific-thing label suggests object`() {
        val verdict = ShotClassifier.classify(null, labels = listOf("Bicycle" to 0.95f))
        assertEquals(ShotKind.OBJECT, verdict.kind)
    }

    @Test
    fun `confidence rises as evidence becomes one-sided`() {
        val weak = ShotClassifier.classify(
            ShotClassifier.MaskStats(coverage = 0.08f, dominantRatio = 0.62f, components = 1, edgeTouch = 0),
        )
        val strong = ShotClassifier.classify(
            ShotClassifier.MaskStats(coverage = 0.30f, dominantRatio = 1.0f, components = 1, edgeTouch = 0),
        )
        assertTrue("${strong.confidence} should exceed ${weak.confidence}", strong.confidence > weak.confidence)
    }

    @Test
    fun `the reason always explains itself`() {
        val stats = ShotClassifier.MaskStats(coverage = 0.01f, dominantRatio = 0.9f, components = 1, edgeTouch = 0)
        val verdict = ShotClassifier.classify(stats)
        assertTrue(verdict.reason.isNotBlank())
        assertTrue(verdict.reason.contains("1%"))
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** A mask containing only a filled rectangle at [x0],[y0] of size [rw]x[rh]. */
    private fun rectMask(x0: Int, y0: Int, rw: Int, rh: Int): FloatArray {
        val mask = FloatArray(w * h)
        fillRect(mask, x0, y0, rw, rh)
        return mask
    }

    private fun fillRect(mask: FloatArray, x0: Int, y0: Int, rw: Int, rh: Int) {
        for (y in y0 until (y0 + rh).coerceAtMost(h)) {
            for (x in x0 until (x0 + rw).coerceAtMost(w)) {
                mask[y * w + x] = 1f
            }
        }
    }
}
