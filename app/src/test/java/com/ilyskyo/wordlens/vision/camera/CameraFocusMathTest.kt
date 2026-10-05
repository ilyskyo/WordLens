// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision.camera

import com.ilyskyo.wordlens.vision.camera.CameraFocusMath.FocusCommand
import com.ilyskyo.wordlens.vision.camera.CameraFocusMath.NormBox
import com.ilyskyo.wordlens.vision.camera.CameraFocusMath.SensorCrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖「点物品 → 相机推过去」的裁切几何。
 *
 * 全部可在纯 JVM 上运行，因为 `CameraFocusMath` 不引用任何 android.graphics 类型。
 * 这不是洁癖：几何错误的现场表现是「画面推近了但目标跑偏」，不可复现也没有日志，
 * 靠真机验证一次要几分钟，而这里是毫秒级。
 */
class CameraFocusMathTest {

    /** 典型手机主摄的传感器有效阵列。 */
    private val sensorW = 4032
    private val sensorH = 3024
    private val nativeAspect = sensorW.toFloat() / sensorH

    private fun focus(
        box: NormBox,
        viewAspect: Float = nativeAspect,
        maxZoom: Float = 8f,
        currentZoom: Float = CameraFocusMath.MIN_ZOOM,
    ) = CameraFocusMath.focusOn(box, sensorW, sensorH, viewAspect, maxZoom, currentZoom)

    @Test
    fun `a centred small box is magnified`() {
        val cmd = focus(NormBox(0.40f, 0.40f, 0.60f, 0.60f))
        assertTrue("zoom=${cmd.zoomRatio}", cmd.zoomRatio > 1.5f)
    }

    @Test
    fun `a centred box ends up centred in the crop`() {
        val cmd = focus(NormBox(0.40f, 0.40f, 0.60f, 0.60f))
        val crop = cmd.crop
        assertEquals(0.5f, (crop.left + crop.width / 2f) / sensorW, 0.01f)
        assertEquals(0.5f, (crop.top + crop.height / 2f) / sensorH, 0.01f)
    }

    /**
     * 回归测试：边缘目标的裁切框不能越出传感器。
     *
     * 越界时相机会静默把区域拉回有效范围，用户看到的是「聚焦之后目标反而漂走了」。
     * 这是最容易漏、也最伤体验的一条。
     */
    @Test
    fun `a box at the left edge never produces an out-of-bounds crop`() {
        for (left in listOf(0f, 0.02f, 0.10f, 0.30f)) {
            val crop = focus(NormBox(left, 0.45f, left + 0.15f, 0.55f)).crop
            assertTrue("left=$left crop=$crop", insideSensor(crop))
        }
    }

    @Test
    fun `a box at the bottom edge never produces an out-of-bounds crop`() {
        val crop = focus(NormBox(0.45f, 0.80f, 0.55f, 0.95f)).crop
        assertTrue("crop=$crop", insideSensor(crop))
        assertTrue(crop.bottom <= sensorH)
    }

    /** 四个角都要试：`clampCentre` 的两个分支都要覆盖到。 */
    @Test
    fun `all four corners are safe`() {
        val corners = listOf(
            NormBox(0f, 0f, 0.2f, 0.2f),
            NormBox(0.8f, 0f, 1f, 0.2f),
            NormBox(0f, 0.8f, 0.2f, 1f),
            NormBox(0.8f, 0.8f, 1f, 1f),
        )
        for (box in corners) {
            val crop = focus(box).crop
            assertTrue("$box -> $crop", insideSensor(crop))
        }
    }

    @Test
    fun `zoom never exceeds what the camera supports`() {
        val cmd = focus(NormBox(0.48f, 0.48f, 0.52f, 0.52f), maxZoom = 4f)
        assertTrue("zoom=${cmd.zoomRatio}", cmd.zoomRatio <= 4f)
    }

