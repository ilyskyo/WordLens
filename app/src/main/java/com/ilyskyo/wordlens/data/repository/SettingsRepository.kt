// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ilyskyo.wordlens.data.model.Lang
import com.ilyskyo.wordlens.data.model.RatingPalette
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

    /** 评级四档按钮的色系。 */
    val ratingPalette: RatingPalette = RatingPalette.WARM,

    /** 每日复习提醒的开关。默认关：不让用户被静默订阅通知。 */
    val reminderEnabled: Boolean = false,

    /** Minutes from local midnight; 20:00 = 20*60. */
    val reminderMinuteOfDay: Int = 20 * 60,

    // ── optional cloud vision backend ────────────────────────────────────────
    val cloudEnabled: Boolean = false,
    val cloudModel: String = DEFAULT_CLOUD_MODEL,
    val cloudApiKey: String = "",
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
            targetLanguage = repairedTarget(
                stored = Lang.fromTag(prefs[Keys.TARGET_LANG] ?: Lang.ENGLISH.tag) ?: Lang.ENGLISH,
                native = Lang.fromTag(prefs[Keys.NATIVE_LANG] ?: Lang.CHINESE.tag) ?: Lang.CHINESE,
            ),
            direction = runCatching {
                StudyDirection.valueOf(prefs[Keys.DIRECTION] ?: StudyDirection.RECOGNIZE.name)
            }.getOrDefault(StudyDirection.RECOGNIZE),
            requestRetention = (prefs[Keys.RETENTION] ?: Fsrs.DEFAULT_REQUEST_RETENTION)
                .coerceIn(Fsrs.MIN_REQUEST_RETENTION, Fsrs.MAX_REQUEST_RETENTION),
            ratingPalette = RatingPalette.fromName(prefs[Keys.RATING_PALETTE]),
            reminderEnabled = prefs[Keys.REMINDER_ON] ?: false,
            reminderMinuteOfDay = (prefs[Keys.REMINDER_MINUTE] ?: 20 * 60)
                .coerceIn(0, 24 * 60 - 1),
            cloudEnabled = prefs[Keys.CLOUD_ON] ?: false,
            cloudModel = prefs[Keys.CLOUD_MODEL] ?: AppSettings.DEFAULT_CLOUD_MODEL,
            cloudApiKey = prefs[Keys.CLOUD_KEY] ?: "",
        )
    }

    suspend fun setNativeLanguage(lang: Lang) = put(Keys.NATIVE_LANG, lang.tag)

    suspend fun setTargetLanguage(lang: Lang) = put(Keys.TARGET_LANG, lang.tag)

    /**
     * 一次编辑写定两个方向。
     *
     * 分两次 `put` 做不到这件事：无论先后，中间都会有一次落进「目标语 == 母语」那一格，
     * 而那一格没有合法的卡可问——正面与背面是同一个词。`edit` 是一次原子写，
     * 流里因此不会出现那一格。
     */
    suspend fun setLanguagePair(native: Lang, target: Lang) {
        context.dataStore.edit {
            it[Keys.NATIVE_LANG] = native.tag
            it[Keys.TARGET_LANG] = target.tag
        }
    }

    suspend fun setDirection(direction: StudyDirection) = put(Keys.DIRECTION, direction.name)

    suspend fun setRetention(value: Double) {
        // 区间由 Fsrs 自己定义，设置页与排期器读同一对常量：
        // 两处各写一份数字，早晚会夹出「滑块能拉到但排期器不接受」的空档。
        put(Keys.RETENTION, value.coerceIn(Fsrs.MIN_REQUEST_RETENTION, Fsrs.MAX_REQUEST_RETENTION))
    }

    suspend fun setRatingPalette(palette: RatingPalette) = put(Keys.RATING_PALETTE, palette.name)

    suspend fun setReminder(enabled: Boolean) = put(Keys.REMINDER_ON, enabled)

    suspend fun setReminderMinuteOfDay(minute: Int) = put(Keys.REMINDER_MINUTE, minute.coerceIn(0, 24 * 60 - 1))

    suspend fun setCloudEnabled(enabled: Boolean) = put(Keys.CLOUD_ON, enabled)

    suspend fun setCloudModel(model: String) = put(Keys.CLOUD_MODEL, model.trim())

    suspend fun setCloudApiKey(key: String) = put(Keys.CLOUD_KEY, key.trim())

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
        val RATING_PALETTE = stringPreferencesKey("rating_palette")
        val REMINDER_ON = booleanPreferencesKey("reminder_enabled")
        val REMINDER_MINUTE = intPreferencesKey("reminder_minute_of_day")
        val CLOUD_ON = booleanPreferencesKey("cloud_enabled")
        val CLOUD_MODEL = stringPreferencesKey("cloud_model")
        val CLOUD_KEY = stringPreferencesKey("cloud_api_key")

        // 这里曾经有一个 `redact_before_upload`：上传前剥 EXIF 的开关。
        // 它被删掉了，因为没有任何代码读它——`CloudVisionEngine.encodeImage` 发出去的字节是
        // `Bitmap.compress` 重新编码出来的，本来就不含 EXIF，「照片不带着坐标出门」是结构性的，
        // 不是开关决定的。一个拨了什么都不发生的开关，比没有这个开关更糟：它会让人以为
        // 关掉之后原图就会出门，而那恰恰是它做不到的事。旧用户 DataStore 里那个键会一直留着，
        // 没有读它的人，也就不需要迁移。
    }

    companion object {
        /**
         * 两个方向读出来是同一门语言时，把目标语挪到第一门不是它的语言上。
         *
         * 需要这一格的理由很具体：**已经有设备存成了 zh/zh**——「不许选成同一对」这条规则
         * 是今天才在界面上立住的（`SettingsViewModel.pairAfterPicking`），在那之前两个选择器
         * 互不知晓。而规则立住并不会让已经存坏的设备自己好起来：用户不去设置页动一下，
         * 他就一直拿到一张没意义的卡——正面「伞」、背面释义也是「伞」，翻面什么也没回忆起来，
         * FSRS 还认真记了一次「想起来了」，这个坏数据会一直影响它给这个词排的间隔。
         *
         * 只改读出来的值，不动存储里那两个键：他不该为一条界面再也不会允许他做出的选择负责；
         * 下次真的选语言时 `setLanguagePair` 会把两个键一起写正。
         */
        internal fun repairedTarget(stored: Lang, native: Lang): Lang =
            if (stored == native) Lang.entries.first { it != native } else stored

        val MINUTE_MS = 60_000L
    }
}
