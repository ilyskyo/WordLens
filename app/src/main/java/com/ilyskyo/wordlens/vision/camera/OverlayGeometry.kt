// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision.camera

import com.ilyskyo.wordlens.vision.camera.CameraFocusMath.NormBox
import com.ilyskyo.wordlens.vision.camera.CameraFocusMath.SensorCrop
import kotlin.math.max

/**
 * 把归一化的检测框映射到取景画面上的像素位置。
 *
 * ## 为什么这件事需要单独一个文件
 *
 * 检测器给的框是**归一化到整幅传感器**的（0..1）。而用户看到的是取景控件，两者之间隔着：
 *
 * 1. 当前裁切区域——一执行变焦聚焦，框在屏幕上的位置就变了；
 * 2. 预览的填充方式——CameraX 默认 `FILL_CENTER`，画面会被裁到铺满取景框，不足的边被切掉。
 *
 * 忽略任何一条的后果都是**词片和物体对不上**，而且越变焦偏得越远。这在预览里是立刻可见的：
 * 用户会以为识别错了，而真正错的是坐标换算。所以这段换算被单独抽出来，纯 Kotlin、无
 * Android 依赖，可以对着手算值验证。
 *
 * ## 为什么要自己算而不是用 CameraX 的 TransformationInfo
 *
 * `PreviewView.getOutputTransform()` 给的是 `Matrix`，它同时包含预览的旋转与镜像。用它确实
 * 能得到正确结果，但那是 4x4 矩阵乘向量——调试时看不出哪一步错了，而且它把「旋转」与
 * 「裁切」耦在一起。我们自己只需要处理裁切与缩放，旋转在检测阶段已经处理过了
 * （见 [CameraFocusMath.orientedBox]），保持一条直线更可靠。
 */
object OverlayGeometry {

    /**
     * 取景画面上的一个矩形（像素）。
     */
    data class ScreenRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f

