// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision

import com.ilyskyo.wordlens.data.model.ShotKind

/**
 * Decides whether a photo is of *one object* or of *a situation*.
 *
 * This runs before any vocabulary work, and it is the highest-leverage decision in the app: the
 * two flows want opposite vocabularies, and a photo of a keyboard at 3% of the frame wants
 * "keyboard", while the same keyboard small in a wide office shot wants "keyboard", "desk",
 * "meeting", "schedule", "quality".
 *
 * ## The primary signal is how much of the frame the subject takes up
 *
 * Size is the signal to trust because it survives the things that break label matching. A
 * labeller will happily call a cramped office photo "Home good" and a close-up of a wrist
 * "Clothing"; it will not call a 1%-of-frame speck an object. So:
 *
 * - a subject too small to be what the photographer was pointing at -> [ShotKind.SCENE]
 * - a subject comfortably filling the frame -> [ShotKind.OBJECT]
 * - a subject filling essentially everything -> [ShotKind.UNCLEAR], because that is a close-up
 *   or a face, and the crop is going to be either meaningless or impolite
 *
 * Two supporting signals exist because size alone cannot separate a small object held at arm's
 * length from a distant scene: **cohesion** (does the foreground form one blob or many) and
 * **label agreement** (does the labeller name a specific thing, or a place/activity).
 *
 * Pure Kotlin on purpose: no Android types, so the thresholds are unit-testable and can be
 * retuned without a device in the loop.
 */
object ShotClassifier {

    // ── size bands, as a fraction of the whole frame ─────────────────────────
    // Chosen from how people actually frame things: you point at an object to fill a good
    // chunk of the viewfinder, and you back off to take in a room.

    /** Below this the "subject" is not what the photo is about. */
    const val TINY = 0.020f

    /** Above this, and below [FILLING], the subject is the subject. */
    const val COMFORTABLE = 0.055f

    /** At or above this the frame is full: close-up, portrait, or a wall. */
    const val FILLING = 0.72f

    /** Fraction of foreground that must sit in the largest blob for "one subject". */
    const val DOMINANT_MIN = 0.62f

    /** Blobs of at least this share of the frame count as "components". */
    const val COMPONENT_MIN_AREA = 0.0025f

    /** Edge indices used by [MaskStats.edgeTouch]. */
    const val EDGE_TOP = 0
    const val EDGE_RIGHT = 1
    const val EDGE_BOTTOM = 2
    const val EDGE_LEFT = 3

    /**
     * Geometry of the segmented foreground.
     *
     * @param coverage foreground pixels divided by total pixels.
     * @param dominantRatio size of the largest connected component divided by total foreground.
     * @param components number of connected components at least [COMPONENT_MIN_AREA].
     * @param edgeTouch bitmap of which frame edges the *dominant* component reaches.
     */
    data class MaskStats(
        val coverage: Float,
        val dominantRatio: Float,
        val components: Int,
        val edgeTouch: Int,
    ) {
        val touchesAllEdges: Boolean get() = edgeTouch == 0b1111

        companion object {
            /** Used when no segmentation is available; makes every signal abstain. */
            val UNKNOWN = MaskStats(0f, 0f, 0, 0)
        }
    }

    /** The decision, with the evidence that produced it so the UI can explain itself. */
    data class Verdict(
        val kind: ShotKind,
        /** 0..1 agreement with [kind]. */
        val confidence: Float,
        /** Short human-readable justification, shown under the mode switch. */
        val reason: String,
        val stats: MaskStats?,
    )

    private const val EPSILON = 1e-6f

