// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.data.repository

import com.ilyskyo.wordlens.data.model.EntrySource
import com.ilyskyo.wordlens.data.model.FsrsState
import com.ilyskyo.wordlens.data.model.ReviewLog
import com.ilyskyo.wordlens.data.model.StudyDirection
import com.ilyskyo.wordlens.data.model.WordCard
import com.ilyskyo.wordlens.data.store.JsonDocument
import com.ilyskyo.wordlens.srs.Fsrs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The whole deck in one file: `filesDir/deck.json`. */
@Serializable
data class DeckDocument(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val cards: List<WordCard> = emptyList(),
    val log: List<ReviewLog> = emptyList(),
) {
    companion object {
        /**
         * 这个构建写的格式版本。它唯一的用途是**拒写**：磁盘上的版本比它大，说明这份数据
         * 是更新的文件格式写的，本构建不该把自己不认识的样子改写回去（见
         * [com.ilyskyo.wordlens.data.store.JsonDocument]）。不做多代迁移——那需要一份
         * 迁移表，而现在只有一代。
         */
        const val CURRENT_SCHEMA = 1
    }
}

/** Counts for the home screen, the widget and the progress ring. */
data class DeckStats(
    val total: Int = 0,
    val newCards: Int = 0,
    val due: Int = 0,
    val learning: Int = 0,
    val mature: Int = 0,
)

/** Outcome of importing a backup, so the UI can report something more useful than "done". */
data class MergeResult(val added: Int, val updated: Int, val totalCards: Int)

/**
 * The single owner of vocabulary state.
 *
 * Scheduling note: [Fsrs.State] is converted to and from the on-disk [FsrsState] here and
 * nowhere else, so a rename of the serialised fields can never reach the scheduler.
 */
