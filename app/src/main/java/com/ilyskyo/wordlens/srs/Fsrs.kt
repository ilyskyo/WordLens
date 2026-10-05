// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.srs

import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * FSRS-6 spaced repetition.
 *
 * Originally adapted from the Blancall engine (same author, MIT), then **verified line by line
 * against the upstream reference implementations**:
 *
 * - formulas and default weights: `open-spaced-repetition/awesome-fsrs/wiki/The-Algorithm`
 * - executable reference: `open-spaced-repetition/fsrs4anki` -> `fsrs4anki_scheduler.js`
 *
 * That cross-check confirmed the 21 default weights, the
 * difficulty / linear-damping / mean-reversion chain, the post-lapse ceiling
 * `min(S'_f, S/e^(w17*w18))`, and `FACTOR = 0.9^(1/DECAY) - 1` with `DECAY = -w20`. It also
 * caught **two defects the Blancall port had dropped**:
 *
 * 1. the same-day `sinc = max(sinc, 1)` floor for grades >= GOOD (see [nextShortTermStability] —
 *    without it, re-showing a strong card and answering *Good* silently *lowers* stability), and
 * 2. the two-decimal rounding the reference applies after every transition (see [round2]),
 *    without which long schedules drift by a day from Anki's FSRS4Anki.
 *
 * The invariants asserted in `FsrsTest` (`R(S,S) = 0.9`, `I(0.9,S) = S`) are definitional, so
 * they catch a sign error in `FACTOR` or `DECAY` without needing golden vectors from a
 * foreign runtime.
 *
 * ## Formulas (open-spaced-repetition/awesome-fsrs, "The Algorithm")
 * - initial stability: `S0(G) = w[G-1]`
 * - initial difficulty: `D0(G) = w4 - e^(w5*(G-1)) + 1`, clamped to [1, 10]
 * - interval: `I(r,S) = S/FACTOR * (r^(1/DECAY) - 1)`, `FACTOR = 0.9^(1/DECAY) - 1`, `DECAY = -w20`
 * - difficulty update: `dD = -w6*(G-3)`, linear damping `(10-D)/9`, mean reversion toward `D0(4)`
 * - recall stability: `S' = S*(1 + e^w8*(11-D)*S^(-w9)*(e^((1-r)*w10)-1)*hardPenalty*easyBonus)`
 * - post-lapse stability: `S' = min(w11*D^(-w12)*((S+1)^w13-1)*e^((1-r)*w14), S/e^(w17*w18))`
 * - same-day stability: `S' = S*e^(w17*(G-3+w18))*S^(-w19)`, floored at `sinc >= 1` for `G >= 3`
 *
 * ## References
 * - Ye, J., Su, J., & Cao, Y. (2022). *A Stochastic Shortest Path Algorithm for Optimizing
 *   Spaced Repetition Scheduling*. https://doi.org/10.1145/3534678.3539081
 * - https://github.com/open-spaced-repetition/awesome-fsrs/wiki/The-Algorithm
 * - https://github.com/open-spaced-repetition/fsrs4anki
 * - https://github.com/open-spaced-repetition/fsrs-rs
 */
object Fsrs {

    /** Review grades, matching the official `Rating` enum. */
    enum class Rating(val value: Int) {
        AGAIN(1), HARD(2), GOOD(3), EASY(4),
        ;

        companion object {
            fun fromValue(v: Int): Rating = entries.firstOrNull { it.value == v } ?: GOOD
        }
    }

    /**
     * Persisted per-card memory state. A card whose [reviewCount] is 0 is "new" and gets the
     * initial-stability path; the FSRS weight vector already encodes "how learnable is the
     * first exposure of a grade-1/2/3/4 word", so no extra per-deck tuning is needed.
     */
    @Serializable
    data class State(
        /** Difficulty D, 1-10. */
        val difficulty: Double = 0.0,
        /** Stability S, in days. */
        val stability: Double = 0.0,
        /** Epoch millis when the card next becomes due. */
        val due: Long = 0L,
        /** Epoch millis of the last review. */
        val lastReview: Long = 0L,
        val reviewCount: Int = 0,
        /** Number of times the card has been graded AGAIN. */
        val lapses: Int = 0,
    )

    /** FSRS-6 default weights (Anki's open-source defaults, w[0..20]). */
    val DEFAULT_PARAMS: List<Double> = listOf(
        0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001,
        1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014,
        1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
    )

    /** Anki's default target retention. */
    const val DEFAULT_REQUEST_RETENTION = 0.9

    /**
     * 保持率的可用区间。
     *
     * 下限 0.70：再低的话排期会拉出一两个月的间隔，复习队列看起来「清空了」，而用户其实正在忘。
     * 上限 0.97：Anki 自己也在这里收口——0.99 意味着几乎每天都要复习新牌，间隔普遍缩到 1 天，
     * 队列会在几天内堆到不可完成。滑块的两端就取这两个值。
     */
    const val MIN_REQUEST_RETENTION = 0.70
    const val MAX_REQUEST_RETENTION = 0.97

