// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision.camera

import com.ilyskyo.wordlens.vision.camera.CameraFocusMath.NormBox
import com.ilyskyo.wordlens.vision.camera.CameraFocusMath.SensorCrop
import com.ilyskyo.wordlens.vision.camera.OverlayGeometry.ViewSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖「归一化检测框 → 屏幕像素」的换算。
 *
 * 这段算错的后果是**词片和物体对不上**，而且越变焦偏得越远。预览里这是立刻可见的：
 * 用户会以为识别错了，而真正错的是坐标，所以必须在纯 JVM 上钉死。
 */
class OverlayGeometryTest {

    private val sensorW = 4032
    private val sensorH = 3024

    /**
     * 典型手机竖持取景框。
     *
     * `rotationDegrees = 90` 是真机上的常态：传感器阵列横置，预览被转 90° 才正。
     * 留这个非零值是为了让测试跑在真实配置上——之前用 0 写测试，结果旋转这条路径
     * 一次都没被覆盖，映射在竖持时是错的而测试全绿。
     */
    private val view = ViewSpec(
        viewWidth = 1080f,
        viewHeight = 1440f,
        sensorWidth = sensorW,
        sensorHeight = sensorH,
        rotationDegrees = 90,
    )

    /** 同样的取景框，但预览不旋转（横持或传感器本身就是竖置的）。 */
    private val viewNoRotation = view.copy(rotationDegrees = 0)

    private val fullCrop = SensorCrop(0f, 0f, sensorW.toFloat(), sensorH.toFloat())

    @Test
    fun `the full frame is centre-cropped to fill the view`() {
        val t = OverlayGeometry.transformFor(view, fullCrop)
        // FILL_CENTER 永不��黑边：宽高里总有一侧溢出，所以至少一个偏移是负的。
        assertTrue("offsetX=${t.offsetX} offsetY=${t.offsetY} 至少一侧应为负", t.offsetX < 0f || t.offsetY < 0f)
        // 旋转 90° 后画面变成 3:4，和 3:4 的取景框同比例 -> 两边都不溢出。
        assertEquals(0f, t.offsetX, 0.01f)
        assertEquals(0f, t.offsetY, 0.01f)
    }

    @Test
    fun `a centred box maps to the centre of the view`() {
        val rect = OverlayGeometry.map(NormBox(0.4f, 0.4f, 0.6f, 0.6f), view, fullCrop)
        // 画面与取景框同比例时，居中的物体必然落在取景框中心。
        assertEquals(view.viewWidth / 2f, rect.centerX, 1f)
        assertEquals(view.viewHeight / 2f, rect.centerY, 1f)
    }

    @Test
    fun `a point round-trips back to the same place`() {
        val start = NormBox(0.30f, 0.35f, 0.45f, 0.50f)
        val screen = OverlayGeometry.map(start, view, fullCrop)
        val centre = OverlayGeometry.screenPointToCentre(
            screenX = screen.centerX,
            screenY = screen.centerY,
            view = view,
            crop = fullCrop,
        )
        assertEquals(start.centerX, centre.centerX, 0.005f)
        assertEquals(start.centerY, centre.centerY, 0.005f)
    }

    /**
     * 回归：变焦之后，框必须跟着一起移动。
     *
     * 最强的断言是「裁切到物体所在区域时，物体恰好落在取景框中心」——这正是变焦聚焦要
     * 达到的效果，而且与旋转方向无关。漏掉减 `crop.left` 的话，预览里会看到
     * 「镜头推过去了，但词还贴在原来的位置」。
     */
    @Test
    fun `cropping onto a box centres it in the view`() {
        val box = NormBox(0.10f, 0.40f, 0.30f, 0.60f)

        // 先取物体四周留一点余量，再按这个区域裁切。
        val marginX = box.left * 0.5f
        val marginY = box.top * 0.5f
        val crop = SensorCrop(
            (box.left - marginX) * sensorW,
            (box.top - marginY) * sensorH,
            (box.right + marginX) * sensorW,
            (box.bottom + marginY) * sensorH,
        )
        val rect = OverlayGeometry.map(box, view, crop)
        assertEquals(view.viewWidth / 2f, rect.centerX, 1f)
        assertEquals(view.viewHeight / 2f, rect.centerY, 1f)
    }

    @Test
    fun `zooming in moves the mapped rectangle`() {
        val box = NormBox(0.10f, 0.40f, 0.30f, 0.60f)
        val before = OverlayGeometry.map(box, view, fullCrop)

        // 只裁一部分，物体应当被推向取景框中心，也就是要离开原来的位置。
        val zoomed = SensorCrop(
            (box.left - 0.4f) * sensorW,
            (box.top - 0.2f) * sensorH,
            (box.right + 0.1f) * sensorW,
            (box.bottom + 0.1f) * sensorH,
        )
        val after = OverlayGeometry.map(box, view, zoomed)

        val shift = kotlin.math.abs(after.centerX - before.centerX) +
            kotlin.math.abs(after.centerY - before.centerY)
        assertTrue("shift=$shift 词片应该跟着画面移动", shift > 100f)
    }

