// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.nav

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ilyskyo.wordlens.R
import com.ilyskyo.wordlens.core.AppContainer
import com.ilyskyo.wordlens.data.model.EntryObject
import com.ilyskyo.wordlens.data.model.EntrySource
import com.ilyskyo.wordlens.data.model.FsrsState
import com.ilyskyo.wordlens.data.model.Lang
import com.ilyskyo.wordlens.data.model.LexiconEntry
import com.ilyskyo.wordlens.data.model.LexiconIndex
import com.ilyskyo.wordlens.data.model.OverlayLayer
import com.ilyskyo.wordlens.data.model.ReviewItem
import com.ilyskyo.wordlens.data.model.ReviewSource
import com.ilyskyo.wordlens.data.model.StudyDirection
import com.ilyskyo.wordlens.data.model.StudyMaterial
import com.ilyskyo.wordlens.data.repository.AppSettings
import com.ilyskyo.wordlens.data.repository.DeckDocument
import com.ilyskyo.wordlens.data.repository.DiaryDocument
import com.ilyskyo.wordlens.srs.Fsrs
import com.ilyskyo.wordlens.ui.lookback.CardWord
import com.ilyskyo.wordlens.ui.lookback.EntryCard
import com.ilyskyo.wordlens.ui.lookback.LookbackUiState
import com.ilyskyo.wordlens.ui.lookback.formatDay
import com.ilyskyo.wordlens.ui.remember.RememberCard
import com.ilyskyo.wordlens.ui.remember.RememberUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.LocalDate

/**
 * 首页（回看 + 记住）的状态来源。
 *
 * ## 队列不是一次性快照
 *
 * 「记住」页的复习队列直接由 deck / diary 的当前数据推导：评一张、扣一张，仓库更新后
 * 队列自然变短。没有「会话开始时冻结一份列表」这种中间层——冻结列表要么在数据变化时
 * 悄悄丢卡，要么在重进页签时把已评过的卡重新排进来。
 *
 * 已完成数（done）是唯一的会话态，切换素材筛选时清零：那是用户主动开始的新的一轮。
 */
