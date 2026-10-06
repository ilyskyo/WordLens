// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.data.repository

import com.ilyskyo.wordlens.core.voice.VoiceMemo
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
import org.junit.Assert.assertNull
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
        audioDir = audioRoot(),
        scope = scope,
    )

    /** `filesDir/audio` 的同构位置。单独一个目录正是「哪个人声目录该被扫」能一眼核对的理由。 */
    private fun audioRoot(): File = File(folder.root, "audio").apply { mkdirs() }

    /** 那段声音真的落在磁盘上，名字由条目 id 算出来——与录音机写的是同一个位置。 */
    private fun audioOnDisk(id: String): File =
        File(audioRoot(), VoiceMemo.fileName(id)).apply { writeText("m4a-bytes") }

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
        val repo = DiaryRepository(doc, folder.root, audioRoot(), scope)

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

    // ── 语音附件 ────────────────────────────────────────────────────────────

    /**
     * 「收下」不搬文件：录的时候它就长在最终名字上，这一步只是让日记开始引用它。
     *
     * 少一次跨目录移动，就少一次「移完了但文档没写成」的中间状态——那正是本仓库在
     * 删除路径上刻意避开的那一种。
     */
    @Test
    fun `keeping a take points the entry at the file where it already lies`() = runBlocking {
        val repo = diaryRepo(importedEntry("e4"))
        val onDisk = audioOnDisk("e4")

        assertTrue(repo.attachAudio("e4", 12_345L))

        val entry = repo.document.value.entries.first()
        assertEquals(VoiceMemo.fileName("e4"), entry.audioPath)
        assertEquals(12_345L, entry.audioDurationMs)
        assertTrue("收下这一步不该把文件搬走或删掉", onDisk.isFile)
    }

    @Test
    fun `keeping a take on a deleted entry fails instead of lying`() = runBlocking {
        val repo = diaryRepo(importedEntry("e5"))

        assertFalse(repo.attachAudio("gone", 4_000L))
    }

    /**
     * 「删除录音」必须连文件一起走。
     *
     * 只清字段是最容易写错的一种假装：界面上那条声音没了，磁盘上还剩一段说得出用户家事的
     * 人声，而没有任何地方再引用它——它因此也再没有任何代码会去删它。
     */
    @Test
    fun `deleting a recording clears the field and the file`() = runBlocking {
        val repo = diaryRepo(importedEntry("e6"))
        // 真实顺序：录音机先把文件写到那个位置上，收下只是让日记引用它。
        val onDisk = audioOnDisk("e6")
        assertTrue(repo.attachAudio("e6", 8_000L))
        assertTrue("收下这一步不该动文件", onDisk.isFile)

        assertTrue(repo.detachAudio("e6"))

        assertNull(repo.document.value.entries.first().audioPath)
        assertEquals(0L, repo.document.value.entries.first().audioDurationMs)
        assertFalse("字段清了而文件还在：那是一段没人引用的声音", onDisk.exists())
    }

    /** 删条目要连带把那一段拿走，**包括用户还没决定收不收下的那种**（条目一没，它就永远没人引用）。 */
    @Test
    fun `deleting an entry takes even an unkept recording`() = runBlocking {
        val repo = diaryRepo(importedEntry("e7"))
        val onDisk = audioOnDisk("e7")

        repo.deleteEntry("e7")

        assertFalse(onDisk.exists())
    }

    @Test
    fun `discarding a take never touches a recording the entry keeps`() = runBlocking {
        val repo = diaryRepo(importedEntry("e8"), importedEntry("e9"))
        val kept = audioOnDisk("e8")
        assertTrue(repo.attachAudio("e8", 6_000L))

        repo.discardTake("e8")

        assertTrue("条目正引用着它，那它是内容，不是残留", kept.isFile)

        // 反过来：没被引用的那一段就该真的没掉。
        val orphan = audioOnDisk("e9")
        repo.discardTake("e9")
        assertFalse(orphan.exists())
    }

    /**
     * 启动那一扫只碰没有条目引用的文件。
     *
     * 这条断言之所以值得单独写一遍：这个函数是本项目里唯一一段**会批量删用户数据**的逻辑，
     * 而它判断「没人引用」用的就是文档里那一份条目。条目还没读回来时它对每一段声音都成立，
     * 于是一次排序错误的启动扫会把用户所有录音清光，且没有任何报错。
     * 排序本身挂在 `loadAsync` 里面（见那里的注释），这里守的是它的前提。
     */
    @Test
    fun `the sweep leaves referenced recordings alone`() = runBlocking {
        val entry = importedEntry("e10").copy(audioPath = VoiceMemo.fileName("e10"), audioDurationMs = 9_000L)
        val repo = diaryRepo(entry)
        val kept = audioOnDisk("e10")
        val orphan = audioOnDisk("e11")

        assertEquals(1, repo.sweepUnreferencedAudio())

        assertTrue(kept.isFile)
        assertFalse(orphan.exists())
    }

    /**
     * `diary.json` 坏掉之后，这一次启动**不许**扫录音。
     *
     * 损坏留证成功时 `JsonDocument` 会得到一份合法的空文档并继续允许写盘——于是「没有条目引用
     * 这段录音」对磁盘上每一段都成立，一扫就把用户所有说过的话删干净。而那些 `.m4a` 是不可恢复的，
     * 反倒是 `.corrupt` 里那份 JSON 可以找人修。代价不对称，所以这条路必须堵在代码里，
     * 不能指望「损坏很少发生」。
     */
    @Test
    fun `a corrupt diary does not blank the audio shelf`() = runBlocking {
        val audio = audioRoot()
        val doc = JsonDocument(
            file = File(folder.root, "diary.json"),
            fallback = { DiaryDocument() },
            serializer = DiaryDocument.serializer(),
            scope = scope,
        )
        File(folder.root, "diary.json").writeText("{ not json at all", Charsets.UTF_8)
        val repo = DiaryRepository(doc, folder.root, audio, scope)
        val recording = File(audio, VoiceMemo.fileName("e13")).apply { writeText("m4a-bytes") }

        repo.loadAsync().join()

        assertTrue("损坏之后把用户的录音一次删光了", recording.isFile)
        assertTrue("损坏的字节该留证下来，否则无从追查", folder.root.listFiles()!!.any { ".corrupt" in it.name })
    }

    /** 反过来：读到一份**合法**的空文档时该照扫不误，否则这条隐私保证永远不会兑现。 */
    @Test
    fun `a legitimately empty diary still sweeps`() = runBlocking {
        val audio = audioRoot()
        val doc = JsonDocument(
            file = File(folder.root, "diary.json"),
            fallback = { DiaryDocument() },
            serializer = DiaryDocument.serializer(),
            scope = scope,
        )
        File(folder.root, "diary.json").writeText("""{"schemaVersion":1,"entries":[],"events":[]}""", Charsets.UTF_8)
        val repo = DiaryRepository(doc, folder.root, audio, scope)
        val orphan = File(audio, VoiceMemo.fileName("e14")).apply { writeText("m4a-bytes") }

        repo.loadAsync().join()

        assertFalse(orphan.exists())
    }

    /**
     * 同一条 `Entry` 提交两次只会进去一条。
     *
     * 判重查的是 **id** 而不是内容：`deck.add` 与 `addEvent` 一直按 id 判重，条目这一边过去是
     * 无条件 append，于是三条写入口里有两条守住了唯一性、一条没守。这不是假想的输入——
     * 取景页「保存」的提交是挂起的，那颗按钮在写盘回来之前还按得下去，而第二次提交带着
     * **同一个 Output**（id 也是同一个）再来一遍。
     *
     * 后果不在多一条一样的记录上：时间轴拿条目 id 当 LazyColumn 的 key，重复 key 是
     * `IllegalArgumentException`，用户看到的是一次闪退。
     */
    @Test
    fun `the same entry id is only ever stored once`() = runBlocking {
        val repo = diaryRepo()
        val entry = Entry(id = "e15", photoPath = "e15.jpg", takenAt = now)

        assertTrue("第一次该真的加进去", repo.addEntry(entry))
        assertFalse("第二次是重复提交", repo.addEntry(entry))

        assertEquals(1, repo.document.value.entries.size)
        assertEquals(listOf("e15"), repo.document.value.entries.map { it.id })
    }
}
