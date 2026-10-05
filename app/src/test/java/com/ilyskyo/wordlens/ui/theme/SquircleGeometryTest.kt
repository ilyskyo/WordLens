// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sign

/**
 * 超椭圆采样的几何校验。
 *
 * 这类代码失败时不会崩，只会画出一个「说不上哪里怪」的形状，而 CI 对像素一无所知。
 * 所以这里断言的全是不变量：弧必须精确接在直边端点上、点必须落在超椭圆上、轮廓必须凸。
 * 象限搞错（把右上角的弧画到左上）会同时违反后两条——这一版本最初就错在这里。
 */
class SquircleGeometryTest {

    private val width = 300f
    private val height = 200f
    private val radius = 40f

    @Test
    fun `smoothing 0 degenerates into a plain rounded corner`() {
        // n = 2 就是圆：每个采样点到圆心的距离必须恰好等于半径。
        val points = SquircleGeometry.cornerPoints(
            centreX = 100f, centreY = 100f, radius = 40f,
            fromAngleDeg = 270f, exponent = SquircleGeometry.exponent(0f),
        )
        for (i in 0 until points.size step 2) {
            val distance = hypot((points[i] - 100f).toDouble(), (points[i + 1] - 100f).toDouble())
            assertEquals(40.0, distance, 1e-3)
        }
    }

    @Test
    fun `smoothing maps onto the superellipse exponent and clamps`() {
        assertEquals(2f, SquircleGeometry.exponent(0f), 1e-6f)
        assertEquals(5f, SquircleGeometry.exponent(1f), 1e-6f)
        // 越界夹紧：n<2 会得到内凹的角，n 过大则圆角本身消失，两端都不该被画出来。
        assertEquals(2f, SquircleGeometry.exponent(-1f), 1e-6f)
        assertEquals(5f, SquircleGeometry.exponent(2f), 1e-6f)
    }

    @Test
    fun `every sampled point sits on a corner superellipse`() {
        val points = outline(width, height, radius)
        val n = SquircleGeometry.exponent(0.6f).toDouble()
        val centres = listOf(
            Vec(width - radius, radius), // 右上
            Vec(width - radius, height - radius), // 右下
            Vec(radius, height - radius), // 左下
            Vec(radius, radius), // 左上
        )
        for (point in points) {
            val onAnyCorner = centres.any { centre ->
                val u = abs((point.x - centre.x) / radius).toDouble()
                val v = abs((point.y - centre.y) / radius).toDouble()
                abs(u.pow(n) + v.pow(n) - 1.0) < 1e-3
            }
            assertTrue("point ($point) is not on any corner curve", onAnyCorner)
        }
    }

    @Test
    fun `arcs join the straight edges exactly`() {
        // 这是最容易写错的地方：fromAngle 差 90°，弧就会画到隔壁象限，屏幕上是个风车。
        val steps = SquircleGeometry.CORNER_STEPS
        val points = outline(width, height, radius)

        // 顶点序列是固定节律：直边端点 1 个 + 弧 steps 个，顺时针四组。
        val topEndStart = 1
        val topEndFinish = topEndStart + steps
        val bottomEndStart = topEndFinish + 1
        val bottomEndFinish = bottomEndStart + steps
        val bottomStartFinish = bottomEndFinish + 1 + steps
        val topStartFinish = bottomStartFinish + 1 + steps

        assertEquals(point(radius, 0f), points[0]) // 起点：上边左端
        assertEquals(point(width - radius, 0f), points[topEndStart])
        assertEquals(point(width, radius), points[topEndFinish]) // 右上角弧的终点
        assertEquals(point(width, height - radius), points[bottomEndStart])
        assertEquals(point(width - radius, height), points[bottomEndFinish])
        assertEquals(point(radius, height), points[bottomEndFinish + 1])
        assertEquals(point(0f, height - radius), points[bottomStartFinish])
        assertEquals(point(0f, radius), points[bottomStartFinish + 1])
        assertEquals(point(radius, 0f), points[topStartFinish]) // 回到起点，路径天然闭合
        assertEquals(topStartFinish + 1, points.size)
    }

    @Test
    fun `sheet keeps the bottom square`() {
        // BottomSheet 靠「下两角半径为 0」得到直角，而不是靠一条额外分支。
        val points = outline(
            width, height,
            topStart = radius, topEnd = radius, bottomEnd = 0f, bottomStart = 0f,
        )
        assertEquals(point(width, height), points[14]) // 右上角走完就直接落到底
        assertEquals(point(0f, height), points[27])
        assertEquals(point(radius, 0f), points.first())
        assertEquals(point(radius, 0f), points.last())
        // 半径为 0 的角退化成重合点，但仍然是合法的：不能出现 y 超出边界。
        assertTrue(points.all { it.y <= height + 1e-3f })
        assertTrue(points.all { it.x in -1e-3f..(width + 1e-3f) })
    }

