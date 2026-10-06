// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 日语与韩语的注音层（`assets/lexicon/gloss-*.json`）的回归锁。
 *
 * 内置词典 `en.json` 是 ECDICT 生成的，12006 条里只有 en 与 zh 两种语言。界面却有四种：
 * 于是日语与韩语母语的人铸出的卡背面没有母语释义，回忆方向下正面背面同字而被队列跳过——
 * 核心功能对这两个语言包从来没通过。这两份补丁补的是**相机给得出来的那 155 个概念**。
 *
 * 这里盯的是三件编译与运行时都不会报的事：补丁指着一个不存在的条目 id、
 * 补进去的其实是从英文抄来的一行拉丁字母、以及两种语言补的概念集合悄悄走开。
 */
class GlossOverlayTest {

    private val dir = File("src/main/assets/lexicon")
    private val json = Json { ignoreUnknownKeys = true }

    private val builtin: List<LexiconEntry> =
        json.decodeFromString(LexiconFile.serializer(), File(dir, "en.json").readText(Charsets.UTF_8)).entries

    private fun overlay(tag: String): GlossOverlayFile =
        json.decodeFromString(
            GlossOverlayFile.serializer(),
            File(dir, "gloss-$tag.json").readText(Charsets.UTF_8),
        )

    @Test
    fun everyOverlaidIdResolvesToAShippedEntryAndCarriesThatLanguage() {
        val ids = builtin.map { it.id }.toSet()
        assertEquals("内置词典里有重复 id", ids.size, builtin.size)

        for (tag in listOf("ja", "ko")) {
            val file = overlay(tag)
            assertEquals("注音层的 language 必须与文件名一致", tag, file.language)
            assertTrue("注音层太薄，不足以覆盖相机给得出的那一层", file.entries.size >= 150)

            val unknown = file.entries.filterNot { it.id in ids }.map { it.id }
            assertTrue("补丁指向不存在的条目: $unknown", unknown.isEmpty())

            val blank = file.entries.filter { it.word.isBlank() }.map { it.id }
            assertTrue("补进去的是空白: $blank", blank.isEmpty())

            // 这一条防的不是拼写，是「拿英文顶上去」这件事换了一种形式重新出现：
            // 只要有人用机翻或原样抄一遍，字符集检查就会红。
            val wrongScript = file.entries.filterNot { it.word.matches(scriptOf(tag)) }
            assertTrue("$tag 的注音里有 $wrongScript 不是这个文字的书写系统", wrongScript.isEmpty())
        }
    }

    @Test
    fun japaneseAndKoreanOverlaysCoverTheSameConcepts() {
        val ja = overlay("ja").entries.map { it.id }.toSet()
        val ko = overlay("ko").entries.map { it.id }.toSet()
        assertEquals("两种语言补的概念必须同一批，否则一个语言包会单独瘸掉", ja, ko)
    }

    @Test
    fun withGlossFillsAnEmptySlotAndNeverClobbersBuiltinData() {
        val cup = builtin.first { it.id == "en.cup" }
        val patched = cup.withGloss("ja", "カップ")
        assertEquals("カップ", patched.words["ja"])
        assertEquals("カップ", patched.glosses["ja"])

        // 内置那一层将来真补上 ja 时，补丁不该把更好的数据换成手工的那一条。
        val twice = cup.withGloss("ja", "カップ").withGloss("ja", "コップ")
        assertEquals("已有的那一格不许被覆盖", "カップ", twice.words["ja"])

        // 别的语言不受影响：补 ja 不该让 zh 那一份变得不可读。
        assertEquals(cup.glosses["zh"], twice.glosses["zh"])
        assertNull("补丁不该凭空造出没有的语言", twice.words["de"])
    }

    /** 该语言自己的书写系统：日文假名或汉字，谚文。允许末尾的标点与空白。 */
    private fun scriptOf(tag: String): Regex = when (tag) {
        "ja" -> Regex(".*[\\u3040-\\u30ff\\u4e00-\\u9fff].*")
        "ko" -> Regex(".*[\\uac00-\\ud7af].*")
        else -> Regex(".*")
    }
}
