// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.data.repository

import android.util.Log
import com.ilyskyo.wordlens.core.voice.VoiceMemo
import com.ilyskyo.wordlens.data.model.Entry
import com.ilyskyo.wordlens.data.model.EventCard
import com.ilyskyo.wordlens.data.model.FsrsState
import com.ilyskyo.wordlens.data.model.StudyDirection
import com.ilyskyo.wordlens.data.store.JsonDocument
import com.ilyskyo.wordlens.srs.Fsrs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/** 整本日记一个文件：`filesDir/diary.json`。与 deck.json 分开放，两者生命周期完全不同。 */
@Serializable
data class DiaryDocument(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val entries: List<Entry> = emptyList(),
    val events: List<EventCard> = emptyList(),
) {
    companion object {
        /**
         * 用途与 DeckDocument.CURRENT_SCHEMA 相同：只用来拒写更新格式的数据，不做迁移。
         *
         * 语音附件那两个字段**没有**把它推到 2：新增的都是带默认值的可空/零值字段，
         * 旧的 `diary.json` 里没有这两个 key 时反序列化直接取默认值，而新文件被更早的构建读到
         * 无非是 `ignoreUnknownKeys` 把它忽略掉——两个方向都不需要改任何一个已有字段的含义。
         * 真正需要跳号的是「旧数据还在、但意思变了」那种改动：换 [Entry.dayKey] 的算法、
         * 改任何一个落盘的枚举名、或者给 photoPath 换根目录。
         */
        const val CURRENT_SCHEMA = 1
    }
}

/**
 * 日记与事件卡的唯一持有者。
 *
 * ## 为什么 Entry 与 EventCard 同库
 *
 * 事件几乎总是从某条日记里长出来的（`entryId` 指回去）。分两个文件的话，删一条日记要跨
 * 文件级联，两份原子写之间没有事务，崩溃一次就留下孤儿事件。放一个文件里，删除就是
 * 一次过滤。
 *
 * 词卡（deck.json）不在这里：它是全局词汇表，一张词卡可以被多条日记引用，生命周期
 * 比任何一条日记都长。
 *
 * ## 为什么声音文件也归这里管
 *
 * `filesDir/audio` 下的那一份只有两种身份：被某条日记的 `audioPath` 引用着，或者没人引用。
 * 「没人引用的声音不能留在磁盘上」这条隐私要求，需要一个同时看得见文档与目录的人来执行——
 * 那就是本类。放在调用方（ViewModel）的话，「先删文档还是先删文件」这条顺序会散落到每一处
 * 调用点，而它一旦在某处写反，就会出现「条目没了但那一段声音还在」或者反过来。
 */
