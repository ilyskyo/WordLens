// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `LexiconIndex.normalize` 必须**按词**归一化。
 *
 * ## 这一条守的是一个安静但致命的错
 *
 * 旧实现把字符串 `map` 成 `List<Char>` 之后 `joinToString(" ")`——那个分隔符作用在
 * **每两个字符之间**，不是只在标点处，于是整串被拆成字母。紧接着按 `ARTICLES` 过滤时
 * `it` 已经是单字符，集合里的冠词 `"a"` 就把**每一个字母 a 从所有词里删掉**：
 *
 * - `normalize("tar")` = `"t r"`、`normalize("hat")` = `"h t"`
 * - `normalize("traffic light")` = `"t r f f i c l i g h t"`
 *
 * 后面的 n-gram 那一步因此变成**在字母序列上滑窗**，检测器报 "traffic light" 时
 * 命中 `en.tar`「焦油」与 `en.hat`「帽子」，分数还是双词那一档的 0.78——看起来完全像正常匹配。
 * 而调用方（`VisionRepository`）一次 `confidence` 都没读过，所以没有门槛会把它拦下。
 *
 * 全程没有异常、没有日志、编译与 lint 都过：两边同削一个字母，整词命中侥幸还对，
 * 只有复合标签会冒出别的词。这类「逻辑自洽但语义错位」只能靠断言钉。
 */
class LexiconNormalizeTest {

    @Test
    fun wordsStayWordsInsteadOfBeingSplitIntoLetters() {
        assertEquals("traffic light", LexiconIndex.normalize("traffic light"))
        assertEquals("tar", LexiconIndex.normalize("tar"))
        assertEquals("hat", LexiconIndex.normalize("hat"))
        assertEquals("fire hydrant", LexiconIndex.normalize("Fire Hydrant"))
    }

    @Test
    fun articlesAreDroppedAsWholeWordsAndNeverAsTheLetterA() {
        assertEquals("cat", LexiconIndex.normalize("a cat"))
        assertEquals("apple", LexiconIndex.normalize("an apple"))
        assertEquals("bus", LexiconIndex.normalize("the bus"))
        // 这三条才是旧实现真正塌掉的地方：词里的字母 a 一个都不该少。
        assertEquals("atlas", LexiconIndex.normalize("atlas"))
        assertEquals("banana", LexiconIndex.normalize("banana"))
        assertEquals("area", LexiconIndex.normalize("an area"))
    }

    @Test
    fun punctuationCollapsesAndOtherScriptsSurvive() {
        assertEquals("coffee cup", LexiconIndex.normalize("Coffee cup!"))
        assertEquals("cup of tea", LexiconIndex.normalize("  cup   of  tea  "))
        // 用户自己补的词条可能是任何文字：按 ASCII 过滤会把 café 削成 caf。
        assertEquals("café", LexiconIndex.normalize("café"))
        assertEquals("수건", LexiconIndex.normalize("수건"))
        assertEquals("カップ", LexiconIndex.normalize("カップ"))
    }

    @Test
    fun compoundLabelsNoLongerMatchUnrelatedShortWords() {
        val entries = Json { ignoreUnknownKeys = true }.decodeFromString(
            LexiconFile.serializer(),
            File("src/main/assets/lexicon/en.json").readText(Charsets.UTF_8),
        ).entries
        val index = LexiconIndex(entries)

        for ((label, forbidden) in listOf(
            "traffic light" to setOf("en.tar", "en.hat"),
            "fire hydrant" to setOf("en.fir", "en.ire", "en.fair"),
            "stop sign" to setOf("en.top", "en.psi"),
            "parking meter" to setOf("en.kin"),
            "teddy bear" to setOf("en.dad", "en.tea"),
        )) {
            val ids = index.match(listOf(label to 1.0f)).map { it.entry.id }.toSet()
            val hit = ids.intersect(forbidden)
            assertFalse("$label 命中了与它无关的短词：$hit", hit.isNotEmpty())
        }
    }

    @Test
    fun exactConceptsStillWinAfterTheFix() {
        // 修 normalize 不能把「本来就对的那条路」一起削掉：cell phone 的词头是不带空格的
        // cellphone，整词命中必须仍然是第一名。
        val entries = Json { ignoreUnknownKeys = true }.decodeFromString(
            LexiconFile.serializer(),
            File("src/main/assets/lexicon/en.json").readText(Charsets.UTF_8),
        ).entries
        val hits = LexiconIndex(entries).match(listOf("cell phone" to 1.0f))
        assertTrue("cell phone 一个都没命中", hits.isNotEmpty())
        assertEquals("en.cellphone", hits.first().entry.id)
        // 0.95 是「只差一个空格」那一档（整词 1.0 / 别名 0.97 / 空格差异 0.95 / n-gram 0.78..0.58）。
        assertTrue("命中档位不对：${hits.first().confidence}", hits.first().confidence >= 0.9f)
    }
}
