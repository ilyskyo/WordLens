// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.nav

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ilyskyo.wordlens.R
import com.ilyskyo.wordlens.core.AppContainer
import com.ilyskyo.wordlens.core.voice.PendingTake
import com.ilyskyo.wordlens.core.voice.TakeEvent
import com.ilyskyo.wordlens.core.voice.TakeFile
import com.ilyskyo.wordlens.core.voice.TakeNotice
import com.ilyskyo.wordlens.core.voice.TakePhase
import com.ilyskyo.wordlens.core.voice.TakeStart
import com.ilyskyo.wordlens.core.voice.VoiceMemo
import com.ilyskyo.wordlens.core.voice.VoiceRecorder
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
import com.ilyskyo.wordlens.ui.lookback.dayKeyLabel
import com.ilyskyo.wordlens.ui.components.Notice
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
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong

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

    /** 素材筛选 + 是否翻面 + 本轮答过的张与第一次答错的张，合成一个流，让 combine 的入参控制在 5 个。 */
    private data class Session(
        val material: StudyMaterial = StudyMaterial.WORDS_AND_EVENTS,
        val revealed: Boolean = false,
        /**
         * 此刻看着哪一面。与 [revealed] 是两件事：`revealed` 说的是「这一张这轮翻过没有」
         * （评级的闸门、答题时长的起算点），这一个说的是「现在给用户看的是正面还是背面」。
         *
         * 合成一个布尔用过的地方是这么想的：翻面是单向的，看完答案就该评级。真机上不是——
         * 读完释义想再核对一眼那个词怎么拼，是复习里最常见的一个动作，而单向布尔让它点不动。
         */
        val showingAnswer: Boolean = false,
        /**
         * 本轮**答过的张**（按卡 id 去重）。
         *
         * 用集合而不是计数器，是因为队列不是一次性快照：`buildRemember` 每次输入变化都按
         * `now >= due` 重算，而评为「忘了」的卡下一次到期是一分钟后——只要用户还在这一轮里，
         * 它就会**重新排进来**。按次数计会得到两个错的读数：「已完成 6」把同一张卡数了两次，
         * 而 `total = done + queue.size` 里它又在队列中出现一次，于是进度条会在答完一张之后
         * 往回退一格。
         */
        val attempted: Set<String> = emptySet(),
        /**
         * 本轮里**第一次**就评了「忘了」的张数。
         *
         * 完成页那个读数说的是「第一次就答对」的比例，所以分子分母都必须是张而不是次数：
         * 一张卡先「忘了」再在同一个一分钟后答对，它对「一次答对率」的贡献是 0/1，
         * 而不是 1/2。按次数算的话这个数字会一路逼近 100% 而毫无意义——
         * 重复答错的那几张恰恰是最该被看到的。FSRS 四档里只有 AGAIN 表示「没想起来」。
         */
        val firstMissed: Int = 0,
        /**
         * 本轮第一次翻面的时刻，单位是 `SystemClock.elapsedRealtime()`；0 表示还没开始
         * （空轮不该显示「用时 0 秒」）。
         *
         * 两件事都写在名字里：起算点是**翻面**而不是第一次评级——一轮真正开始于用户开始答题
         * 那一刻，而第一张往往是想得最久的一张，从第一次评级起算会系统性地把最长那段剪掉；
         * 用单调钟而不是墙钟，是因为一次中途的自动校时就能把用时推成负数，而那个数会被
         * 当成本轮成绩读出来。
         */
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

    /**
     * 时间轴解码到这里为止的上限，滚到底之后按「载入更早」往上加。
     *
     * 加上限而不是「按偏移翻页」：日记是会变的（删一条、导一批、筛一天），偏移式分页在数据变化后
     * 要么把同一条显示两次、要么整段跳过；而「本次会话最多解码到第 N 条」这一个数，
     * 无论中间怎么删怎么筛，都只可能重解已经解过的，不会漏也不会重。
     */
    private val timelineLimit = MutableStateFlow(TIMELINE_LIMIT)

    val lookback: StateFlow<LookbackUiState> = combine(
        container.diary.document,
        container.lexicon.index,
        settingsFlow,
        _dayFilter,
        timelineLimit,
    ) { diary, lexicon, settings, dayFilter, limit -> buildLookback(diary, lexicon, settings, dayFilter, limit) }
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
     *
     * 语音那份由 `diary.deleteEntry` 连带删掉，不在这里做：名字按条目 id 定死，
     * 所以「被收下的那段」与「还没收下的那段」在磁盘上是同一个路径，一个分支就够。
     */
    fun onDeleteEntries(ids: Set<String>) {
        if (ids.isEmpty()) return
        // 现场那一段如果正长在要删的条目上，先放掉麦克风并忘掉它。文件不在这里删：
        // deleteEntry 删的就是同一个名字（按条目 id 定死），两处删同一个文件等于承认「删除有两条通路」。
        _take.value?.takeIf { it.entryId in ids }?.let {
            releaseRecorder()
            _take.value = null
        }
        // 选中的那一批里如果被删掉了任何一个，那个 id 必须跟着走：多选模式的判据就是「集合非空」，
        // 于是留下一个已经不存在的 id，界面上就是一个指着空气的「已选 1 项」，而那颗删除键
        // 按下去什么文件都不会少——读起来像按钮坏了。走得通的一步：长按卡片进入多选，
        // 再长按顶部页签随机漫步到另一条、在详情页删掉它、返回，多选模式原样还在。
        // 剪在这里而不是各调用点自觉处理，因为删除有两条通路（多选、详情页），而漏一条不会报错。
        _selected.value = _selected.value - ids
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
        val pool = container.diary.document.value.entries.filter { it.dayKey != today }
        // 排除最近去过的那几天是为了「别连着重复」，而不是「那几天从此去不了」。
        // 只有少数几天过去时，两种做法分得出来：一个用户只有 20 月 3 号那一个过去的日子，
        // 第二次按随机漫步就会听到一句「日记还太空」——他的日记不空，他只是真的只有那一天。
        // 一句在这种情形下为假的说明，比连着两次落在同一天糟得多：后者只是无趣，前者是撒谎。
        val candidates = pool.filterNot { it.dayKey in walked }.ifEmpty { pool }
        val pick = candidates.randomOrNull() ?: run {
            emit(container.appContext.getString(R.string.walk_empty))
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

    /**
     * 现场那一段录音，null 表示没有那一段。
     *
     * 它是**会话状态而不是数据**：一段还没被收下的声音不属于任何一条日记，也不该进 diary.json，
     * 但它必须活得比这一页的界面长——转屏会拆掉组合，而用户不该因为手机转了一下就丢掉刚录的十秒。
     * 所以放在 VM 里，而不是 `remember` 里。
     */
    private val _take = MutableStateFlow<PendingTake?>(null)

    /**
     * 拿着麦克风的那台录音机。
     *
     * 交还麦克风的四个时机都在这个类里，少一个都会留下「界面已经没了而麦克风还拿着」的状态：
     * 用户按停、退到后台、离开这一条、VM 被清掉。每一条都走 [releaseRecorder]，不各自 release。
     */
    private var recorder: VoiceRecorder? = null

    /**
     * 详情页在看哪一条、草稿是什么、现场有没有那一段——三样合成一路再进 combine。
     *
     * 不是为了好看：`combine` 的强类型入参到五个为止，而这段的输入正好六个。把它们折成一个
     * 「界面焦点」反而更贴近事实——这三样说的是同一件事：用户此刻对着哪一条在做什么。
     */
    private val detailFocus = combine(selectedEntryId, eventDraft, _take) { id, draft, take ->
        DetailFocus(id, draft, take)
    }

    private data class DetailFocus(val entryId: String?, val draft: String, val take: PendingTake?)

    val detail: StateFlow<EntryDetailState?> = combine(
        container.diary.document,
        container.lexicon.index,
        settingsFlow,
        detailFocus,
    ) { diary, lexicon, settings, focus -> buildDetail(diary, lexicon, settings, focus) }
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

    /**
     * 点卡片。**没翻过就翻开，已经翻过就在两面之间来回。**
     *
     * 原来这里直接接的是 [onReveal]，而它第一行是 `if (revealed) return`：翻到背面之后
     * 这张卡就再也点不动了，想回头看一眼正面只能先把答案评掉。来回翻不重置任何读数——
     * 见 [onReveal] 里那段关于计时起点的说明。
     */
    fun onCardTap() {
        if (!session.value.revealed) {
            onReveal()
            return
        }
        session.update { it.copy(showingAnswer = !it.showingAnswer) }
    }

    private fun onReveal() {
        if (session.value.revealed) return
        // 单调钟：墙钟在这一轮中间被人调一下（自动校时开关、时区、NTP 步进），「这题想了多久」
        // 就会变成负数或几十小时，而那个数是**写进复习日志**的——它不会当场报错，只在几个月后的
        // 间隔里露出来。同一件事 `core/RetryGate.kt` 与语音那顆秒表都论证过一次。
        revealedAt = SystemClock.elapsedRealtime()
        // 翻开一次就等于「这是用户要重新回答的一张卡」，重复评级的闸门随之放开：
        // 评级按钮只有翻面之后才可按（enabled = revealed），所以任何一次合法的第二评，
        // 中间必然经过一次新的 reveal。用它当释放点，不用往队列上挂额外的收集器。
        gradedKey = null
        session.update {
            it.copy(
                revealed = true,
                showingAnswer = true,
                startedAt = if (it.startedAt == 0L) revealedAt else it.startedAt,
            )
        }
    }

    /**
     * 已经受理、但还没从队列里消失的那一张。
     *
     * `gradeLock` 只保证两次写不交错，不保证同一张卡不被评两次：连点两下按钮时，两次
     * `remember.value.current` 读到的都是同一张（流的更新要等磁盘写完之后才回来），于是
     * 同一个词被 FSRS 连着推进两次、done 多算一次、复习日志里多一条凭空的记录，而界面上
     * 只少了一张卡——没人会看出中间多跑了一次调度。
     */
    private var gradedKey: String? = null

    fun onGrade(rating: Fsrs.Rating) {
        val item = remember.value.current?.item ?: return
        val key = when (item) {
            is ReviewItem.Word -> "w:" + item.card.id
            is ReviewItem.Event -> "e:" + item.card.id
        }
        if (key == gradedKey) return
        gradedKey = key
        val now = System.currentTimeMillis()
        // 时长取自单调钟与单调钟之差；`now` 仍然是墙钟，因为它是要落盘的那次复习**时刻**。
        // 两者混用是这个文件里最容易写错的一格：把墙钟当时长用，一次校时就是一笔永久坏数据。
        val elapsed = if (revealedAt > 0L) {
            (SystemClock.elapsedRealtime() - revealedAt).coerceAtLeast(0L)
        } else {
            0L
        }
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
                val firstThisRound = key !in current.attempted
                current.copy(
                    revealed = false,
                    showingAnswer = false,
                    attempted = current.attempted + key,
                    firstMissed = current.firstMissed +
                        if (firstThisRound && rating == Fsrs.Rating.AGAIN) 1 else 0,
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
     *
     * 装的是 [Notice]（文案 + 第几次）而不是裸文案：`StateFlow` 按值相等去重，同一句话连着来两次
     * 会被下游当成一次变化，于是提示条只缩短不重播。连点两次发音正是这一格。
     */
    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    /** 每一次开口都是新的一次，与文案是否相同无关。 */
    private val noticeSeq = AtomicLong()

    private fun emit(message: String) {
        _notice.value = Notice(message, noticeSeq.incrementAndGet())
    }

    /**
     * 刚存下的记录必须当场看得见。
     *
     * 时间轴正筛着某一天时存进一条新记录：新记录属于「今天」，不在筛选结果里，于是取景页
     * 关掉、列表一模一样——界面既没说「存下了」，也没说「你现在筛着别的日子」。
     * 这个坑是日历筛选自带的（快门也会踩），相册导入只是让它更容易碰到：导入的照片
     * 本来就常常是别的日子的。
     *
     * 放开筛选必须同时说一句：不通知就改动用户自己设的条件，是另一种静默。
     */
    fun revealSavedEntry(dayKey: String) {
        val filter = _dayFilter.value ?: return
        if (filter == dayKey) return
        _dayFilter.value = null
        emit(
            container.appContext.getString(
                R.string.notice_filter_cleared,
                dayKeyLabel(dayKey, container.appContext),
            ),
        )
    }

    /** 月历点某一天 = 只看那一天；再点一次已选中的那天（或「显示全部」）取消。 */
    fun onPickDay(dayKey: String?) {
        _dayFilter.update { if (dayKey == it) null else dayKey }
    }

    /** 载入更早的记录。解码仍然在 Default 线程，且每次只多 [TIMELINE_PAGE] 张。 */
    fun onLoadOlder() {
        timelineLimit.update { it + TIMELINE_PAGE }
    }

    /**
     * 把一句话投递到主页这条会自己消失的提示层。
     *
     * 存在的理由很具体：相册导入完成后取景页就关掉了，而「照片存下了、但这次没认出词」
     * 正是**那一刻之后**才需要说的一句话——取景页自己的 NoticeHost 随场景一起被拆掉，
     * 在那里开口等于什么都没说。复用同一条 notice 通路，而不是再造一个「跨页提示」的状态。
     */
    fun showNotice(message: String) {
        emit(message)
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
        emit(container.appContext.getString(resId, lang.nativeName))
    }

    /**
     * 进程被杀再回来时，详情页那一格还欠着装载。
     *
     * 页面栈跨进程死亡能恢复，ViewModel 不能——恢复出来的栈里有一条
     * `Detail(entryId)`，而 `selectedEntryId` 是空的，于是用户看到自己离开时那一页的空壳。
     * 只在真的对不上时才重开：同一条重复装载会白解一次 1440px 的图。
     */
    fun ensureEntryShown(id: String) {
        if (selectedEntryId.value != id) onOpenEntry(id)
    }

    fun onOpenEntry(id: String) {
        eventDraft.value = ""
        selectedEntryId.value = id
    }

    fun onCloseEntry() {
        // 离开这一条 = 现场那段交还给用户稍后决定：麦克风必须当场松手，文件留着。
        // 放在这里而不是界面的 onDispose 里，是因为转屏也会拆掉组合而 VM 不死——在 onDispose
        // 里停的话，「转一下手机就把正在录的声音停了」，而那件事用户完全没做过。
        sealTake(TakeEvent.HAND_OFF)
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
        // 草稿在按下这一刻就交出去，而不是等 addEvent 回来。事件卡的 id 每次都是新造的，
        // 所以仓库那头的按 id 判重**挡不住**连点两下：两次提交是两个不同的 id、同一句话，
        // 复习队列里从此多出一句一模一样的话，各自按各自的间隔回来。
        // 清掉之后输入框当场空了——那 also 是「记下了」该有的即时反馈。
        eventDraft.value = ""
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
        }
    }

    // ── 语音日记附件 ──────────────────────────────────────────────────

    /**
     * 按下「录一段」。界面已经按 [VoiceMemo.actionFor] 的结论确认过可以动手（权限到手，
     * 要覆盖旧的那一段也已经确认过），这里仍然重问四道边界：现场有没有别的一段、条目还在不在、
     * 旧的录音是不是得先没掉、以及**开录成功那一刻用户还在不在这一条上**（最后那道只能在
     * `start()` 回来之后问，见下面那段注释）。
     *
     * 最后那道尤其要紧：MediaRecorder 对着一个已经存在的路径是**截断重写**，所以「旧的先删掉」
     * 必须发生在开录之前——而让它合法的只有界面上那一次确认。这也是 [TakeAction.REPLACE] 在
     * [VoiceMemo.actionFor] 里排在权限之后的原因：先问权限，问不到就什么都还没动过。
     */
    fun onStartTake(entryId: String) {
        val pending = _take.value
        when {
            // 另一条上那段没收下的声音不能占着位置一直等：这一按把它顶掉。不静默处理——
            // 那是一段用户说过、但还没决定要不要留下的话。
            pending != null && pending.entryId != entryId -> clearTake(TakeEvent.SUPERSEDED)

            // 同一条上还挂着一段没收下的：先处理那一段再录第二段。这种情况界面上画的是那一段的
            // 控件而不是录音键，走到这里已经是异常，所以说一句而不是默默顶掉自己。
            pending != null -> showNotice(container.appContext.getString(R.string.notice_audio_take_pending))
        }
        if (_take.value != null) return
        val file = File(container.audioDir, VoiceMemo.fileName(entryId))
        viewModelScope.launch {
            val entry = container.diary.document.value.entries.firstOrNull { it.id == entryId }
                ?: return@launch
            if (entry.audioPath != null) container.diary.detachAudio(entryId)
            val handle = VoiceRecorder(container.appContext, file) { source, event ->
                onRecorderEvent(source, event)
            }
            // 先登记再开录：录音机在 start 里就能异步报回一个错误，而那条消息是排到主线程队列上的，
            // 可能比我们这里赋值更早到。没登记的话它会被当成「迟到的事件」丢掉，结果界面上留着一段
            // 永远在录、而录音机已经死了的状态。
            recorder = handle
            // 在 IO 上开录：prepare() 要碰文件系统，而 VOICE_RECOGNITION 那条链在部分机型上要
            // 建立一次音频通路——那几十毫秒足够让主线程在按下去那一刻掉一帧。
            val outcome = withContext(Dispatchers.IO) { handle.start() }
            if (outcome != TakeStart.OK) {
                releaseRecorder()
                reportTake(if (outcome == TakeStart.IN_USE) TakeNotice.IN_USE else TakeNotice.FAILED)
                return@launch
            }
            if (selectedEntryId.value != entryId) {
                // 那几十毫秒里用户已经离开了这一条。离开那一步（sealTake/HAND_OFF）当时找不到
                // 「那一段」可封——`_take` 还没登记——所以没有任何界面会来按停下，而这一头已经把
                // 麦克风拿住了：不拦的话它会一直录到 90 秒上限，期间磁盘上在写一段没人看住的人声，
                // 而这正是 HAND_OFF 那条路要防的事。当场还回去，半截的文件交给仓库删。
                releaseRecorder()
                container.diary.discardTake(entryId)
                return@launch
            }
            _take.value = PendingTake(entryId, TakePhase.RECORDING, handle.startedAtElapsed)
        }
    }

    /** 用户按「停下」：当场松手，把那一段留在磁盘上等他决定。 */
    fun onStopTake() {
        sealTake(TakeEvent.STOPPED)
    }

    /**
     * 退到后台或离开这一条。
     *
     * 与按停走同一条路，只是必须说一句：不说的话，那颗秒表是无声无息停下来的，
     * 用户读到的是「界面坏了」，而不是「录音被打断了」。
     */
    fun onTakeHandedOff() {
        sealTake(TakeEvent.HAND_OFF)
    }

    /**
     * 结束一次录制：交还麦克风，然后把那一段留在磁盘上等用户决定。
     *
     * 顺序是**先改状态、后收尾**，这两个动作之间差几十毫秒，而谁先谁后决定了这段时间里界面上
     * 是什么。先把阶段推到 STAGED（时长还是 null），录音键就被那一段的控件换掉，而收下与丢弃
     * 在时长未知时都是灰的——于是没有人能对着一个还没写完的文件做动作。反过来先阻塞收尾，
     * 界面会继续显示「正在录」几十毫秒，而麦克风其实早就还回去了。
     */
    private fun sealTake(event: TakeEvent) {
        val take = _take.value ?: return
        val next = VoiceMemo.transition(take.phase, event) ?: return
        val handle = recorder
        recorder = null
        if (handle == null) {
            // 没有录音机可收尾，就说明「那一段」其实不存在。这时候把阶段推到 STAGED 会得到一个
            // 卡住的界面：读数写「正在收尾…」，而「收下」在时长未知时是灰的（`onCommitTake`
            // 那头也拿不到 `stagedMs`），唯一还能按的是「丢弃」——等于让用户为了离开一个
            // 不存在的状态做一次删除决定。清掉它，并说一句，而不是让人自己猜。
            _take.value = null
            reportTake(TakeNotice.FAILED)
            return
        }
        _take.value = take.copy(phase = next.phase, stagedMs = null)
        next.notice?.let { reportTake(it) }
        viewModelScope.launch {
            val ms = withContext(Dispatchers.IO) { handle.finish() }
            // 「那半截不可信」不问阶段：这是收尾这一步对文件的事实判断，而阶段机管的是麦克风的
            // 归属。所以这里不走 transition，直接按废段处理——那一段从来没被谁同意留下过。
            if (ms < 0L || !VoiceMemo.isKeepable(ms)) {
                if (_take.value?.entryId == take.entryId) _take.value = null
                container.diary.discardTake(take.entryId)
                reportTake(if (ms < 0L) TakeNotice.FAILED else TakeNotice.TOO_SHORT)
                return@launch
            }
            // 只在「这还是那一段」的时候把时长填进去。期间用户可能已经把它丢掉、或者去录了
            // 别的一条；把状态盖回去会让一段已经被删掉的声音在界面上凭空复活。
            _take.value = _take.value
                ?.takeIf { it.entryId == take.entryId && it.stagedMs == null }
                ?.copy(stagedMs = ms)
        }
    }

    /** 用户收下那一段：让这条日记开始引用它。文件不搬家——它本来就长在最终名字上。 */
    fun onCommitTake(entryId: String) {
        val take = _take.value ?: return
        if (take.entryId != entryId) return
        // 还在收尾时不给收下：这时候连它有多长都不知道，而时长是播放条的一部分。
        val ms = take.stagedMs ?: return
        if (VoiceMemo.transition(take.phase, TakeEvent.COMMITTED) == null) return
        _take.value = null
        viewModelScope.launch {
            if (!container.diary.attachAudio(entryId, ms)) {
                // 条目不在了（在别处被删掉）。那一段声音也就没人引用，跟着清掉而不是留着，
                // 并且把这句话说出来——界面上刚刚什么都没变，是最容易被读成「没点上」的一种失败。
                container.diary.discardTake(entryId)
                reportTake(TakeNotice.FAILED)
            }
        }
    }

    /** 用户丢弃那一段。 */
    fun onDiscardTake() {
        clearTake(TakeEvent.DISCARDED)
    }

    /** 「删除录音」：字段与文件一起走。界面上那句话已经确认过一次了。 */
    fun onDetachAudio(entryId: String) {
        viewModelScope.launch { container.diary.detachAudio(entryId) }
    }

    /** 放不出声音必须说出来。这是这个功能里最像「坏了」的一种表现。 */
    fun onAudioPlaybackFailed() {
        showNotice(container.appContext.getString(R.string.notice_audio_playback_failed))
    }

    /**
     * 现场那一段不再有人接手：麦克风当场还回去，文件交给仓库删。
     *
     * 删文件不在这里做而要走 `diary.discardTake`，是因为「没被引用」这件事只有持有条目的那一方
     * 说得准——`audioPath` 正指着它的那一份是内容，不是残留。
     */
    private fun clearTake(event: TakeEvent) {
        val take = _take.value ?: return
        val next = VoiceMemo.transition(take.phase, event) ?: return
        if (take.phase == TakePhase.RECORDING) releaseRecorder()
        _take.value = null
        if (next.file == TakeFile.DELETE) {
            viewModelScope.launch { container.diary.discardTake(take.entryId) }
        }
        next.notice?.let { reportTake(it) }
    }

    /**
     * 录音机报回来的事。
     *
     * **只受理现在还拿着的那一台**报回来的：一台已经收手或被丢下的录音机，消息仍可能排在 Looper
     * 队列里晚一步送到（框架的监听器按 Looper 投递，release 不撤回已经排好的那条），
     * 而那时候磁盘上那一段可能已经完整可读了——跟着它去删文件，就是丢掉用户录好的一段声音。
     */
    private fun onRecorderEvent(source: VoiceRecorder, event: TakeEvent) {
        if (source !== recorder) return
        if (event == TakeEvent.LIMIT) sealTake(event) else clearTake(event)
    }

    /** 只交还麦克风，不决定文件的去留——那是调用方的事。 */
    private fun releaseRecorder() {
        val handle = recorder
        recorder = null
        handle?.abandon()
    }

    /**
     * 把判定的那一句翻成资源 id。
     *
     * 两种「录不成」分开说：一种用户可以自己做点什么（把通话挂掉），一种只是机器出了事。
     * 合成一句「录音失败」等于两种都没告诉用户接下来该怎么办。
     */
    private fun reportTake(notice: TakeNotice) {
        val resId = when (notice) {
            TakeNotice.LIMIT -> R.string.notice_audio_limit
            TakeNotice.HANDED_OFF -> R.string.notice_audio_handed_off
            TakeNotice.TOO_SHORT -> R.string.notice_audio_too_short
            TakeNotice.DISCARDED -> R.string.notice_audio_discarded
            TakeNotice.SUPERSEDED -> R.string.notice_audio_superseded
            TakeNotice.IN_USE -> R.string.notice_mic_busy
            TakeNotice.FAILED -> R.string.notice_audio_failed
        }
        showNotice(container.appContext.getString(resId))
    }

    /**
     * VM 被清掉意味着不会再有人回到这一页收那一段，所以这里**不**走「停完留下等决定」：
     * 认真 stop() 得到的是一份谁也不引用的完整文件，而那正是这个功能要避开的东西。
     * 直接丢下（麦克风当场归还），那份没写完的交给下次启动的清扫删掉。
     */
    override fun onCleared() {
        releaseRecorder()
        _take.value = null
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

        // 两面同字的卡不是一张「难的卡」，而是一张**没有背面**的卡：翻面这个动作在它身上
        // 不产生任何信息，评级也就无从谈起。它们来自一段已经被修好的设置——母语与目标语
        // 曾被选成同一门，那时铸下的卡词头写的就是母语，所以修好方向也修不回卡上的字。
        // 只能在这里挡住；但必须把数量说出来，不能让用户以为队列空了。
        var skippedSameFace = 0
        val queue = buildList {
            if (current.material != StudyMaterial.EVENTS) {
                val due = deck.cards.filter { card ->
                    // 已掌握的卡彻底不出现在队列里——它靠手动归档，不靠 EASY 的长间隔。
                    val state = card.state(direction)
                    !card.mastered && (state == null || now >= state.due)
                }
                val native = settings.nativeLanguage
                val (reviewable, collapsed) = due.partition {
                    !facesCollapse(it.headword, it.gloss(native), direction)
                }
                skippedSameFace = collapsed.size
                reviewable.forEach { add(ReviewItem.Word(it)) }
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
            done = current.attempted.size,
            total = current.attempted.size + queue.size,
            current = head?.let { cardFor(it, settings) },
            revealed = current.revealed,
            showingAnswer = current.showingAnswer,
            finished = head == null && current.attempted.isNotEmpty(),
            streakDays = container.deck.streak(now),
            speakEnabled = ttsReady,
            intervals = previewIntervals(head, direction),
            archived = archived,
            skippedSameFace = skippedSameFace,
            // 卡堆只需要知道「后面还有几张」，不需要知道内容：
            // 提前把下一张的词露出来会直接毁掉自由回忆这件事，而复习的全部价值就在这里。
            upcoming = (queue.size - 1).coerceAtLeast(0),
            // `startedAt` 是 elapsedRealtime 刻度，所以这里不能跟上面那个墙钟 `now` 相减——
            // 两个不同源的数相减，得到的数没有意义（而它显示成「用时 3 秒」时就完全看不出错了）。
            sessionSeconds = if (current.startedAt == 0L) 0 else
                ((SystemClock.elapsedRealtime() - current.startedAt) / 1000L).coerceAtLeast(0L).toInt(),
            firstTryAccuracy = if (current.attempted.isEmpty()) 1f else
                (current.attempted.size - current.firstMissed).toFloat() / current.attempted.size,
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
            val (prompt, back) = facesOf(card.headword, card.gloss(settings.nativeLanguage), settings.direction)
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
        limit: Int,
    ): LookbackUiState {
        val today = LocalDate.now().toString()
        val allDays = diary.entries.map { it.dayKey }.toSet()
        // 先筛再解码：筛到某一天之后没必要把另外几十张的位图都解一遍。
        val matching = diary.entries
            .filter { dayFilter == null || it.dayKey == dayFilter }
            .sortedByDescending { it.takenAt }
        val shown = matching.take(limit)
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
            // 这个数决定列表末尾那一句「更早的还有多少条」。少了它，滚到第 40 条就是一片空白，
            // 而用户没有任何办法知道是自己记完了还是界面没显示——静默截断一份日记，
            // 读起来跟「记录丢了」一模一样。
            olderCount = matching.size - shown.size,
        )
    }

    /**
     * 组装详情页：把存进日记的四角坐标重新长回这张照片上。
     *
     * `EntryObject` 的框归一化在**传感器坐标系**里，而界面上显示的是按 EXIF 转正后的位图，
     * 所以中间必须过一次 `imageBoxFromSensorNorm`。这一步不能省：省略之后竖持拍的照片里，
     * 词会压在错的位置上——而这正是「场景式」与普通日记的分界线，错了就等于没有。
     *
     * 语音那两样是「条目引用着」与「磁盘上确有其文件」分开的：`diary.json` 会单独上云，
     * 声音不会（§8.7），所以换机之后完全可能读到 audioPath 而没有那个文件。这时候界面上要说的
     * 是「这段声音不在这台设备上」，而不是画一条按下去没有反应的播放条。
     */
    private fun buildDetail(
        diary: DiaryDocument,
        lexicon: LexiconIndex,
        settings: AppSettings,
        focus: DetailFocus,
    ): EntryDetailState? {
        val entry = focus.entryId?.let { id -> diary.entries.firstOrNull { it.id == id } } ?: return null
        val file = File(container.entryPhotoDir, entry.photoPath)
        val decoded = decodedDetailPhoto(file)
        val degrees = decoded?.exifDegrees ?: 0
        val audio = entry.audioPath?.let { name ->
            // 只问一次 exists：这一条是 combine 里的分支，别的都不做，播不动的时候播放器自己会说。
            File(container.audioDir, name).takeIf { it.isFile }
        }
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
            eventDraft = focus.draft,
            eventCount = diary.events.count { it.entryId == entry.id },
            // 现场那一段只属于它那一条：在别条的详情页上它不该露出来（也录不进这一条）。
            take = focus.take?.takeIf { it.entryId == entry.id },
            audioFile = audio,
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

        /**
         * 「载入更早」一次加多少。与首屏同量：一次点击就该多出一整屏可以滚的内容，
         * 而不是让人反复按同一个按钮——每按一次都是 40 张照片的重解。
         */
        private const val TIMELINE_PAGE = 40

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
