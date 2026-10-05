// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.data.repository

import com.ilyskyo.wordlens.data.model.EventCard
import com.ilyskyo.wordlens.data.model.FsrsState
import com.ilyskyo.wordlens.data.model.StudyDirection
import com.ilyskyo.wordlens.data.model.WordCard
import com.ilyskyo.wordlens.data.store.JsonDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 「标记已掌握」必须是真的离开队列，而且能回来。
 *
 * 这条规则是产品层定下的：评级的 EASY 只是把间隔拉长，已掌握才是「别再给我看它」。
 * 两者一旦在数据层混成一个概念，界面上无论怎么分开都会漏。仓库是唯一持有状态的地方，
 * 所以断言打在这里。
 */
class DeckRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val now = System.currentTimeMillis()

    private fun deckRepo(vararg cards: WordCard): DeckRepository {
        // 不 newFile：JsonDocument 把「存在但空」当损坏文件处理，这里要的是干净的新文件。
        val doc = JsonDocument(
            file = File(folder.root, "deck.json"),
            fallback = { DeckDocument(cards = cards.toList()) },
            serializer = DeckDocument.serializer(),
            scope = scope,
        )
        return DeckRepository(doc, scope)
    }

    private fun diaryRepo(vararg events: EventCard): DiaryRepository {
        val doc = JsonDocument(
            file = File(folder.root, "diary.json"),
            fallback = { DiaryDocument(events = events.toList()) },
            serializer = DiaryDocument.serializer(),
            scope = scope,
        )
        return DiaryRepository(doc, folder.root, scope)
    }

    private fun card(id: String, mastered: Boolean = false, due: Long? = null) = WordCard(
        id = id,
        headword = id,
        language = "en",
        mastered = mastered,
        states = if (due == null) emptyMap() else mapOf(StudyDirection.RECOGNIZE.name to FsrsState(due = due, n = 1)),
    )

    /** 未标已掌握的卡：到期与全新都进队列；标了之后两张都不进，且统计里算「学会」。 */
    @Test
    fun `mastered cards leave the due queue and count as mature`() = runBlocking {
        val repo = deckRepo(
            card("due", due = now - 1000),
            card("fresh"),
        )
        assertEquals(2, repo.dueCards(StudyDirection.RECOGNIZE, now).size)

        repo.update(repo.card("due")!!.copy(mastered = true))
        val rest = repo.dueCards(StudyDirection.RECOGNIZE, now).map { it.id }
        assertEquals(listOf("fresh"), rest)

        val stats = repo.stats(StudyDirection.RECOGNIZE, now)
        assertEquals(0, stats.due)
        assertEquals(1, stats.mature)
        assertEquals(1, stats.newCards)
    }

    /** 归档不是单行道：取消标记后要能原样回到队列。 */
    @Test
    fun `unmarking puts the card back`() = runBlocking {
        val repo = deckRepo(card("w", mastered = true, due = now - 1000))
        assertTrue(repo.dueCards(StudyDirection.RECOGNIZE, now).isEmpty())

        repo.update(repo.card("w")!!.copy(mastered = false))
        assertEquals(listOf("w"), repo.dueCards(StudyDirection.RECOGNIZE, now).map { it.id })
    }

    /** 事件走的是另一个文件，但规则必须一致——否则「词汇与事件」一起练时队列语义会分裂。 */
    @Test
    fun `events follow the same archiving rule`() = runBlocking {
        val event = EventCard(id = "e1", text = "楼下那家面馆", happenedAt = now)
        val repo = diaryRepo(event)
        assertEquals(1, repo.dueEvents(now).size)

        repo.updateEvent(repo.event("e1")!!.copy(mastered = true))
        assertFalse(repo.dueEvents(now).any { it.id == "e1" })

        repo.updateEvent(repo.event("e1")!!.copy(mastered = false))
        assertEquals(1, repo.dueEvents(now).size)
    }
}
