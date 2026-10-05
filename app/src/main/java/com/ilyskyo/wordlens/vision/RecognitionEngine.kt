// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision

import android.graphics.Bitmap
import com.ilyskyo.wordlens.data.model.LexiconMatch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Which backend produced a result. Shown in the UI so guesses can be weighted by trust. */
enum class RecognitionEngineId(val displayName: String) {
    ON_DEVICE("On-device"),
    CLOUD("Cloud vision"),
    MANUAL("Typed by you"),
}

/**
 * One raw string the model saw, with its own confidence.
 *
 * Kept separate from [LexiconMatch] on purpose: showing the user *both* "the model said
 * 'Coffee cup' at 0.71" and "so we picked `cup`" is what makes an on-device guess reviewable
 * instead of magic. A vocabulary app that silently invents words teaches the wrong lesson.
 */
data class RawLabel(val text: String, val score: Float)

/** A lexicon entry that matched, with enough context to explain the choice. */
data class Suggestion(
    val match: LexiconMatch,
    /** Every plausible entry, best first — the user picks instead of us guessing. */
    val alternatives: List<LexiconMatch>,
    val engine: RecognitionEngineId,
    val rawLabels: List<RawLabel> = emptyList(),
) {
    val confidence: Float get() = match.confidence
    /** True when two candidates are close enough that picking for the user would be rude. */
    val isAmbiguous: Boolean
        get() = alternatives.size > 1 &&
            (alternatives[0].confidence - alternatives[1].confidence) < AMBIGUITY_MARGIN

    private companion object {
        /** Confidence gap below which we show a chooser instead of committing. */
        const val AMBIGUITY_MARGIN = 0.08f
    }
}

/**
 * The result of looking at a photo.
 *
 * [NoMatch] is a first-class outcome, not an error. On-device models cover a few hundred
 * everyday concepts; the moment the photo shows something outside that, the honest answer is
 * "I don't know this one" plus the option to type it in. Returning a confident wrong answer
 * would be worse than admitting ignorance, especially for a learning tool.
 */
sealed interface RecognitionOutcome {
    data class Found(
        val suggestions: List<Suggestion>,
        val rawLabels: List<RawLabel>,
        val engine: RecognitionEngineId,
    ) : RecognitionOutcome

    /** The model produced labels but none of them are in the lexicon. */
    data class NoMatch(
        val rawLabels: List<RawLabel>,
        val engine: RecognitionEngineId,
    ) : RecognitionOutcome

    /** The engine could not run at all (no network, missing model, bad key). */
    data class Failed(val reason: String, val cause: Throwable? = null) : RecognitionOutcome
}

/**
 * Something that can look at a bitmap and say what it might be.
 *
 * Implementations must be safe to call from a background dispatcher and must never throw for an
 * ordinary "I could not do it" condition — return [RecognitionOutcome.Failed] instead.
 */
interface RecognitionEngine {
    val id: RecognitionEngineId

    /** True when the engine needs a working internet connection. */
    val requiresNetwork: Boolean

    /** Human-readable availability, e.g. "not downloaded yet" or "no API key set". */
    fun unavailableReason(): String? = null

    suspend fun label(bitmap: Bitmap): List<RawLabel>

    fun close() = Unit
}

/**
 * Bridges a Play Services [com.google.android.gms.tasks.Task] to a coroutine.
 *
 * Written by hand rather than pulled from `kotlinx-coroutines-play-services` for one less
 * dependency in a project whose whole pitch is that it builds anywhere.
 */
suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
        addOnFailureListener { error -> if (cont.isActive) cont.resumeWithException(error) }
        addOnCanceledListener { cont.cancel() }
    }
