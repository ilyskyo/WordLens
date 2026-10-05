// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.srs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Guards the FSRS-6 port against the two classes of bug that are invisible in normal use:
 * a wrong sign in `FACTOR`/`DECAY`, and a dropped term in a rarely-taken branch.
 *
 * The assertions here are *definitional invariants* rather than golden vectors transcribed
 * from another runtime, so they stay meaningful even if the upstream implementation changes:
 * the whole design of FSRS rests on `R(S,S) = 0.9` and `I(0.9,S) = S`.
 */
class FsrsTest {

    private val day = 24L * 60 * 60 * 1000

    /**
     * Anchored to *local* noon so that `t0 - 2h` is guaranteed to stay on the same calendar
     * day in whatever timezone the build machine is in. The engine's same-day branch compares
     * `LocalDate`s, so a UTC-based constant would make these tests fail east of UTC+11.
     */
    private val t0: Long = java.time.LocalDate.now()
        .atTime(12, 0)
        .atZone(java.time.ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    @Test
    fun `default weights match the published FSRS-6 vector`() {
        assertEquals(21, Fsrs.DEFAULT_PARAMS.size)
        assertEquals(
            listOf(
                0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001,
                1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014,
                1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
            ),
            Fsrs.DEFAULT_PARAMS,
        )
    }

    /**
     * The whole point of defining `FACTOR = 0.9^(1/DECAY) - 1` is that a card reviewed exactly
     * one stability-interval later sits at 90% retrievability. Getting the exponent sign wrong
     * still produces a plausible-looking curve, so pin it down.
     */
    @Test
    fun `retrievability at one stability interval is exactly ninety percent`() {
        for (s in listOf(0.5, 1.0, 3.0, 21.0, 180.0, 3650.0)) {
            val state = Fsrs.State(stability = s, lastReview = t0, reviewCount = 1)
            val r = Fsrs.retention(state, now = t0 + (s * day).toLong())
            assertEquals("S=$s", 0.9, r, 1e-9)
        }
    }

    /**
     * The mirror image: at the default retention the scheduled interval equals S.
     *
     * The trick is making stability survive the review unchanged, which it does when
     * retrievability is exactly 1 (the `e^((1-R)*w10) - 1` growth term collapses to zero).
     * `due == lastReview` also keeps us off the same-day branch, which would substitute the
     * short-term formula.
     */
    @Test
    fun `interval at ninety percent retention equals stability`() {
        for (s in listOf(1.0, 2.5, 8.0, 45.0, 300.0)) {
            val state = Fsrs.State(stability = s, due = t0, lastReview = t0, reviewCount = 1)
            val good = Fsrs.previewIntervals(state, now = t0)[Fsrs.Rating.GOOD]!!
            assertEquals("S=$s", Math.round(s).toInt(), good)
        }
    }

    /** Raising the target retention must never lengthen the interval. */
    @Test
    fun `higher retention shortens intervals`() {
        val state = Fsrs.State(stability = 30.0, lastReview = t0, reviewCount = 4)
        Fsrs.configure(Fsrs.DEFAULT_PARAMS, 0.9)
        val relaxed = Fsrs.previewIntervals(state, t0 + 30 * day)[Fsrs.Rating.GOOD]!!
        Fsrs.configure(Fsrs.DEFAULT_PARAMS, 0.97)
        val strict = Fsrs.previewIntervals(state, t0 + 30 * day)[Fsrs.Rating.GOOD]!!
        Fsrs.configure(Fsrs.DEFAULT_PARAMS, Fsrs.DEFAULT_REQUEST_RETENTION)

        assertTrue("retention 0.97 ($strict) should not exceed retention 0.90 ($relaxed)", strict <= relaxed)
    }

    /**
     * Regression for the dropped `sinc` floor.
     *
     * Same-day re-show of a card that already has strong stability: `s^-w19` can outweigh the
     * `e^(w17*(G-3+w18))` growth, so an unclamped Good would *reduce* S. The reference
     * implementation clamps `sinc` to 1 for every grade >= GOOD.
     */
    @Test
    fun `same-day good never lowers stability`() {
        // due is far in the future and lastReview is earlier today, so `evolve` takes the
        // short-term branch: same calendar day AND not yet due.
        for (s in listOf(10.0, 50.0, 200.0, 1000.0)) {
            val state = Fsrs.State(
                stability = s,
                difficulty = 5.0,
                due = t0 + 30 * day,
                lastReview = t0 - 2 * 60 * 60 * 1000, // two hours ago, same calendar day as t0
                reviewCount = 6,
            )
            val out = Fsrs.review(state, Fsrs.Rating.GOOD, now = t0)
            assertTrue("S=$s -> ${out.stability}", out.stability >= s)
        }
    }

    /** The floor must not be so aggressive that a same-day Easy stops helping entirely. */
    @Test
    fun `same-day easy still grows stability`() {
        val state = Fsrs.State(
            stability = 10.0,
            difficulty = 5.0,
            due = t0 + 30 * day,
            lastReview = t0 - 2 * 60 * 60 * 1000,
            reviewCount = 6,
        )
        val out = Fsrs.review(state, Fsrs.Rating.EASY, now = t0)
        assertTrue("${out.stability}", out.stability > 10.0)
    }

    /** Difficulty must stay inside the DSR model's domain no matter how the card is abused. */
    @Test
    fun `difficulty stays within one and ten`() {
        var state = Fsrs.State()
        val ratings = listOf(
            Fsrs.Rating.EASY, Fsrs.Rating.EASY, Fsrs.Rating.EASY, Fsrs.Rating.EASY,
            Fsrs.Rating.AGAIN, Fsrs.Rating.AGAIN, Fsrs.Rating.AGAIN,
            Fsrs.Rating.GOOD, Fsrs.Rating.HARD,
        )
        var now = t0
        for (r in ratings) {
            // Advance a day each time so we leave the same-day branch.
            now += day
            state = Fsrs.review(state, r, now)
            assertTrue("D=${state.difficulty}", state.difficulty in 1.0..10.0)
            assertTrue("S=${state.stability}", state.stability > 0.0)
        }
    }

    /** Intervals must be strictly ordered by grade, otherwise the four buttons are a lie. */
    @Test
    fun `previewed intervals are ordered again then hard then good then easy`() {
        val state = Fsrs.State(
            stability = 25.0,
            difficulty = 5.0,
            due = t0 + 25 * day,
            lastReview = t0,
            reviewCount = 5,
        )
        val p = Fsrs.previewIntervals(state, t0 + 25 * day)
        val again = p[Fsrs.Rating.AGAIN]!!
        val hard = p[Fsrs.Rating.HARD]!!
        val good = p[Fsrs.Rating.GOOD]!!
        val easy = p[Fsrs.Rating.EASY]!!
        assertTrue("$again < $hard < $good < $easy", again < hard && hard < good && good < easy)
    }

    /** A lapse must not leave the card more stable than it was. */
    @Test
    fun `lapsing reduces stability and counts a lapse`() {
        val state = Fsrs.State(
            stability = 100.0,
            difficulty = 5.0,
            due = t0,
            lastReview = t0 - 100 * day,
            reviewCount = 5,
            lapses = 1,
        )
        val out = Fsrs.review(state, Fsrs.Rating.AGAIN, t0)
        assertTrue("${out.stability}", out.stability < state.stability)
        assertEquals(2, out.lapses)
        assertEquals(6, out.reviewCount)
    }

    /** Difficulty must rise when the learner says Again and fall when they say Easy. */
    @Test
    fun `difficulty moves in the expected direction`() {
        val base = Fsrs.State(
            stability = 20.0,
            difficulty = 5.0,
            due = t0,
            lastReview = t0 - 20 * day,
            reviewCount = 5,
        )
        val again = Fsrs.review(base, Fsrs.Rating.AGAIN, t0)
        val easy = Fsrs.review(base, Fsrs.Rating.EASY, t0)
        assertTrue("AGAIN D=${again.difficulty}", again.difficulty > base.difficulty)
        assertTrue("EASY D=${easy.difficulty}", easy.difficulty < base.difficulty)
    }

    /** New cards are due immediately and report no retention (nothing learned yet). */
    @Test
    fun `a fresh state is new, never due and has zero retention`() {
        val s = Fsrs.State()
        assertTrue(s.reviewCount == 0)
        assertEquals(Int.MAX_VALUE, Fsrs.daysUntilDue(s, t0))
        assertEquals(0.0, Fsrs.retention(s, t0), 1e-12)
    }

    /** Rounding to two decimals must not accumulate visible drift across a long schedule. */
    @Test
    fun `state stays on the two decimal grid after many reviews`() {
        var state = Fsrs.State()
        var now = t0
        repeat(40) { i ->
            now += 3 * day
            state = Fsrs.review(state, Fsrs.Rating.entries[i % 4], now)
            assertEquals("iteration $i D", state.difficulty, round2(state.difficulty), 1e-9)
            assertEquals("iteration $i S", state.stability, round2(state.stability), 1e-9)
        }
    }

    private fun round2(v: Double) = kotlin.math.round(v * 100.0) / 100.0
}
