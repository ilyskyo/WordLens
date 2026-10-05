// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.data.repository

import com.ilyskyo.wordlens.data.model.CardOrigin
import com.ilyskyo.wordlens.data.model.Entry
import com.ilyskyo.wordlens.data.model.EntryObject
import com.ilyskyo.wordlens.data.model.EventCard
import com.ilyskyo.wordlens.data.model.OverlayLayer
import com.ilyskyo.wordlens.data.model.WordCard
import com.ilyskyo.wordlens.data.store.JsonDocument
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 删除级联与来源标记。
 *
 * ## 为什么这两件事需要仓库层的测试
 *
 * 删一条日记在界面上只是一个「删除」按钮，实际上要做四件事：条目本身、它的 `entries/` 照片、
 * 它的 `entries/` 贴纸副本、以及**指不回这张照片的事件卡**。哪一件没做都不会报错，
 * 只会留下磁盘上没人引用的文件，或者一条指向已消失事件的孤儿卡。
 * 相册导入用的是同一套文件命名与同一套路径约定（这是它必须和拍照同一条流水线的理由之一），
 * 所以这里测的是那条约定本身：导入的记录不该有任何特殊的删除分支。
 *
 * 另一条断言看着很小，但它写在数据层：从词典里收进来的词不是从照片上抠下来的贴纸。
 * 今天没有任何界面读 `origin`，所以这个字段说假话是**隐形**的——而它会一直留在 deck.json 里，
 * 等第一个按来源分组的功能把它当成真话用。
 */
class DiaryRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val now = System.currentTimeMillis()

    private fun diaryRepo(vararg entries: Entry): DiaryRepository = DiaryRepository(
        doc = JsonDocument(
            file = File(folder.root, "diary.json"),
            fallback = { DiaryDocument(entries = entries.toList()) },
            serializer = DiaryDocument.serializer(),
            scope = scope,
        ),
        photoDir = folder.root,
        scope = scope,
    )

    /** 一条「已经躺在私有目录里」的记录：照片与贴纸文件都真的建出来。 */
    private fun importedEntry(id: String): Entry {
        val photo = "$id.jpg"
        val sticker = "st-$id.png"
        File(folder.root, photo).writeText("jpeg-bytes")
        File(folder.root, sticker).writeText("png-bytes")
        return Entry(
            id = id,
            photoPath = photo,
            takenAt = now,
            objects = listOf(
                EntryObject(
                    id = "cup",
                    word = "cup",
                    lexiconEntryId = "lex-1",
                    score = 0.9f,
                    left = 0.1f,
                    top = 0.1f,
                    right = 0.4f,
                    bottom = 0.6f,
                    stickerPath = sticker,
                    layer = OverlayLayer.ITEM,
                ),
            ),
        )
    }

    @Test
    fun `deleting an entry removes its photo and the entries-side sticker copy`() = runBlocking {
        val entry = importedEntry("e1")
        val repo = diaryRepo(entry)
        val photo = File(folder.root, entry.photoPath)
        val copy = File(folder.root, "st-e1.png")
        assertTrue(photo.isFile)
        assertTrue(copy.isFile)

        val returned = repo.deleteEntry("e1")

        assertFalse("照片没跟着条目走", photo.exists())
        assertFalse("entries/ 里那份贴纸副本没跟着条目走", copy.exists())
        // 返回的是贴纸名，`stickers/` 那一份由调用方按「牌组还引用着吗」决定去留。
        assertEquals(listOf("st-e1.png"), returned)
        assertTrue(repo.document.value.entries.none { it.id == "e1" })
    }

    /**
     * Entry 与 EventCard 同住一个文件的理由：删日记时事件要一起走。
     *
     * 分两个文件就得跨文件级联，两次原子写之间没有事务——崩一次就留下一张指向已消失照片的事件卡。
     */
    @Test
    fun `events born from an entry go away with it`() = runBlocking {
        val doc = JsonDocument(
            file = File(folder.root, "diary.json"),
            fallback = {
                DiaryDocument(
                    entries = listOf(importedEntry("e2")),
                    events = listOf(
                        EventCard(id = "ev", entryId = "e2", text = "在那家面馆", happenedAt = now),
                        EventCard(id = "ev2", entryId = "other", text = "别处", happenedAt = now),
                    ),
                )
            },
            serializer = DiaryDocument.serializer(),
            scope = scope,
        )
        val repo = DiaryRepository(doc, folder.root, scope)

        repo.deleteEntry("e2")

        val events = repo.document.value.events.map { it.id }
        assertEquals(listOf("ev2"), events)
    }

    @Test
    fun `deleting an unknown entry is a no-op that removes no files`() = runBlocking {
        val entry = importedEntry("e3")
        val repo = diaryRepo(entry)

        val returned = repo.deleteEntry("nope")

        assertEquals(emptyList<String>(), returned)
        // 「删不掉」和「没有可删的」在界面上是两回事，但都不该动别人的文件。
        assertTrue(File(folder.root, entry.photoPath).isFile)
        assertEquals(1, repo.document.value.entries.size)
    }

    /**
     * 词典收录与手写的词，落盘不能写着「我是抠下来的贴纸」。
     *
     * `PhotoEntryPipeline` 会显式盖上 STICKER；剩下的路径（搜索页收录、取景页手写）
     * 走的是默认值，而默认值曾经是 STICKER —— 那时候每一条手输的词都在磁盘上撒谎。
     */
    @Test
    fun `a card without imagery is not recorded as a sticker`() {
        val typed = WordCard(id = "c1", headword = "miso", language = "en")
        assertEquals(CardOrigin.TEXT, typed.origin)
    }
}
