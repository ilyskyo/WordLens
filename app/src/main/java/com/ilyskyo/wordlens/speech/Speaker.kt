// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.speech

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.ilyskyo.wordlens.data.model.Lang
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Text to speech, wrapped so the rest of the app never touches `TextToSpeech` directly.
 *
 * ## Why the system engine rather than a bundled neural voice
 *
 * CapWords uses Neural TTS and it is the right call for them: iOS ships it. Android has no
 * equivalent guarantee — quality is entirely at the mercy of whichever engine the user has
 * installed, and on many devices there is none for Japanese or Korean at all. Bundling a
 * neural voice would add tens of megabytes and a native runtime to an app whose pitch is
 * "everything on device, nothing hidden"; shipping a file would also quietly contradict the
 * privacy claim.
 *
 * So: use what is there, report honestly when it is missing, and let the user hear the problem
 * instead of discovering it during a review session.
 */
class Speaker(context: Context) {

    private val appContext = context.applicationContext

    private var tts: TextToSpeech? = null

    /** Set once the engine finishes initialising; false means no speech at all. */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** Engines present on this device, for the settings screen. */
    private val _engines = MutableStateFlow<List<String>>(emptyList())
    val engines: StateFlow<List<String>> = _engines.asStateFlow()

    /** Languages the current engine cannot speak. */
    private val _unsupported = MutableStateFlow<Set<Lang>>(emptySet())
    val unsupportedLanguages: StateFlow<Set<Lang>> = _unsupported.asStateFlow()

    private val utteranceCounter = AtomicInteger(0)

    /** Called when the currently-spoken utterance finishes, so the UI can stop its animation. */
    var onDone: (() -> Unit)? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts
                _ready.value = engine != null
                if (engine != null) {
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) = Unit
                        override fun onDone(id: String?) {
                            if (id == currentUtterance) {
                                currentUtterance = null
                                onDone?.invoke()
                            }
                        }

                        @Deprecated("Required by the base class; not used.")
                        override fun onError(id: String?) {
                            if (id == currentUtterance) {
                                currentUtterance = null
                                onDone?.invoke()
                            }
                        }
                    })
                    refreshEngines()
                }
            } else {
                Log.w(TAG, "TextToSpeech init failed: $status")
                _ready.value = false
            }
        }
    }

    private var currentUtterance: String? = null

    /**
     * Speak [text] in [language].
     *
     * @return false if nothing was spoken, so callers can show a hint instead of pretending.
     */
    fun speak(text: String, language: Lang, rateMultiplier: Float = 1.0f, pitch: Float = 1.0f): Boolean {
        val engine = tts ?: return false
        if (!_ready.value) return false
        if (text.isBlank()) return false

        val locale = Locale.forLanguageTag(language.ttsLocale)
        val status = runCatching { engine.setLanguage(locale) }
            .getOrElse {
                Log.w(TAG, "setLanguage(${language.ttsLocale}) threw", it)
                TextToSpeech.LANG_NOT_SUPPORTED
            }
        if (status == TextToSpeech.LANG_MISSING_DATA || status == TextToSpeech.LANG_NOT_SUPPORTED) {
            _unsupported.value = _unsupported.value + language
            return false
        }
        _unsupported.value = _unsupported.value - language

        engine.setSpeechRate((DEFAULT_RATE * rateMultiplier).coerceIn(0.5f, 2.0f))
        engine.setPitch(pitch.coerceIn(0.5f, 2.0f))

        val id = "wl-${utteranceCounter.incrementAndGet()}"
        currentUtterance = id
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)
        }
        val result = runCatching { engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) }
            .getOrElse {
                Log.w(TAG, "speak threw", it)
                TextToSpeech.ERROR
            }
        if (result != TextToSpeech.SUCCESS) {
            currentUtterance = null
            return false
        }
        return true
    }

    fun stop() {
        currentUtterance = null
        tts?.stop()
    }

    /**
     * Check which of the four shipped languages this device can actually speak.
     *
     * Called from the settings screen rather than lazily because probing mutates engine state,
     * and doing it mid-review would make the next utterance sound different.
     */
    fun probeSupport(languages: List<Lang> = Lang.entries): Set<Lang> {
        val engine = tts ?: return languages.toSet()
        if (!_ready.value) return languages.toSet()
        val unsupported = mutableSetOf<Lang>()
        for (lang in languages) {
            val code = runCatching {
                engine.setLanguage(Locale.forLanguageTag(lang.ttsLocale))
            }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (code == TextToSpeech.LANG_MISSING_DATA || code == TextToSpeech.LANG_NOT_SUPPORTED) {
                unsupported += lang
            }
        }
        _unsupported.value = unsupported
        return unsupported
    }

    @Suppress("DEPRECATION")
    private fun refreshEngines() {
        val engine = tts ?: return
        val names = runCatching {
            engine.engines?.mapNotNull { it.name } ?: emptyList()
        }.getOrDefault(emptyList())
        _engines.value = names
    }

    fun release() {
        currentUtterance = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        _ready.value = false
    }

    private companion object {
        const val TAG = "Speaker"

        /** `TextToSpeech.setSpeechRate(1.0)` is already quite fast for a learner. */
        const val DEFAULT_RATE = 0.92f
    }
}

/** Convenience: `speakable` is false when the language is known to be unsupported. */
val Set<Lang>.missingFor: List<Lang>
    get() = Lang.entries.filterNot { contains(it) }
