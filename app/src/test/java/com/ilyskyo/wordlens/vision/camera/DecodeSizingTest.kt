// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解码尺寸的两步算术。
 *
 * 只做 `inSampleSize` 而不做第二步，解码结果最长能顶到目标的 2 倍——按面积就是 4 倍内存，
 * 时间轴上几十张照片足以在滚动时把进程撑爆。这类错误不崩、不报错，只是占的内存慢慢变多，
 * 所以两步都写成纯函数并逐个数字对拍。
 */
class DecodeSizingTest {

    /** 契约里的旗舰用例：2560×1920 要 1280。 */
    @Test
    fun `the flagship source halves once and lands on the target edge`() {
        assertEquals(2, DecodeSizing.inSampleSizeFor(2560, 1920, 1280))
        // 第二步的精确尺寸：长边正好 1280，1920 按同比例是 960。
        // 注意 (1280, 640) 那一对属于 2560×1280 的源图，见下一个用例——
        // 保持宽高比与「长边恰为 maxPx」同时成立时，1920 只可能变 960。
        assertEquals(1280 to 960, DecodeSizing.scaledSize(2560, 1920, 1280))
    }

    /** 2:1 的宽幅源图才是 (1280, 640)。宽高比是硬约束，任何结果都必须守得住。 */
    @Test
    fun `a wide two to one source scales to the matching half`() {
        assertEquals(2, DecodeSizing.inSampleSizeFor(2560, 1280, 1280))
        assertEquals(1280 to 640, DecodeSizing.scaledSize(2560, 1280, 1280))
    }

    /** 原图比目标小：既不解也不放大，原样返回。 */
    @Test
    fun `a source smaller than the target is never enlarged`() {
        assertEquals(1, DecodeSizing.inSampleSizeFor(640, 480, 1280))
        assertEquals(640 to 480, DecodeSizing.scaledSize(640, 480, 1280))
        // 正好等于目标也算「不需要降」，边界归在不放大这一侧。
        assertEquals(1, DecodeSizing.inSampleSizeFor(1280, 720, 1280))
        assertEquals(1280 to 720, DecodeSizing.scaledSize(1280, 720, 1280))
    }

    @Test
    fun `a square source keeps both edges on the target`() {
        assertEquals(2, DecodeSizing.inSampleSizeFor(2000, 2000, 1000))
        assertEquals(1000 to 1000, DecodeSizing.scaledSize(2000, 2000, 1000))
    }

    /**
     * 极窄长条：4000 缩到 8 分之一正好是 500 = 目标，再降一档只剩 250 就变成放大，所以停在 8。
     * 短边按比例是 1.25 像素，必须给 1 而不是 0——0 宽或 0 高的位图会让 `createBitmap` 抛。
     */
    @Test
    fun `a very narrow strip still gets one pixel on the short edge`() {
        assertEquals(8, DecodeSizing.inSampleSizeFor(4000, 10, 500))
        assertEquals(500 to 1, DecodeSizing.scaledSize(4000, 10, 500))
    }

    /** 12MP 照片进时间轴（maxPx=768）：先降到 4 分之一，再精确缩到 768×576。 */
    @Test
    fun `a phone photo pays a quarter of what it used to`() {
        assertEquals(4, DecodeSizing.inSampleSizeFor(4032, 3024, 768))
        assertEquals(768 to 576, DecodeSizing.scaledSize(4032, 3024, 768))
        // 只做第一步的话停在 1008×756：按面积 2.7 倍、ARGB_8888 下 3.05MB 对 1.77MB。
        assertEquals(1008 to 756, ceilDiv(4032, 4) to ceilDiv(3024, 4))
    }

    /**
     * 三条普适性质，横扫一批尺寸与目标：
     * - `inSampleSize` 必须是 2 的幂且不小于 1；
     * - 降采样后的长边**不得短于**最终目标长边，否则第二步就成了放大，软图会毁掉词框贴合度；
     * - 最终长边恰好是 `min(源长边, maxPx)`，两个分量都 >= 1，且长短边的相对关系不翻。
     */
    @Test
    fun `the two steps never upscale and always land on the target edge`() {
        val sources = listOf(
            2560 to 1920, 4032 to 3024, 6000 to 4000, 1080 to 1920, 1 to 1, 3 to 4096,
            800 to 600, 1280 to 1281, 2016 to 1512, 512 to 513, 4096 to 1,
        )
        val targets = listOf(1, 2, 100, 256, 512, 768, 1024, 1280, 1440, 4096)
        for ((w, h) in sources) {
            for (maxPx in targets) {
                val label = "$w x $h -> $maxPx"
                val sample = DecodeSizing.inSampleSizeFor(w, h, maxPx)
                assertTrue("$label: sample=$sample is not a power of two", isPowerOfTwo(sample))
                val longest = maxOf(w, h)
                val target = DecodeSizing.scaledSize(w, h, maxPx)
                val targetLongest = maxOf(target.first, target.second)
                assertEquals("$label long edge", minOf(longest, maxPx), targetLongest)
                assertTrue(
                    "$label: sampled long edge ${ceilDiv(longest, sample)} must reach $targetLongest",
                    ceilDiv(longest, sample) >= targetLongest,
                )
                assertTrue("$label edges must stay positive", target.first >= 1 && target.second >= 1)
                // 宽高比可以被取整拉到几乎看不出，但长边和短边不能互换位置。
                if (w > h) {
                    assertTrue("$label aspect flipped: $target", target.first >= target.second)
                } else if (w < h) {
                    assertTrue("$label aspect flipped: $target", target.second >= target.first)
                } else {
                    assertEquals("$label a square stays square", target.first, target.second)
                }
            }
        }
    }

    /** 退化入参返回安全默认值而不是抛：解码失败路径上的异常会盖掉真正的错误原因。 */
    @Test
    fun `degenerate inputs fall back instead of throwing`() {
        assertEquals(1, DecodeSizing.inSampleSizeFor(0, 0, 1280))
        assertEquals(1, DecodeSizing.inSampleSizeFor(-2560, 1920, 1280))
        assertEquals(1, DecodeSizing.inSampleSizeFor(2560, 1920, 0))
        assertEquals(1, DecodeSizing.inSampleSizeFor(2560, 1920, -1))
        assertEquals(1 to 1, DecodeSizing.scaledSize(0, 100, 1280))
        assertEquals(1 to 1, DecodeSizing.scaledSize(100, 100, 0))
        assertEquals(1 to 1, DecodeSizing.scaledSize(-5, -5, 100))
    }

    /**
     * 只降不升时第一步最坏浪费一档：长边不超过目标的 2 倍。
     * 这条既是 `inSampleSize` 的下界证明，也是「为什么必须做第二步」的根据。
     */
    @Test
    fun `sampling alone wastes at most one doubling`() {
        // 2016 对目标 1024：降一档只剩 1008 就不够了，所以只能保持 1，代价是整张图大一倍。
        assertEquals(1, DecodeSizing.inSampleSizeFor(2016, 1512, 1024))
        assertTrue(ceilDiv(2016, 1) < 1024 * 2)
        assertEquals(1024 to 768, DecodeSizing.scaledSize(2016, 1512, 1024))
    }

    private fun isPowerOfTwo(value: Int): Boolean = value >= 1 && (value and (value - 1)) == 0

    /** 解码器的整数除法给出的是上取整后的物理像素，测试里用它算「降采样之后到底是多大」。 */
    private fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor
}