        /** 这个矩形有多少落在取景框内，0..1。用于把已经推出画面的词片淡出。 */
        fun visibleFraction(viewW: Float, viewH: Float): Float {
            val x0 = left.coerceIn(0f, viewW)
            val x1 = right.coerceIn(0f, viewW)
            val y0 = top.coerceIn(0f, viewH)
            val y1 = bottom.coerceIn(0f, viewH)
            val visible = (x1 - x0).coerceAtLeast(0f) * (y1 - y0).coerceAtLeast(0f)
            val total = width.coerceAtLeast(0.001f) * height.coerceAtLeast(0.001f)
            return (visible / total).coerceIn(0f, 1f)
        }
    }

    /**
     * 取景控件的几何信息。
     *
     * @param viewWidth 取景控件宽，像素
     * @param viewHeight 取景控件高，像素
     * @param sensorWidth 传感器有效阵列宽
     * @param sensorHeight 传感器有效阵列高
     * @param rotationDegrees 传感器相对显示方向的旋转（90/180/270）。
     *
     *   这个参数**不能省**。传感器阵列通常是横向的（4:3），而竖屏取景框是竖向的（3:4），
     *   预览会被旋转 90°。不算这个旋转就去比较宽高比，得到的判断是反的——而这是手机竖持
     *   这个最常见的场景，等于映射在最需要它的时候总是错的。
     */
    data class ViewSpec(
        val viewWidth: Float,
        val viewHeight: Float,
        val sensorWidth: Int,
        val sensorHeight: Int,
        val rotationDegrees: Int = 0,
    ) {
        /** 旋转是否会让传感器坐标轴互换（预览转了 90° 或 270°）。 */
        val swapsAxes: Boolean
            get() {
                val r = normalizeDegrees(rotationDegrees)
                return r == 90 || r == 270
            }
    }

    /**
     * 裁切区域旋转到「显示方向」之后的尺寸。
     *
     * 传感器阵列通常是横向的，预览竖持时会被转 90°，所以屏幕上的画面宽高是**互换**的。
     * 忘了换就会把 4:3 当成 3:4 去比，推近程度和词片位置会一起偏。
     */
    fun displayedSize(crop: SensorCrop, rotationDegrees: Int): Pair<Float, Float> {
        val r = normalizeDegrees(rotationDegrees)
        return if (r == 90 || r == 270) {
            crop.height to crop.width
        } else {
            crop.width to crop.height
        }
    }

    /**
     * 计算「显示方向像素 → 屏幕像素」的映射参数。
     *
     * `FILL_CENTER`（CameraX 预览的默认值）：**保持宽高比铺满取景框，多出来的一侧居中裁掉**。
     *
     * 这就是 `max(按宽铺满, 按高铺满)`：取小的那个会留黑边（`FIT_CENTER` 的行为），
     * 而我们要的是无黑边的满屏。注意这里算出来的 offset 一定是 ≤ 0——因为总有一侧要溢出。
     */
    data class Transform(
        val scale: Float,
        val offsetX: Float,
        val offsetY: Float,
    ) {
        fun mapX(displayX: Float): Float = displayX * scale + offsetX
        fun mapY(displayY: Float): Float = displayY * scale + offsetY
    }

    /**
     * 求变换参数。
     *
     * @param crop 当前裁切区域（传感器像素）。变焦聚焦之后必须传最新值。
     */
    fun transformFor(view: ViewSpec, crop: SensorCrop): Transform {
        require(view.viewWidth > 0f && view.viewHeight > 0f) { "view size must be positive" }
        require(crop.width > 0f && crop.height > 0f) { "crop must have area" }

        val (dispW, dispH) = displayedSize(crop, view.rotationDegrees)
        // max 而不是分支：见 Transform 的注释。
        val scale = max(view.viewWidth / dispW, view.viewHeight / dispH)
        return Transform(
            scale = scale,
            offsetX = (view.viewWidth - dispW * scale) / 2f,
            offsetY = (view.viewHeight - dispH * scale) / 2f,
        )
    }

    /**
     * 把一个归一化检测框映射到屏幕。
     *
     * @param box 归一化到**整幅传感器**、**传感器方向**的框（检测器直接给的那个）。
     * @param crop 当前裁切区域，同样是传感器像素。
     */
    fun map(box: NormBox, view: ViewSpec, crop: SensorCrop): ScreenRect {
        val t = transformFor(view, crop)
        // 归一化 -> 传感器像素 -> 裁切区内坐标
        val x0 = box.left * view.sensorWidth - crop.left
        val y0 = box.top * view.sensorHeight - crop.top
        val x1 = box.right * view.sensorWidth - crop.left
        val y1 = box.bottom * view.sensorHeight - crop.top

        // 旋转是绕画面中心、且轴对齐的，所以四个角分别转即可，结果仍是矩形。
        val tl = rotatePoint(x0, y0, crop, view.rotationDegrees)
        val br = rotatePoint(x1, y1, crop, view.rotationDegrees)
        return ScreenRect(
            left = minOf(t.mapX(tl.first), t.mapX(br.first)),
            top = minOf(t.mapY(tl.second), t.mapY(br.second)),
            right = maxOf(t.mapX(tl.first), t.mapX(br.first)),
            bottom = maxOf(t.mapY(tl.second), t.mapY(br.second)),
        )
    }

    /**
     * 由屏幕上的点反推归一化检测框的**中心**，用于「点哪儿就选哪个物体」。
     *
     * 有了这个，用户不必精确点中物体，点它附近的词片也可以——而点中词片本来就更准，
     * 因为词片比物体本身大。
     */
    fun screenPointToCentre(
        screenX: Float,
        screenY: Float,
        view: ViewSpec,
        crop: SensorCrop,
    ): NormBox {
        val t = transformFor(view, crop)
        // 屏幕 -> 显示方向像素（裁切区内）
        val dispX = (screenX - t.offsetX) / t.scale
        val dispY = (screenY - t.offsetY) / t.scale
        // 反向旋转回传感器方向 -> 传感器像素 -> 归一化
        val (u, v) = unrotatePoint(dispX, dispY, crop, view.rotationDegrees)
        val cx = ((u + crop.left) / view.sensorWidth).coerceIn(0f, 1f)
        val cy = ((v + crop.top) / view.sensorHeight).coerceIn(0f, 1f)
        // 返回一个零尺寸的框：它的中心就是要选的点。
        return NormBox(cx, cy, cx, cy)
    }

    /**
     * 裁切区内的传感器坐标 -> 显示方向坐标。
     *
     * `rotationDegrees` 是**顺时针**（CameraX 里预览转正需要的角度）。
     *
     * 推导：以 90° 顺时针为例，原图上 (x, y) 转到新图上应为 (cropH - y, x)，
     * 因为新图宽高互换了，且靠右的一列（y 大）变成新图靠上的一行。180° 与 270° 同理。
     */
    private fun rotatePoint(
        u: Float,
        v: Float,
        crop: SensorCrop,
        rotationDegrees: Int,
    ): Pair<Float, Float> = when (normalizeDegrees(rotationDegrees)) {
        90 -> (crop.height - v) to u
        180 -> (crop.width - u) to (crop.height - v)
        270 -> v to (crop.width - u)
        else -> u to v
    }

    /** [rotatePoint] 的逆：显示方向坐标 -> 裁切区内的传感器坐标。 */
    private fun unrotatePoint(
        dispX: Float,
        dispY: Float,
        crop: SensorCrop,
        rotationDegrees: Int,
    ): Pair<Float, Float> = when (normalizeDegrees(rotationDegrees)) {
        90 -> dispY to (crop.height - dispX)
        180 -> (crop.width - dispX) to (crop.height - dispY)
        270 -> (crop.width - dispY) to dispX
        else -> dispX to dispY
    }

    private fun normalizeDegrees(deg: Int): Int = ((deg % 360) + 360) % 360

    /**
     * 把**分析流画面**里的归一化框换算回整幅传感器的归一化框。
     *
     * ImageAnalysis 的帧只包含当前裁切区域（变焦之后传感器不再全量出图），检测器在帧内
     * 归一化的坐标直接拿去映射会整体跑偏。这一函数就是那条偏移的逆运算。
     */
    fun frameBoxToSensorNorm(
        box: NormBox,
        crop: SensorCrop,
        sensorWidth: Int,
        sensorHeight: Int,
    ): NormBox {
        require(sensorWidth > 0 && sensorHeight > 0) { "sensor size must be positive" }
        fun mapX(frameX: Float): Float = (crop.left + frameX * crop.width) / sensorWidth
        fun mapY(frameY: Float): Float = (crop.top + frameY * crop.height) / sensorHeight
        return NormBox(
            left = mapX(box.left).coerceIn(0f, 1f),
            top = mapY(box.top).coerceIn(0f, 1f),
            right = mapX(box.right).coerceIn(0f, 1f),
            bottom = mapY(box.bottom).coerceIn(0f, 1f),
        )
    }

    /**
     * 词片锚点：放在物体框的哪个位置。
     *
     * 默认锚在框的**上方**，而不是中心。原因是中心会被物体本身挡住——透明贴纸还好，
     * 但一个词压在杯子正中间会挡住它，而它才是用户真正在看的东西。
     */
    fun chipAnchor(rect: ScreenRect, chipHeight: Float): Pair<Float, Float> = Pair(
        rect.centerX,
        // 词片底边贴在框顶边往上一点，留 6dp 让它不贴死。
        rect.top - chipHeight - CHIP_GAP,
    )

    private const val CHIP_GAP = 6f
}