// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ilyskyo.wordlens.core.AppContainer
import com.ilyskyo.wordlens.data.model.Entry
import com.ilyskyo.wordlens.data.model.EntrySource
import com.ilyskyo.wordlens.data.model.Lang
import com.ilyskyo.wordlens.data.model.LexiconEntry
import com.ilyskyo.wordlens.data.model.LexiconIndex
import com.ilyskyo.wordlens.data.model.WordCard
import com.ilyskyo.wordlens.data.repository.AppSettings
import com.ilyskyo.wordlens.data.repository.DeckDocument
import com.ilyskyo.wordlens.data.repository.DiaryDocument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 词典里可以一键收进牌组的一条。 */
data class Suggestion(
    val entry: LexiconEntry,
    val headword: String,
    val gloss: String?,
)

/** 搜索页状态。三区各自独立，不做分页也不做筛选器叠加。 */
data class SearchUiState(
    val query: String = "",
    /** 已经在自己牌组里的词。 */
    val collected: List<WordCard> = emptyList(),
    /** 词典命中但还没收进来的。 */
    val suggestions: List<Suggestion> = emptyList(),
    /** 日记里出现过这个词的条目。 */
    val diaryHits: List<Entry> = emptyList(),
    /** 本次操作后已经被收录的 id，按钮据此显示「已收」。 */
    val justAdded: Set<String> = emptySet(),
)

/**
 * 搜索 / 添加页。
 *
 * ## 为什么词库不是第三个标签
 *
 * 说明书 §5：词汇库就是时间轴的一个筛选视图，把它拆成平级页面会让「今天该做什么」变得不明显。
 * 所以这里是一个**搜索面**：同一个输入框同时命中牌组、词典和日记，而不是又一个列表页。
 *
 * ## 手动收录为什么在这儿
 *
 * `EntrySource.MANUAL` 一直存在却没有可达路径。取景器里认不出来的词，说明书让用户「直接把它写下来」
 * （§nomatch），那句话必须真能兑现——这里就是它兑现的地方。
 */
class SearchViewModel(private val container: AppContainer) : ViewModel() {

    private val query = MutableStateFlow("")
    private val added = MutableStateFlow(emptySet<String>())

    val state: StateFlow<SearchUiState> = combine(
        container.deck.document,
        container.diary.document,
        container.lexicon.index,
        container.settings.settings,
        query,
    ) { deck, diary, lexicon, settings, text -> build(deck, diary, lexicon, settings, text) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun onQueryChange(text: String) {
        query.value = text
    }

    /**
     * 收录词典里的一条。
     *
     * 去重按**词头**而不是 id：`LexiconEntry.toCard` 每次都发一个新 id，所以
     * `DeckRepository.add` 里的 id 判重只挡得住同一张卡的重放，挡不住连点两次按钮
     * 塞进两张一模一样的空卡。取景页那边留同名重复是有意的（同一个词、不同的场景与贴纸），
     * 这里没有照片也没有场景，重复就是纯噪音。
     */
    fun onCollect(lexiconEntryId: String) {
        val entry = container.lexicon.byId(lexiconEntryId) ?: return
        val headword = LexiconIndex.normalize(entry.headword)
        if (headword.isNotBlank() && container.deck.snapshot().any { LexiconIndex.normalize(it.headword) == headword }) {
            added.value = added.value + entry.id
            return
        }
        viewModelScope.launch {
            // 设置是冷流，读当前值得显式 first()；这里不缓存，因为改语言不该留下旧方向。
            val settings = container.settings.settings.first()
            val card = container.cardFrom(
                entry,
                settings.targetLanguage,
                settings.nativeLanguage,
                EntrySource.MANUAL,
            ) ?: return@launch
            if (container.deck.add(card)) {
                added.value = added.value + entry.id
            }
        }
    }

    fun onSpeak(word: String, languageTag: String?) {
        val lang = languageTag?.let(Lang::fromAnyTag)
        viewModelScope.launch {
            val target = lang ?: container.settings.settings.first().targetLanguage
            container.speaker.speak(word, target)
        }
    }

    private fun build(
        deck: DeckDocument,
        diary: DiaryDocument,
        lexicon: LexiconIndex,
        settings: AppSettings,
        text: String,
    ): SearchUiState {
        val q = text.trim()
        val needle = if (q.isEmpty()) "" else LexiconIndex.normalize(q)
        val collected = if (needle.isEmpty()) {
            deck.cards.sortedByDescending { it.updatedAt }.take(RECENT_LIMIT)
        } else {
            deck.cards.filter { card ->
                LexiconIndex.normalize(card.headword).contains(needle) ||
                    card.glosses.values.any { LexiconIndex.normalize(it).contains(needle) }
            }
        }
        // 已经在牌组里的词典条目不再提议——否则用户会在两个区看到同一件事。
        val owned = deck.cards.map { LexiconIndex.normalize(it.headword) }.toSet()
        val suggestions = if (needle.isEmpty()) {
            emptyList()
        } else {
            lexicon.search(q, language = null, limit = SUGGESTION_LIMIT)
                .filter { LexiconIndex.normalize(it.headword) !in owned }
                .map { Suggestion(it, it.headword, it.gloss(settings.nativeLanguage)) }
        }
        val diaryHits = if (needle.isEmpty()) {
            emptyList()
        } else {
            diary.entries
                .filter { entry ->
                    LexiconIndex.normalize(entry.title.orEmpty()).contains(needle) ||
                        LexiconIndex.normalize(entry.summary.orEmpty()).contains(needle) ||
                        entry.keywords.any { LexiconIndex.normalize(it).contains(needle) } ||
                        entry.objects.any { LexiconIndex.normalize(it.word).contains(needle) }
                }
                .sortedByDescending { it.takenAt }
                .take(DIARY_LIMIT)
        }
        return SearchUiState(
            query = text,
            collected = collected,
            suggestions = suggestions,
            diaryHits = diaryHits,
            justAdded = added.value,
        )
    }

    companion object {
        private const val RECENT_LIMIT = 30
        private const val SUGGESTION_LIMIT = 24
        private const val DIARY_LIMIT = 20

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { SearchViewModel(container) }
        }
    }
}
