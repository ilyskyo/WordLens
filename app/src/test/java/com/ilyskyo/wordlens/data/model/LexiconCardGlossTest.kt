// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 铸卡时释义从词典进卡的那条路。
 *
 * 这张卡背面写什么，是复习里唯一被人读的东西。它比「有没有释义」更容易出错的是**写进去一句
 * 不是释义的话**：借来的别的语言、或者干脆是词头自己。前者让中文用户被日文评级，后者铸出一张
 * 正面背面同字的卡——那种卡不能复习，却会一直躺在队列里，而且这个谎是**落在磁盘上**的，
 * 修好设置也不会回头改它。所以这一整类错必须在铸卡这一侧就断掉。
 */
class LexiconCardGlossTest {

    private fun entry(glosses: Map<String, String>, words: Map<String, String> = mapOf("en" to "umbrella", "zh" to "伞")) =
        LexiconEntry(id = "en.umbrella", words = words, glosses = glosses)

    @Test
    fun `the meaning written in the native language is kept`() {
        val card = entry(mapOf("zh" to "伞", "ja" to "傘")).toCard(Lang.ENGLISH, Lang.CHINESE, EntrySource.MANUAL)
        assertEquals(mapOf("zh" to "伞", "ja" to "傘"), card?.glosses)
    }

    @Test
    fun `a meaning from another language is not moved into the native slot`() {
        // 词典里只有日文那一条。旧实现会把它写进 glosses["zh"]，中文用户翻到背面看到的是「傘」，
        // 而这一行正是他要被评级的位置。
        val card = entry(mapOf("ja" to "傘")).toCard(Lang.ENGLISH, Lang.CHINESE, EntrySource.MANUAL)
        assertNull(card?.glosses?.get("zh"))
        assertEquals("傘", card?.glosses?.get("ja"))
    }

    @Test
    fun `a card without any meaning does not write its own headword as the translation`() {
        // 旧实现退到 `?: w`，于是 glosses["zh"] == "umbrella" == headword：一张正面背面同字的卡。
        val card = entry(emptyMap()).toCard(Lang.ENGLISH, Lang.CHINESE, EntrySource.MANUAL)
        assertEquals("umbrella", card?.headword)
        assertNull(card?.glosses?.get("zh"))
    }

    @Test
    fun `a native gloss that is just the headword again is not a translation`() {
        // 词典数据脏成这样的条目是有的（`{"zh": "umbrella"}`）。留着它就等于自己铸化石。
        val card = entry(mapOf("zh" to " umbrella ", "ja" to "傘")).toCard(Lang.ENGLISH, Lang.CHINESE, EntrySource.MANUAL)
        assertNull(card?.glosses?.get("zh"))
        assertEquals("傘", card?.glosses?.get("ja"))
    }

    @Test
    fun `a monolingual card keeps a definition that happens to equal the headword`() {
        // 只学英文的人：母语就是英文时，释义与词头同字是**正常**的（shampoo 的英文释义就是它自己）。
        // 这一格不该被滤掉——滤掉它是在删用户唯一能读的那一行。
        val card = entry(mapOf("en" to "shampoo"), words = mapOf("en" to "shampoo"))
            .toCard(Lang.ENGLISH, Lang.CHINESE, EntrySource.MANUAL)
        assertEquals("shampoo", card?.glosses?.get("en"))
    }
}
