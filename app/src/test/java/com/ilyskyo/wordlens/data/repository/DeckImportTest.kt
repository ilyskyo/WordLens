// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.data.repository

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 导入一份牌组文件时的边界。
 *
 * 这里的输入完全由用户从文件选择器里挑，是这份代码少数几个真正的外部边界之一。
 * 两条断言各挡一种难看：格式不对要**安静地**变成 null（不是崩），过大要**在被解析之前**
 * 就停下——解析一个几百 MB 的东西会让进程死在路上，没有提示也没有日志，用户看到的
 * 是「点了导入，App 没了」。
 */
class DeckImportTest {

    private fun stream(text: String): InputStream = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))

    private fun docWith(vararg headwords: String): String {
        val cards = headwords.joinToString(",") { word ->
            """{"id":"$word","headword":"$word","language":"en"}"""
        }
        return """{"schemaVersion":1,"cards":[$cards]}"""
    }

    @Test
    fun `a well formed deck comes back with its cards`() {
        val deck = decodeDeck(stream(docWith("cup", "mug")))
        assertNotNull(deck)
        assertEquals(listOf("cup", "mug"), deck?.cards?.map { it.headword })
    }

    @Test
    fun `garbage and non-deck json both read as null instead of throwing`() {
        assertNull(decodeDeck(stream("not json at all")))
        assertNull(decodeDeck(stream("[1,2,3]")))
        assertNull(decodeDeck(stream("")))
        // 少一个必填字段（cards 里的 id）也是 null：kotlinx 默认值兜得住可选字段，
        // 兜不住的就是不完整的文件，那种文件不该被半收进来。
        assertNull(decodeDeck(stream("""{"cards":[{"headword":"cup"}]}""")))
    }

    @Test
    fun `a file over the byte cap is refused before anything is parsed`() {
        // 上限参数化就是为了这条测试能便宜地跑到 8 MB 那个分支上。
        val huge = docWith("cup") + " ".repeat(4096)
        assertNull(decodeDeck(stream(huge), maxBytes = 64))
    }

    /** 边界要正好：不多读一字节，也不误杀恰好等于上限的文件。 */
    @Test
    fun `the cap is exact at the byte boundary`() {
        val exact = """{"cards":[]}"""
        val bytes = exact.toByteArray(Charsets.UTF_8).size
        assertNotNull(decodeDeck(stream(exact), maxBytes = bytes))
        assertNull(decodeDeck(stream(exact), maxBytes = bytes - 1))
    }

    @Test
    fun `the text entry point keeps the same tolerance`() {
        assertNull(decodeDeckText("{"))
        assertEquals(1, decodeDeckText(docWith("cup"))?.cards?.size)
    }
}
