// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.core

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.ilyskyo.wordlens.data.model.EntrySource
import com.ilyskyo.wordlens.data.model.LexiconEntry
import com.ilyskyo.wordlens.data.model.RatingPalette
import com.ilyskyo.wordlens.data.model.WordCard
import com.ilyskyo.wordlens.data.repository.DeckDocument
import com.ilyskyo.wordlens.data.repository.DeckRepository
import com.ilyskyo.wordlens.data.repository.DiaryDocument
import com.ilyskyo.wordlens.data.repository.DiaryRepository
import com.ilyskyo.wordlens.data.repository.LexiconRepository
import com.ilyskyo.wordlens.core.reminder.ReminderScheduler
import com.ilyskyo.wordlens.data.repository.SettingsRepository
import com.ilyskyo.wordlens.data.store.JsonDocument
import com.ilyskyo.wordlens.speech.Speaker
import com.ilyskyo.wordlens.srs.Fsrs
import com.ilyskyo.wordlens.vision.PhotoEntryPipeline
import com.ilyskyo.wordlens.vision.VisionRepository
import com.ilyskyo.wordlens.vision.detection.EfficientDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/**
 * Manual dependency container.
 *
 * Deliberately not Hilt. The graph is a dozen objects deep with no scoping subtleties, and
 * annotation processing is the single most common source of first-build pain in a project like
 * this — one less processor is one less thing to break when a dependency moves. If the graph
 * ever grows scopes that actually differ, swapping this for Hilt is a contained change because
 * nothing outside this file knows how objects are constructed.
 *
 * Everything lives in [ApplicationScope]; the app is small enough that per-screen scopes buy
 * nothing, and a leaked camera analyser is worse than a slightly long-lived repository.
 */
class AppContainer(context: Context) {

    /** 暴露给 ViewModel 读取字符串资源；仓库层需要的路径已经各自持有。 */
    val appContext: Context = context.applicationContext

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── storage ──────────────────────────────────────────────────────────────

    /** Sticker cut-outs, referenced by name from `WordCard.stickerPath`. */
    val stickerDir: File = File(appContext.filesDir, "stickers").apply { mkdirs() }

    /** Full-resolution source photos, kept separately so a sticker can be re-cut. */
    val photoDir: File = File(appContext.filesDir, "photos").apply { mkdirs() }

    /** 日记条目的照片。与 photoDir 分开：Entry.photoPath 以这里为根，删除时好级联。 */
    val entryPhotoDir: File = File(appContext.filesDir, "entries").apply { mkdirs() }

    /**
     * 语音日记附件（`Entry.audioPath` 的根）。
     *
     * 与照片分开一个目录而不是塞进 `entries/`，是为了让「哪个目录里是人声」这件事在磁盘上
     * 一眼可辨：清扫未引用的录音、以及核对备份边界，都只需要看这一个目录。
     *
     * 备份边界不用新写一行规则：`data_extraction_rules.xml` 与 `backup_rules.xml` 都是
     * `<include>` **白名单**（只有 deck.json 与 diary.json），一旦写了 include，Android 就只
     * 备份列出的路径，其余一律不外传——所以 `filesDir/audio` 按构造就不上云。人声比照片更
     * 敏感（语气、背景里有人在说话），而默认云备份不是端到端加密的（说明书 §8.4/§8.7）。
     * 面对面换机是端到端加密的、且发生在用户主动换机那一刻，那一条 `<device-transfer>` 带全部。
     */
    val audioDir: File = File(appContext.filesDir, "audio").apply { mkdirs() }

    private val deckDocument = JsonDocument(
        file = File(appContext.filesDir, "deck.json"),
        fallback = { DeckDocument() },
        serializer = DeckDocument.serializer(),
        scope = applicationScope,
        tag = "DeckDocument",
        currentSchema = DeckDocument.CURRENT_SCHEMA,
        schemaOf = DeckDocument::schemaVersion,
    )

    val deck = DeckRepository(deckDocument, applicationScope)