    /**
     * @param stats foreground geometry, or null when segmentation is unavailable.
     * @param labels labeller output as `(text, score)`, best first.
     * @param scenePrior hint from the labeller that the frame reads as a place/activity.
     */
    fun classify(
        stats: MaskStats?,
        labels: List<Pair<String, Float>> = emptyList(),
        scenePrior: Boolean = false,
    ): Verdict {
        if (stats == null) return labelOnly(labels)

        // No background to read: the question itself does not apply. A close-up of a wrist and a
        // photograph of a brick wall both fill the frame, and calling either of them a "scene"
        // would be a confident non-answer. Abstain and let the user pick.
        if (stats.coverage >= FILLING) {
            return Verdict(
                ShotKind.UNCLEAR,
                confidence = 0.30f,
                reason = "The photo is a close-up: the frame is filled edge to edge, so there is " +
                    "no surroundings to read as a scene.",
                stats = stats,
            )
        }

        val labelLean = labelLean(labels, scenePrior)
        // `labelLean` is positive when the labels read as a place, so it must raise the scene
        // evidence and lower the object evidence. Adding it to the object side instead — which
        // is easy to do and hard to see — makes "Kitchen" argue for a single object.
        val evidenceForObject = objectScore(stats) - labelLean
        val evidenceForScene = sceneScore(stats) + labelLean

        // Logistic squash so that "one argument much stronger than the other" reads as
        // confidence, rather than the raw difference — which grows without bound and made a
        // perfectly clear shot report low confidence.
        val pObject = logistic(evidenceForObject)
        val pScene = logistic(evidenceForScene)

        val kind = when {
            evidenceForObject < WEAK && evidenceForScene < WEAK ->
                ShotKind.UNCLEAR to "Nothing dominant was found in the photo."

            kotlin.math.abs(pObject - pScene) < AMBIGUOUS_MARGIN ->
                ShotKind.UNCLEAR to "The photo could read either way."

            pObject > pScene ->
                ShotKind.OBJECT to explain(stats, labelLean)

            else ->
                ShotKind.SCENE to explain(stats, labelLean)
        }

        return Verdict(
            kind = kind.first,
            confidence = maxOf(pObject, pScene),
            reason = kind.second,
            stats = stats,
        )
    }

    /**
     * Fallback when segmentation is unavailable (no Play services, or the model is not
     * downloaded yet).
     *
     * Much weaker than the geometric path and honest about it: label strings alone cannot tell
     * a close-up from a wide shot, so this biases to offering the user the choice.
     */
    private fun labelOnly(labels: List<Pair<String, Float>>): Verdict {
        val lean = labelLean(labels, scenePrior = false)
        return when {
            lean > 0.30f -> Verdict(
                ShotKind.SCENE,
                confidence = lean.coerceIn(0f, 1f),
                reason = "The scene reads like a place or an activity.",
                stats = null,
            )

            lean < -0.30f -> Verdict(
                ShotKind.OBJECT,
                confidence = (-lean).coerceIn(0f, 1f),
                reason = "The photo names a specific thing.",
                stats = null,
            )

            else -> Verdict(
                ShotKind.UNCLEAR,
                confidence = 0.25f,
                reason = "Segmentation is unavailable, so this could not be judged automatically.",
                stats = null,
            )
        }
    }

    // ── individual signals ───────────────────────────────────────────────────

    /** Positive evidence for "one subject". */
    private fun objectScore(s: MaskStats): Float {
        if (s.coverage <= EPSILON) return 0f
        if (s.coverage < TINY) return -0.6f // too small to be the point of the photo

        // Ramp in over [TINY]..[COMFORTABLE], then decay towards [FILLING] (handled earlier,
        // but kept continuous so the score never jumps if the band is retuned).
        val sizeVote = when {
            s.coverage < COMFORTABLE ->
                (s.coverage - TINY) / (COMFORTABLE - TINY) * 0.6f - 0.3f

            else -> 0.3f * (1f - (s.coverage - COMFORTABLE) / (FILLING - COMFORTABLE))
        }

        val cohesion = ((s.dominantRatio - DOMINANT_MIN) / (1f - DOMINANT_MIN)).coerceIn(0f, 1f) * 0.4f
        val edgePenalty = if (s.touchesAllEdges) -0.25f else 0f
        return sizeVote + cohesion + edgePenalty
    }

    /** Positive evidence for "a situation". */
    private fun sceneScore(s: MaskStats): Float {
        if (s.coverage <= EPSILON) return 0.7f // nothing dominant at all
        if (s.coverage < TINY) return 0.7f // distant subject: the environment is the point

        val scattered = (1f - s.dominantRatio).coerceIn(0f, 1f) * 0.5f
        val manyParts = ((s.components - 1).coerceAtLeast(0) / 4f).coerceAtMost(1f) * 0.3f
        val edgeBonus = if (s.touchesAllEdges) 0.2f else 0f
        return scattered + manyParts + edgeBonus
    }

