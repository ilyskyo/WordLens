// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分享进来的文本怎么变成一个能用的查询。
 *
 * 这是 manifest 里那两个 PROCESS_TEXT / SEND 入口唯一可测的一段：剩下的都是 intent 布线，
 * 只能在真机上从别的 App 点进来验。而恰恰这一小段决定用户看到的是真话还是假话——
 * 把整段选区原样丢进搜索，词典一定不命中，界面回一句「什么都没找到」，
 * 那句话说的是搜索，听起来却像「这个词你不认识」。
 */
class SharedQueryTest {

    @Test
    fun `nothing shared is not a query`() {
        assertNull(SearchViewModel.queryFromShared(null))
        assertNull(SearchViewModel.queryFromShared(""))
        // 全空白也要算「没有」：空查询会把「最近收录」那一整屏摊开，
        // 用户以为分享成功了，实际只是打开了一个页面。
        assertNull(SearchViewModel.queryFromShared("   \n\t "))
    }

    @Test
    fun `surrounding whitespace is stripped`() {
        assertEquals("cup", SearchViewModel.queryFromShared("  cup  "))
    }

    @Test
    fun `the first non-blank line wins`() {
        assertEquals("cup", SearchViewModel.queryFromShared("cup\nspoon\nfork"))
        // 选区常常带着前后各一行空白（有些浏览器这么给），那不是内容。
        assertEquals("miso soup", SearchViewModel.queryFromShared("\n\tmiso soup\n"))
    }

    @Test
    fun `a short sentence stays a sentence`() {
        // 日记的匹配是 contains：一句选中的话本来就可能命中某条记录，
        // 只取第一个词反而会把它丢掉。
        assertEquals("the soup was clear", SearchViewModel.queryFromShared("the soup was clear"))
    }

    @Test
    fun `a long selection is cut instead of searched verbatim`() {
        val query = SearchViewModel.queryFromShared("a".repeat(200))!!
        assertEquals(MAX, query.length)
        assertTrue(query.all { it == 'a' })
    }

    @Test
    fun `the cut never leaves a dangling space`() {
        // 80 个字符正好落在 "word " 的尾巴上：切完必须把尾部空白收掉，
        // 否则查询里带一个看不见的空格，匹配结果与用户看到的字面不一致。
        val shared = "word ".repeat(40).trim()
        val query = SearchViewModel.queryFromShared(shared)!!
        assertEquals(query, query.trim())
        assertTrue(query.length <= MAX)
    }

    private companion object {
        /** 与 SearchViewModel.MAX_SHARED_QUERY 同值；改那里要改这里。 */
        const val MAX = 80
    }
}
