// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按字节上限的 LRU。
 *
 * 值类型直接用 `Int`，并让 `sizeOf = { it }`——**值本身就是它的字节数**，这样每条断言都能
 * 手算对账，而不用在测试里再写一遍被测逻辑。真实调用方给的是位图的像素字节数。
 */
class ByteLruCacheTest {

    private fun cache(cap: Long) = ByteLruCache<String, Int>(maxBytes = cap, sizeOf = { it })

    @Test
    fun `evicts the least recently used entry when over budget`() {
        val cache = cache(10)
        assertTrue(cache.put("a", 4))
        assertTrue(cache.put("b", 4))
        assertEquals(8L, cache.sizeBytes)
        // 再放一条就 12 > 10：按插入顺序，最久未用的是 a。
        assertTrue(cache.put("c", 4))
        assertNull(cache.get("a"))
        assertEquals(4, cache.get("b"))
        assertEquals(4, cache.get("c"))
        assertEquals(8L, cache.sizeBytes)
        assertEquals(1, cache.evictionCount)
    }

    /** 命中必须把条目提到「年轻」端：时间轴滚回上一页时不该重新解码。 */
    @Test
    fun `a hit protects the entry from the next eviction`() {
        val cache = cache(10)
        cache.put("a", 4)
        cache.put("b", 4)
        cache.get("a")
        cache.put("c", 4)
        assertEquals(4, cache.get("a"))
        assertNull("b is the one nobody touched", cache.get("b"))
    }

    /** 同一个 key 换值要先扣旧账，否则反复替换会把 sizeBytes 越推越高。 */
    @Test
    fun `reputting a key replaces its bytes instead of adding them`() {
        val cache = cache(10)
        cache.put("a", 3)
        cache.put("a", 5)
        assertEquals(5L, cache.sizeBytes)
        assertEquals(5, cache.get("a"))
        cache.put("a", 2)
        assertEquals(2L, cache.sizeBytes)
        assertEquals("没有真正超预算，一次都不该淘汰", 0, cache.evictionCount)
    }

    /**
     * 单条自己就超上限：不存，而且**已有的条目一个都不动**。
     * 如果让它进缓存再被淘汰，等于用一张放不下的图把整缓存冲掉，还让 evictionCount
     * 看起来像缓存在正常工作。
     */
    @Test
    fun `an entry bigger than the cap is refused without evicting anything`() {
        val cache = cache(10)
        cache.put("a", 6)
        assertFalse(cache.put("huge", 11))
        assertNull(cache.get("huge"))
        assertEquals(6, cache.get("a"))
        assertEquals(6L, cache.sizeBytes)
        assertEquals(0, cache.evictionCount)
        // 超上限的替换也要把同 key 的旧值作废：它对应的已经不是这个 value 了。
        assertFalse(cache.put("a", 12))
        assertNull(cache.get("a"))
    }

    /** 边界：正好等于上限的一条存得下，并挤掉别人。 */
    @Test
    fun `an entry exactly the size of the cap is kept`() {
        val cache = cache(10)
        cache.put("a", 6)
        assertTrue(cache.put("full", 10))
        assertEquals(10L, cache.sizeBytes)
        assertEquals(10, cache.get("full"))
        assertNull(cache.get("a"))
        assertEquals(1, cache.evictionCount)
    }

    /** 0 字节条目（调用方用来记「这文件解不出来」的占位）不占内存，也不该被拒。 */
    @Test
    fun `a zero cap still holds zero byte placeholders`() {
        val cache = cache(0)
        assertTrue(cache.put("broken file", 0))
        assertEquals(0, cache.get("broken file"))
        assertFalse(cache.put("photo", 1))
        assertEquals(0L, cache.sizeBytes)
        assertEquals(0, cache.evictionCount)
    }

    /** 预算被调用方算成负数（溢出、常量写错）时按 0 处理，判据不能反号。 */
    @Test
    fun `a negative budget behaves like a zero one`() {
        val cache = cache(-5_000)
        assertEquals(0L, cache.maxBytes)
        assertFalse(cache.put("photo", 1))
        assertTrue(cache.put("placeholder", 0))
        assertEquals(0L, cache.sizeBytes)
    }

    @Test
    fun `remove and clear keep the byte accounting honest`() {
        val cache = cache(10)
        cache.put("a", 4)
        cache.put("b", 4)
        assertEquals(4, cache.remove("a"))
        assertEquals(4L, cache.sizeBytes)
        assertNull(cache.get("a"))
        assertNull(cache.remove("never there"))
        cache.put("c", 4)
        // 先制造一次真实淘汰，再 clear：淘汰计数是用过的历史，不该被清空抹掉。
        cache.put("d", 4)
        val evictions = cache.evictionCount
        assertTrue(evictions > 0)
        cache.clear()
        assertEquals(0L, cache.sizeBytes)
        assertNull(cache.get("b"))
        assertEquals(evictions, cache.evictionCount)
        assertTrue("clear 之后还得能继续用", cache.put("e", 9))
        assertEquals(9L, cache.sizeBytes)
    }

    /** sizeOf 给出负数（估算溢出）时按 0 记账：账目一负，上限就再也拦不住任何东西了。 */
    @Test
    fun `a bogus negative size estimate cannot disarm the cap`() {
        val cache = ByteLruCache<String, Int>(maxBytes = 10, sizeOf = { Int.MIN_VALUE })
        assertTrue(cache.put("a", 1))
        assertTrue(cache.put("b", 1))
        assertEquals(0L, cache.sizeBytes)
        assertTrue("账目不能变负", cache.sizeBytes >= 0L)
        assertEquals(0, cache.evictionCount)
    }
}