    /** 已经放大过之后不该自己缩回去：用户是在"凑近看"，不是在做幂运算。 */
    @Test
    fun `zoom never shrinks below the current zoom`() {
        val cmd = focus(NormBox(0f, 0f, 1f, 1f), currentZoom = 3f)
        assertTrue("zoom=${cmd.zoomRatio}", cmd.zoomRatio >= 3f)
    }

    /** 超宽屏预览（21:9 看 4:3 传感器）左右被裁，可见宽度是瓶颈。 */
    @Test
    fun `an ultrawide preview stays inside the sensor`() {
        val crop = focus(NormBox(0.45f, 0.45f, 0.55f, 0.55f), viewAspect = 2.33f).crop
        assertTrue("crop=$crop", insideSensor(crop))
    }

    /**
     * 回归：可见范围必须按**预览比例**算，而不是按传感器比例。
     *
     * 超宽屏预览会裁掉传感器上下两侧，可见高度变小，所以一个「又宽又扁」的框在超宽屏下
     * 能推的倍数**更小**——因为它的短边（高度）在可见范围里占比更大了。
     * 如果按整个传感器算，得到的倍率在两种预览下完全一样，用户会感觉推近的力度不跟手。
     */
    @Test
    fun `a wide flat box can be pushed less in an ultrawide preview`() {
        val box = NormBox(0.10f, 0.45f, 0.90f, 0.55f) // 占 80% 宽、10% 高
        val native = focus(box, viewAspect = nativeAspect).zoomRatio
        val wide = focus(box, viewAspect = 2.33f).zoomRatio
        assertTrue("native=$native wide=$wide", wide < native)
    }

    /**
     * 一个**又高又窄**的框，在两种预览下倍率相同。
     *
     * 这条不是多余的：它钉住了「取 max」这个选择的含义。窄框由**宽度**决定倍率，而超宽屏
     * 只裁高度、不裁宽度，所以可见宽度不变 -> 倍率不变。如果哪天有人误把可见宽度也按
     * 预览比例去缩，这条会立刻失败，而那是一个不会报错、只会让画面推得过多或过少的 bug。
     */
    @Test
    fun `a tall narrow box is width-limited so both previews zoom the same`() {
        val box = NormBox(0.45f, 0.05f, 0.55f, 0.95f) // 10% 宽、90% 高
        val native = focus(box, viewAspect = nativeAspect).zoomRatio
        val wide = focus(box, viewAspect = 2.33f).zoomRatio
        assertEquals(native, wide, 0.01f)
    }

    @Test
    fun `a zero-area box degrades to the full frame instead of dividing by zero`() {
        val cmd = focus(NormBox(0.5f, 0.5f, 0.5f, 0.5f))
        assertEquals(
            SensorCrop(0f, 0f, sensorW.toFloat(), sensorH.toFloat()),
            cmd.crop,
        )
    }

    @Test
    fun `converting to integer pixels never inverts the rectangle`() {
        val cmd = focus(NormBox(0.05f, 0.05f, 0.95f, 0.95f))
        val ints = cmd.toIntCrop()
        assertTrue(ints.right > ints.left)
        assertTrue(ints.bottom > ints.top)
        assertTrue(ints.left >= 0 && ints.top >= 0)
        assertTrue(ints.right <= sensorW && ints.bottom <= sensorH)
    }

    // ── 传感器旋转 ──────────────────────────────────────────────────────────
    // ���组最容易错：横屏拍照时传感器相对检测图像转了 90°，直接用框会聚焦到别的物体上。

    @Test
    fun `rotation 0 leaves the box untouched`() {
        val box = NormBox(0.1f, 0.2f, 0.3f, 0.4f)
        assertEquals(box, CameraFocusMath.orientedBox(box, 0))
    }