    private val diaryDocument = JsonDocument(
        file = File(appContext.filesDir, "diary.json"),
        fallback = { DiaryDocument() },
        serializer = DiaryDocument.serializer(),
        scope = applicationScope,
        tag = "DiaryDocument",
        currentSchema = DiaryDocument.CURRENT_SCHEMA,
        schemaOf = DiaryDocument::schemaVersion,
    )

    val diary = DiaryRepository(diaryDocument, entryPhotoDir, audioDir, applicationScope)

    val lexicon = LexiconRepository(appContext, applicationScope)

    val settings = SettingsRepository(appContext)

    // ── behaviour ────────────────────────────────────────────────────────────

    val speaker = Speaker(appContext)

    val vision = VisionRepository(appContext, applicationScope, lexicon, settings)

    /**
     * 「一张照片 → 一条日记条目」的流水线，快门与相册导入共用这一个实例。
     *
     * 放在容器里而不是各个 ViewModel 里各建一个，是这条设计的全部意义：两个入口如果
     * 各自 new 一份，落盘规则（贴纸文件名、EntryObject 用哪个坐标系、卡片上的场景字段）
     * 就有两份可以互相落后的对象。这里连**实例**也只有一个。
     */
    val photoPipeline = PhotoEntryPipeline(
        entryPhotoDir = entryPhotoDir,
        stickerDir = stickerDir,
        vision = vision,
        lexicon = lexicon,
        diary = diary,
        deck = deck,
        // 传「怎么拿到检测器」而不是检测器本身：它现在可能正卡在 RetryGate 的冷却窗口里。
        // 每次跑流水线都重新问一次门，导入这条路才有第二次机会；缓存住那个 null 就是
        // 把上面注释里写过的老 bug 再犯一遍。
        detector = { detectorOrNull() },
    )

    /**
     * COCO 物体检测器（取景页词片的来源），取不到就是 null——取景页降级为无词片。
     *
     * 这里原来是 `val detector by lazy { create() }`。`EfficientDetector.create` 失败时
     * **返回 null 而不是抛**，而 lazy 会把那一次 null 永久缓存：一次瞬时的 TFLite 分配失败
     * 就让整个进程的词片再也不出现，且没有任何日志说明为什么。
     *
     * 现在三件事分开：
     * - 成功的实例存进 [detectorInstance]。帧回调是 2Hz，每次 miss 都重建等于每两秒
     *   载一次 4.5MB 模型；
     * - 失败走 [detectorGate] 的冷却窗口，窗口内直接返回 null，不再空转去载模型；
     * - 失败日志只打一次。每帧一条 `w` 会把 logcat 刷满，反而把真正的原因埋掉。
     *
     * `@Synchronized` 是必须的：这个方法在分析线程（Default）与主线程上都会被读，
     * 而 [RetryGate] 自己刻意不加锁。
     *
     * 默认时钟是 `elapsedRealtime()` 而不是墙钟——`RetryGate` 的文件注释论证过这件事：
     * 用户改系统时间或 NTP 校时不该把冷却窗口提前打开（那会立刻再载一次 4.5MB 模型），
     * 前进一跳再退回来甚至可能触发两次。这里原来传的是 `System.currentTimeMillis()`，
     * 恰好把那条论证反着写了。
     */
    @Synchronized
    fun detectorOrNull(nowMs: Long = SystemClock.elapsedRealtime()): EfficientDetector? {
        detectorInstance?.let { return it }
        if (!detectorGate.allow(nowMs)) return null
        val created = runCatching { EfficientDetector.create(appContext) }.getOrNull()
        return if (created != null) {
            detectorInstance = created
            detectorGate.reset()
            detectorFailureLogged = false
            created
        } else {
            detectorGate.recordFailure(nowMs)
            if (!detectorFailureLogged) {
                detectorFailureLogged = true
                Log.w(
                    TAG,
                    "object detector unavailable; chips stay off, retry allowed again in " +
                        "${detectorGate.retryInMs(nowMs)}ms",
                )
            }
            null
        }
    }

