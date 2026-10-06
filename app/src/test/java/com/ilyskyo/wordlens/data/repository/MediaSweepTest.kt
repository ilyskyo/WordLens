// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.data.repository

import com.ilyskyo.wordlens.data.model.Entry
import com.ilyskyo.wordlens.data.model.EntryObject
import com.ilyskyo.wordlens.data.model.OverlayLayer
import com.ilyskyo.wordlens.data.model.WordCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动那一次媒体清扫的判据。
 *
 * ## 为什么这一小段要单独测
 *
 * `AppContainer.sweepUnreferencedMedia` 删的是用户拍过却没有留下的照片，而它判断「没人引用」
 * 用的是两份文档 + 一道时间门槛。三样任何一样写错都不会报错：保留集漏掉词卡那一侧，
 * 下一次冷启动就把还在用的贴纸删了；门槛写丢，正在导入的那一张被删。
 * 这两种的现场都是「我的照片少了」，而那恰好是这本日记最不能给人的印象。
 *
 * 判据抽成纯函数（[MediaSweep]）之后，这些都能在 JVM 上逐条断言，不需要设备、也不需要真文件。
 */
class MediaSweepTest {

    private val now = 1_700_000_000_000L
    private val hour = MediaSweep.MIN_AGE_MILLIS

    private fun objectWith(sticker: String?) = EntryObject(
        id = "obj-1",
        word = "cup",
        lexiconEntryId = null,
        score = 0.9f,
        left = 0.1f,
        top = 0.1f,
        right = 0.4f,
        bottom = 0.6f,
        stickerPath = sticker,
        layer = OverlayLayer.ITEM,
    )

    private fun entry(id: String, photo: String, sticker: String? = null) = Entry(
        id = id,
        photoPath = photo,
        takenAt = now,
        objects = listOf(objectWith(sticker)),
    )

    @Test
    fun `the keep set covers photos, entry-side stickers, and card-side stickers`() {
        val entries = listOf(entry("e1", "p1.jpg", sticker = "st-e1.png"))
        val cards = listOf(WordCard(id = "c1", headword = "cup", language = "en", stickerPath = "st-e9.png"))

        val keep = MediaSweep.referenced(entries, cards)

        assertEquals(setOf("p1.jpg", "st-e1.png", "st-e9.png"), keep)
    }

    /** 词卡可以活得比引用它的日记久——这条就是 `deleteEntry` 把贴纸名交回调用方的同一个理由。 */
    @Test
    fun `a sticker only a card still references is kept`() {
        val keep = MediaSweep.referenced(
            entries = emptyList(),
            cards = listOf(WordCard(id = "c1", headword = "cup", language = "en", stickerPath = "st-e1.png")),
        )
        assertTrue(keep.contains("st-e1.png"))
    }

    /** 空名字进保留集会变成一条谁都能撞上的通配；宁可少留，也别错留。 */
    @Test
    fun `blank and absent references do not widen the keep set`() {
        val keep = MediaSweep.referenced(
            entries = listOf(entry("e1", "p1.jpg", sticker = null)),
            cards = listOf(WordCard(id = "c1", headword = "cup", language = "en", stickerPath = "")),
        )
        assertEquals(setOf("p1.jpg"), keep)
    }

    @Test
    fun `an unreferenced old file is swept and a referenced one is not`() {
        val listing = listOf(
            MediaSweep.Candidate("p1.jpg", lastModified = now - hour),
            MediaSweep.Candidate("orphan.jpg", lastModified = now - hour),
        )

        val stale = MediaSweep.stale(listing, referenced = setOf("p1.jpg"), nowMillis = now)

        assertEquals(listOf("orphan.jpg"), stale)
    }

    /**
     * 新文件一律不动。
     *
     * 冷启动时「分享到见词」与相册导入走的是**先写文件、后写引用**那一条流水线，
     * 清扫插在中间删掉的就是用户刚刚分享、正准备保存的那一张——把一次隐私修复变成一次数据丢失。
     * 这条断言是这道门槛存在的唯一理由，别把它当冗余删掉。
     */
    @Test
    fun `a fresh orphan is left alone because an import may be in flight`() {
        val listing = listOf(MediaSweep.Candidate("just-shared.jpg", lastModified = now - 1_000L))

        assertEquals(emptyList<String>(), MediaSweep.stale(listing, referenced = emptySet(), nowMillis = now))
    }

    /** 门槛取「达到即算」：边界上含进去，规则才是可预测的一行减法。 */
    @Test
    fun `the age threshold is inclusive`() {
        val listing = listOf(MediaSweep.Candidate("exactly.jpg", lastModified = now - hour))

        assertEquals(listOf("exactly.jpg"), MediaSweep.stale(listing, referenced = emptySet(), nowMillis = now))
    }

    /** 空目录不是错误：新装机 `listFiles()` 给的是空数组，那一次什么都不该删。 */
    @Test
    fun `an empty listing sweeps nothing`() {
        assertEquals(emptyList<String>(), MediaSweep.stale(emptyList(), referenced = emptySet(), nowMillis = now))
    }
}
