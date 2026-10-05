// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 页面栈的编解码。
 *
 * 这段代码唯一的失败模式是**恢复出错**，而它发生在用户看不见的地方：转屏或进程被杀之后回来，
 * 栈要么恢复了、要么没恢复，两种都不会报错。所以断言写在编码这一层——它是「转屏不许弄丢
 * 你正看着的那一页」这条标准真正落地的地方。
 */
class PageStackTest {

    @Test
    fun `every page survives a round trip in order`() {
        val stack = listOf(
            Page.Home,
            Page.Detail("entry-7"),
            Page.Capture,
            Page.Settings,
        )
        assertEquals(stack, PageStack.decode(PageStack.encode(stack)))
    }

    @Test
    fun `the detail page keeps its entry id`() {
        // 恢复出来必须是**同一条**日记：丢掉 id 就等于把用户送进一个空壳详情页。
        val encoded = PageStack.encode(listOf(Page.Home, Page.Detail("entry-42")))
        assertEquals(listOf("home", "detail:entry-42"), encoded)
        assertEquals(Page.Detail("entry-42"), PageStack.decode(encoded)[1])
    }

    @Test
    fun `unknown entries are dropped instead of taking the whole stack down`() {
        // 旧版本或改过名之后的字符串：认不出来的那一格丢掉，其余照旧。
        val restored = PageStack.decode(listOf("home", "who-knows-what", "settings"))
        assertEquals(listOf(Page.Home, Page.Settings), restored)
    }

    @Test
    fun `an empty or garbage payload lands on home`() {
        assertEquals(listOf(Page.Home), PageStack.decode(emptyList()))
        assertEquals(listOf(Page.Home), PageStack.decode(listOf("detail:", "detail:")))
        // "detail:" 后面没有 id：这不是一个可恢复的页面，而空栈会让返回键没有意义。
    }

    @Test
    fun `push goes deeper and pop comes back without ever emptying`() {
        var stack = PageStack.initial
        stack = PageStack.push(stack, Page.Capture)
        stack = PageStack.push(stack, Page.Settings)
        assertEquals(listOf(Page.Home, Page.Capture, Page.Settings), stack)

        stack = PageStack.pop(stack)
        assertEquals(listOf(Page.Home, Page.Capture), stack)

        stack = PageStack.pop(stack)
        stack = PageStack.pop(stack)
        // 弹到栈底还继续弹的话，`top` 就没有值了——返回键该是「已经在这里了」，不是消失。
        assertEquals(listOf(Page.Home), stack)
    }
}