    private var detectorInstance: EfficientDetector? = null

    private val detectorGate = RetryGate(DETECTOR_RETRY_COOLDOWN_MS)

    private var detectorFailureLogged = false

    /**
     * Cards due right now, in whichever direction the user is currently drilling.
     *
     * Suspends because the direction lives in DataStore; the widget provider runs in a coroutine
     * so it can afford to wait for the first emission.
     */
    suspend fun dueCount(): Int {
        val direction = settings.settings.first().direction
        return deck.stats(direction).due
    }

    /** Build a card from a lexicon entry without touching the repository. */
    fun cardFrom(
        entry: LexiconEntry,
        target: com.ilyskyo.wordlens.data.model.Lang,
        native: com.ilyskyo.wordlens.data.model.Lang,
        source: EntrySource = EntrySource.ON_DEVICE,
    ): WordCard? = entry.toCard(target, native, source)

    fun warmUp() {
        deck.loadAsync()
        diary.loadAsync()
        lexicon.loadAsync()
        // 词表是一份小 JSON，冷启动就该在：取景页第一次出词前它必须就绪。
        vision.loadTaxonomy()
        applyRetentionFromSettings()
        applyReminderFromSettings()
    }

    /**
     * 每日提醒的排期，与设置里那两项**始终一致**。
     *
     * 关键是这条通路只有一处：设置页只写 DataStore，不直接叫调度器干活。这样
     * 「改了设置但没重排」「启动时排期已经被系统清掉」这两类问题都不会存在——
     * 任何一次设置流变化都会把排期重新对齐一遍，冷启动也会。
     */
    private fun applyReminderFromSettings() {
        applicationScope.launch {
            settings.settings
                .map { it.reminderEnabled to it.reminderMinuteOfDay }
                .distinctUntilChanged()
                .collect { (enabled, minuteOfDay) ->
                    reminderScheduler.sync(enabled, minuteOfDay)
                }
        }
    }

    val reminderScheduler = ReminderScheduler(appContext)

    /**
     * 用户选的目标保持率 → 排期器。
     *
     * 唯一通路放在容器里，而不是每个 ViewModel 各自 `configure` 一遍：`Fsrs` 是进程级单例，
     * 复习页算间隔、小组件数到期、设置页回显读的都是它。谁最后写它一旦有第二处，
     * 「按钮上的天数和真正排出来的天数不一致」就只是时间问题。
     *
     * 冷启动时这一路是异步的（DataStore 第一次读通常几毫秒），而牌组本身也在异步加载，
     * 所以第一张卡渲染时保持率已经就位；把它做成同步读反而会拖住 onCreate。
     */
    private fun applyRetentionFromSettings() {
        applicationScope.launch {
            settings.settings
                .map { it.requestRetention }
                .distinctUntilChanged()
                .collect { Fsrs.setRequestRetention(it) }
        }
    }

    /**
     * 评级四档的色系选择。主题在 `MainActivity` 订阅它，设置页写它。
     *
     * Eagerly：主题必须在第一帧就有正确的颜色，不能让按钮先闪一下默认色系。
     */
    val ratingPalette: StateFlow<RatingPalette> = settings.settings
        .map { it.ratingPalette }
        .distinctUntilChanged()
        .stateIn(applicationScope, SharingStarted.Eagerly, RatingPalette.WARM)

    private companion object {
        private const val TAG = "AppContainer"

        /**
         * 检测器加载失败后的重试冷却：30 秒。
         *
         * 模型重建本身只有几十毫秒，所以这个数字不是给「重建」留时间，而是给「系统缓过来」
         * 留时间（内存压力、别的 App  releasing camera）。太短就是每帧硬撞，太长就等于
         * 这一场拍摄再也看不见词片。
         */
        private const val DETECTOR_RETRY_COOLDOWN_MS = 30_000L
    }
}
