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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
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
 * 只留下「牌组里确实还找得到这个词」的那些标记，`id` 是词典条目 id。
 *
 * 单独抽成一个不碰 Android 的函数有两个理由：这条判据是这次修复的全部实质内容，而
 * `SearchViewModel` 需要 `AppContainer`（JVM 里造不出来）；另外它必须对**认不出的 id** 保守——
 * 用户词典里删掉的一条也会留下标记，那种 id 查不到词头，留着就等于永久灰着一个按钮。
 *
 * 判据用规范化的词头而不是 id：牌组里的卡是 `toCard` 发的新 id，与词典条目 id 天生不同源，
 * 而「这个词我收过了」问的本来就是词，不是哪一行 JSON。
 */
internal fun Set<String>.retainingOwned(
    owned: Set<String>,
    headwordOf: (String) -> String?,
): Set<String> {
    if (isEmpty()) return emptySet()
    val kept = mutableSetOf<String>()
    for (id in this) {
        val word = headwordOf(id) ?: continue
        if (LexiconIndex.normalize(word) in owned) kept += id
    }
    return kept
}

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

    /** 正在写入途中的词头，挡住连点两下。只在主线程动它，理由见 [onCollect]。 */
    private val collecting = mutableSetOf<String>()

    val state: StateFlow<SearchUiState> = combine(
        container.deck.document,
        container.diary.document,
        container.lexicon.index,
        container.settings.settings,
        query,
    ) { deck, diary, lexicon, settings, text -> build(deck, diary, lexicon, settings, text) }
        // `added` 必须是第二个 combine 的**输入**而不是 build 里顺手读的一份值：
        // 收录成功后，牌组流的变化会先于 `added.value` 写下来到达这里，于是那一格的
        // 「已收」有没有出现取决于两次写入谁先跑到——而它没有第二次机会被重算。
        //
        // 过一道 `stillOwned` 是因为同一个标记会**永不失效**：用户收下 soba、之后把那张卡删了，
        // 词典那一格会重新出现在建议里（`owned` 那层过滤放它回来了），而按钮仍然按着
        // `added` 里的旧标记灰着、写着「已收」。那就是一个说谎的读数加一条死路——
        // 唯一的出路是退出这一页让 ViewModel 死掉。
        .combine(added) { found, just -> found.copy(justAdded = stillOwned(just)) }
        // 每一次按键都要在 12000 条词典里做线性搜索，再把整本日记扫一遍匹配标题/摘要/关键词/物体。
        // combine 的变换跑在**下游收集器**的上下文里，而下游是 viewModelScope（Main.immediate）：
        // 不加这一句就是每敲一个字在主线程扫一遍词典。首页的位图解码早就走同样的路。
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun onQueryChange(text: String) {
        query.value = text
    }

    /** 标记里还在牌组的那些。见上面 `combine(added)` 那段：删掉一张卡，那个词就该能重新收。 */
    private fun stillOwned(markers: Set<String>): Set<String> = markers.retainingOwned(
        owned = container.deck.snapshot().mapTo(mutableSetOf()) { LexiconIndex.normalize(it.headword) },
        headwordOf = { id -> container.lexicon.byId(id)?.headword },
    )

    /**
     * 收录词典里的一条。
     *
     * 去重按**词头**而不是 id：`LexiconEntry.toCard` 每次都发一个新 id，所以
     * `DeckRepository.add` 里的 id 判重只挡得住同一张卡的重放。取景页那边留同名重复是有意的
     * （同一个词、不同的场景与贴纸），这里没有照片也没有场景，重复就是纯噪音。
     *
     * 但**查牌组挡不住连点两下**：那一次查是同步读 `snapshot()`，而写入要等
     * `settings.first()` 挂起之后再回来——第二下完全能在第一张卡落库之前通过判重，于是塞进
     * 两张一模一样的卡。原来这段注释写着「挡得住」，那是一句关于代码的假话。现在加一道在途词头：
     * 第一下把词头占住，第二下当场返回。只改一个 `MutableSet` 而不上锁，是因为 `onCollect` 由
     * 界面回调进来、`viewModelScope` 是 `Main.immediate`，两下点击之间没有并发；
     * 判重的**结果**才需要跨那一次挂起，而那个结果就记在这一个集合里。
     */
    fun onCollect(lexiconEntryId: String) {
        val entry = container.lexicon.byId(lexiconEntryId) ?: return
        val headword = LexiconIndex.normalize(entry.headword)
        if (headword.isBlank()) return
        if (container.deck.snapshot().any { LexiconIndex.normalize(it.headword) == headword }) {
            added.value = added.value + entry.id
            return
        }
        if (!collecting.add(headword)) return
        viewModelScope.launch {
            try {
                // 设置是冷流，读当前值得显式 first()；这里不缓存，因为改语言不该留下旧方向。
                val settings = container.settings.settings.first()
                val card = container.cardFrom(
                    entry,
                    settings.targetLanguage,
                    settings.nativeLanguage,
                    EntrySource.MANUAL,
                )
                if (card != null && container.deck.add(card)) {
                    added.value = added.value + entry.id
                }
            } finally {
                collecting.remove(headword)
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
            // justAdded 不在这里读：它由上面第二个 combine 贴上来，理由写在那儿。
        )
    }

    companion object {
        private const val RECENT_LIMIT = 30
        private const val SUGGESTION_LIMIT = 24
        private const val DIARY_LIMIT = 20

        /** 分享进来的文本能当查询的最长长度。一整段文字作为搜索词只会保证「没有结果」。 */
        private const val MAX_SHARED_QUERY = 80

        /**
         * 把「别的 App 里选中的那段文字」折成一个可用的查询。
         *
         * 只取第一行、掐到 [MAX_SHARED_QUERY]：选区可能是整段摘要，而整段拿去做
         * `contains` 匹配，词典一定不命中、日记也几乎一定不命中，用户看到的是一句
         * 「什么都没找到」——那句话在这种情况下是假的，真正没做的是搜索。
         * 空与全空白返回 null，调用方据此**什么都不做**：静默不开页面，比开一个空页面好。
         */
        fun queryFromShared(raw: String?): String? {
            val firstLine = raw?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: return null
            if (firstLine.isEmpty()) return null
            return if (firstLine.length <= MAX_SHARED_QUERY) firstLine else firstLine.take(MAX_SHARED_QUERY).trimEnd()
        }

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { SearchViewModel(container) }
        }
    }
}