class DiaryRepository(
    private val doc: JsonDocument<DiaryDocument>,
    private val photoDir: File,
    private val audioDir: File,
    private val scope: CoroutineScope,
) {
    val document: StateFlow<DiaryDocument> = doc.state

    val entries: StateFlow<List<Entry>> = doc.state
        .map { it.entries }
        .stateIn(scope, SharingStarted.Eagerly, doc.current.entries)

    val events: StateFlow<List<EventCard>> = doc.state
        .map { it.events }
        .stateIn(scope, SharingStarted.Eagerly, doc.current.events)

    suspend fun load() = doc.load()

    /**
     * 内存里这份文档是不是磁盘上那份的忠实反映。
     *
     * 任何「拿这份文档去判断磁盘上哪些文件没人引用」的逻辑（启动清扫，见 `MediaSweep`）都必须
     * 先问它：读失败或架构更新时 `persisting` 为 false，损坏留证后从空文档起步时
     * `recoveredBlank` 为 true——两种情况下「没有条目引用它」都对每一个文件成立，
     * 而照着它删是不可恢复的。
     */
    val storageTrusted: Boolean get() = doc.persisting && !doc.recoveredBlank

    /**
     * 冷启动那一次读，读完顺手把没人引用的声音扫掉。
     *
     * 清扫**必须**挂在读之后、且在同一个函数里：`JsonDocument` 的读是异步的，谁在条目读回来之前
     * 扫，谁就会对每一段录音都得出「没有条目引用它」，把用户所有的声音一次删光。把它写成
     * AppContainer 里单独的一条 launch，就等于把排序交给调用点去记，而这条排序错了没有崩溃、
     * 只有第二天打不开的录音。
     *
     * 同一个理由还挡住第二种更隐蔽的失败：**读到空文档和「什么都没有」不是一回事**。
     * `diary.json` 损坏并被留证之后，内存里是一份合法的空文档（还能继续写盘），
     * 而磁盘上每一段录音都存在——那一扫会把用户所有说过的话删干净，而 `.corrupt` 里那份
     * 反倒是可以找人修的。代价不对称，所以 `persisting`（没读懂 / 架构比这个构建还新）或
     * `recoveredBlank`（损坏后从空文档起步）任意为真时，这一次不扫。
     */
    fun loadAsync(): Job = scope.launch {
        doc.load()
        if (!doc.persisting || doc.recoveredBlank) {
            Log.w(TAG, "audio sweep skipped: the diary did not come back as itself")
            return@launch
        }
        val swept = sweepUnreferencedAudio()
        if (swept > 0) Log.i(TAG, "Swept $swept unreferenced audio file(s)")
    }

    // ── entries ──────────────────────────────────────────────────────────────

    /**
     * 加一条日记；同一个 id 只认第一次。
     *
     * 这一条判重不是防御谁，而是这个文档的**唯一性约定本身**：`updateEntry`、`deleteEntry`、
     * `attachAudio` 与详情页都按 `firstOrNull { it.id == ... }` 找条目，时间轴还直接把条目 id
     * 当作 LazyColumn 的 key。而 `deck.add` 与 [addEvent] 两边本来就各自按 id 判重，只有这里
     * 一直是无条件 append——于是一次被触发两遍的提交会留下两条同 id 的记录，界面拿到的是
     * 重复 key 抛出的 IllegalArgumentException，用户看到的是一次闪退，而不是「多了一条一样的」。
     *
     * @return 真的加进去了一条吗。false 表示这份提交是重复的，调用方据此决定还要不要说话。
     */
    suspend fun addEntry(entry: Entry): Boolean {
        var added = false
        doc.update { current ->
            added = current.entries.none { it.id == entry.id }
            if (added) current.copy(entries = current.entries + entry) else current
        }
        return added
    }

    suspend fun updateEntry(entry: Entry) = doc.update { current ->
        current.copy(
            entries = current.entries.map {
                if (it.id == entry.id) entry.copy(updatedAt = System.currentTimeMillis()) else it
            },
        )
    }

    /**
     * 删一条日记，连带它的事件、照片、贴纸副本与声音。
     *
     * 照片与声音在文件系统里而事件在 JSON 里，顺序必须**先改文档再删文件**：反过来做，中途崩溃
     * 会得到「照片没了但日记还在」的坏数据；按现在的顺序，最坏是留下孤儿文件，可被清扫。
     *
     * 声音那份是**按条目 id 算出来的名字**删的，不是读 `audioPath`：一段用户还没决定收不收下的
     * 录音占的是同一个位置，而条目一旦没了就永远不会有谁去引用它——留在磁盘上是一段没人认领的
     * 声音，那是隐私问题，不是几个 KB 的漏。
     *
     * @return 这条日记引用过的贴纸文件名（相对 `filesDir/entries`）。`stickers/` 下还有同名
     *   副本，那份可能被词卡单独引用着，所以**不在这里删**，交给持有牌组的调用方判断。
     */
    suspend fun deleteEntry(id: String): List<String> {
        var photoPath: String? = null
        var stickers: List<String> = emptyList()
        doc.update { current ->
            val victim = current.entries.firstOrNull { it.id == id }
            photoPath = victim?.photoPath
            stickers = victim?.objects?.mapNotNull { it.stickerPath }.orEmpty()
            current.copy(
                entries = current.entries.filterNot { it.id == id },
                events = current.events.filterNot { it.entryId == id },
            )
        }
        photoPath?.let { runCatching { File(photoDir, it).delete() } }
        stickers.forEach { name -> runCatching { File(photoDir, name).delete() } }
        runCatching { File(audioDir, VoiceMemo.fileName(id)).delete() }
        return stickers
    }

    // ── 语音附件 ────────────────────────────────────────────────────────────

    /**
     * 收下那一段声音：让这条日记引用它，并记下时长。
     *
     * 文件不搬家——录的时候它就已经长在最终名字上了。这是「按 id 定名」换来的简单：
     * 少一次跨目录移动，也就少一次「移完了但文档没写成」的中间状态。
     *
     * @return 条目在不在。不在说明它已经被删掉了，调用方要把那一段当垃圾处理，
     *   而不是假装收下成功却没有任何地方显示它。
     */
    suspend fun attachAudio(id: String, durationMs: Long): Boolean {
        var attached = false
        doc.update { current ->
            val victim = current.entries.firstOrNull { it.id == id }
            attached = victim != null
            if (victim == null) {
                current
            } else {
                current.copy(
                    entries = current.entries.map {
                        if (it.id != id) {
                            it
                        } else {
                            it.copy(
                                audioPath = VoiceMemo.fileName(id),
                                audioDurationMs = durationMs,
                                updatedAt = System.currentTimeMillis(),
                            )
                        }
                    },
                )
            }
        }
        return attached
    }

    /**
     * 「删除录音」：把那段声音从这条日记上摘掉，并把文件真的删掉。
     *
     * 只清字段不删文件是一种假装——界面上没有了，磁盘上还在，而它是一段说得出用户家事的
     * 人声。顺序仍然是文档先、文件后，与 [deleteEntry] 同一个理由。
     *
     * @return 磁盘上确有一个文件被删掉了。没有也不代表失败：条目本来就没声音。
     */
    suspend fun detachAudio(id: String): Boolean {
        doc.update { current ->
            current.copy(
                entries = current.entries.map {
                    if (it.id != id || it.audioPath == null) {
                        it
                    } else {
                        it.copy(
                            audioPath = null,
                            audioDurationMs = 0L,
                            updatedAt = System.currentTimeMillis(),
                        )
                    }
                },
            )
        }
        return runCatching { File(audioDir, VoiceMemo.fileName(id)).delete() }.getOrDefault(false)
    }

    /**
     * 丢掉一段**没被任何日记引用**的录音：用户按了丢弃、收尾时发现只有半截、
     * 或者用户去录别的一条时上一段被让位。
     *
     * 先看一眼文档再动手：`audioPath` 正指着它的那一份是内容，不是残留，删了就是删用户的记录。
     * 条目已经不在了同样不动手——那份声音已经被 [deleteEntry] 连带删过，这里再删一次只是
     * 对着不存在的文件浪费一次调用，而那会让人以为「删除有两条通路」。
     */
    suspend fun discardTake(id: String) {
        val entry = doc.current.entries.firstOrNull { it.id == id } ?: return
        if (entry.audioPath != null) return
        runCatching { File(audioDir, VoiceMemo.fileName(id)).delete() }
    }

    /**
     * 磁盘上有、日记里没有任何条目引用的声音，全部删掉。返回删掉的个数。
     *
     * 只在进程刚起来时跑（见 `AppContainer.warmUp`）。「没被引用」在这台机器上只有一种成因：
     * 上一次进程被系统杀掉时，有一段还没决定收不收下的录音停在磁盘上。没被用户同意留下过的
     * 声音不该活过这一次启动；而正在录的那一段不可能在启动之前存在，所以这一扫不会碰到来路
     * 正当的文件。
     *
     * **必须在文档读回来之后调用**（[load]）：没读到条目时「没有条目引用它」对每一段声音都成立，
     * 那一扫就把用户所有的录音清光了。这是本方法唯一的危险处，调用点用 `load()` 挡着。
     */
    suspend fun sweepUnreferencedAudio(): Int = withContext(Dispatchers.IO) {
        val referenced = doc.current.entries.mapNotNull { it.audioPath }.toSet()
        val files = audioDir.listFiles() ?: return@withContext 0
        var removed = 0
        files.forEach { file ->
            if (file.isFile && file.name !in referenced && runCatching { file.delete() }.getOrDefault(false)) {
                removed++
            }
        }
        removed
    }

    // ── events ───────────────────────────────────────────────────────────────

    suspend fun addEvent(event: EventCard): Boolean {
        var added = false
        doc.update { current ->
            if (current.events.any { it.id == event.id }) {
                current
            } else {
                added = true
                current.copy(events = current.events + event)
            }
        }
        return added
    }

    suspend fun updateEvent(event: EventCard) = doc.update { current ->
        current.copy(
            events = current.events.map {
                if (it.id == event.id) event.copy(updatedAt = System.currentTimeMillis()) else it
            },
        )
    }

    suspend fun deleteEvent(id: String) = doc.update { current ->
        current.copy(events = current.events.filterNot { it.id == id })
    }

    /** 复习一条事件并返回新间隔（天）。语义与 DeckRepository.grade 一致。 */
    suspend fun gradeEvent(
        id: String,
        rating: Fsrs.Rating,
        elapsedMs: Long = 0,
        now: Long = System.currentTimeMillis(),
    ): Int {
        var interval = 0
        doc.update { current ->
            val event = current.events.firstOrNull { it.id == id } ?: return@update current
            val scheduler = event.state()?.toScheduler() ?: Fsrs.State()
            val next = Fsrs.review(scheduler, rating, now)
            interval = Fsrs.daysUntilDue(next, now)
            current.copy(
                events = current.events.map {
                    if (it.id != id) {
                        it
                    } else {
                        it.copy(
                            states = it.states + (StudyDirection.RECOGNIZE.name to next.toPersisted()),
                            updatedAt = now,
                        )
                    }
                },
            )
        }
        return interval
    }

    /** 到期或全新的事件；手动标记已掌握的不再回来。 */
    fun dueEvents(now: Long = System.currentTimeMillis()): List<EventCard> =
        doc.current.events.filter { event ->
            val state = event.state()
            !event.mastered && (state == null || now >= state.due)
        }

    fun event(id: String): EventCard? = doc.current.events.firstOrNull { it.id == id }

    private companion object {
        const val TAG = "DiaryRepo"
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

private fun Fsrs.State.toPersisted(): FsrsState = FsrsState(
    d = difficulty,
    s = stability,
    due = due,
    last = lastReview,
    n = reviewCount,
    lapses = lapses,
)