    @Test
    fun `outline stays inside its box and stays convex`() {
        val variants = listOf(
            outline(width, height, radius),
            outline(width, height, topStart = radius, topEnd = radius, bottomEnd = 0f, bottomStart = 0f),
            outline(width, height, radius, smoothing = 0f),
            outline(width, height, radius, smoothing = 1f),
        )
        variants.forEachIndexed { index, points ->
            points.forEach { (x, y) ->
                assertTrue("x=$x out of [0,$width] in variant $index", x in -1e-3f..(width + 1e-3f))
                assertTrue("y=$y out of [0,$height] in variant $index", y in -1e-3f..(height + 1e-3f))
            }
            // 凸性：相邻边的叉积必须同号。象限画错会立刻产生反向的叉积。
            // 半径为 0 时会出现完全重合的采样点，所以允许 0；[Muted] 一档退化出的是直线，也允许 0。
            var turnSign = 0f
            for (i in points.indices) {
                val a = points[i]
                val b = points[(i + 1) % points.size]
                val c = points[(i + 2) % points.size]
                val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
                if (abs(cross) < 1e-4f) continue
                if (turnSign == 0f) {
                    turnSign = cross.sign
                } else {
                    assertEquals(
                        "concave turn at $i in variant $index",
                        turnSign,
                        cross.sign,
                        1e-6f,
                    )
                }
            }
        }
    }

    @Test
    fun `corner samples are strictly monotone along the arc`() {
        // 右上角：从 (w−r, 0) 走到 (w, r)，x 与 y 都只能单调增大。
        // 一旦某个采样点回退，就说明角度扫过了错误的象限。
        val samples = SquircleGeometry.cornerPoints(
            centreX = width - radius, centreY = radius, radius = radius,
            fromAngleDeg = 270f, exponent = SquircleGeometry.exponent(0.6f),
        )
        var previousX = width - radius
        var previousY = 0f
        for (i in 0 until samples.size step 2) {
            assertTrue("x regressed at $i", samples[i] >= previousX - 1e-4f)
            assertTrue("y regressed at $i", samples[i + 1] >= previousY - 1e-4f)
            previousX = samples[i]
            previousY = samples[i + 1]
        }
        assertEquals(width, previousX, 1e-3f)
        assertEquals(radius, previousY, 1e-3f)
    }

    @Test
    fun `radii that do not fit are scaled proportionally`() {
        // 上边两端之和超过宽度时必须整体缩，而不是逐角夹：逐角夹会让同一条边上的两个角长得不一样。
        val clamped = SquircleGeometry.clampRadii(
            width = 100f, height = 400f,
            topStart = 60f, topEnd = 60f, bottomEnd = 60f, bottomStart = 60f,
        )
        // 约束是 100 / 120 = 0.8333，四个角同比例变到 50，正好铺满上边。
        clamped.forEach { assertEquals(50f, it, 1e-3f) }
    }

    @Test
    fun `radii that already fit are untouched`() {
        val clamped = SquircleGeometry.clampRadii(
            width = 300f, height = 200f,
            topStart = 40f, topEnd = 40f, bottomEnd = 0f, bottomStart = 0f,
        )
        assertEquals(40f, clamped[0], 1e-6f)
        assertEquals(40f, clamped[1], 1e-6f)
        assertEquals(0f, clamped[2], 1e-6f)
        assertEquals(0f, clamped[3], 1e-6f)
    }

    @Test
    fun `a zero radius at every corner is still a valid rectangle`() {
        val points = outline(width, height, r = 0f)
        assertEquals(point(0f, 0f), points.first())
        assertEquals(point(0f, 0f), points.last())
        assertTrue(points.all { it.x in 0f..width && it.y in 0f..height })
    }

    private data class Vec(val x: Float, val y: Float)

    private fun outline(
        w: Float,
        h: Float,
        r: Float,
        smoothing: Float = 0.6f,
    ): List<Vec> = toVecs(SquircleGeometry.outlinePoints(w, h, r, smoothing))

    private fun outline(
        w: Float,
        h: Float,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        smoothing: Float = 0.6f,
    ): List<Vec> = toVecs(
        SquircleGeometry.outlinePoints(w, h, topStart, topEnd, bottomEnd, bottomStart, smoothing),
    )

    private fun toVecs(flat: FloatArray): List<Vec> =
        flat.toList().chunked(2) { (x, y) -> Vec(x, y) }

    private fun point(x: Float, y: Float) = Vec(x, y)

    private fun assertEquals(expected: Vec, actual: Vec) {
        assertEquals(expected.x, actual.x, 1e-3f)
        assertEquals(expected.y, actual.y, 1e-3f)
    }
}
