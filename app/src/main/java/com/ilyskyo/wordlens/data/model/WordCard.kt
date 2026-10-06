// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.data.model

import kotlinx.serialization.Serializable

/**
 * The four languages WordLens ships support for.
 *
 * [tag] is the BCP-47 tag used as a key in [WordCard.glosses] and in the lexicon files.
 * [ttsLocale] is what gets handed to `TextToSpeech.setLanguage` — note that Chinese and
 * Japanese TTS quality varies wildly by installed engine, so [voiceHint] exists purely to
 * tell the user what a bad result sounds like and what to do about it.
 */
enum class Lang(
    val tag: String,
    val englishName: String,
    val nativeName: String,
    val ttsLocale: String,
    val isToneScript: Boolean = false,
) {
    ENGLISH("en", "English", "English", "en-US"),
    CHINESE("zh", "Chinese", "中文", "zh-CN", isToneScript = true),
    JAPANESE("ja", "Japanese", "日本語", "ja-JP"),
    KOREAN("ko", "Korean", "한국어", "ko-KR"),
    ;

    companion object {
        fun fromTag(tag: String): Lang? = entries.firstOrNull { it.tag == tag }

        /**
         * Accepts both our short tag ("zh") and a full BCP-47 tag ("zh-CN", "zh-Hans-CN").
         * Users' own files and ML Kit output use both forms.
         */
        fun fromAnyTag(tag: String): Lang? {
            val primary = tag.substringBefore('-').lowercase()
            return entries.firstOrNull { it.tag == primary }
        }
    }
}

/**
 * Which way round a card is being asked.
 *
 * Deliberately *not* stored on the card: one photographed object carries all four
 * translations, so the same row can be drilled as "cup -> 杯子" or "杯子 -> cup" without
 * duplicating the memory state. Two directions of the same word are genuinely two different
 * memories though, which is handled by keying [Fsrs.State] on card id + direction.
 */
enum class StudyDirection {
    /** Show the foreign word, recall the meaning. */
    RECOGNIZE,

    /** Show the meaning, recall the foreign word. Production-style. */
    RECALL,
    ;

    fun toggled(): StudyDirection = if (this == RECOGNIZE) RECALL else RECOGNIZE
}

/** Where a card came from. Shown in the UI so users can trust on-device guesses less. */
@Serializable
enum class EntrySource {
    /** Typed in by hand. */
    MANUAL,

    /** ML Kit bundled on-device image labeller. */
    ON_DEVICE,

    /** Optional bring-your-own-key vision model. */
    CLOUD,

    /** Imported from a backup or an Anki export. */
    IMPORTED,
}

/**
 * How a card was captured, which decides what art it gets on the deck wall.
 *
 * A [STICKER] card has a background-removed cut-out and reads as a physical object. A [SCENE]
 * card has no cut-out — its picture is the whole photograph, shown small as a reminder of
 * context — and pretending otherwise would either crop the scene into nonsense or leave a
 * rectangle sitting on a wall of die-cut shapes.
 */
@Serializable
enum class CardOrigin {
    /** Background-removed subject, rendered with a die-cut border. */
    STICKER,

    /** One word out of a scene photo; the photo is context, not the subject. */
    SCENE,

    /** Text-only card, e.g. typed or imported. */
    TEXT,
}

/**
 * One vocabulary item.
 *
 * Storage note: the deck is a single human-readable JSON document under `filesDir`. That is a
 * deliberate trade — a Room database would scale further and query more easily, but a plain
 * file can be diffed, hand-edited, grepped and committed to a backup archive without tooling,
 * and the deck for a hobby vocabulary app stays comfortably in the low thousands.
 */
