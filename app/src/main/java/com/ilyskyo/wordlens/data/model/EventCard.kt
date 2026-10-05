// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.data.model

import kotlinx.serialization.Serializable

/**
 * 「记住」页里被 FSRS 调度的一条**事件**。
 *
 * ## 为什么要把事件也做成卡片
 *
 * 「记住」页顶部的「记事件 / 记词汇」不是两套东西，是同一套调度器的两种素材。用户真正
 * 想练的不只是外语词，还有「那天发生了什么」——按间隔重复回放某一天的片段，是一种真实
 * 且有效的记忆训练，而且它是这本日记**独有**的能力：一个背单词 App 做不到这个。
 *
 * 所以事件和词汇共用 [FsrsState] 与同一个复习队列，差别只在卡片长什么样、正面问什么。
 * 让两者共用一个调度器而不是写两套，也是为了以后能训练出「哪种素材更容易记住」的结论。
 *
 * ## 与 [WordCard] 的分工
 *
 * | | [WordCard] | [EventCard] |
 * |---|---|---|
 * | 正面 | 单词（或释义） | 一句事件描述 |
 * | 背面 | 音标、翻译、例句 | 原始要点、时间、照片 |
 * | 方向 | 认识 / 产出两套状态 | 只有一套 |
 * | 视觉 | 抠出来的贴纸 | 照片缩略图（若有） |
 *
 * 事件没有「产出」方向——复述一段经历和认出它的难度相同，所以只给一个状态。
 */
@Serializable
data class EventCard(
    val id: String,

    /** 事件描述的正文。一句话，不要写成日记。 */
    val text: String,

    /** 发生时间。时间轴上的排序依据，也是「那天」的锚点。 */
    val happenedAt: Long,

    /** 关联的日记条目。事件可以没有任何照片，只是一段文字。 */
    val entryId: String? = null,

    /**
     * 这条事件从哪来。
     *
     * AI 摘要和用户手写的事件在复习时应当**区别对待**：AI 生成的内容可能本身就是错的，
     * 而复习会把错误内容反复巩固。所以界面上要给一个明显的来源标记，并且
     * [ReviewSource.AI] 的事件在评级时提示用户核对原文。
     */
    val source: ReviewSource = ReviewSource.USER,

    /** 关联照片的文件名，相对 `filesDir/entries`。卡片可以没有照片。 */
    val photoPath: String? = null,

    val tags: List<String> = emptyList(),

    /** 同 [WordCard.mastered]：手动归档，退出队列，与评级的 EASY 无关。 */
    val mastered: Boolean = false,

    val createdAt: Long = System.currentTimeMillis(),

    val updatedAt: Long = System.currentTimeMillis(),

    /**
     * 按方向存的 FSRS 状态。事件只用 [StudyDirection.RECOGNIZE] 一个键，
     * 保留 map 是为了和 [WordCard] 共用同一个队列实现与同一套序列化格式。
     */
    val states: Map<String, FsrsState> = emptyMap(),
) {
    fun state(direction: StudyDirection = StudyDirection.RECOGNIZE): FsrsState? =
        states[direction.name]

    val isNew: Boolean get() = states.isEmpty()

    /** 本地日期键 yyyy-MM-dd，「那天总结」的分组键。 */
    val dayKey: String
        get() = java.time.Instant.ofEpochMilli(happenedAt)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .toString()

    companion object {
        fun newId(): String = WordCard.newId()
    }
}

/** 复习素材的来源。这个字段决定复习界面上的提示强度。 */
@Serializable
enum class ReviewSource {
    /** 用户自己写的。默认可信。 */
    USER,

    /**
     * 模型生成的。可能在细节上出错，而复习会不断巩固它——所以复习界面必须提示用户
     * 核对原文，且默认不把它当成权威内容。
     */
    AI,

    /** 从照片里的可读文字或词典匹配而来。 */
    DERIVED,
}

/**
 * 「记住」页的素材筛选。
 *
 * 三个而不是两个：词汇与事件之外，还有一个 [WORDS_AND_EVENTS]，因为多数时候用户是想
 * 把两种一起练的，而把它们分开意味着要切两次页签。
 */
enum class StudyMaterial(val titleRes: Int) {
    WORDS_AND_EVENTS(com.ilyskyo.wordlens.R.string.study_both),
    WORDS(com.ilyskyo.wordlens.R.string.study_words),
    EVENTS(com.ilyskyo.wordlens.R.string.study_events),
}

/** 复习队列里的一项。词汇与事件共用队列，所以队列元素必须能是两者之一。 */
sealed interface ReviewItem {
    val id: String
    val state: FsrsState?

    data class Word(val card: WordCard) : ReviewItem {
        override val id: String get() = card.id
        override val state: FsrsState? get() = card.state(StudyDirection.RECOGNIZE)
    }

    data class Event(val card: EventCard) : ReviewItem {
        override val id: String get() = card.id
        override val state: FsrsState? get() = card.state()
    }
}