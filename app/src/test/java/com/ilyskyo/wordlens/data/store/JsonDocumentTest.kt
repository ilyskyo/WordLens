// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.data.store

import com.ilyskyo.wordlens.data.model.WordCard
import com.ilyskyo.wordlens.data.repository.DeckDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.UncheckedIOException

/**
 * 「读失败」与「文档损坏」必须走相反的处理，而这两类失败在真机上都会发生。
 *
 * 之所以要 [DiskOps] 这一层接缝，是因为「文件读得到、但改名失败」这种组合在真机上出现过
 * （跨卷、存储拦截器、瞬时抖动），而在纯 File API 上根本造不出来 —— 恰恰是这几种组合决定
 * 用户的数据是被保住还是被清空。
 */
class JsonDocumentTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun document(disk: FakeDisk, currentSchema: Int = DeckDocument.CURRENT_SCHEMA) = JsonDocument(
        file = File(folder.root, "deck.json"),
        fallback = { DeckDocument() },
        serializer = DeckDocument.serializer(),
        scope = scope,
        disk = disk,
        currentSchema = currentSchema,
        schemaOf = DeckDocument::schemaVersion,
    )

    private fun card(id: String) = WordCard(id = id, headword = id, language = "en")

    /** 一次瞬时 IO 抖动：不能改名，更不能把空文档写回磁盘。 */
    @Test
    fun `a transient read failure never overwrites the file`() = runBlocking {
        val disk = FakeDisk().apply {
            // 文件必须在：不存在是正常首启，走不到「读失败」那条分支。
            contents[FILE] = """{"schemaVersion":1,"cards":[],"log":[]}"""
            readFailure = IOException("storage busy")
        }
        val doc = document(disk)

        doc.load()

        assertTrue("the bytes must be left alone", disk.renamed.isEmpty())
        assertFalse(doc.persisting)

        doc.update { it.copy(cards = listOf(card("cup")) ) }

        assertTrue("writing now would overwrite data we never read: ${disk.writtenTo}", disk.writtenTo.isEmpty())
        // 内存里照常反映用户的编辑，界面不会卡住。
        assertEquals(1, doc.current.cards.size)
    }

    /** 磁盘恢复后重试，落盘能力要跟着恢复。 */
    @Test
    fun `retryLoad restores persistence once the disk answers`() = runBlocking {
        val disk = FakeDisk().apply {
            contents[FILE] = WordLensJson.instance.encodeToString(DeckDocument.serializer(), DeckDocument())
            readFailure = IOException("busy")
        }
        val doc = document(disk)
        doc.load()
        assertFalse(doc.persisting)

        disk.readFailure = null
        assertTrue(doc.retryLoad())
        doc.update { it.copy(cards = listOf(card("cup"))) }
        assertEquals(listOf(FILE), disk.writtenTo)
    }

    /** 真损坏：留证恰好一次，然后允许从空文档继续写。 */
    @Test
    fun `unparseable bytes are preserved exactly once`() = runBlocking {
        val disk = FakeDisk().apply { contents[FILE] = "{{{ not json" }
        val doc = document(disk)

        doc.load()

        assertEquals(1, disk.renamed.size)
        assertTrue("salvaged name: ${disk.renamed[0]}", disk.renamed[0].second.contains("deck.json.corrupt-"))
        assertTrue(doc.persisting)
        doc.update { it.copy(cards = listOf(card("cup"))) }
        assertEquals(1, disk.writtenTo.size)
    }

    /** 留证失败时宁可不写：数据不见了却无从追查，比这次编辑丢掉糟得多。 */
    @Test
    fun `a corrupt file that cannot be preserved blocks writes`() = runBlocking {
        val disk = FakeDisk().apply {
            contents[FILE] = "{{{ not json"
            renameFailure = true
            copyFailure = true
        }
        val doc = document(disk)

        doc.load()

        assertFalse(doc.persisting)
        doc.update { it.copy(cards = listOf(card("cup"))) }
        assertTrue(disk.writtenTo.isEmpty())
    }

    /** 磁盘上的格式比本构建还新：可以显示，但绝不回写。 */
    @Test
    fun `a newer schema is shown read-only`() = runBlocking {
        val disk = FakeDisk().apply { contents[FILE] = """{"schemaVersion":99,"cards":[],"log":[]}""" }
        val doc = document(disk)

        doc.load()

        assertEquals(99, doc.current.schemaVersion)
        assertFalse(doc.persisting)
        doc.update { it.copy(cards = listOf(card("cup"))) }
        assertTrue(disk.writtenTo.isEmpty())
    }

    /** 文件不存在是正常首启，不是失败。 */
    @Test
    fun `a missing document starts fresh and persists normally`() = runBlocking {
        val disk = FakeDisk()
        val doc = document(disk)

        doc.load()

        assertTrue(doc.persisting)
        doc.update { it.copy(cards = listOf(card("cup"))) }
        assertEquals(listOf(FILE), disk.writtenTo)
    }

    /** 分类错了就会走反的处理，所以这条单独钉住。 */
    @Test
    fun `unknown failures fall on the safe side`() {
        assertEquals(ReadFailure.Io, classifyReadFailure(IOException("busy")))
        assertEquals(ReadFailure.Io, classifyReadFailure(SecurityException("denied")))
        assertEquals(ReadFailure.Io, classifyReadFailure(UncheckedIOException(IOException())))
        assertEquals(ReadFailure.Corrupt, classifyReadFailure(
            SerializationException("broken"),
        ))
        // NPE 之类不能推断成「文件坏了」——它更可能是我们自己的 bug，改名会毁掉现场。
        assertEquals(ReadFailure.Unknown, classifyReadFailure(NullPointerException()))
    }

    /** 真实文件系统：写成功就不该留下 .tmp，重复写要覆盖而不是追加。 */
    @Test
    fun `real disk writes leave no temp file`() {
        val target = File(folder.root, "deck.json")
        assertEquals(WriteResult.Written, RealDiskOps.write(target, """{"schemaVersion":1}"""))
        assertEquals("""{"schemaVersion":1}""", target.readText())
        assertEquals(WriteResult.Written, RealDiskOps.write(target, "second"))
        assertEquals("second", target.readText())
        assertTrue(
            "leftovers: ${folder.root.list()?.joinToString()}",
            folder.root.list()!!.filter { it != "deck.json" }.isEmpty(),
        )
    }

    /** `.bak` 那条退路只在改名不动时走；能改名时绝不该产生备份文件。 */
    @Test
    fun `no backup file when the rename works`() {
        val target = File(folder.root, "deck.json")
        RealDiskOps.write(target, "one")
        RealDiskOps.write(target, "two")
        assertNotEquals(0, target.length())
        assertFalse(File(folder.root, "deck.json.bak").exists())
    }

    private companion object {
        const val FILE = "deck.json"
    }
}

/** 可控磁盘：把真机上会出现、但纯 File API 造不出来的组合摆出来。 */
private class FakeDisk : DiskOps {
    val contents = LinkedHashMap<String, String>()
    val renamed = mutableListOf<Pair<String, String>>()
    val writtenTo = mutableListOf<String>()
    var readFailure: Throwable? = null
    var renameFailure = false
    var copyFailure = false

    override fun exists(file: File): Boolean = contents.containsKey(file.name)

    override fun readText(file: File): String {
        readFailure?.let { throw it }
        return contents[file.name] ?: throw IOException("no such file: ${file.name}")
    }

    override fun rename(from: File, to: File): Boolean {
        if (renameFailure) return false
        val body = contents.remove(from.name) ?: return false
        contents[to.name] = body
        renamed += from.name to to.name
        return true
    }

    override fun copy(from: File, to: File): Boolean {
        if (copyFailure) return false
        val body = contents[from.name] ?: return false
        contents[to.name] = body
        return true
    }

    override fun write(target: File, text: String): WriteResult {
        contents[target.name] = text
        writtenTo += target.name
        return WriteResult.Written
    }
}