@Serializable
data class WordCard(
    /** Stable id (UUID hex). */
    val id: String,

    /** The word itself, in [language]. */
    val headword: String,

    /** BCP-47 tag of [headword]. */
    val language: String,

    /** International Phonetic Association transcription, with or without slashes. */
    val ipa: String? = null,

    /** Meaning keyed by target tag: `{"zh": "杯子", "ja": "カップ"}`. */
    val glosses: Map<String, String> = emptyMap(),

    /** Example sentence in [language]. */
    val example: String? = null,

    /** Translation of [example], keyed by target tag. */
    val exampleGlosses: Map<String, String> = emptyMap(),

    /** Emoji stand-in shown when there is no sticker photo yet. */
    val emoji: String? = null,

    /** Image filename relative to `filesDir/stickers`. */
    val stickerPath: String? = null,

    /** Image filename relative to `filesDir/photos`, un-background-removed. */
    val originalPhotoPath: String? = null,

    /**
     * The scene this word was collected from, if any.
     *
     * This is context, not scheduling: the card keeps its own FSRS state either way. But it is
     * what lets the review screen show "you met this in the office" alongside the word, which
     * is a large part of why the word sticks.
     */
    val sceneId: String? = null,

    /** Denormalised so the deck wall can render a caption without loading every scene. */
    val sceneLabel: String? = null,

    /** Emoji for the originating scene, denormalised for the same reason. */
    val sceneEmoji: String? = null,

    /**
     * 这张卡的词是怎么来的。
     *
     * 默认是 [CardOrigin.TEXT]：从词典里查一条然后收进牌组（搜索页的收录、取景页的手写）
     * 是**唯一**不走 `PhotoEntryPipeline` 的建卡路径，而那两条路径压根没有画面。
     * 之前默认是 STICKER，于是手输的词在磁盘上写着「我是从照片上抠下来的贴纸」——
     * 今天没有任何界面读这个字段，所以看不出来；但它是一句会一直留在 deck.json 里的假话，
     * 而第一个真的按来源分组的视图会把它当真话用。
     *
     * [CardOrigin.STICKER] 由流水线显式写上（`PhotoEntryPipeline` 里那一处），不靠默认值。
     * [CardOrigin.SCENE] 暂时没有任何生产者：整张照片那条路进的是日记而不是牌组，
     * 「氛围词」不生成卡。留着它是因为这是**已落盘的词汇表**，删一个常量就等于改数据格式，
     * 而这条改动的收益是零。
     */
    val origin: CardOrigin = CardOrigin.TEXT,

    /** Free-form tags, e.g. `food`, `kitchen`, `street`. */
    val tags: List<String> = emptyList(),

    /** Free-text category, mirrored from the lexicon when the entry came from one. */
    val category: String? = null,

    val source: EntrySource = EntrySource.MANUAL,

    /**
     * 用户手动「标记已掌握」。为 true 时这张卡彻底退出复习队列。
     *
     * 它和评级的 EASY **不是一回事**：EASY 只是「这次记得很牢」，间隔会变长但之后仍要回来；
     * 已掌握是「以后别再问我」。把两者混成一个按钮，用户会把还没真记住的词归档掉，
     * 而 FSRS 再也不会提醒他。所以它单独一个字段，入口在长按菜单里。
     */
    val mastered: Boolean = false,

    val createdAt: Long = System.currentTimeMillis(),

    val updatedAt: Long = System.currentTimeMillis(),

    /**
     * FSRS state **per study direction**, keyed by `StudyDirection.name`.
     *
     * Recognising `cup` and producing `cup` are separate memories with separate stability and
     * separate lapse counts, so they are scheduled independently. A missing key means "never
     * studied in that direction"; that is different from a state that exists and is new-ish.
     */
    val states: Map<String, FsrsState> = emptyMap(),
) {
    /** Look up this card's meaning in [target], falling back to any gloss we do have. */
    fun gloss(target: Lang): String? = glosses[target.tag]
        ?: glosses[Lang.fromAnyTag(target.tag)?.tag ?: target.tag]

    fun exampleTranslation(target: Lang): String? =
        exampleGlosses[target.tag] ?: exampleGlosses[Lang.fromAnyTag(target.tag)?.tag ?: target.tag]

    fun headLang(): Lang? = Lang.fromAnyTag(language)

    /** FSRS state for one direction, or null when never studied that way. */
    fun state(direction: StudyDirection): FsrsState? = states[direction.name]

    /** Never studied in any direction. */
    val isNew: Boolean get() = states.isEmpty()

    /** Studied at least once in [direction]. */
    fun isStudied(direction: StudyDirection): Boolean = state(direction) != null

    /** Earliest due timestamp across all directions, or null when the card is entirely new. */
    fun earliestDue(): Long? = states.values.minOfOrNull { it.due }

    /** True if [direction] is due (or new) right now. */
    fun isDue(direction: StudyDirection, now: Long = System.currentTimeMillis()): Boolean {
        val s = state(direction) ?: return true
        return now >= s.due
    }

    companion object {
        fun newId(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
    }
}

/**
 * FSRS memory state.
 *
 * Kept as a distinct file-local type rather than reusing `srs.Fsrs.State` directly so the
 * serialised field names are stable short strings (`d`, `s`, `due`, …). The scheduler is a
 * swappable detail; the on-disk format is not.
 */
@Serializable
data class FsrsState(
    /** Difficulty D, 1-10. */
    val d: Double = 0.0,
    /** Stability S, in days. */
    val s: Double = 0.0,
    /** Epoch millis when due. */
    val due: Long = 0L,
    /** Epoch millis of last review. */
    val last: Long = 0L,
    /** Times reviewed. */
    val n: Int = 0,
    /** Times graded AGAIN. */
    val lapses: Int = 0,
) {
    val isNew: Boolean get() = n == 0

    companion object {
        val EMPTY = FsrsState()
    }
}

/** One line in the review history. Kept for the streak/stats screen and for FSRS optimisation. */
@Serializable
data class ReviewLog(
    val cardId: String,
    val direction: StudyDirection,
    val rating: Int,
    val at: Long,
    /** How the answer was graded, when not a plain self-report. */
    val source: String = "self",
    /** Milliseconds spent on the card. */
    val elapsedMs: Long = 0,
)
