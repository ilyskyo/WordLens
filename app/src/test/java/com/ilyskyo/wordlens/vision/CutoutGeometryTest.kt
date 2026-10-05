// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 贴纸裁切几何。
 *
 * 这一层存在的理由是一个静默失效的 bug：分割位图是工作尺度（长边 1024），前景紧框是原图像素
 * 尺度（长边可达 2560），拿后者的数值直接裁前者会越界抛异常，而异常被 `runCatching` 吞掉之后
 * 表现为「贴纸一直是空的、界面不报错、日志也没有」。尺度换算与夹紧因此写成纯函数，
 * 在 JVM 上逐种情况钉住。
 */
class CutoutGeometryTest {

    /** 原图 2000×1500、cutout 1000×750：一个源像素对应半个 cutout 像素。 */
    @Test
    fun `scales a source-space bounds into cutout pixels`() {
        val rect = CutoutGeometry.cropRect(
            bounds = intArrayOf(100, 200, 300, 400),
            sourceWidth = 2000,
            sourceHeight = 1500,
            cutoutWidth = 1000,
            cutoutHeight = 750,
        )
        assertArrayEqualsSafe(intArrayOf(50, 100, 100, 100), rect)
    }

    /** 越出原图的脏数据必须被夹到位图内，而不是抛异常。 */
    @Test
    fun `clamps a bounds that overruns the source`() {
        assertArrayEqualsSafe(
            intArrayOf(50, 50, 450, 350),
            CutoutGeometry.cropRect(
                bounds = intArrayOf(100, 100, 3000, 9000),
                sourceWidth = 1000,
                sourceHeight = 800,
                cutoutWidth = 500,
                cutoutHeight = 400,
            ),
        )
    }

    /** 负原点夹到 0，宽度跟着收缩——不然 createBitmap 会因为 x<0 直接抛。 */
    @Test
    fun `negative origin clamps to zero`() {
        assertArrayEqualsSafe(
            intArrayOf(0, 0, 20, 20),
            CutoutGeometry.cropRect(intArrayOf(-50, -30, 200, 200), 1000, 1000, 100, 100),
        )
    }

    /** 白描边要的边距不能把框推出图外。 */
    @Test
    fun `pad stays inside the bitmap`() {
        val rect = CutoutGeometry.cropRect(
            bounds = intArrayOf(0, 0, 20, 20),
            sourceWidth = 1000,
            sourceHeight = 1000,
            cutoutWidth = 100,
            cutoutHeight = 100,
            padCutoutPx = 64,
        )!!
        assertEquals(0, rect[0])
        assertEquals(66, rect[2])
        assertEquals(66, rect[3])
    }

    /** 完全在图外的框返回 null：调用方要显式处理「没有贴纸」。 */
    @Test
    fun `a box entirely outside yields null`() {
        assertNull(CutoutGeometry.cropRect(intArrayOf(1500, 1500, 2000, 2000), 1000, 1000, 100, 100))
    }

    /** 退化入参一律 null，不抛。 */
    @Test
    fun `degenerate inputs yield null`() {
        assertNull(CutoutGeometry.cropRect(intArrayOf(0, 0, 10, 10), 100, 100, 0, 100))
        assertNull(CutoutGeometry.cropRect(intArrayOf(0, 0, 10, 10), 100, 100, 100, 0))
        assertNull(CutoutGeometry.cropRect(intArrayOf(0, 0, 10, 10), 0, 100, 100, 100))
        assertNull(CutoutGeometry.cropRect(intArrayOf(0, 0, 10), 100, 100, 100, 100))
    }

    /**
     * 极小前景缩到工作尺度后不足 1 像素时给 1×1，而不是 null。
     *
     * 区分得开才有效：null 会被调用方读成「后端没出图」从而放弃整条抠图路径，
     * 而 1 像素的贴纸只是难看，语义上仍然成功。
     */
    @Test
    fun `sub-pixel bounds still produce one pixel`() {
        assertArrayEqualsSafe(
            intArrayOf(0, 0, 1, 1),
            CutoutGeometry.cropRect(intArrayOf(0, 0, 1, 1), 4000, 4000, 100, 100),
        )
    }

    /** 贴在右下角的 1 像素框不能越出最后一行/列。 */
    @Test
    fun `one pixel at the far edge stays inside`() {
        assertArrayEqualsSafe(
            intArrayOf(99, 99, 1, 1),
            CutoutGeometry.cropRect(intArrayOf(3999, 3999, 4000, 4000), 4000, 4000, 100, 100),
        )
    }

    /** capRegion 只改输出尺寸、不挪原点：同一块区域、更少的像素。 */
    @Test
    fun `capRegion keeps the origin and the aspect`() {
        val capped = CutoutGeometry.capRegion(intArrayOf(30, 40, 2000, 1000), 500)
        assertEquals(30, capped[0])
        assertEquals(40, capped[1])
        assertEquals(500, capped[2])
        assertEquals(250, capped[3])

        assertArrayEqualsSafe(intArrayOf(0, 0, 100, 80), CutoutGeometry.capRegion(intArrayOf(0, 0, 100, 80), 500))
    }

