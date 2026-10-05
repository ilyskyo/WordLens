// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision.camera

import com.ilyskyo.wordlens.vision.camera.CameraFocusMath.NormBox
import com.ilyskyo.wordlens.vision.camera.CameraFocusMath.SensorCrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * YUV 转换与取景几何的纯 JVM 验证。
 *
 * 这些代码的失败方式在真机上极难定位：颜色整体偏绿像是滤镜问题，词片漂移像是识别错误，
 * 而实际都只是一步 stride 或一次旋转方向写反。全部写成手算对拍，毫秒级复现。
 */
class YuvFramesTest {

    /** 按无符号字节写测试平面（240 在 Kotlin 里是 -16）。 */
    private fun plane(vararg unsigned: Int) = unsigned.map { it.toByte() }.toByteArray()

    // ── toPixels：stride 处理 ───────────────────────────────────────────────

    /** 灰点：Y=150, U=V=128 应还原出 (155,155,155)。 */
    @Test
    fun `neutral chroma decodes to grey`() {
        val y = plane(150, 150, 150, 150)
        val pixels = YuvFrames.toPixels(y, 2, 1, plane(128), plane(128), 1, 2, 2, 2)
        for (p in pixels) {
            assertEquals(0xFF shl 24, p and (0xFF shl 24))
            assertEquals(155, (p shr 16) and 0xFF)
            assertEquals(155, (p shr 8) and 0xFF)
            assertEquals(155, p and 0xFF)
        }
    }

    /** 行末填充字节必须被跳过：rowStride=4、width=2 时第二行从下标 4 开始读。 */
    @Test
    fun `row padding is skipped`() {
        val y = plane(100, 100, -1, -1, 200, 200, -1, -1)
        val pixels = YuvFrames.toPixels(y, 4, 1, plane(128, 128), plane(128, 128), 2, 2, 2, 2)
        // Y=200 → R=(200-16)*298/256≈214；Y=100 → 97。若按 width 连续读，第二行会读到 -1(255)。
        assertEquals(214, (pixels[2] shr 16) and 0xFF)
        assertEquals(97, (pixels[0] shr 16) and 0xFF)
    }

    /**
     * uvPixelStride=2（420 半分辨率）与越界色度平面的夹紧。
     *
     * 注意 4:2:0 的语义：第 0、1 行**共用**色度第 0 行（`chromaRow = row / 2`），所以两像素
     * 高度根本走不到越界分支——要测夹紧必须给四行。
     */
    @Test
    fun `half-resolution chroma and undersized planes clamp`() {
        val y = plane(150, 150, 150, 150, 150, 150, 150, 150)
        // u/v 各只有两个字节、uvRowStride=2：色度第 1 行的下标 2 越界，必须夹到最后一个
        // 采样点（u[1]=0、v[1]=0）而不是抛异常。
        val pixels = YuvFrames.toPixels(y, 2, 1, plane(128, 0), plane(240, 0), 2, 2, 2, 4)
        // 行 0/1：U=128（中性）、V=240 → vv=112 → c=(150-16)*298=39932；
        // R=(39932+409*112)>>8=334→255, G=(39932-208*112)>>8=64, B=39932>>8=155
        for (row in 0..1) {
            assertEquals(255, (pixels[row * 2] shr 16) and 0xFF)
            assertEquals(64, (pixels[row * 2] shr 8) and 0xFF)
            assertEquals(155, pixels[row * 2] and 0xFF)
        }
        // 行 2/3：夹到 uv=vv=-128 → R=(39932-52350)>>8<0→0，G=(39932+12800+26624)>>8=310→255，
        // B=(39932-66176)>>8<0→0——极端下界色，但至少不崩、词片照常出。
        for (row in 2..3) {
            val p = pixels[row * 2]
            assertEquals(0, (p shr 16) and 0xFF)
            assertEquals(255, (p shr 8) and 0xFF)
            assertEquals(0, p and 0xFF)
        }
    }

    /** 列奇数、色度半分辨率：最后一列复用最后一个色度采样点。 */
    @Test
    fun `odd width reuses last chroma sample`() {
        val y = plane(150, 150, 150)
        val pixels = YuvFrames.toPixels(y, 3, 1, plane(128), plane(128), 1, 2, 3, 1)
        assertEquals(155, (pixels[2] shr 16) and 0xFF)
    }

    // ── luma：氛围词的粗信号 ────────────────────────────────────────────────

    @Test
    fun `luma reports brightness and warmth`() {
        val red = (0xFF shl 24) or (0xFF shl 16)
        val green = (0xFF shl 24) or (0xFF shl 8)
        val black = 0xFF shl 24
        // Rec.709 下纯绿亮度最高（0.7152）；实现按像素取整，这里用整数值避免舍入歧义。
        val bright = YuvFrames.luma(intArrayOf(green, green), step = 1)
        val dark = YuvFrames.luma(intArrayOf(black, black), step = 1)
        // 0.7152*255 在 float 下是 182.379，逐像素取整后平均 → 182/255。
        assertEquals(182 / 255f, bright.brightness, 0.001f)
        assertEquals(0f, dark.brightness, 0.001f)

        // 一红一蓝：平均 R-B 为 0，冷暖互相抵消。
        val neutral = YuvFrames.luma(intArrayOf(red, blueish()), step = 1)
        assertEquals(0f, neutral.warmth, 0.001f)
        // 实现是「先按像素取整再平均」，纯蓝会向下少 1；取大容差把断言钉在**符号与量级**上，
        // 而不是钉死某一种舍入方式——舍入细节变了不该让行为测试红。
        val warm = YuvFrames.luma(intArrayOf(red, red, red, red), step = 1)
        assertTrue("warmth=${warm.warmth}", warm.warmth > 0.5f)
        val cool = YuvFrames.luma(intArrayOf(blueish(), blueish(), blueish(), blueish()), step = 1)
        assertTrue("warmth=${cool.warmth}", cool.warmth < -0.5f)
    }

