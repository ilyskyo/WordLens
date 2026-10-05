// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision

import com.ilyskyo.wordlens.data.model.LexiconEntry
import com.ilyskyo.wordlens.data.model.LexiconIndex
import com.ilyskyo.wordlens.data.model.LexiconMatch
import com.ilyskyo.wordlens.data.model.SceneGuess
import com.ilyskyo.wordlens.data.model.SceneKind
import com.ilyskyo.wordlens.data.model.SceneTaxonomy
import com.ilyskyo.wordlens.data.model.SceneTaxonomyAccept

/**
 * Picks which scene taxonomy entry a photo belongs to, and turns that entry into words.
 *
 * ## Why a hand-written taxonomy rather than a classifier
 *
 * The obvious approach is to ask a model for the scene label. But a label alone is not enough:
 * the app needs the *words that belong to the scene*, and no vision model emits "schedule" or
 * "quality" because those are not visible. That vocabulary is editorial, it is the same for
 * everyone, and it belongs in a JSON file people can read and extend — which is also the most
 * attractive contribution surface this project has.
 *
 * ## How matching works
 *
 * Longest-alias-wins with a specificity weight, because "Home good" should count for `home` and
 * not for `house`, and "Kitchen" should beat a generic `room` alias that happens to be a
 * substring. Normalisation is shared with the lexicon so a label and a headword are reduced the
 * same way.
 *
 * Pure Kotlin, so the matching rules are unit-testable.
 */
class SceneClassifier(private val taxonomy: SceneTaxonomy) {

    /** Pre-tokenised aliases so the hot path does no string splitting. */
    private val aliases: List<Triple<SceneKind, String, Float>> = taxonomy.scenes.flatMap { scene ->
        val raw = buildList {
            // The scene's own id and its primary label are always aliases.
            add(scene.id)
            scene.label.values.forEach { add(it) }
            addAll(scene.labelAliases)
        }
        raw.asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .map { normalised ->
                // Longer aliases are more specific and should outweigh incidental substrings:
                // "home" matching inside "homestead" is noise, "classroom" is a real signal.
                val specificity = (normalised.length.coerceAtMost(12) / 12f).coerceAtLeast(0.35f)
                Triple(scene, normalised, specificity)
            }
            .toList()
    }

    val sceneCount: Int get() = taxonomy.scenes.size

    /**
     * Score every scene against the labeller's labels.
     *
     * @return the best guess, or a null [SceneKind] when nothing cleared [SceneTaxonomyAccept.FLOOR].
     */
    fun classify(labels: List<Pair<String, Float>>): SceneGuess {
        if (labels.isEmpty() || aliases.isEmpty()) return SceneGuess(null, 0f)

        val scores = HashMap<String, Float>(taxonomy.scenes.size)
        val evidence = HashMap<String, MutableList<String>>()

        for ((rawLabel, modelScore) in labels) {
            val label = LexiconIndex.normalize(rawLabel)
            if (label.isEmpty()) continue
            val weight = modelScore.coerceIn(0f, 1f)
            if (weight <= 0f) continue

            // Best alias hit for this label wins; do not let one label vote twice for a scene.
            val bestPerScene = HashMap<String, Pair<Float, String>>()
            for ((scene, alias, specificity) in aliases) {
                val hit = matchQuality(label, alias) ?: continue
                val contribution = hit * specificity * weight
                val current = bestPerScene[scene.id]
                if (current == null || contribution > current.first) {
                    bestPerScene[scene.id] = contribution to rawLabel
                }
            }

            for ((sceneId, pair) in bestPerScene) {
                val scene = taxonomy.byId(sceneId) ?: continue
                scores[sceneId] = (scores[sceneId] ?: 0f) + pair.first * scene.prior
                evidence.getOrPut(sceneId) { mutableListOf() } += pair.second
            }
        }

        if (scores.isEmpty()) return SceneGuess(null, 0f)

        val ranked = scores.entries.sortedByDescending { it.value }
        val best = ranked.first()
        val runnerUp = ranked.getOrNull(1)?.value ?: 0f

        val topScore = best.value
        val confidence = normalise(topScore, runnerUp)

        val scene = taxonomy.byId(best.key)
        if (confidence < SceneTaxonomyAccept.FLOOR || scene == null) {
            return SceneGuess(
                kind = null,
                confidence = confidence,
                evidence = evidence[best.key].orEmpty(),
            )
        }
        return SceneGuess(
            kind = scene,
            confidence = confidence,
            evidence = evidence[best.key].orEmpty(),
        )
    }

    /**
     * The words that belong to [kind], resolved against the shipped dictionary.
     *
     * Words the dictionary does not have are skipped rather than shown as bare strings: a word
     * with no IPA and no gloss is not learnable here, and offering it anyway would look like a
     * bug.
     *
     * [extra] carries anything the labeller actually saw that belongs to this scene, so the
     * user gets the specific thing in their photo ("their own mug") alongside the generic
     * vocabulary of the room.
     */
    fun wordsFor(kind: SceneKind?, lexicon: LexiconIndex, extra: List<LexiconMatch> = emptyList()): List<LexiconEntry> {
        val out = LinkedHashMap<String, LexiconEntry>()
        for (match in extra) out.putIfAbsent(match.entry.id, match.entry)

        val keys = kind?.words.orEmpty()
        for (key in keys) {
            val entry = lexicon.byId("en.$key") ?: lexicon.search(key, limit = 1).firstOrNull()
            if (entry != null) out.putIfAbsent(entry.id, entry)
        }
        return out.values.toList()
    }

    /**
     * Quality of `label` matching `alias`, or null when it does not match.
     *
     * Exact match beats whole-token containment, which beats a bare substring hit. Without the
     * token check, `home` would fire on "homestead" and `room` would fire on "bathroom" — both
     * wrong, and the second one is common enough to matter.
     */
    private fun matchQuality(label: String, alias: String): Float? = when {
        label == alias -> 1.0f
        label.contains(alias) && alias.length >= 3 -> {
            // Whole-token containment.
            if (containsWord(label, alias)) 0.85f else 0.45f
        }

        alias.contains(label) && label.length >= 4 -> {
            // The taxonomy is more specific than the label, e.g. label "Kitchen" vs alias
            // "kitchen sink". Partial credit only.
            0.4f
        }

        else -> null
    }

    private fun containsWord(haystack: String, needle: String): Boolean {
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            val beforeOk = index == 0 || haystack[index - 1] == ' '
            val end = index + needle.length
            val afterOk = end == haystack.length || haystack[end] == ' '
            if (beforeOk && afterOk) return true
            index = haystack.indexOf(needle, index + 1)
        }
        return false
    }

    /** Squash the raw score, discounted when a runner-up is close behind. */
    private fun normalise(top: Float, runnerUp: Float): Float {
        val base = 1f - 1f / (1f + top * 1.4f)
        val margin = if (top <= 0f) 0f else ((top - runnerUp) / top).coerceIn(0f, 1f)
        return (base * (0.65f + 0.35f * margin)).coerceIn(0f, 1f)
    }
}
