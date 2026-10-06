// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户自己补的那一条词条。
 *
 * ## 这一小段工厂函数为什么要测
 *
 * 它是「我不认识这个词」那条路的起点，而这条路的全部价值在于**下一次认得**：
 * id 稳定，同一个词第二次保存才是覆盖而不是攒出三条只差空格的副本；`words` 的键必须正好是
 * 目标语的 tag，否则 [LexiconEntry.toCard] 拿不到词、条目进不了复习队列；索引按 `headword` 的
 * 规范化形式建，所以词里多一个空格都会让它查不到。这三件事错了都不报错，只是那条路悄悄断掉。
 */
class LexiconUserEntryTest {

    @Test
    fun `an added word is keyed by the language being learned`() {
        val entry = LexiconEntry.userEntry("soba", "荞麦面", Lang.ENGLISH, Lang.CHINESE)
        assertNotNull(entry)
        assertEquals("user-en.soba", entry!!.id)
        assertEquals(mapOf("en" to "soba"), entry.words)
        assertEquals(mapOf("zh" to "荞麦面"), entry.glosses)
        assertEquals("soba", entry.headword)
        assertEquals("en", entry.primaryLanguage)
    }

    /** id 由词本身算出来，所以大小写与首尾空格不会攒出重复条目。 */
    @Test
    fun `the same word lands on the same id`() {
        val a = LexiconEntry.userEntry("  Soba ", "荞麦面", Lang.ENGLISH, Lang.CHINESE)
        val b = LexiconEntry.userEntry("soba", "另一种说法", Lang.ENGLISH, Lang.CHINESE)
        assertEquals(a!!.id, b!!.id)
    }

    @Test
    fun `a phrase keeps one id`() {
        val entry = LexiconEntry.userEntry("Sweet  Potato", "红薯", Lang.ENGLISH, Lang.CHINESE)
        assertEquals("user-en.sweet-potato", entry!!.id)
    }

    /** 非拉丁的目标语：headword 取「第一个非空」，正是这条路能走通的形状。 */
    @Test
    fun `a non-latin target language still has a headword`() {
        val entry = LexiconEntry.userEntry("カップ", "cup", Lang.JAPANESE, Lang.ENGLISH)
        assertEquals("user-ja.カップ", entry!!.id)
        assertEquals("カップ", entry.headword)
        assertEquals("ja", entry.primaryLanguage)
    }

    @Test
    fun `blank input is not an entry`() {
        assertNull(LexiconEntry.userEntry("", "意思", Lang.ENGLISH, Lang.CHINESE))
        assertNull(LexiconEntry.userEntry("   ", "意思", Lang.ENGLISH, Lang.CHINESE))
        assertNull(LexiconEntry.userEntry("soba", "", Lang.ENGLISH, Lang.CHINESE))
        assertNull(LexiconEntry.userEntry("soba", "  ", Lang.ENGLISH, Lang.CHINESE))
    }

    /**
     * 补完之后必须能长成一张复习卡。
     *
     * `toCard` 只认 `words[target.tag]`，所以这一条断言守的是「加进去」与「能进队列」之间
     * 那一步——它断了的话，这一节就只是一个存了字的文本框。
     */
    @Test
    fun `an added word becomes a card in the review direction`() {
        val entry = LexiconEntry.userEntry("soba", "荞麦面", Lang.ENGLISH, Lang.CHINESE)!!
        val card = entry.toCard(target = Lang.ENGLISH, native = Lang.CHINESE, source = EntrySource.MANUAL)
        assertNotNull(card)
        assertEquals("soba", card!!.headword)
        assertEquals("荞麦面", card.glosses["zh"])
        assertEquals(EntrySource.MANUAL, card.source)
    }

    /** 下一次真的查得到：索引按 headword 建，用户那一层合进去之后同一个词不再落空。 */
    @Test
    fun `the index finds a word the user added`() {
        val entry = LexiconEntry.userEntry("soba", "荞麦面", Lang.ENGLISH, Lang.CHINESE)!!
        val found = LexiconIndex(listOf(entry)).search("soba")
        assertTrue("加进去之后仍然搜不到，这条路就是断的", found.any { it.id == entry.id })
    }

    /**
     * 冠词本身也要查得到。
     *
     * `normalize` 把 a / an / the / some 整词剔掉，这四个词的规范形式是**空串**；
     * 而空串在子串匹配里等于「全表」，在调用方又被当作「还没输入」。三件事叠在一起的后果是：
     * 词典里明摆着收着 "a"，搜索页输入 a 却回一句「Nothing matches」——四个常用英文词
     * 在自家词典上查不出来，而界面给的理由是「换个词试试」。
     */
    @Test
    fun `an article is still findable as a word`() {
        val article = LexiconEntry.userEntry("a", "一个（不定冠词）", Lang.ENGLISH, Lang.CHINESE)
        val other = LexiconEntry.userEntry("apple", "苹果", Lang.ENGLISH, Lang.CHINESE)
        val index = LexiconIndex(listOfNotNull(article, other))

        assertEquals(listOf("a"), index.search("a").map { it.headword })
        // 反过来：空规范查询不能被放开成整张表，否则输入 "the" 会列出前 N 条不相干的词。
        assertTrue("查一个词典里没有的冠词应该什么都不是，而不是全表",
            index.search("the").isEmpty())
    }

    /**
     * 注音不跨语言借用。
     *
     * 中文目标的卡上出现英语音标，读起来像「这题的答案顺便写在题目上了」；而 `cardFor`
     * 明确写着正面是母语释义时不能给音标。守的就是这一格。
     */
    @Test
    fun `a card never borrows another language's phonetics`() {
        val entry = LexiconEntry.userEntry("umbrella", "伞", Lang.ENGLISH, Lang.CHINESE)
            .let { requireNotNull(it).copy(ipa = mapOf("en" to "/ʌmˈbrelə/")) }

        assertEquals("/ʌmˈbrelə/", entry.ipaFor(Lang.ENGLISH))
        assertNull("中文目标没有自己的注音，就该空着", entry.ipaFor(Lang.CHINESE))
        assertNull(entry.toCard(Lang.CHINESE, Lang.ENGLISH, EntrySource.MANUAL)?.ipa)
        assertEquals("/ʌmˈbrelə/", entry.toCard(Lang.ENGLISH, Lang.CHINESE, EntrySource.MANUAL)?.ipa)
    }
}