class DeckRepository(
    private val doc: JsonDocument<DeckDocument>,
    private val scope: CoroutineScope,
) {
    /** Serialises grades so two rapid taps cannot interleave a read-modify-write. */
    private val reviewLock = Mutex()

    val document: StateFlow<DeckDocument> = doc.state

    val cards: StateFlow<List<WordCard>> = doc.state
        .map { it.cards }
        .stateIn(scope, SharingStarted.Eagerly, doc.current.cards)

    val log: StateFlow<List<ReviewLog>> = doc.state
        .map { it.log }
        .stateIn(scope, SharingStarted.Eagerly, doc.current.log)

    suspend fun load() = doc.load()

    fun loadAsync() = doc.loadAsync()

    // ── mutations ────────────────────────────────────────────────────────────

    /** Returns the number of cards actually added; a duplicate id is ignored, not an error. */
    suspend fun add(card: WordCard): Boolean {
        var added = false
        doc.update { current ->
            if (current.cards.any { it.id == card.id }) {
                current
            } else {
                added = true
                current.copy(cards = current.cards + card)
            }
        }
        return added
    }

    suspend fun addAll(newCards: List<WordCard>): Int {
        if (newCards.isEmpty()) return 0
        var added = 0
        doc.update { current ->
            val seen = current.cards.mapTo(HashSet()) { it.id }
            val fresh = newCards.filter { seen.add(it.id) }
            added = fresh.size
            current.copy(cards = current.cards + fresh)
        }
        return added
    }

    suspend fun update(card: WordCard) = doc.update { current ->
        current.copy(
            cards = current.cards.map {
                if (it.id == card.id) card.copy(updatedAt = System.currentTimeMillis()) else it
            },
        )
    }

    suspend fun delete(id: String) = doc.update { current ->
        current.copy(cards = current.cards.filterNot { it.id == id })
    }

    /**
     * Apply a review grade and return the interval in days it produced.
     *
     * The interval is returned rather than recomputed by the UI because the committed value
     * went through the ±5% fuzz — a UI that recomputed would occasionally show a different
     * number from the one the scheduler actually stored.
     */
    suspend fun grade(
        cardId: String,
        direction: StudyDirection,
        rating: Fsrs.Rating,
        elapsedMs: Long = 0,
        now: Long = System.currentTimeMillis(),
    ): Int = reviewLock.withLock {
        var interval = 0
        doc.update { current ->
            val card = current.cards.firstOrNull { it.id == cardId } ?: return@update current
            val next = Fsrs.review(card.state(direction)?.toScheduler() ?: Fsrs.State(), rating, now)
            interval = Fsrs.daysUntilDue(next, now)
            current.copy(
                cards = current.cards.map {
                    if (it.id != cardId) {
                        it
                    } else {
                        it.copy(
                            states = it.states + (direction.name to next.toPersisted()),
                            updatedAt = now,
                        )
                    }
                },
                log = current.log + ReviewLog(
                    cardId = cardId,
                    direction = direction,
                    rating = rating.value,
                    at = now,
                    elapsedMs = elapsedMs,
                ),
            )
        }
        interval
    }

    /**
     * Forget progress for one direction, or for the card entirely.
     *
     * The review log is intentionally left alone: it is the learner's history and the source
     * data any future FSRS optimiser would need.
     */
    suspend fun reset(cardId: String, direction: StudyDirection? = null) = doc.update { current ->
        current.copy(
            cards = current.cards.map { card ->
                if (card.id != cardId) {
                    card
                } else {
                    card.copy(
                        states = if (direction == null) emptyMap() else card.states - direction.name,
                        updatedAt = System.currentTimeMillis(),
                    )
                }
            },
        )
    }

    // ── queries ──────────────────────────────────────────────────────────────

    /** Cards due (or new) in [direction]. Manually mastered cards have left the deck. */
    fun dueCards(
        direction: StudyDirection,
        now: Long = System.currentTimeMillis(),
        includeNew: Boolean = true,
    ): List<WordCard> = doc.current.cards.filter { card ->
        if (card.mastered) {
            false
        } else {
            val state = card.state(direction)
            if (state == null) includeNew else now >= state.due
        }
    }

    fun card(id: String): WordCard? = doc.current.cards.firstOrNull { it.id == id }

    /**
     * 这张贴纸是否还被某张词卡引用。
     *
     * 同一张贴纸在 `stickers/` 与 `entries/` 各存一份，两边生命周期不同：删日记要能收回
     * entries 那份，但只有牌组不再引用时才该动 stickers 那份——否则词卡会指向一个空文件。
     */
    fun referencesSticker(name: String): Boolean = doc.current.cards.any { it.stickerPath == name }

    fun snapshot(): List<WordCard> = doc.current.cards

    fun stats(direction: StudyDirection, now: Long = System.currentTimeMillis()): DeckStats {
        var fresh = 0
        var due = 0
        var learning = 0
        var mature = 0
        for (card in doc.current.cards) {
            val s = card.state(direction)
            when {
                // 用户自己判定「已掌握」，算学会，不再占用到期数。
                card.mastered -> mature++
                s == null -> fresh++
                now >= s.due -> due++
                s.n < MATURITY_REVIEW_COUNT -> learning++
                else -> mature++
            }
        }
        return DeckStats(
            total = doc.current.cards.size,
            newCards = fresh,
            due = due,
            learning = learning,
            mature = mature,
        )
    }

    /**
     * Consecutive days with at least one review, counting back from today.
     *
     * A streak survives "nothing reviewed *yet* today" by anchoring on yesterday: a learner who
     * opens the app before dinner should not see their 30-day streak reported as broken.
     */
    fun streak(now: Long = System.currentTimeMillis()): Int {
        val days = doc.current.log.mapTo(HashSet()) { localDateKey(it.at) }
        if (days.isEmpty()) return 0

        var cursor = Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate()
        if (days.contains(cursor.toString())) {
            // anchor stays on today
        } else {
            cursor = cursor.minusDays(1)
            if (!days.contains(cursor.toString())) return 0
        }

        var count = 0
        while (days.contains(cursor.toString())) {
            count++
            cursor = cursor.minusDays(1)
        }
        return count
    }

    /** Reviews per local day for the last [days] days, oldest first. */
    fun dailyCounts(days: Int = 30): List<Pair<LocalDate, Int>> {
        val today = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZONE).toLocalDate()
        val buckets = LinkedHashMap<LocalDate, Int>()
        for (offset in (days - 1) downTo 0) buckets[today.minusDays(offset.toLong())] = 0
        for (entry in doc.current.log) {
            val date = Instant.ofEpochMilli(entry.at).atZone(ZONE).toLocalDate()
            if (buckets.containsKey(date)) buckets[date] = buckets.getValue(date) + 1
        }
        return buckets.toList()
    }

    /** Import merge: union of cards and glosses, never a regression in review progress. */
    suspend fun mergeFrom(imported: DeckDocument): MergeResult {
        var added = 0
        var updated = 0
        val result = doc.update { current ->
            val byId = LinkedHashMap(current.cards.associateBy { it.id })
            for (incoming in imported.cards) {
                val existing = byId[incoming.id]
                if (existing == null) {
                    // 新进来的那一张盖上 IMPORTED。`EntrySource` 说的是「这张卡在这个设备上
                    // 是怎么来的」，而文件里自称的 source 说的是别人设备上发生的事：一份外来的
                    // deck.json 可以声称任何一张词卡是 ON_DEVICE 认出来的，而我们无从核对。
                    // 计划里「导入之后 IMPORTED 不再是死值」这条要的就是这件事——它一直没被
                    // 写下来，所以牌组里「别人给的」和「我自己拍的」长得一模一样。
                    // 只盖新进的：已存在那一张走 mergeCard，那条路以**我的**身份为底，
                    // source 不会被外来的文件改写。
                    byId[incoming.id] = incoming.copy(source = EntrySource.IMPORTED)
                    added++
                } else {
                    byId[incoming.id] = mergeCard(existing, incoming)
                    updated++
                }
            }
            current.copy(cards = byId.values.toList())
        }
        return MergeResult(added, updated, result.cards.size)
    }

    /**
     * Conflict rule on import: for each direction keep whichever record has been reviewed more
     * often, then never move `due` earlier or `last` later than what we already had. Importing
     * an old backup must not resurrect forgotten cards.
     */
    private fun mergeCard(mine: WordCard, theirs: WordCard): WordCard {
        val directions = (mine.states.keys + theirs.states.keys)
        val mergedStates = directions.associateWith { key ->
            val a = mine.states[key]
            val b = theirs.states[key]
            when {
                a == null -> b!!
                b == null -> a
                b.n > a.n -> b.copy(due = maxOf(b.due, a.due), last = maxOf(b.last, a.last))
                else -> a
            }
        }
        // 以**我的那一张**为底，只把缺的补上。
        //
        // 这里原来是 `theirs.copy(...)`：一份外来的 deck.json 于是可以把自己机器上认出来的卡
        // 改成「贴纸来自这张照片」（origin/stickerPath/originalPhotoPath 全换成它的），
        // 可以把自己的 headword 换掉，并且因为它那份没写 `mastered`，默认 false 就把用户
        // 「别再给我看它」的归档**悄悄取消**了。注释承诺的是「复习进度绝不退步」，
        // 而实际被覆盖的是这张卡的**全部身份**——进度只是其中最小的一块。
        //
        // 顺带同一处的 map 合并也是反的：`a + b` 是 b 按语言键赢，也就是外来的释义盖掉
        // 用户自己改过的那一条。应该是我的赢，缺的语言才从外来的补。
        return mine.copy(
            ipa = mine.ipa ?: theirs.ipa,
            emoji = mine.emoji ?: theirs.emoji,
            example = mine.example ?: theirs.example,
            category = mine.category ?: theirs.category,
            sceneId = mine.sceneId ?: theirs.sceneId,
            sceneLabel = mine.sceneLabel ?: theirs.sceneLabel,
            sceneEmoji = mine.sceneEmoji ?: theirs.sceneEmoji,
            stickerPath = mine.stickerPath ?: theirs.stickerPath,
            originalPhotoPath = mine.originalPhotoPath ?: theirs.originalPhotoPath,
            glosses = theirs.glosses + mine.glosses,
            exampleGlosses = theirs.exampleGlosses + mine.exampleGlosses,
            tags = (mine.tags + theirs.tags).distinct(),
            states = mergedStates,
            createdAt = minOf(mine.createdAt, theirs.createdAt),
            updatedAt = System.currentTimeMillis(),
        )
    }

    private companion object {
        /** Reviews at or above this count count as "mature" for stats and progress colouring. */
        const val MATURITY_REVIEW_COUNT = 5

        val ZONE: ZoneId get() = ZoneId.systemDefault()

        fun localDateKey(epochMillis: Long): String =
            Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    }
}

/** On-disk shape -> scheduler shape. */
private fun FsrsState.toScheduler(): Fsrs.State = Fsrs.State(
    difficulty = d,
    stability = s,
    due = due,
    lastReview = last,
    reviewCount = n,
    lapses = lapses,
)

/** Scheduler shape -> on-disk shape. */
private fun Fsrs.State.toPersisted(): FsrsState = FsrsState(
    d = difficulty,
    s = stability,
    due = due,
    last = lastReview,
    n = reviewCount,
    lapses = lapses,
)