    /** Logistic squash. The slope puts a typical clear case (|evidence| ~ 0.4) near 0.62. */
    private fun logistic(evidence: Float): Float =
        1f / (1f + kotlin.math.exp(-evidence * SLOPE))

    private const val SLOPE = 1.703f

    /**
     * How much the labeller's own words lean towards a scene.
     *
     * Positive means "sounds like a place or activity", negative means "names a specific
     * thing". Applied as a swing in both directions rather than as its own class, because the
     * labels are a corroborating signal, not the deciding one.
     *
     * The average is over the labels actually seen, not over [TOP_LABELS]. Dividing by the cap
     * made a single confident "Beach" score 0.19 and fall below the abstain threshold, which is
     * exactly backwards.
     */
    private fun labelLean(labels: List<Pair<String, Float>>, scenePrior: Boolean): Float {
        val considered = labels.take(TOP_LABELS)
        if (considered.isEmpty()) return if (scenePrior) SCENE_PRIOR else 0f

        var total = 0f
        for ((text, score) in considered) {
            val t = text.lowercase()
            val weight = score.coerceIn(0f, 1f)
            total += when {
                PLACE_WORDS.any { t.contains(it) } -> weight
                ACTIVITY_WORDS.any { t.contains(it) } -> weight * 0.8f
                ABSTRACT_HINT_WORDS.any { t.contains(it) } -> weight * 0.5f
                // A specific noun with no place signal in it is weak evidence the other way.
                // Without this branch a label set like [("Bicycle", 0.9)] produces exactly zero
                // lean and the shot abstains, even though naming a bicycle is the clearest
                // possible statement that the photo is of one object.
                else -> -weight * SPECIFIC_NOUN_LEAN
            }
        }
        val mean = (total / considered.size).coerceIn(-1f, 1f)
        return mean + if (scenePrior) SCENE_PRIOR else 0f
    }

    /** How far a plain specific-noun label pushes towards "one object". */
    private const val SPECIFIC_NOUN_LEAN = 0.7f

    /** Bonus when some other component has already decided the frame reads as a place. */
    private const val SCENE_PRIOR = 0.2f

    private fun explain(s: MaskStats, labelLean: Float): String = when {
        s.coverage >= FILLING -> "The frame is filled edge to edge, so there is no scene to read."
        s.coverage < TINY -> "The subject is only ${pct(s.coverage)} of the photo, so the surroundings matter more."
        s.dominantRatio < DOMINANT_MIN -> "The foreground is scattered across ${s.components} separate parts."
        s.components >= 4 -> "There are ${s.components} separate things in this photo."
        labelLean > 0.2f -> "One object filling ${pct(s.coverage)} of the frame, in a recognisable place."
        else -> "One object filling ${pct(s.coverage)} of the frame."
    }

    private fun pct(v: Float): String = "${(v * 100).toInt()}%"

    // ── label vocabulary ─────────────────────────────────────────────────────

    private const val TOP_LABELS = 5

    /** Below this total, neither score is trusted. */
    private const val WEAK = 0.15f

    /** Below this gap between the two scores, the shot is genuinely ambiguous. */
    private const val AMBIGUOUS_MARGIN = 0.20f

    /**
     * Words in a labeller label that indicate a place. ML Kit's base model has a handful of
     * "Home good"-style labels; scenes come through far more often as places than as objects
     * because an object photo rarely has a legible background.
     */
    private val PLACE_WORDS = listOf(
        "room", "kitchen", "bedroom", "office", "classroom", "restaurant", "cafe", "shop",
        "store", "market", "supermarket", "station", "airport", "park", "beach", "street",
        "city", "village", "home", "house", "apartment", "hotel", "museum", "library",
        "hospital", "church", "temple", "bridge", "harbour", "harbor", "farm", "garden",
        "playground", "gym", "pool", "mountain", "forest", "desert", "sky", "landscape",
        "indoor", "outdoor", "interior", "corridor", "hall", "hallway", "balcony", "cellar",
        "camp", "court", "field", "stadium", "theater", "theatre", "cinema", "mall",
    )

