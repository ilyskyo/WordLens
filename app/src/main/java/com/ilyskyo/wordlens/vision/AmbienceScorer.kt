// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision

import com.ilyskyo.wordlens.data.model.AmbienceCue
import com.ilyskyo.wordlens.data.model.AmbienceWord

/**
 * 用取景阶段的廉价信号给氛围词排序。
 *
 * 氛围词不是检测出来的——没有任何模型会输出「温馨」。但**拍摄环境**是可测的：亮度、
 * 冷暖、画面里物体的数量。[AmbienceCue] 把这些信号写成区间，这里做区间适配度评分。
 *
 * 纯 Kotlin、无 Android 依赖：排序规则会反复调（哪条氛围词该先出现是产品品味问题），
 * 能在 JVM 上毫秒级验证是它值得单独成文的原因。
 */
object AmbienceScorer {

    /**
     * @param brightness 平均亮度 0..1。
     * @param warmth 冷暖倾向 -1..1，正值偏暖。
     * @param objectCount 本帧检测到的物体数。
     * @return 适配的 [words]，适配度高的在前；cue 为 null 的词永远适配、排在最后
     *   （它们是「任何时候都说得通」的保底）。
     */
    fun rank(
        words: List<AmbienceWord>,
        brightness: Float,
        warmth: Float,
        objectCount: Int,
    ): List<AmbienceWord> = words
        .mapNotNull { word -> word.cue?.let { word to fit(it, brightness, warmth, objectCount) } }
        .filter { it.second > 0f }
        .sortedByDescending { it.second }
        .map { it.first }
        // 无 cue 的保底词保持原顺序接在后面。
        .plus(words.filter { it.cue == null })

    /**
     * 适配度 0..1；<=0 表示至少有一条硬性区间不满足。
     *
     * 每条约束给 1 分，但按「离边界的距离」打折：贴着阈值只算 0.4，深入区间一半算 1.0。
     * 这让「明显是暖光」的画面把 warm 排到「凑合算暖」的画面前面，而不是两者并列。
     */
    private fun fit(cue: AmbienceCue, brightness: Float, warmth: Float, objects: Int): Float {
        var score = 0f
        var constraints = 0

        fun margin(value: Float, min: Float?, max: Float?): Float? {
            if (min == null && max == null) return null
            val lo = min ?: Float.MIN_VALUE
            val hi = max ?: Float.MAX_VALUE
            if (value < lo || value > hi) return -1f
            val room = (hi - lo).takeIf { it.isFinite() && it > 0f } ?: return 0.6f
            val distance = minOf(value - lo, hi - value)
            return (0.4f + 0.6f * (distance / (room / 2f))).coerceAtMost(1f)
        }

        listOf(
            margin(brightness, cue.minBrightness, cue.maxBrightness),
            margin(warmth, cue.minWarmth, cue.maxWarmth),
            objectsMargin(objects, cue.minObjects, cue.maxObjects),
        ).forEach { m ->
            if (m != null) {
                constraints++
                if (m < 0f) return -1f
                score += m
            }
        }
        return if (constraints == 0) 1f else score / constraints
    }

    /** 物体数是整数，单独算：区间中点的距离按半宽归一。 */
    private fun objectsMargin(objects: Int, min: Int?, max: Int?): Float? {
        if (min == null && max == null) return null
        val lo = min ?: Int.MIN_VALUE
        val hi = max ?: Int.MAX_VALUE
        if (objects < lo || objects > hi) return -1f
        if (min != null && max != null && max > min) {
            val half = (max - min) / 2f
            val centre = min + half
            val distance = kotlin.math.abs(objects - centre)
            return (1f - distance / half).coerceIn(0.3f, 1f)
        }
        return 0.6f
    }
}