    /** 不旋转时，传感器的左上角就应该是取景框的左上角。 */
    @Test
    fun `a box at the top-left maps into the top-left of the view`() {
        val rect = OverlayGeometry.map(NormBox(0f, 0f, 0.2f, 0.2f), viewNoRotation, fullCrop)
        assertEquals(0f, rect.left, 1f)
        assertEquals(0f, rect.top, 1f)
    }

    /**
     * 旋转 90° 时，传感器左上角落在取景框**左下**角。
     *
     * 这条锁的是旋转方向本身：方向搞反了不会崩，只是词片全贴在错误的边上，
     * 而且 90° 和 270° 的错误互为镜像，肉眼很难立刻判断对错。
     */
    @Test
    fun `rotation ninety sends the sensor top-left to the bottom-left`() {
        val rect = OverlayGeometry.map(NormBox(0f, 0f, 0.2f, 0.2f), view, fullCrop)
        assertEquals(0f, rect.left, 1f)
        assertEquals(view.viewHeight, rect.bottom, 1f)
    }

    @Test
    fun `relative proportions survive the mapping`() {
        val a = OverlayGeometry.map(NormBox(0f, 0f, 0.5f, 0.5f), view, fullCrop)
        val b = OverlayGeometry.map(NormBox(0.5f, 0.5f, 1f, 1f), view, fullCrop)
        // 同样的传感器尺寸 -> 同样的屏幕尺寸。
        assertEquals(a.width, b.width, 0.01f)
        assertEquals(a.height, b.height, 0.01f)
        // 对角的两块不该重叠。
        assertTrue(a.right <= b.left + 1f || b.right <= a.left + 1f ||
            a.bottom <= b.top + 1f || b.bottom <= a.top + 1f)
    }

    /**
     * 取景框比例与画面不同时，`FILL_CENTER` 是**居中裁切**而不是留黑边。
     *
     * 之前把这两种行为搞反了，测试也跟着写反，于是错的行为在测试里是绿的。
     */
    @Test
    fun `a wide view centre-crops the tall side`() {
        val wide = ViewSpec(1440f, 720f, sensorW, sensorH, rotationDegrees = 90)
        val t = OverlayGeometry.transformFor(wide, fullCrop)
        assertEquals(0f, t.offsetX, 0.01f)
        assertTrue("offsetY=${t.offsetY} 应为负（上下被裁掉）", t.offsetY < 0f)
        // 铺满：较长的那一维正好等于取景框。
        assertEquals(wide.viewWidth, fullCrop.height * t.scale, 1f)
    }

    @Test
    fun `visibility fraction is one for a fully visible box`() {
        val rect = OverlayGeometry.map(NormBox(0.4f, 0.4f, 0.6f, 0.6f), view, fullCrop)
        assertEquals(1f, rect.visibleFraction(view.viewWidth, view.viewHeight), 0.02f)
    }

    @Test
    fun `visibility fraction drops to zero when fully off screen`() {
        val off = OverlayGeometry.ScreenRect(-500f, -500f, -400f, -400f)
        assertEquals(0f, off.visibleFraction(view.viewWidth, view.viewHeight), 0.001f)
    }

    @Test
    fun `visibility fraction is partial when half out`() {
        // 上半截在画面外：只有一半可见。
        val straddling = OverlayGeometry.ScreenRect(
            0f,
            -view.viewHeight / 4f,
            view.viewWidth,
            view.viewHeight / 4f,
        )
        assertEquals(0.5f, straddling.visibleFraction(view.viewWidth, view.viewHeight), 0.02f)
    }

    /** 退化输入：零尺寸视图或零面积裁切会除零，必须拒绝而不是产生 NaN。 */
    @Test
    fun `degenerate inputs are rejected`() {
        val zeroView = ViewSpec(0f, 100f, sensorW, sensorH)
        var threw = false
        try {
            OverlayGeometry.transformFor(zeroView, fullCrop)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("零尺寸视图应抛异常", threw)

        val emptyCrop = SensorCrop(0f, 0f, 0f, 0f)
        threw = false
        try {
            OverlayGeometry.transformFor(view, emptyCrop)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("零面积裁切应抛异常", threw)
    }

    @Test
    fun `chip anchor sits above the box rather than covering it`() {
        val rect = OverlayGeometry.map(NormBox(0.3f, 0.5f, 0.7f, 0.9f), view, fullCrop)
        val (x, y) = OverlayGeometry.chipAnchor(rect, chipHeight = 60f)
        assertEquals(rect.centerX, x, 0.01f)
        assertTrue("anchorY=$y 应小于 rect.top=${rect.top}", y < rect.top)
        // 两者之间保留一点空隙，不贴死。
        assertTrue("间距过小", rect.top - y > 5f)
    }

    @Test
    fun `a tap outside the sensor is clamped rather than producing garbage`() {
        val c = OverlayGeometry.screenPointToCentre(99999f, -99999f, view, fullCrop)
        assertTrue("cx=${c.left}", c.left in 0f..1f)
        assertTrue("cy=${c.top}", c.top in 0f..1f)
    }
}