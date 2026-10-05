// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.data.repository

import com.ilyskyo.wordlens.data.model.CardOrigin
import com.ilyskyo.wordlens.data.model.EntrySource
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
        return DiaryRepository(doc, folder.root, File(folder.root, "audio"), scope)
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

    /**
     * 删日记时要靠这个判断决定 `stickers/` 那份副本去留。
     *
     * 判错的两种后果都不体面：多删了，词卡指向一个空文件；少删了，磁盘上永远留着一张
     * 没有任何界面能再看到的贴纸。
     */
    @Test
    fun `sticker references are answered from the deck`() = runBlocking {
        val repo = deckRepo(card("a").let { it.copy(stickerPath = "st-a.png") }, card("b"))
        assertTrue(repo.referencesSticker("st-a.png"))
        assertFalse(repo.referencesSticker("st-b.png"))
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

    /**
     * 导入的新卡要带着「它是别人给的」进牌组。
     *
     * `EntrySource.IMPORTED` 一直是死值：`source` 说的是这张卡在**这台机器**上怎么来的，
     * 而一份外来的 deck.json 自称什么都可以自称，我们无从核对。
     */
    @Test
    fun `an imported card arrives stamped as imported`() = runBlocking {
        val foreign = WordCard(
            id = "f1",
            headword = "soba",
            language = "en",
            source = EntrySource.ON_DEVICE,
        )
        val repo = deckRepo()

        val result = repo.mergeFrom(DeckDocument(cards = listOf(foreign)))

        assertEquals(1, result.added)
        assertEquals(
            EntrySource.IMPORTED,
            repo.card("f1")?.source,
        )
    }

    /**
     * 已经在我牌组里的那一张，身份不能被一次导入改写。
     *
     * 回归的是 `mergeCard` 原来以 `theirs.copy(…)` 为底的那件事：外来的文件会换掉
     * origin/stickerPath/headword，并且因为它那一份没写 `mastered`，默认值 false 会把用户
     * 按下过的「别再给我看它」**悄悄取消**。进度不退步是注释已经写了的，这几样是它没说的。
     */
    @Test
    fun `merging keeps the card I already own`() = runBlocking {
        val mine = WordCard(
            id = "m1",
            headword = "broth",
            language = "en",
            source = EntrySource.ON_DEVICE,
            origin = CardOrigin.STICKER,
            stickerPath = "st-m1.png",
            mastered = true,
            glosses = mapOf("zh" to "我自己写的释义"),
            states = mapOf(StudyDirection.RECOGNIZE.name to FsrsState(due = now + 600_000, n = 3)),
        )
        val theirs = WordCard(
            id = "m1",
            headword = "stock",
            language = "en",
            source = EntrySource.CLOUD,
            mastered = false,
            glosses = mapOf("zh" to "别人给的释义"),
            states = mapOf(StudyDirection.RECOGNIZE.name to FsrsState(due = now - 600_000, n = 1)),
        )
        val repo = deckRepo(mine)

        repo.mergeFrom(DeckDocument(cards = listOf(theirs)))
        val kept = repo.card("m1")!!

        assertEquals("broth", kept.headword)
        assertEquals(EntrySource.ON_DEVICE, kept.source)
        assertEquals("st-m1.png", kept.stickerPath)
        assertTrue("导入把已掌握的卡偷偷放回了队列", kept.mastered)
        assertEquals("我自己写的释义", kept.glosses["zh"])
        // 进度仍然取进步的那一份，不退步——这一条原来就对。
        assertEquals(3, kept.states.getValue(StudyDirection.RECOGNIZE.name).n)
    }
}