    private const val MIN_STABILITY = 0.1
    private const val MS_PER_DAY = 24L * 60 * 60 * 1000
    private const val MAX_INTERVAL_DAYS = 36500

    private val rng = Random(System.nanoTime())

    @Volatile
    private var params: List<Double> = DEFAULT_PARAMS

    @Volatile
    private var requestRetention: Double = DEFAULT_REQUEST_RETENTION

    private val decay: Double get() = -params[20]
    private val factor: Double get() = 0.9.pow(1.0 / decay) - 1

    /**
     * Swap in a custom weight vector (e.g. from a future FSRS optimizer run) and target
     * retention. Rejects malformed input rather than silently degrading the schedule.
     */
    fun configure(w: List<Double>, retention: Double) {
        if (w.size == 21 && w.all { it.isFinite() } && w[20] > 0) {
            params = w
            setRequestRetention(retention)
        }
    }

    /**
     * 只改目标保持率，保留当前权重向量。
     *
     * 这件事 [configure] 做不了：它要求同时传入 21 维权重，而我们能拿出的只有 [DEFAULT_PARAMS]。
     * 用户在设置页拖动保持率滑块并不是在重新调模型，只是想要不同的到期密度——走 configure
     * 会把未来任何优化器产出的权重向量悄悄抹掉。
     *
     * 越界输入是夹紧而不是拒绝：调用方是一个绑定 DataStore 的滑块，旧版本已经落盘的取值
     * 不该让排期从此不再响应。
     */
    fun setRequestRetention(retention: Double) {
        if (!retention.isFinite()) return
        requestRetention = retention.coerceIn(MIN_REQUEST_RETENTION, MAX_REQUEST_RETENTION)
    }

    /** 当前排期使用的目标保持率，供设置页回显。 */
    fun currentRequestRetention(): Double = requestRetention

    /** Probability the card is still recalled right now, 0..1. */
    fun retention(state: State, now: Long = System.currentTimeMillis()): Double {
        if (state.stability <= 0.0) return 0.0
        return forgettingCurve((now - state.lastReview).toDouble() / MS_PER_DAY, state.stability)
    }

    /** Negative = overdue, 0 = due today, positive = days remaining. */
    fun daysUntilDue(state: State, now: Long = System.currentTimeMillis()): Int {
        if (state.reviewCount == 0) return Int.MAX_VALUE
        return kotlin.math.ceil((state.due - now).toDouble() / MS_PER_DAY).toInt()
    }

    fun isDue(state: State, now: Long = System.currentTimeMillis()): Boolean =
        state.reviewCount > 0 && now >= state.due

    /**
     * The interval each grade *would* produce, without the ±5% fuzz.
     *
     * The review screen renders these directly on the four buttons ("2d", "6d", "3w"), which
     * is the single most useful thing a scheduler can tell a learner before they commit.
     */
    fun previewIntervals(
        state: State,
        now: Long = System.currentTimeMillis(),
    ): Map<Rating, Int> = Rating.entries.associateWith { rating ->
        intervalForStability(evolve(state, rating, now).second, fuzz = false)
    }

    /** Apply a review and return the next state. The caller persists it. */
    fun review(state: State, rating: Rating, now: Long = System.currentTimeMillis()): State {
        val (d, s) = evolve(state, rating, now)
        val days = intervalForStability(s, fuzz = true)
        return State(
            difficulty = d,
            stability = s,
            due = now + days * MS_PER_DAY,
            lastReview = now,
            reviewCount = state.reviewCount + 1,
            lapses = state.lapses + if (rating == Rating.AGAIN) 1 else 0,
        )
    }

    /** Shortest interval this configuration would ever schedule, used to warn about absurd retention settings. */
    fun minimumIntervalDays(): Int = intervalForStability(MIN_STABILITY, fuzz = false)

    /** Longest interval this configuration would ever schedule. */
    fun maximumIntervalDays(): Int =
        intervalForStability(params[3] * 100, fuzz = false).coerceAtMost(MAX_INTERVAL_DAYS)

    // ── internals ────────────────────────────────────────────────────────────

