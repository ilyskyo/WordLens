// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ilyskyo.wordlens.data.model.Lang
import com.ilyskyo.wordlens.data.model.StudyDirection
import com.ilyskyo.wordlens.srs.Fsrs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "wordlens_settings")

/** Everything the user can configure, in one immutable snapshot. */
data class AppSettings(
    /** Language the user already knows; the gloss side of every card. */
    val nativeLanguage: Lang = Lang.CHINESE,

    /** Language being learned; the headword side of every card. */
    val targetLanguage: Lang = Lang.ENGLISH,

    /** Whether a card shows the word or the meaning on its front. */
    val direction: StudyDirection = StudyDirection.RECOGNIZE,

    /** FSRS target retention. 0.9 is Anki's default. */
    val requestRetention: Double = Fsrs.DEFAULT_REQUEST_RETENTION,

    /** Run the labeller on the preview stream as well as on the shutter. */
    val livePreview: Boolean = true,

    /** Minimum labeller score (0-1) before a suggestion is offered at all. */
    val confidenceFloor: Float = 0.45f,

    /** Ask before saving when the top suggestion is ambiguous between close alternatives. */
    val confirmAmbiguous: Boolean = true,

    val reminderEnabled: Boolean = false,

    /** Minutes from local midnight; 20:00 = 20*60. */
    val reminderMinuteOfDay: Int = 20 * 60,

    /** Ask for the camera permission on first launch. */
    val onboardingComplete: Boolean = false,

    // ── optional cloud vision backend ────────────────────────────────────────
    val cloudEnabled: Boolean = false,
    val cloudModel: String = DEFAULT_CLOUD_MODEL,
    val cloudApiKey: String = "",

    /** Strip the EXIF/location metadata before anything is sent to a vision API. */
    val redactBeforeUpload: Boolean = true,
) {
    /** True when the cloud backend is both switched on and actually usable. */
    val cloudReady: Boolean get() = cloudEnabled && cloudApiKey.isNotBlank()

    /** The language a card's front should be written in, given the current direction. */
    fun frontLanguage(): Lang = if (direction == StudyDirection.RECOGNIZE) targetLanguage else nativeLanguage

    /** The language a card's back should be written in. */
    fun backLanguage(): Lang = if (direction == StudyDirection.RECOGNIZE) nativeLanguage else targetLanguage

    companion object {
        /**
         * Default model id for the optional vision backend.
         *
         * This is only a *suggestion*: users bring their own key and many will want a different
         * model, so the setting is editable and the app never assumes a vendor is reachable.
         */
        const val DEFAULT_CLOUD_MODEL = "claude-sonnet-4-5"
    }
}

/**
 * Preferences, via DataStore.
 *
 * Security note on [AppSettings.cloudApiKey]: it is stored **unencrypted** in the app's private
 * DataStore. That is a deliberate, documented choice rather than an oversight — the key belongs
 * to the user, never leaves the device except inside the request they asked for, and pulling in
 * a keystore-backed store would mean either a deprecated API or a maintenance burden this
 * project should not take on. The settings screen states this in plain language and offers a
 * one-tap wipe.
 */
class SettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            nativeLanguage = Lang.fromTag(prefs[Keys.NATIVE_LANG] ?: Lang.CHINESE.tag) ?: Lang.CHINESE,
            targetLanguage = Lang.fromTag(prefs[Keys.TARGET_LANG] ?: Lang.ENGLISH.tag) ?: Lang.ENGLISH,
            direction = runCatching {
                StudyDirection.valueOf(prefs[Keys.DIRECTION] ?: StudyDirection.RECOGNIZE.name)
            }.getOrDefault(StudyDirection.RECOGNIZE),
            requestRetention = (prefs[Keys.RETENTION] ?: Fsrs.DEFAULT_REQUEST_RETENTION)
                .coerceIn(0.7, 0.98),
            livePreview = prefs[Keys.LIVE_PREVIEW] ?: true,
            confidenceFloor = (prefs[Keys.CONFIDENCE_FLOOR] ?: 0.45f).coerceIn(0.05f, 0.95f),
            confirmAmbiguous = prefs[Keys.CONFIRM_AMBIGUOUS] ?: true,
            reminderEnabled = prefs[Keys.REMINDER_ON] ?: false,
            reminderMinuteOfDay = (prefs[Keys.REMINDER_MINUTE] ?: 20 * 60)
                .coerceIn(0, 24 * 60 - 1),
            onboardingComplete = prefs[Keys.ONBOARDING_DONE] ?: false,
            cloudEnabled = prefs[Keys.CLOUD_ON] ?: false,
            cloudModel = prefs[Keys.CLOUD_MODEL] ?: AppSettings.DEFAULT_CLOUD_MODEL,
            cloudApiKey = prefs[Keys.CLOUD_KEY] ?: "",
            redactBeforeUpload = prefs[Keys.REDACT] ?: true,
        )
    }

    suspend fun setNativeLanguage(lang: Lang) = put(Keys.NATIVE_LANG, lang.tag)

    suspend fun setTargetLanguage(lang: Lang) = put(Keys.TARGET_LANG, lang.tag)

    suspend fun setDirection(direction: StudyDirection) = put(Keys.DIRECTION, direction.name)

    suspend fun setRetention(value: Double) {
        // Clamp to a range where FSRS still produces sane intervals. 0.99 asks for an interval
        // so short the learner reviews 300 times a day; 0.7 forgets almost immediately.
        put(Keys.RETENTION, value.coerceIn(0.7, 0.98))
    }

    suspend fun setLivePreview(enabled: Boolean) = put(Keys.LIVE_PREVIEW, enabled)

    suspend fun setConfidenceFloor(value: Float) = put(Keys.CONFIDENCE_FLOOR, value.coerceIn(0.05f, 0.95f))

    suspend fun setConfirmAmbiguous(enabled: Boolean) = put(Keys.CONFIRM_AMBIGUOUS, enabled)

    suspend fun setReminder(enabled: Boolean) = put(Keys.REMINDER_ON, enabled)

    suspend fun setReminderMinuteOfDay(minute: Int) = put(Keys.REMINDER_MINUTE, minute.coerceIn(0, 24 * 60 - 1))

    suspend fun setOnboardingComplete(done: Boolean) = put(Keys.ONBOARDING_DONE, done)

    suspend fun setCloudEnabled(enabled: Boolean) = put(Keys.CLOUD_ON, enabled)

    suspend fun setCloudModel(model: String) = put(Keys.CLOUD_MODEL, model.trim())

    suspend fun setCloudApiKey(key: String) = put(Keys.CLOUD_KEY, key.trim())

    suspend fun setRedactBeforeUpload(enabled: Boolean) = put(Keys.REDACT, enabled)

    /** Forget the key without touching anything else. */
    suspend fun clearCloudApiKey() = context.dataStore.edit { it.remove(Keys.CLOUD_KEY) }

    /** Convenience for `Speaker`: the TTS locale for a given language. */
    fun ttsLocale(lang: Lang): String = lang.ttsLocale

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    private object Keys {
        val NATIVE_LANG = stringPreferencesKey("native_language")
        val TARGET_LANG = stringPreferencesKey("target_language")
        val DIRECTION = stringPreferencesKey("direction")
        val RETENTION = doublePreferencesKey("request_retention")
        val LIVE_PREVIEW = booleanPreferencesKey("live_preview")
        val CONFIDENCE_FLOOR = floatPreferencesKey("confidence_floor")
        val CONFIRM_AMBIGUOUS = booleanPreferencesKey("confirm_ambiguous")
        val REMINDER_ON = booleanPreferencesKey("reminder_enabled")
        val REMINDER_MINUTE = intPreferencesKey("reminder_minute_of_day")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_complete")
        val CLOUD_ON = booleanPreferencesKey("cloud_enabled")
        val CLOUD_MODEL = stringPreferencesKey("cloud_model")
        val CLOUD_KEY = stringPreferencesKey("cloud_api_key")
        val REDACT = booleanPreferencesKey("redact_before_upload")
    }

    companion object {
        val MINUTE_MS = 60_000L
    }
}