    /** 极扁的区域被限时不能塌成 0 像素。 */
    @Test
    fun `capRegion never collapses an edge to zero`() {
        val capped = CutoutGeometry.capRegion(intArrayOf(0, 0, 1000, 3), 10)
        assertEquals(10, capped[2])
        assertEquals(1, capped[3])
    }

    private fun assertArrayEqualsSafe(expected: IntArray, actual: IntArray?) {
        assertNotNull(actual)
        assertArrayEquals(expected, actual!!)
    }
}

/**
 * mask → alpha 通道。
 *
 * 采样用纹素中心对齐（先 +0.5 再 -0.5）。这个约定写错不会崩，只会让透明边缘整体偏移一个纹素
 * ——在 die-cut 白描边上表现为一圈漏光，真机上很难归因，所以用具体数字钉住。
 */
class AlphaMatteTest {

    @Test
    fun `constant mask becomes constant alpha`() {
        val alpha = AlphaMatte.alphaForRegion(
            mask = FloatArray(8) { 0.8f },
            maskWidth = 4,
            maskHeight = 2,
            sourceWidth = 4,
            sourceHeight = 2,
            region = intArrayOf(0, 0, 4, 2),
            outWidth = 4,
            outHeight = 2,
        )
        assertEquals(8, alpha.size)
        // round(0.8 * 255) = 204
        assertTrue("was ${alpha.joinToString()}", alpha.all { it == 204 })
    }

    /** 4 纹素的 mask 放大到 4 像素：边界正好落在两个纹素之间时给 50%。 */
    @Test
    fun `sampling aligns to texel centres`() {
        val alpha = AlphaMatte.alphaForRegion(
            mask = floatArrayOf(1f, 1f, 0f, 0f),
            maskWidth = 4,
            maskHeight = 1,
            sourceWidth = 4,
            sourceHeight = 1,
            region = intArrayOf(0, 0, 4, 1),
            outWidth = 4,
            outHeight = 1,
        )
        assertArrayEquals(intArrayOf(255, 128, 0, 0), alpha)
    }

    /** 输出尺寸与区域尺寸不同（贴纸被缩过）时，边缘仍然落在同一处相对位置上。 */
    @Test
    fun `scaling the output keeps the alpha registered`() {
        val alpha = AlphaMatte.alphaForRegion(
            mask = floatArrayOf(1f, 0f),
            maskWidth = 2,
            maskHeight = 1,
            sourceWidth = 100,
            sourceHeight = 50,
            region = intArrayOf(0, 0, 100, 50),
            outWidth = 4,
            outHeight = 1,
        )
        assertEquals(255, alpha[0])
        assertEquals(0, alpha[3])
        assertTrue("should decay left to right: ${alpha.joinToString()}", alpha[0] > alpha[1])
        assertTrue(alpha[1] > alpha[2])
    }

    /** region 取源图右半时采样要跟着挪过去。 */
    @Test
    fun `a cropped region samples the matching part`() {
        val mask = floatArrayOf(1f, 1f, 0f, 0f)
        val right = AlphaMatte.alphaForRegion(mask, 4, 1, 4, 1, intArrayOf(2, 0, 2, 1), 2, 1)
        assertArrayEquals(intArrayOf(0, 0), right)
        // 左半的第二个像素中心正好压在 mask 的第 2 个纹素边界上，所以是 50%。
        val left = AlphaMatte.alphaForRegion(mask, 4, 1, 4, 1, intArrayOf(0, 0, 2, 1), 2, 1)
        assertArrayEquals(intArrayOf(255, 128), left)
    }

    @Test
    fun `applyAlpha writes alpha and keeps rgb`() {
        val argb = intArrayOf(0x112233, 0x445566, 0x778899)
        val out = AlphaMatte.applyAlpha(argb, intArrayOf(255, 0, 128))
        assertEquals((255 shl 24) or 0x112233, out[0])
        // 全透明处 RGB 必须留着：抹平会让半透明边缘发黑。
        assertEquals(0x445566, out[1])
        assertEquals((128 shl 24) or 0x778899, out[2])
    }

    /** 入参退化时给全 0（一张全透明贴纸），不抛异常。 */
    @Test
    fun `degenerate inputs yield a transparent sticker instead of throwing`() {
        val noMask = AlphaMatte.alphaForRegion(FloatArray(4), 0, 1, 10, 10, intArrayOf(0, 0, 5, 5), 5, 5)
        assertEquals(25, noMask.size)
        assertTrue(noMask.all { it == 0 })

        val shortMask = AlphaMatte.alphaForRegion(FloatArray(2), 4, 4, 10, 10, intArrayOf(0, 0, 5, 5), 5, 5)
        assertTrue(shortMask.all { it == 0 })

        val badRegion = AlphaMatte.alphaForRegion(FloatArray(16), 4, 4, 10, 10, intArrayOf(0, 0, 5), 5, 5)
        assertTrue(badRegion.all { it == 0 })
    }
}
