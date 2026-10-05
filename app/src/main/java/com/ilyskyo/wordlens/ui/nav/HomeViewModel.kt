// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.nav

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ilyskyo.wordlens.R
import com.ilyskyo.wordlens.core.AppContainer
import com.ilyskyo.wordlens.data.model.EntryObject
import com.ilyskyo.wordlens.data.model.EntryMood
import com.ilyskyo.wordlens.data.model.EventCard
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
import com.ilyskyo.wordlens.ui.common.ByteLruCache
import com.ilyskyo.wordlens.ui.lookback.CardWord
import com.ilyskyo.wordlens.ui.lookback.EntryCard
import com.ilyskyo.wordlens.ui.lookback.LookbackUiState
import com.ilyskyo.wordlens.ui.lookback.DayGroup
import com.ilyskyo.wordlens.ui.lookback.EntryDetailState
import com.ilyskyo.wordlens.ui.lookback.ObjectPlace
import com.ilyskyo.wordlens.ui.lookback.dayLabel
import com.ilyskyo.wordlens.ui.lookback.formatDay
import com.ilyskyo.wordlens.ui.remember.RememberCard
import com.ilyskyo.wordlens.ui.remember.RememberUiState
import com.ilyskyo.wordlens.vision.camera.CameraFocusMath
import com.ilyskyo.wordlens.vision.camera.CameraFocusMath.NormBox
import com.ilyskyo.wordlens.vision.camera.PhotoDecoder
import com.ilyskyo.wordlens.widget.DueWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        /**
         * 本轮里评了「忘了」的张数。
         *
         * 完成页那个正确率读数必须说清它是**第一次就答对**的比例：一张卡先看背面再评「好」
         * 也算对，那样这个数字会一路逼近 100% 而毫无意义。这里只统计 AGAIN，
         * 因为 FSRS 的四档里只有 AGAIN 表示「没想起来」。
         */
        val missed: Int = 0,
        /** 本轮第一张卡被评的时刻；0 表示还没开始，免得空轮显示「用时 0 秒」。 */
        val startedAt: Long = 0L,
    )

    private val session = MutableStateFlow(Session())

    /** 翻面时刻，用于把停留时长写进复习日志（FSRS 日后调参的原料）。 */
    private var revealedAt = 0L

    /** 串行化评级，连点两下不会交错读改写；仓库层的锁只管它自己的文件。 */
    private val gradeLock = Mutex()

    // ── 回看 ────────────────────────────────────────────────────────────────

    /** 日历选中的那一天。null = 不筛。放在 VM 里，转屏与去详情页再回来都不会丢。 */
    private val _dayFilter = MutableStateFlow<String?>(null)

    val lookback: StateFlow<LookbackUiState> = combine(
        container.diary.document,
        container.lexicon.index,
        settingsFlow,
        _dayFilter,
    ) { diary, lexicon, settings, dayFilter -> buildLookback(diary, lexicon, settings, dayFilter) }
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

    // ── 时间轴多选 ────────────────────────────────────────────────────────

    private val _selected = MutableStateFlow<Set<String>>(emptySet())

    /** 随机漫步最近去过哪几天。只在内存里——它是「别连着重复」，不是用户数据。 */
    private val walked = ArrayDeque<String>()

    /** 非空即处于多选模式。UI 与返回键都只看这一个集合，不再另存一个布尔。 */
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    /** 长按卡片：空集合时进入多选并选中它，之后每次长按切换一项。 */
    fun onLongPressEntry(id: String) {
        _selected.update { current ->
            when {
                current.isEmpty() -> setOf(id)
                id in current -> current - id
                else -> current + id
            }
        }
    }

    fun onSelectAllEntries() {
        _selected.value = lookback.value.groups.flatMap { group -> group.cards.map { it.entry.id } }.toSet()
    }

    fun onClearSelection() {
        _selected.value = emptySet()
    }

    /**
     * 批量删除选中的条目。不可逆，所以调用方必须先二次确认。
     *
     * 贴纸副本的取舍：`entries/` 下那份随条目一起删；`stickers/` 下同名那份只有在没有词卡
     * 引用它时才删——词卡可能活得比这条日记久。
     */
    fun onDeleteSelected() {
        val ids = _selected.value
        _selected.value = emptySet()
        onDeleteEntries(ids)
    }

    /**
     * 删掉这些日记与它们的文件。多选删除与详情页删除共用这一条。
     *
     * 贴纸副本的取舍：`entries/` 下那份随条目一起删；`stickers/` 下同名那份只有在没有词卡
     * 引用它时才删——词卡可能活得比这条日记久。
     */
    fun onDeleteEntries(ids: Set<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { id ->
                container.diary.deleteEntry(id).forEach { name ->
                    if (!container.deck.referencesSticker(name)) {
                        runCatching { File(container.stickerDir, name).delete() }
                    }
                }
            }
            DueWidgetProvider.refresh(container.appContext)
        }
    }

    /**
     * 随机漫步：跳回任意一个过去的日子。
     *
     * 排除今天（今天没什么可「回到」的），并记住最近几步去过哪天——真随机会连着三次
     * 落在同一天，那读起来像坏了而不是随机。
     *
     * @return 选中条目的 id；没有可去的过去时返回 null，由界面决定怎么告诉用户。
     */
    fun onRandomWalk(): String? {
        val today = LocalDate.now().toString()
        val candidates = container.diary.document.value.entries
            .filter { it.dayKey != today && it.dayKey !in walked }
        val pick = candidates.randomOrNull() ?: run {
            _notice.value = container.appContext.getString(R.string.walk_empty)
            return null
        }
        walked.addLast(pick.dayKey)
        while (walked.size > WALK_MEMORY) walked.removeFirst()
        return pick.id
    }

    // ── 条目详情 ────────────────────────────────────────────────────────────

    private val selectedEntryId = MutableStateFlow<String?>(null)

    /** 详情页「补一句」的草稿放在 VM 里，转屏不会丢。 */
    private val eventDraft = MutableStateFlow("")

    val detail: StateFlow<EntryDetailState?> = combine(
        container.diary.document,
        container.lexicon.index,
        settingsFlow,
        selectedEntryId,
        eventDraft,
    ) { diary, lexicon, settings, id, draft -> buildDetail(diary, lexicon, settings, id, draft) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 只缓存上一次那张：详情页一次只看一条，而 combine 会随输入逐字重算。 */
    private var lastDecoded: Pair<String, PhotoDecoder.UprightImage?>? = null

    // ── 回调 ────────────────────────────────────────────────────────────────

    fun onMaterialChange(material: StudyMaterial) {
        // 换素材就是开始新的一轮：计数、用时与正确率全部归零，
        // 否则「本轮」会同时统计两件不同的事。
        session.update { Session(material = material) }
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
            session.update { current ->
                current.copy(
                    revealed = false,
                    done = current.done + 1,
                    missed = current.missed + if (rating == Fsrs.Rating.AGAIN) 1 else 0,
                    startedAt = if (current.startedAt == 0L) now else current.startedAt,
                )
            }
            // 桌面上那个数得跟着变。系统刷新最快 30 分钟一次，对一个「还剩几个」的读数没意义。
            DueWidgetProvider.refresh(container.appContext)
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
            DueWidgetProvider.refresh(container.appContext)
        }
    }

    /** 取消归档。id 可能来自牌组也可能来自日记，两边各查一次。 */
    fun onUnmark(id: String) {
        viewModelScope.launch {
            gradeLock.withLock {
                container.deck.card(id)?.let { container.deck.update(it.copy(mastered = false)) }
                container.diary.event(id)?.let { container.diary.updateEvent(it.copy(mastered = false)) }
            }
            DueWidgetProvider.refresh(container.appContext)
        }
    }

    /**
     * 一次性低调提示。见 [com.ilyskyo.wordlens.ui.components.NoticeHost] 为什么这里不用 Toast。
     *
     * 只放「用户刚才那一下没成功」这一类信息。可成功的反馈不该占用它：那是动效的工作。
     */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** 月历点某一天 = 只看那一天；再点一次已选中的那天（或「显示全部」）取消。 */
    fun onPickDay(dayKey: String?) {
        _dayFilter.update { if (dayKey == it) null else dayKey }
    }

    /**
     * 把一句话投递到主页这条会自己消失的提示层。
     *
     * 存在的理由很具体：相册导入完成后取景页就关掉了，而「照片存下了、但这次没认出词」
     * 正是**那一刻之后**才需要说的一句话——取景页自己的 NoticeHost 随场景一起被拆掉，
     * 在那里开口等于什么都没说。复用同一条 notice 通路，而不是再造一个「跨页提示」的状态。
     */
    fun showNotice(message: String) {
        _notice.value = message
    }

    fun acknowledgeNotice() {
        _notice.value = null
    }

    /**
     * 发音。**点了必须有回声**：`speak()` 返回 false 意味着这台机器现在念不出来，
     * 而静默无声是最容易被当成「App 坏了」的一种失败——尤其这个产品把发音做成了一颗
     * 56dp 的大按钮。
     *
     * 两种失败分开说：语言在装好的引擎里根本没有对应语音（该去装引擎），
     * 以及引擎在但这次没出声（重试或换引擎）。前者是可以改善的长期状态，
     * 后者是一次性的，把两者混成一句「无法发音」等于什么也没告诉用户。
     */
    fun onSpeak() {
        val card = (remember.value.current?.item as? ReviewItem.Word)?.card ?: return
        val lang = card.headLang() ?: settingsFlow.value.targetLanguage
        speakOrNotify(card.headword, lang)
    }

    /** 回看页某张卡片的发音：读第一个物品词。 */
    fun onEntrySpeak(card: EntryCard) {
        val word = card.words.firstOrNull { it.text.isNotBlank() } ?: return
        val lang = Lang.fromAnyTag(word.languageTag ?: "en") ?: settingsFlow.value.targetLanguage
        speakOrNotify(word.text, lang)
    }

    /**
     * 详情页的编辑保存。只改用户能直接看见的三项，其余字段原样写回。
     *
     * `summarySource` 必须跟着改：用户重写过的那句话就不再是机器整理的，如果继续顶着
     * AI 标记，界面上会出现「这句我写的，它说是模型写的」——而 §8.1 要求的正是
     * 「机器生成的东西必须能被认出来」，反向误标同样是破坏这条约定。
     */
    fun onSaveEditing(title: String, summary: String, mood: EntryMood?) {
        val current = detail.value?.entry ?: return
        val trimmedSummary = summary.trim()
        viewModelScope.launch {
            gradeLock.withLock {
                container.diary.updateEntry(
                    current.copy(
                        title = title.trim().takeIf { it.isNotEmpty() },
                        summary = trimmedSummary.takeIf { it.isNotEmpty() },
                        mood = mood,
                        summarySource = if (trimmedSummary.isEmpty() || trimmedSummary == current.summary) {
                            current.summarySource
                        } else {
                            EntrySource.MANUAL
                        },
                    ),
                )
            }
        }
    }

    private fun speakOrNotify(text: String, lang: Lang) {
        if (container.speaker.speak(text, lang)) return
        val missing = lang in container.speaker.unsupportedLanguages.value
        val resId = if (missing) R.string.notice_tts_unsupported else R.string.notice_tts_silent
        _notice.value = container.appContext.getString(resId, lang.nativeName)
    }

    fun onOpenEntry(id: String) {
        eventDraft.value = ""
        selectedEntryId.value = id
    }

    fun onCloseEntry() {
        selectedEntryId.value = null
    }

    fun onEventDraftChange(text: String) {
        eventDraft.value = text
    }

    /**
     * 「补一句当时发生了什么」→ 一条 `source = USER` 的事件卡。
     *
     * 这是事件卡唯一的诞生地（说明书 §4.2 的第三种落库）。写下来的这句话和照片同一天、
     * 同一条目，复习时它按自己的间隔回来——背单词 App 给不了这种「回放某天」的训练。
     */
    fun onSaveEvent() {
        val id = selectedEntryId.value ?: return
        val text = eventDraft.value.trim()
        if (text.isEmpty()) return
        val entry = container.diary.document.value.entries.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            container.diary.addEvent(
                EventCard(
                    id = EventCard.newId(),
                    text = text,
                    happenedAt = entry.takenAt,
                    entryId = entry.id,
                    // 缩略图直接引用条目照片：同一个文件，不复制第二份。
                    photoPath = entry.photoPath,
                    source = ReviewSource.USER,
                ),
            )
            eventDraft.value = ""
        }
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
            // 卡堆只需要知道「后面还有几张」，不需要知道内容：
            // 提前把下一张的词露出来会直接毁掉自由回忆这件事，而复习的全部价值就在这里。
            upcoming = (queue.size - 1).coerceAtLeast(0),
            sessionSeconds = if (current.startedAt == 0L) 0 else ((now - current.startedAt) / 1000L).toInt(),
            firstTryAccuracy = if (current.done == 0) 1f else
                (current.done - current.missed).toFloat() / current.done,
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
        dayFilter: String?,
    ): LookbackUiState {
        val today = LocalDate.now().toString()
        val allDays = diary.entries.map { it.dayKey }.toSet()
        // 先筛再解码：筛到某一天之后没必要把另外几十张的位图都解一遍。
        val shown = diary.entries
            .filter { dayFilter == null || it.dayKey == dayFilter }
            .sortedByDescending { it.takenAt }
            .take(TIMELINE_LIMIT)
        val cards = shown.map { entry ->
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
        }
        // 输入已经按时间倒序，groupBy 保序，所以分组天与组内卡片都是「新的在上」。
        // 分组键直接用条目自己的 dayKey：日历筛选用的也是这个键，两处各算一遍的话，
        // 跨零点或跨夏令时的时候会出现「筛得到、却分不进任何一组」的空列表。
        val groups = cards
            .groupBy { it.entry.dayKey }
            .map { (day, groupCards) ->
                DayGroup(day, dayLabel(groupCards.first().entry.takenAt, container.appContext), groupCards)
            }
        return LookbackUiState(
            groups = groups,
            todayCount = diary.entries.count { it.dayKey == today },
            selectedDay = dayFilter,
            daysWithEntries = allDays,
        )
    }

    /**
     * 组装详情页：把存进日记的四角坐标重新长回这张照片上。
     *
     * `EntryObject` 的框归一化在**传感器坐标系**里，而界面上显示的是按 EXIF 转正后的位图，
     * 所以中间必须过一次 `imageBoxFromSensorNorm`。这一步不能省：省略之后竖持拍的照片里，
     * 词会压在错的位置上——而这正是「场景式」与普通日记的分界线，错了就等于没有。
     */
    private fun buildDetail(
        diary: DiaryDocument,
        lexicon: LexiconIndex,
        settings: AppSettings,
        entryId: String?,
        draft: String,
    ): EntryDetailState? {
        val entry = entryId?.let { id -> diary.entries.firstOrNull { it.id == id } } ?: return null
        val file = File(container.entryPhotoDir, entry.photoPath)
        val decoded = decodedDetailPhoto(file)
        val degrees = decoded?.exifDegrees ?: 0
        return EntryDetailState(
            entry = entry,
            photo = decoded?.bitmap,
            objects = entry.objects
                .filter { it.layer == OverlayLayer.ITEM }
                .map { obj ->
                    ObjectPlace(
                        id = obj.id,
                        // 正面显示的是记录当时的那个词，释义只在被词典命中时补上。
                        word = obj.word,
                        gloss = obj.lexiconEntryId?.let(lexicon::byId)?.gloss(settings.nativeLanguage),
                        box = CameraFocusMath.imageBoxFromSensorNorm(
                            NormBox(obj.left, obj.top, obj.right, obj.bottom),
                            degrees,
                        ),
                        hasSticker = obj.stickerPath != null,
                    )
                },
            ambience = entry.ambience,
            moodLabel = entry.mood?.let { container.appContext.getString(it.labelRes) },
            eventDraft = draft,
            eventCount = diary.events.count { it.entryId == entry.id },
        )
    }

    private fun decodedDetailPhoto(file: File): PhotoDecoder.UprightImage? {
        lastDecoded?.takeIf { it.first == file.path }?.let { return it.second }
        val decoded = PhotoDecoder.decodeUpright(file, MAX_DETAIL_PX)
        lastDecoded = file.path to decoded
        return decoded
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

    /**
     * 时间轴会反复解码同一批照片，所以要缓存；但缓存必须按**字节**封顶。
     *
     * 按条数封顶是原来这里的做法（`size > 24` 之类），而一张 640px 的 ARGB 位图是 1.6MB，
     * 一张 256px 的贴纸是 0.25MB——同样 24 条，实际占用能差 6 倍。真正会 OOM 的是字节，
     * 所以上限取「应用堆的 1/4」：再多就会把 Compose 自己的绘制与解码峰值挤到没有余地。
     */
    private val bitmapCache = ByteLruCache<String, Bitmap>(
        maxBytes = Runtime.getRuntime().maxMemory() / 4,
        sizeOf = { bitmap -> bitmap.byteCount },
    )

    private fun decodeSampled(file: File, maxPx: Int): Bitmap? {
        if (!file.isFile) return null
        val key = "$maxPx:${file.path}"
        bitmapCache.get(key)?.let { return it }
        // 走 PhotoDecoder 而不是裸 BitmapFactory：CameraX 竖持写出的 JPEG 像素网格是横的，
        // 方向只存在 EXIF 里，而 BitmapFactory 不读 EXIF——不转正的话时间轴上的照片整张躺倒，
        // 且不报任何错。
        val bitmap = PhotoDecoder.decodeUpright(file, maxPx)?.bitmap
        // 解码失败**不进缓存**：负缓存会让一次临时的 IO 抖动在剩下的会话里都显示成空白。
        if (bitmap != null) bitmapCache.put(key, bitmap)
        return bitmap
    }

    companion object {
        /**
         * 时间轴一次最多解码这么多张——更早的历史还在，但不必此刻解码。
         *
         * 40 而不是 80：这一屏之上最多同时看到三五张，多出来的解码只是把堆吃光，
         * 换来的是「往上滚的时候不用等」。滚过 40 张之后再往上，本来就需要重新解码。
         */
        private const val TIMELINE_LIMIT = 40

        /** 时间轴缩略图的长边。640 而不是 768：卡片在屏幕上最大也就 380dp，2.5 倍余量足够。 */
        private const val MAX_ENTRY_PX = 640
        private const val MAX_CARD_PX = 256

        /** 详情页只有全屏一张图，可以解得比时间轴大得多——词框要压在真实细节上。 */
        private const val MAX_DETAIL_PX = 1440

        /** 随机漫步的去重窗口：连着八次不重复同一天，够打破「刚去过又回来」的错觉。 */
        private const val WALK_MEMORY = 8

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
