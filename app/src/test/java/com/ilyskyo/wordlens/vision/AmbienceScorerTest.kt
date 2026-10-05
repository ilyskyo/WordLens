// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision

import com.ilyskyo.wordlens.data.model.AmbienceCue
import com.ilyskyo.wordlens.data.model.AmbienceWord
import org.junit.Assert.assertEquals
import org.junit.Test

/** 氛围词排序：区间适配、硬淘汰与保底顺序。 */
class AmbienceScorerTest {

    private fun word(id: String, cue: AmbienceCue?) = AmbienceWord(id, mapOf("en" to id), cue = cue)

    @Test
    fun `words failing a hard bound are dropped`() {
        val warm = word("warm", AmbienceCue(minWarmth = 0.1f))
        val ranked = AmbienceScorer.rank(listOf(warm), brightness = 0.5f, warmth = -0.5f, objectCount = 2)
        assertEquals(emptyList<String>(), ranked.map { it.id })
    }

    @Test
    fun `deeper fit ranks ahead of marginal fit`() {
        // 排序衡量的是**离区间边界的余量**：同一阈值下，刚好过线的排不进，明显深入的排得进。
        val two = listOf(
            word("edge", AmbienceCue(minBrightness = 0.1f, maxBrightness = 0.9f)),
            word("open", AmbienceCue(minBrightness = 0.1f)),
        )
        val ordered = AmbienceScorer.rank(two, brightness = 0.12f, warmth = 0f, objectCount = 0)
        assertEquals(listOf("edge", "open"), ordered.map { it.id })
        // 贴线但仍在区间内：不被淘汰。
        val single = listOf(word("marginal", AmbienceCue(minWarmth = 0.1f, maxWarmth = 1f)))
        assertEquals(
            listOf("marginal"),
            AmbienceScorer.rank(single, brightness = 0.5f, warmth = 0.15f, objectCount = 0).map { it.id },
        )
    }

    @Test
    fun `cueless words are a stable tail`() {
        val always = word("quiet", null)
        val dim = word("dim", AmbienceCue(maxBrightness = 0.4f))
        val ranked = AmbienceScorer.rank(listOf(always, dim), brightness = 0.1f, warmth = 0f, objectCount = 0)
        assertEquals(listOf("dim", "quiet"), ranked.map { it.id })
        // 什么都不适配时保底词仍在。
        val none = AmbienceScorer.rank(listOf(always, dim), brightness = 0.9f, warmth = 0f, objectCount = 0)
        assertEquals(listOf("quiet"), none.map { it.id })
    }

    @Test
    fun `object count centred range ranks best in the middle`() {
        val busy = word("crowded", AmbienceCue(minObjects = 2, maxObjects = 8))
        val at4 = AmbienceScorer.rank(listOf(busy), 0.5f, 0f, objectCount = 4)
        val at2 = AmbienceScorer.rank(listOf(busy), 0.5f, 0f, objectCount = 2)
        assertEquals(1, at4.size)
        assertEquals(1, at2.size)
        assertEquals(0, AmbienceScorer.rank(listOf(busy), 0.5f, 0f, objectCount = 1).size)
        assertEquals(0, AmbienceScorer.rank(listOf(busy), 0.5f, 0f, objectCount = 9).size)
    }
}