    private fun blueish(): Int = (0xFF shl 24) or 0xFF

    @Test
    fun `luma on empty frame is neutral`() {
        val luma = YuvFrames.luma(IntArray(0))
        assertEquals(0f, luma.brightness, 0f)
        assertEquals(0f, luma.warmth, 0f)
    }

    // ── 传感器 ↔ 显示图 的旋转互逆 ──────────────────────────────────────────

    /** displayPointFromSensorNorm 必须是 orientedBox 的逆：点先转正再反旋要回到原处。 */
    @Test
    fun `display point inverts orientedBox at every rotation`() {
        // 成对出现：图像角 → orientedBox 转正 → 反旋，必须回到同一个角。
        // 把传感器角按「左上/右上/左下/右下」的字面顺序配给图像角是错的——旋转后角会换位。
        val points = listOf(
            0.20f to 0.35f, 0.75f to 0.45f, 0.50f to 0.50f, 0.05f to 0.95f,
        )
        for (deg in intArrayOf(0, 90, 180, 270)) {
            for ((x, y) in points) {
                val sensor = CameraFocusMath.orientedBox(NormBox(x, y, x, y), deg)
                val (dx, dy) = CameraFocusMath.displayPointFromSensorNorm(sensor.left, sensor.top, deg)
                assertEquals("deg=$deg ($x,$y) x", x, dx, 0.001f)
                assertEquals("deg=$deg ($x,$y) y", y, dy, 0.001f)
            }
        }
    }

    /** imageBoxFromSensorNorm 应与逐角反旋一致（旋转后角会换位，必须按角配对而不是按边界名）。 */
    @Test
    fun `imageBox matches per-corner unrotation`() {
        val img = NormBox(0.20f, 0.35f, 0.45f, 0.70f)
        val corners = listOf(
            img.left to img.top, img.right to img.top,
            img.left to img.bottom, img.right to img.bottom,
        )
        for (deg in intArrayOf(90, 180, 270)) {
            // 图像框整体转正，再整体反旋回图像，应与原框一致。
            // 逐字段比容差而不是 data class 相等：1-(1-x) 会差一个 ULP，精确相等是假阳性。
            assertBoxNear(img, CameraFocusMath.imageBoxFromSensorNorm(CameraFocusMath.orientedBox(img, deg), deg), deg)
            // 逐角反旋：每个传感器角都要落回它对应的那个图像角。
            val sensor = CameraFocusMath.orientedBox(img, deg)
            val sensorCorners = corners.map { (x, y) ->
                val b = CameraFocusMath.orientedBox(NormBox(x, y, x, y), deg)
                b.left to b.top
            }
            for (i in corners.indices) {
                val (dx, dy) = CameraFocusMath.displayPointFromSensorNorm(sensorCorners[i].first, sensorCorners[i].second, deg)
                assertEquals("deg=$deg corner=$i x", corners[i].first, dx, 0.001f)
                assertEquals("deg=$deg corner=$i y", corners[i].second, dy, 0.001f)
            }
            val back = CameraFocusMath.imageBoxFromSensorNorm(sensor, deg)
            assertTrue("box must stay ordered", back.left < back.right && back.top < back.bottom)
        }
    }

    private fun assertBoxNear(expected: NormBox, actual: NormBox, deg: Int) {
        assertEquals("deg=$deg left", expected.left, actual.left, 0.001f)
        assertEquals("deg=$deg top", expected.top, actual.top, 0.001f)
        assertEquals("deg=$deg right", expected.right, actual.right, 0.001f)
        assertEquals("deg=$deg bottom", expected.bottom, actual.bottom, 0.001f)
    }

    // ── frameBoxToSensorNorm：变焦后的坐标回填 ──────────────────────────────

    @Test
    fun `full sensor crop is the identity`() {
        val box = NormBox(0.1f, 0.2f, 0.3f, 0.4f)
        val out = OverlayGeometry.frameBoxToSensorNorm(box, SensorCrop(0f, 0f, 4032f, 3024f), 4032, 3024)
        assertEquals(box, out)
    }

    @Test
    fun `offset crop shifts frame coordinates back`() {
        val crop = SensorCrop(1000f, 600f, 3000f, 2100f)
        val out = OverlayGeometry.frameBoxToSensorNorm(NormBox(0f, 0f, 1f, 1f), crop, 4000, 3000)
        assertEquals(0.25f, out.left, 0.001f)
        assertEquals(0.2f, out.top, 0.001f)
        assertEquals(0.75f, out.right, 0.001f)
        assertEquals(0.7f, out.bottom, 0.001f)
    }

    @Test
    fun `frames are clamped into the sensor`() {
        // 驱动给了超出裁切区的框：结果必须落在 0..1，不能把越界坐标喂给覆盖层。
        val out = OverlayGeometry.frameBoxToSensorNorm(
            NormBox(-0.5f, -0.2f, 1.4f, 1.1f),
            SensorCrop(0f, 0f, 100f, 100f),
            100,
            100,
        )
        assertEquals(0f, out.left, 0f)
        assertEquals(1f, out.right, 0f)
        assertEquals(1f, out.bottom, 0f)
    }
}