    /**
     * 顺时针 90°：(x, y) -> (1 - y, x)。
     *
     * 检测图里的**左上角**小方块（x 贴左、y 贴上）转到**右上角**（x 贴右、y 贴上）。
     * y 不变是因为映射把新的 y 取自旧的 x，而旧 x 是 0（贴左），`1 - 0 = 1` 才是贴右——
     * 旧 x=0 对应新 y=0，所以纵向位置不变，横向翻到另一侧。
     */
    @Test
    fun `rotation 90 moves the top-left box to the top-right`() {
        val out = CameraFocusMath.orientedBox(NormBox(0f, 0f, 0.2f, 0.2f), 90)
        assertEquals(0.8f, out.left, 1e-4f)    // x 贴右
        assertEquals(0f, out.top, 1e-4f)       // y 仍贴上
        assertEquals(1f, out.right, 1e-4f)
        assertEquals(0.2f, out.bottom, 1e-4f)
    }

    /** 180° 是对角翻转：左上角的小方块应当落到右下角，且矩形不能上下颠倒。 */
    @Test
    fun `rotation 180 mirrors the box through the centre`() {
        val out = CameraFocusMath.orientedBox(NormBox(0f, 0f, 0.2f, 0.2f), 180)
        assertEquals(0.8f, out.left, 1e-4f)
        assertEquals(0.8f, out.top, 1e-4f)
        assertEquals(1f, out.right, 1e-4f)
        assertEquals(1f, out.bottom, 1e-4f)
        assertTrue("top=${out.top} bottom=${out.bottom}", out.bottom > out.top)
    }

    /**
     * 逆时针 90°：(x, y) -> (y, 1 - x)。
     *
     * 左上角逆时针转过去落到**左下角**：y 贴下边缘、x 贴左边缘。
     */
    @Test
    fun `rotation 270 moves the top-left box to the bottom-left`() {
        val out = CameraFocusMath.orientedBox(NormBox(0f, 0f, 0.2f, 0.2f), 270)
        assertEquals(0f, out.left, 1e-4f)      // x 贴左
        assertEquals(0.8f, out.top, 1e-4f)     // y 贴下
        assertEquals(0.2f, out.right, 1e-4f)
        assertEquals(1f, out.bottom, 1e-4f)
    }

    /** 任何旋转都不能把矩形上下或左右翻转——那会让框变成负面积，后续裁切全错。 */
    @Test
    fun `no rotation ever inverts the rectangle`() {
        val box = NormBox(0.1f, 0.2f, 0.3f, 0.4f)
        for (deg in listOf(0, 90, 180, 270, 360, 450, -90)) {
            val out = CameraFocusMath.orientedBox(box, deg)
            assertTrue("deg=$deg out=$out", out.right > out.left && out.bottom > out.top)
        }
    }

    /** 转 360° 必须回到原位，否则每转一圈目标就漂一次。 */
    @Test
    fun `rotation wraps instead of accumulating`() {
        val box = NormBox(0.1f, 0.2f, 0.3f, 0.4f)
        assertEquals(box, CameraFocusMath.orientedBox(box, 360))
        assertEquals(box, CameraFocusMath.orientedBox(box, -360))
        assertEquals(
            CameraFocusMath.orientedBox(box, 90),
            CameraFocusMath.orientedBox(box, 450),
        )
    }

    /** 转四次 90° 必须回到原位：这一条同时验证四个分支互为逆运算。 */
    @Test
    fun `four quarter turns return to the start`() {
        val box = NormBox(0.12f, 0.24f, 0.38f, 0.62f)
        var current = box
        repeat(4) { current = CameraFocusMath.orientedBox(current, 90) }
        assertEquals(box.left, current.left, 1e-4f)
        assertEquals(box.top, current.top, 1e-4f)
        assertEquals(box.right, current.right, 1e-4f)
        assertEquals(box.bottom, current.bottom, 1e-4f)
    }

    private fun insideSensor(crop: SensorCrop): Boolean =
        crop.left >= 0f && crop.top >= 0f &&
            crop.width > 0f && crop.height > 0f &&
            crop.right <= sensorW && crop.bottom <= sensorH
}