    /** Words indicating an activity, which reads as a situation rather than a thing. */
    private val ACTIVITY_WORDS = listOf(
        "cooking", "eating", "drinking", "reading", "writing", "working", "studying",
        "playing", "running", "walking", "sleeping", "travelling", "traveling", "shopping",
        "meeting", "party", "sport", "exercise", "training", "waiting", "commuting",
        "camping", "fishing", "swimming", "cycling", "hiking", "cleaning", "cooking",
    )

    /**
     * Labels that describe a *relationship* or an abstraction rather than a thing. Seeing one
     * of these means the labeller is looking at the whole situation.
     */
    private val ABSTRACT_HINT_WORDS = listOf(
        "love", "space", "nature", "team", "sport", "travel", "food", "clothing", "vehicle",
        "plant", "animal", "people", "baby", "family", "friendship", "happiness", "fun",
    )

    // ── mask analysis ────────────────────────────────────────────────────────

    /**
     * Measure the foreground geometry from a confidence mask.
     *
     * @param mask confidence values in row-major order, one per pixel, 0..1.
     * @param width mask width in pixels.
     * @param height mask height in pixels.
     * @param threshold confidence at or above which a pixel counts as foreground.
     *
     * Intended to run on a *downscaled* mask (the caller resizes to ~160px on the long edge):
     * connected-component labelling is O(pixels) but on a 12MP frame it would stutter the
     * preview, and the geometry this measures does not change with resolution.
     */
    fun analyseMask(mask: FloatArray, width: Int, height: Int, threshold: Float = 0.5f): MaskStats {
        val total = width * height
        if (total <= 0) return MaskStats.UNKNOWN

        val foreground = BooleanArray(total)
        var foregroundCount = 0
        for (i in 0 until total) {
            if (mask[i] >= threshold) {
                foreground[i] = true
                foregroundCount++
            }
        }
        if (foregroundCount == 0) return MaskStats(0f, 0f, 0, 0)
        val coverage = foregroundCount.toFloat() / total

        // Iterative flood fill with an explicit stack: recursion over a 25k-pixel mask would be
        // a stack-overflow risk on the main thread. `seen` is set at push time, so each pixel is
        // pushed at most once and a `total`-sized stack can never overflow.
        val seen = BooleanArray(total)
        val stack = IntArray(total)
        val minArea = (total * COMPONENT_MIN_AREA).toInt().coerceAtLeast(1)

        var bestSize = 0
        var bestEdgeTouch = 0
        var components = 0

        for (seed in 0 until total) {
            if (!foreground[seed] || seen[seed]) continue

            var top = 0
            stack[top++] = seed
            seen[seed] = true
            var size = 0
            var edge = 0

            while (top > 0) {
                val p = stack[--top]
                size++
                val x = p % width
                val y = p / width
                if (y == 0) edge = edge or (1 shl EDGE_TOP)
                if (y == height - 1) edge = edge or (1 shl EDGE_BOTTOM)
                if (x == 0) edge = edge or (1 shl EDGE_LEFT)
                if (x == width - 1) edge = edge or (1 shl EDGE_RIGHT)

                if (x > 0) {
                    val n = p - 1
                    if (foreground[n] && !seen[n]) { seen[n] = true; stack[top++] = n }
                }
                if (x < width - 1) {
                    val n = p + 1
                    if (foreground[n] && !seen[n]) { seen[n] = true; stack[top++] = n }
                }
                if (y > 0) {
                    val n = p - width
                    if (foreground[n] && !seen[n]) { seen[n] = true; stack[top++] = n }
                }
                if (y < height - 1) {
                    val n = p + width
                    if (foreground[n] && !seen[n]) { seen[n] = true; stack[top++] = n }
                }
            }

            if (size >= minArea) components++
            if (size > bestSize) {
                bestSize = size
                bestEdgeTouch = edge
            }
        }

        return MaskStats(
            coverage = coverage,
            dominantRatio = (bestSize.toFloat() / foregroundCount).coerceIn(0f, 1f),
            components = components,
            edgeTouch = bestEdgeTouch,
        )
    }
}