class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val settingsFlow = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** 素材筛选 + 是否翻面 + 本轮已完成数，合成一个流，让 combine 的入参控制在 5 个。 */
    private data class Session(
        val material: StudyMaterial = StudyMaterial.WORDS_AND_EVENTS,
        val revealed: Boolean = false,
        val done: Int = 0,
    )

    private val session = MutableStateFlow(Session())

    /** 翻面时刻，用于把停留时长写进复习日志（FSRS 日后调参的原料）。 */
    private var revealedAt = 0L

    /** 串行化评级，连点两下不会交错读改写；仓库层的锁只管它自己的文件。 */
    private val gradeLock = Mutex()

    // ── 回看 ────────────────────────────────────────────────────────────────

    val lookback: StateFlow<LookbackUiState> = combine(
        container.diary.document,
        container.lexicon.index,
        settingsFlow,
    ) { diary, lexicon, settings -> buildLookback(diary, lexicon, settings) }
        // 照片解码是这批流里唯一的重活，放到 Default 上，别占主线程。
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LookbackUiState())

    // ── 记住 ────────────────────────────────────────────────────────────────

    val remember: StateFlow<RememberUiState> = combine(
        container.deck.document,
        container.diary.document,
        settingsFlow,
        session,
        container.speaker.ready,
    ) { deck, diary, settings, current, ttsReady ->
        buildRemember(deck, diary, settings, current, ttsReady)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RememberUiState())

    // ── 回调 ────────────────────────────────────────────────────────────────

    fun onMaterialChange(material: StudyMaterial) {
        session.update { it.copy(material = material, revealed = false, done = 0) }
    }

    fun onReveal() {
        if (session.value.revealed) return
        revealedAt = System.currentTimeMillis()
        session.update { it.copy(revealed = true) }
    }

    fun onGrade(rating: Fsrs.Rating) {
        val item = remember.value.current?.item ?: return
        val now = System.currentTimeMillis()
        val elapsed = if (revealedAt > 0) now - revealedAt else 0L
        viewModelScope.launch {
            gradeLock.withLock {
                when (item) {
                    is ReviewItem.Word ->
                        container.deck.grade(item.card.id, settingsFlow.value.direction, rating, elapsed, now)

                    is ReviewItem.Event ->
                        container.diary.gradeEvent(item.card.id, rating, elapsed, now)
                }
            }
            revealedAt = 0L
            // 仓库更新会推动流重算队列，这里只复位翻面与计数。
            session.update { it.copy(revealed = false, done = it.done + 1) }
        }
    }

    /**
     * 长按菜单里的「标记已掌握」：把当前这张卡永久移出队列。
     *
     * 和 [onGrade] 是两件事——评级只改变下次间隔，这一步决定它还会不会出现。所以它不占
     * 评级按钮的位置，也不是不可逆：已归档的清单在复习页的空状态里可以打开并取消。
     */
    fun onMarkMastered() {
        val item = remember.value.current?.item ?: return
        viewModelScope.launch {
            gradeLock.withLock {
                when (item) {
                    is ReviewItem.Word -> container.deck.update(item.card.copy(mastered = true))
                    is ReviewItem.Event -> container.diary.updateEvent(item.card.copy(mastered = true))
                }
            }
        }
    }

    /** 取消归档。id 可能来自牌组也可能来自日记，两边各查一次。 */
    fun onUnmark(id: String) {
        viewModelScope.launch {
            gradeLock.withLock {
                container.deck.card(id)?.let { container.deck.update(it.copy(mastered = false)) }
                container.diary.event(id)?.let { container.diary.updateEvent(it.copy(mastered = false)) }
            }
        }
    }

    /** 复习页发音：词汇卡读词条本身；事件是一句话，界面本来就不给发音按钮。 */    fun onSpeak() {
        val card = (remember.value.current?.item as? ReviewItem.Word)?.card ?: return
        val lang = card.headLang() ?: settingsFlow.value.targetLanguage
        container.speaker.speak(card.headword, lang)
    }

    /** 回看页某张卡片的发音：读第一个物品词。 */
    fun onEntrySpeak(card: EntryCard) {
        val word = card.words.firstOrNull { it.text.isNotBlank() } ?: return
        val lang = Lang.fromAnyTag(word.languageTag ?: "en") ?: settingsFlow.value.targetLanguage
        container.speaker.speak(word.text, lang)
    }

    // ── 构建 ────────────────────────────────────────────────────────────────

    private fun buildRemember(
        deck: DeckDocument,
        diary: DiaryDocument,
        settings: AppSettings,
        current: Session,
        ttsReady: Boolean,
    ): RememberUiState {
        val now = System.currentTimeMillis()
        val direction = settings.direction

        val queue = buildList {
            if (current.material != StudyMaterial.EVENTS) {
                deck.cards
                    .filter { card ->
                        // 已掌握的卡彻底不出现在队列里——它靠手动归档，不靠 EASY 的长间隔。
                        val state = card.state(direction)
                        !card.mastered && (state == null || now >= state.due)
                    }
                    .forEach { add(ReviewItem.Word(it)) }
            }
            if (current.material != StudyMaterial.WORDS) {
                diary.events
                    .filter { event ->
                        val state = event.state()
                        !event.mastered && (state == null || now >= state.due)
                    }
                    .forEach { add(ReviewItem.Event(it)) }
            }
        }

        val head = queue.firstOrNull()
        // 已归档的排在队列之后：它们不再被调度，但必须能被翻出来取消，否则这个动作就是单行道。
        val archived = buildList {
            if (current.material != StudyMaterial.EVENTS) {
                deck.cards.filter { it.mastered }.forEach { add(ReviewItem.Word(it)) }
            }
            if (current.material != StudyMaterial.WORDS) {
                diary.events.filter { it.mastered }.forEach { add(ReviewItem.Event(it)) }
            }
        }
        return RememberUiState(
            material = current.material,
            done = current.done,
            total = current.done + queue.size,
            current = head?.let { cardFor(it, settings) },
            revealed = current.revealed,
            finished = head == null && current.done > 0,
            streakDays = container.deck.streak(now),
            speakEnabled = ttsReady,
            intervals = previewIntervals(head, direction),
            archived = archived,
        )
    }

    /** 四个评级各自会把这张卡推到几天后。新卡从空状态推演，与调度器的起点一致。 */
    private fun previewIntervals(item: ReviewItem?, direction: StudyDirection): Map<Fsrs.Rating, Int> {
        val state = when (item) {
            is ReviewItem.Word -> item.card.state(direction)
            is ReviewItem.Event -> item.card.state()
            null -> null
        }
        return Fsrs.previewIntervals(state?.toScheduler() ?: Fsrs.State())
    }

    private fun cardFor(item: ReviewItem, settings: AppSettings): RememberCard = when (item) {
        is ReviewItem.Word -> {
            val card = item.card
            val prompt: String
            val back: String?
            when (settings.direction) {
                StudyDirection.RECOGNIZE -> {
                    prompt = card.headword
                    back = card.gloss(settings.nativeLanguage)
                }

                StudyDirection.RECALL -> {
                    prompt = card.gloss(settings.nativeLanguage) ?: card.headword
                    back = card.headword
                }
            }
            RememberCard(
                item = item,
                prompt = prompt,
                // 音标只在正面是外语词时有意义；正面是母语释义时它就是答案的一部分，不能剧透。
                ipa = if (settings.direction == StudyDirection.RECOGNIZE) card.ipa else null,
                gloss = back,
                example = card.example,
                photo = card.stickerPath?.let { decodeSampled(File(container.stickerDir, it), MAX_CARD_PX) },
                source = card.source.toReviewSource(),
            )
        }

        is ReviewItem.Event -> {
            val event = item.card
            RememberCard(
                item = item,
                // 正面只给线索（日期 + 照片），事件正文留在背面——否则翻面就不是回忆而是核对。
                prompt = container.appContext.getString(R.string.review_event_prompt),
                gloss = event.text,
                photo = event.photoPath?.let { decodeSampled(File(container.entryPhotoDir, it), MAX_CARD_PX) },
                source = event.source,
                dayLabel = formatDay(event.happenedAt, container.appContext),
            )
        }
    }

    private fun buildLookback(
        diary: DiaryDocument,
        lexicon: LexiconIndex,
        settings: AppSettings,
    ): LookbackUiState {
        val today = LocalDate.now().toString()
        val shown = diary.entries.sortedByDescending { it.takenAt }.take(TIMELINE_LIMIT)
        return LookbackUiState(
            entries = shown.map { entry ->
                EntryCard(
                    entry = entry,
                    photo = decodeSampled(File(container.entryPhotoDir, entry.photoPath), MAX_ENTRY_PX),
                    sticker = entry.objects
                        .firstOrNull { it.stickerPath != null }
                        ?.let { decodeSampled(File(container.entryPhotoDir, it.stickerPath!!), MAX_CARD_PX) },
                    words = entry.objects
                        .filter { it.layer == OverlayLayer.ITEM }
                        .map { cardWordFor(it, lexicon, settings) },
                )
            },
            todayCount = diary.entries.count { it.dayKey == today },
        )
    }

    /** 把存进日记的展示词回填成词典的音标与释义。词典里没有就只显示词本身。 */
    private fun cardWordFor(
        obj: EntryObject,
        lexicon: LexiconIndex,
        settings: AppSettings,
    ): CardWord {
        val entry: LexiconEntry? = obj.lexiconEntryId?.let(lexicon::byId)
            ?: lexicon.match(listOf(obj.word to 1f)).firstOrNull()?.entry
        val wordLang = entry?.words?.entries
            ?.firstOrNull { LexiconIndex.normalize(it.value) == LexiconIndex.normalize(obj.word) }
            ?.key
        return CardWord(
            text = obj.word,
            ipa = entry?.ipaFor(Lang.ENGLISH)?.takeIf { wordLang == null || wordLang == Lang.ENGLISH.tag },
            gloss = entry?.gloss(settings.nativeLanguage),
            languageTag = wordLang ?: entry?.primaryLanguage,
        )
    }

    // ── 照片解码 ────────────────────────────────────────────────────────────

    /** 时间轴会反复解码同一批照片；一个小 LRU 就够，滚动才不会掉帧。 */
    private val bitmapCache = object : LinkedHashMap<String, Bitmap?>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap?>) =
            size > BITMAP_CACHE_SIZE
    }

    private fun decodeSampled(file: File, maxPx: Int): Bitmap? {
        if (!file.isFile) return null
        val key = "$maxPx:${file.path}"
        synchronized(bitmapCache) { bitmapCache[key]?.let { return it } }
        val bitmap = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > maxPx * 2 || bounds.outHeight / sample > maxPx * 2) {
                sample *= 2
            }
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
        synchronized(bitmapCache) { bitmapCache[key] = bitmap }
        return bitmap
    }

    companion object {
        /** 时间轴一次最多解码这么多张——更早的历史还在，但不必此刻解码。 */
        private const val TIMELINE_LIMIT = 80

        private const val MAX_ENTRY_PX = 768
        private const val MAX_CARD_PX = 256
        private const val BITMAP_CACHE_SIZE = 64

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(container) }
        }
    }
}

private fun FsrsState.toScheduler(): Fsrs.State = Fsrs.State(
    difficulty = d,
    stability = s,
    due = due,
    lastReview = last,
    reviewCount = n,
    lapses = lapses,
)

/** 复习界面按来源强弱提示；只有云模型产物需要「请核对原文」。 */
private fun EntrySource.toReviewSource(): ReviewSource = when (this) {
    EntrySource.CLOUD -> ReviewSource.AI
    EntrySource.MANUAL, EntrySource.IMPORTED -> ReviewSource.USER
    EntrySource.ON_DEVICE -> ReviewSource.DERIVED
}