    /** Returns the post-review (difficulty, stability) pair for a grade. */
    private fun evolve(state: State, rating: Rating, now: Long): Pair<Double, Double> {
        val isNew = state.reviewCount == 0 || state.stability <= 0.0
        if (isNew) return initDifficulty(rating) to initStability(rating)

        val elapsedDays = (now - state.lastReview).toDouble() / MS_PER_DAY

        // Re-showing the same card on the same calendar day must not push the interval out;
        // FSRS-6 has a dedicated convergence term for this.
        val sameDay = now >= state.lastReview &&
            java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate() ==
                java.time.Instant.ofEpochMilli(state.lastReview)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        if (sameDay && now < state.due) {
            val converged = nextShortTermStability(state.stability, rating)
            return nextDifficulty(state.difficulty, rating) to converged
        }

        val retrievability = forgettingCurve(elapsedDays, state.stability)
        val nextStability = when (rating) {
            Rating.AGAIN -> nextForgetStability(state.difficulty, state.stability, retrievability)
            else -> nextRecallStability(state.difficulty, state.stability, retrievability, rating)
        }
        return nextDifficulty(state.difficulty, rating) to nextStability
    }

    private fun forgettingCurve(elapsedDays: Double, stability: Double): Double {
        // Guard the power: elapsed<=0 or S<=0 would otherwise produce NaN.
        if (elapsedDays <= 0.0) return 1.0
        if (stability <= 0.0) return 0.0
        return (1 + factor * elapsedDays / stability).pow(-params[20])
    }

    private fun initDifficulty(rating: Rating): Double =
        constrainDifficulty(params[4] - exp(params[5] * (rating.value - 1)) + 1)

    private fun initStability(rating: Rating): Double =
        round2(params[rating.value - 1]).coerceAtLeast(MIN_STABILITY)

    /**
     * Same-day ("short term") stability.
     *
     * The `sinc` clamp below is not cosmetic and is easy to lose in a port: the wiki states
     * "In practice, we should ensure that SInc >= 1 when G >= 2", and the reference
     * implementation enforces it for every grade >= GOOD. Without it, tapping *Good* on a
     * same-day re-show of an already-strong card *shrinks* stability — the `s^-w19` damping
     * term overtakes the `e^(w17*(G-3+w18))` growth term once S is large — which quietly
     * penalises the exact behaviour a learner is being told to reward.
     */
    private fun nextShortTermStability(s: Double, rating: Rating): Double {
        var sinc = exp(params[17] * (rating.value - 3 + params[18])) * s.pow(-params[19])
        if (rating.value >= Rating.GOOD.value) sinc = max(sinc, 1.0)
        return round2(s * sinc).coerceAtLeast(MIN_STABILITY)
    }

    private fun intervalForStability(stability: Double, fuzz: Boolean): Int {
        val raw = stability / factor * (requestRetention.pow(1.0 / decay) - 1)
        val value = if (fuzz) applyFuzz(raw) else raw
        return value.roundToInt().coerceIn(1, MAX_INTERVAL_DAYS)
    }

    /**
     * ±5% jitter, applied only once the interval is long enough that identical intervals
     * would pile every card up on the same future day.
     */
    private fun applyFuzz(interval: Double): Double {
        if (interval < 2.5) return interval
        val ivl = interval.roundToInt()
        val minIvl = max(2.0, (ivl * 0.95 - 1).roundToInt().toDouble())
        val maxIvl = (ivl * 1.05 + 1).roundToInt().toDouble()
        return floor(rng.nextDouble() * (maxIvl - minIvl + 1) + minIvl)
    }

    private fun nextDifficulty(currentD: Double, rating: Rating): Double {
        val deltaD = -params[6] * (rating.value - 3)
        val damped = deltaD * (10 - currentD) / 9
        val nextD = currentD + damped
        val reverted = params[7] * initDifficulty(Rating.EASY) + (1 - params[7]) * nextD
        return constrainDifficulty(reverted)
    }

    private fun nextRecallStability(d: Double, s: Double, r: Double, rating: Rating): Double {
        val hardPenalty = if (rating == Rating.HARD) params[15] else 1.0
        val easyBonus = if (rating == Rating.EASY) params[16] else 1.0
        val growth = exp(params[8]) * (11 - d) * s.pow(-params[9]) *
            (exp((1 - r) * params[10]) - 1) * hardPenalty * easyBonus
        return round2(s * (1 + growth)).coerceAtLeast(MIN_STABILITY)
    }

    private fun nextForgetStability(d: Double, s: Double, r: Double): Double {
        val ceiling = s / exp(params[17] * params[18])
        val result = params[11] * d.pow(-params[12]) * ((s + 1).pow(params[13]) - 1) *
            exp((1 - r) * params[14])
        return round2(min(result, ceiling)).coerceAtLeast(MIN_STABILITY)
    }

    /**
     * The reference implementation rounds D and S to two decimals after every transition
     * (`(+x).toFixed(2)`). Keeping that rounding means our intervals agree with Anki's
     * FSRS4Anki card-for-card instead of drifting by a day on long schedules.
     */
    private fun round2(v: Double): Double = kotlin.math.round(v * 100.0) / 100.0

    private fun constrainDifficulty(d: Double): Double = round2(d).coerceIn(1.0, 10.0)
}
