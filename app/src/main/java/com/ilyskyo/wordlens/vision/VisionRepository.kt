// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.ilyskyo.wordlens.data.model.Lang
import com.ilyskyo.wordlens.data.model.LexiconEntry
import com.ilyskyo.wordlens.data.model.LexiconMatch
import com.ilyskyo.wordlens.data.model.SceneGuess
import com.ilyskyo.wordlens.data.model.SceneTaxonomy
import com.ilyskyo.wordlens.data.repository.LexiconRepository
import com.ilyskyo.wordlens.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * What the app concluded about one photo. Everything the UI needs, decided in one pass.
 */
data class ShotAnalysis(
    val verdict: ShotClassifier.Verdict,
    val labels: List<RawLabel>,
    val scene: SceneGuess,
    val outcome: RecognitionOutcome,
    /** Suggestions resolved for the shot's dominant mode. */
    val suggestions: List<Suggestion>,
)

/**
 * The one place that looks at a photo and decides what to do with it.
 *
 * ## The order of operations, and why
 *
 * 1. **Segment** the frame. Everything downstream depends on knowing where the subject is, and
 *    nothing else can be trusted without it.
 * 2. **Classify the shot** as object or scene ([ShotClassifier]). This has to happen *before*
 *    vocabulary is gathered because the two modes ask the dictionary different questions, and
 *    answering the wrong one produces a confidently irrelevant screen.
 * 3. **Label** the image ([RecognitionEngine]).
 * 4. **Classify the scene** from those labels, but only when the shot is a scene.
 * 5. **Resolve words**: the dictionary entries matching what the model saw, plus the scene's
 *    own vocabulary.
 *
 * Every step degrades rather than fails. No segmentation means the shot classifier abstains and
 * the UI offers both modes; no dictionary hit means "I don't know this one" plus a text field;
 * no scene match means the detected words are offered loose, without a scene wrapper.
 */
class VisionRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val lexiconRepository: LexiconRepository,
    private val settingsRepository: SettingsRepository,
) {
    private val onDevice = MlKitOnDeviceEngine()

    @Volatile
    private var cloud: CloudVisionEngine? = null

    /** Taxonomy as loaded from assets; empty until [loadTaxonomy] has run. */
    private val _taxonomy = MutableStateFlow(SceneTaxonomy())
    val taxonomy: StateFlow<SceneTaxonomy> = _taxonomy.asStateFlow()

    /** Segmentation backends, best first. */
    private val _segmenters = MutableStateFlow<List<SubjectSegmenter>>(emptyList())
    val segmenters: StateFlow<List<SubjectSegmenter>> = _segmenters.asStateFlow()

    fun loadTaxonomy() {
        val text = runCatching {
            context.assets.open("scenes/scenes.json").use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        if (text == null) {
            Log.w(TAG, "assets/scenes/scenes.json missing; scene mode will offer loose words only")
            return
        }
        runCatching {
            com.ilyskyo.wordlens.data.store.WordLensJson.instance
                .decodeFromString(SceneTaxonomy.serializer(), text)
        }.onSuccess {
            _taxonomy.value = it
            Log.i(TAG, "Loaded ${it.size} scene kinds")
        }.onFailure { Log.e(TAG, "Failed to parse scene taxonomy", it) }
    }

    /**
     * Prepare the segmentation backends.
     *
     * Order matters: the automatic ML Kit segmenter is preferred because it needs no tap, and
     * the MediaPipe tap-to-select fallback is always available so the object flow keeps working
     * on devices without Play services.
     */
    fun prepareSegmentation() {
        if (_segmenters.value.isNotEmpty()) return
        val list = buildList {
            SubjectSegmenter.available(context)?.let { add(it) }
            runCatching { MagicTouchSegmenter(context) }.getOrNull()?.let { add(it) }
        }
        _segmenters.value = list
        Log.i(TAG, "Segmentation backends: ${list.map { it::class.simpleName }}")
    }

    /** The engine that will actually run for the current settings. */
    private suspend fun engineFor(allowCloud: Boolean): RecognitionEngine? {
        if (allowCloud) {
            val settings = settingsRepository.settings.first()
            if (settings.cloudReady) {
                val existing = cloud
                if (existing != null && existing.matches(settings.cloudModel, settings.cloudApiKey)) {
                    return existing
                }
                val created = CloudVisionEngine(settings.cloudApiKey, settings.cloudModel)
                if (created.unavailableReason() == null) {
                    cloud = created
                    return created
                }
                Log.w(TAG, "Cloud vision unusable: ${created.unavailableReason()}")
            }
        }
        return onDevice
    }

    /** Look at [bitmap] and produce everything the UI needs to decide what to show. */
    suspend fun analyse(
        bitmap: Bitmap,
        stats: ShotClassifier.MaskStats?,
        tapPoint: Pair<Float, Float>? = null,
        preferCloud: Boolean = false,
    ): ShotAnalysis {
        val engine = engineFor(preferCloud)
        val labels: List<RawLabel> = runCatching {
            engine?.label(bitmap).orEmpty()
        }.getOrElse {
            Log.w(TAG, "labelling threw", it)
            emptyList()
        }

        val verdict = ShotClassifier.classify(stats, labels.map { it.text to it.score })

        val sceneClassifier = SceneClassifier(_taxonomy.value)
        val lexicon = lexiconRepository.index.value
        val matches = lexicon.match(labels.map { it.text to it.score })

        // A scene is only worth classifying when the shot actually reads as one. Doing it for an
        // object shot would attach "kitchen" vocabulary to a photo of a single mug.
        val scene = if (verdict.kind == com.ilyskyo.wordlens.data.model.ShotKind.SCENE ||
            verdict.kind == com.ilyskyo.wordlens.data.model.ShotKind.UNCLEAR
        ) {
            sceneClassifier.classify(labels.map { it.text to it.score })
        } else {
            SceneGuess(null, 0f)
        }

        val engineId = engine?.id ?: RecognitionEngineId.ON_DEVICE
        val outcome = when {
            matches.isNotEmpty() -> RecognitionOutcome.Found(
                suggestions = matches.map { Suggestion(it, matches, engineId, labels) },
                rawLabels = labels,
                engine = engineId,
            )

            else -> RecognitionOutcome.NoMatch(labels, engineId)
        }

        return ShotAnalysis(
            verdict = verdict,
            labels = labels,
            scene = scene,
            outcome = outcome,
            suggestions = (outcome as? RecognitionOutcome.Found)?.suggestions.orEmpty(),
        )
    }

    /** Words to offer for a scene: the taxonomy's vocabulary plus what was actually detected. */
    suspend fun sceneWords(scene: SceneGuess, detected: List<LexiconMatch>): List<LexiconEntry> =
        SceneClassifier(_taxonomy.value).wordsFor(scene.kind, lexiconRepository.index.value, detected)

    fun lexiconMatchCount(): Int = lexiconRepository.index.value.size

    fun close() {
        onDevice.close()
        cloud?.close()
        cloud = null
        _segmenters.value.forEach { runCatching { it.close() } }
    }

    private companion object {
        const val TAG = "VisionRepo"
    }
}
