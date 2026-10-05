// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.search

import com.ilyskyo.wordlens.data.model.LexiconIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「已收」标记的失效。
 *
 * ## 为什么单独测这一小段
 *
 * 它原来是**只增不减**的：收下 soba 之后把那张卡删掉，词典那一格会重新出现在建议里
 * （牌组不再拥有它，`owned` 那层过滤放它回来了），而按钮还按着旧标记灰着、写着「已收」。
 * 那是两件事叠在一起：一个说谎的读数，加一条死路——唯一的出路是退出这一页让 ViewModel 死掉。
 *
 * `SearchViewModel` 需要 `AppContainer`，JVM 里造不出来，所以把判据抽成了
 * [retainingOwned]：这一小段就是那次修复的全部实质内容，抽出来才断言得到。
 */
class SearchJustAddedTest {

    private val words = mapOf("en.soba" to "soba", "en.cup" to "cup")

    private fun headwordOf(id: String): String? = words[id]

    /**
     * 两边的词头都要过 `LexiconIndex.normalize`，这是这一段的契约本身。
     *
     * `owned` 在生产里是 `deck.snapshot().map { normalize(it.headword) }`（见
     * `SearchViewModel.stillOwned`），而标记那一头也在函数里过同一个 normalize。
     * 这里不直接写 `"soba"` 是因为 normalize **不是**「小写加去空格」那种直观形状：
     * 它把每个字母当成一个 token（`cup` → `c u p`），所以只有两边都过同一个函数才相等。
     * 单测把这个写出来，是为了让下一个改 normalize 的人在这里撞到，而不是在牌组去重上。
     */
    @Test
    fun `a marker survives while the deck still owns the word`() {
        val kept = setOf("en.soba").retainingOwned(
            owned = setOf(LexiconIndex.normalize("soba")),
            headwordOf = ::headwordOf,
        )
        assertEquals(setOf("en.soba"), kept)
    }

    /** 这一次修复的本体：卡被删掉，那个词就得能重新收。 */
    @Test
    fun `a marker is released once the card is deleted`() {
        val kept = setOf("en.soba").retainingOwned(owned = emptySet(), headwordOf = ::headwordOf)
        assertTrue("牌组已经不认得这个词，标记还在——按钮会永远灰着", kept.isEmpty())
    }

    /** 词典里查不到的 id（用户自己加的那条被删了）同样失效：留着等于永久灰着一个按钮。 */
    @Test
    fun `an unknown id cannot hold the button down forever`() {
        val kept = setOf("en.gone").retainingOwned(owned = setOf("soba"), headwordOf = ::headwordOf)
        assertTrue(kept.isEmpty())
    }

    @Test
    fun `only the markers whose word is owned survive`() {
        val kept = setOf("en.soba", "en.cup").retainingOwned(
            owned = setOf(LexiconIndex.normalize("cup")),
            headwordOf = ::headwordOf,
        )
        assertEquals(setOf("en.cup"), kept)
    }

    /** 大小写与首尾空格不影响判等——但只在两边都过 normalize 的前提下。 */
    @Test
    fun `matching ignores case and padding the way the index does`() {
        val kept = setOf("en.cup").retainingOwned(
            owned = setOf(LexiconIndex.normalize("  CUP ")),
            headwordOf = ::headwordOf,
        )
        assertEquals(setOf("en.cup"), kept)
    }

    @Test
    fun `an empty marker set stays empty without touching the deck`() {
        assertEquals(emptySet<String>(), emptySet<String>().retainingOwned(owned = setOf("soba"), headwordOf = ::headwordOf))
    }
}
