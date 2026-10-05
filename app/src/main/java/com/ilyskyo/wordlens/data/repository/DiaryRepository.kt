// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.data.repository

import com.ilyskyo.wordlens.data.model.Entry
import com.ilyskyo.wordlens.data.model.EventCard
import com.ilyskyo.wordlens.data.model.FsrsState
import com.ilyskyo.wordlens.data.model.StudyDirection
import com.ilyskyo.wordlens.data.store.JsonDocument
import com.ilyskyo.wordlens.srs.Fsrs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
        /** 用途与 DeckDocument.CURRENT_SCHEMA 相同：只用来拒写更新格式的数据，不做迁移。 */
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
 */
class DiaryRepository(
    private val doc: JsonDocument<DiaryDocument>,
    private val photoDir: File,
    scope: CoroutineScope,
) {
    val document: StateFlow<DiaryDocument> = doc.state

    val entries: StateFlow<List<Entry>> = doc.state
        .map { it.entries }
        .stateIn(scope, SharingStarted.Eagerly, doc.current.entries)

    val events: StateFlow<List<EventCard>> = doc.state
        .map { it.events }
        .stateIn(scope, SharingStarted.Eagerly, doc.current.events)

    suspend fun load() = doc.load()

    fun loadAsync() = doc.loadAsync()

    // ── entries ──────────────────────────────────────────────────────────────

    suspend fun addEntry(entry: Entry) = doc.update { current ->
        current.copy(entries = current.entries + entry)
    }

    suspend fun updateEntry(entry: Entry) = doc.update { current ->
        current.copy(
            entries = current.entries.map {
                if (it.id == entry.id) entry.copy(updatedAt = System.currentTimeMillis()) else it
            },
        )
    }

    /**
     * 删一条日记，连带它的事件与照片文件。
     *
     * 照片在文件系统里而事件在 JSON 里，顺序必须**先改文档再删文件**：反过来做，中途崩溃
     * 会得到「照片没了但日记还在」的坏数据；按现在的顺序，最坏是留下孤儿照片，可被清理。
     */
    suspend fun deleteEntry(id: String) {
        var photoPath: String? = null
        doc.update { current ->
            val victim = current.entries.firstOrNull { it.id == id }
            photoPath = victim?.photoPath
            current.copy(
                entries = current.entries.filterNot { it.id == id },
                events = current.events.filterNot { it.entryId == id },
            )
        }
        photoPath?.let { runCatching { File(photoDir, it).delete() } }
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
