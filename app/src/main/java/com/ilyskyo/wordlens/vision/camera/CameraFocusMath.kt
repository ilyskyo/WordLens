// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision.camera

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 「点一下物体，相机就推过去」的变焦与裁切计算。
 *
 * ## 这里刻意不 import 任何 android.graphics 类型
 *
 * 最初这个文件用的是 `android.graphics.RectF` / `Rect`，结果 15 个单测里有 13 个失败——
 * 不是逻辑错，而是 `android.util` 在 JVM 单测里是**打桩的空壳**，所有取值方法都返回 0，
 * `equals` 也不比较内容。
 *
 * 与其为此引入 Robolectric（多一个依赖、多一层构建时间），不如承认一个更根本的事实：
 * **这段几何计算与 Android 无关**。坐标换算只有一处需要真正的 `Rect`，放在调用方做，
 * 这个文件就能永远在 JVM 上被验证。
 *
 * 几何错误最难在真机上发现——现场表现是「画面推近了但目标跑偏」，不可复现、也没有日志。
 * 把它变成毫秒级的单测，是这个文件存在的意义。
 */
object CameraFocusMath {

    /**
     * 目标物体在最终画面里占多大。
     *
     * 不推到 100%：顶满会让物体紧贴四边、完全没有环境，看起来像局部截图而不是
     * 「凑近去看」。留 30% 余量更像人凑过去的视角。
     */
    const val TARGET_FILL = 0.70f

    /** 最小倍率。低于 1 在部分设备上会切到超广角，画面边缘畸变严重。 */
    const val MIN_ZOOM = 1f

    /**
     * 计算把 [target] 推到画面中央、并放大到 [TARGET_FILL] 所需的裁切区域。
     *
     * @param target 归一化到传感器有效阵列的框（0..1）
     * @param sensorWidth 传感器有效阵列宽，像素
     * @param sensorHeight 传感器有效阵列高，像素
     * @param viewAspect 取景画面宽高比（预览控件的宽 / 高）
     * @param maxZoomRatio 该相机支持的最大倍率
     * @param currentZoomRatio 当前倍率；继续放大时以它为下限，避免越推越近之后自己缩回去
     */
    fun focusOn(
        target: NormBox,
        sensorWidth: Int,
        sensorHeight: Int,
        viewAspect: Float,
        maxZoomRatio: Float,
        currentZoomRatio: Float = MIN_ZOOM,
    ): FocusCommand {
        require(sensorWidth > 0 && sensorHeight > 0) { "sensor size must be positive" }
        require(viewAspect > 0f) { "viewAspect must be positive" }

        val boxW = target.width.coerceIn(0f, 1f) * sensorWidth
        val boxH = target.height.coerceIn(0f, 1f) * sensorHeight
        if (boxW <= 0f || boxH <= 0f) {
            return FocusCommand(
                SensorCrop(0f, 0f, sensorWidth.toFloat(), sensorHeight.toFloat()),
                MIN_ZOOM,
            )
        }

        // 传感器按自身宽高比成像，预览按 viewAspect 显示，多出来的部分会被裁掉。
        // 所以缩放要按**实际可见范围**算，而不是按整个传感器算——
        // 这是「推近了但目标还是没占满画面」最常见的原因。
        val sensorAspect = sensorWidth.toFloat() / sensorHeight
        val visibleW: Float
        val visibleH: Float
        if (viewAspect > sensorAspect) {
            // 预览比传感器宽：上下被裁，可见高度是瓶颈。
            visibleW = sensorWidth.toFloat()
            visibleH = sensorWidth / viewAspect
        } else {
            visibleH = sensorHeight.toFloat()
            visibleW = sensorHeight * viewAspect
        }

        // 让目标框的较大边占可见范围的 TARGET_FILL。
        //
        // 方向很容易写反：要的是「可见范围 / 目标大小」的比值，而不是反过来。
        // 反过来算会得到一个小于 1 的数，被 MIN_ZOOM 夹成 1.0，于是表现为「点了没反应」。
        val wanted = max(
            visibleW * TARGET_FILL / boxW,
            visibleH * TARGET_FILL / boxH,
        )
        val zoom = wanted.coerceIn(
            currentZoomRatio.coerceAtLeast(MIN_ZOOM),
            maxZoomRatio.coerceAtLeast(MIN_ZOOM),
        )

        val cropW = sensorWidth / zoom
        val cropH = sensorHeight / zoom

        val centreX = target.centerX.coerceIn(0f, 1f) * sensorWidth
        val centreY = target.centerY.coerceIn(0f, 1f) * sensorHeight

        val left = clampCentre(centreX, cropW, sensorWidth)
        val top = clampCentre(centreY, cropH, sensorHeight)

        return FocusCommand(
            crop = SensorCrop(left, top, left + cropW, top + cropH),
            zoomRatio = zoom,
        )
    }

    /**
     * 把裁切区中心移到 [centre]，但不允许越出传感器。
     *
     * 不夹紧的后果：目标在画面边缘时裁切框会越界，相机会静默把它拉回有效范围，
     * 于是「聚焦」悄悄变成了「漂移」。夹紧之后边缘目标会停在画面边缘，
     * 而不会反过来滚走或者消失。
     */
    private fun clampCentre(centre: Float, cropSize: Float, sensorSize: Int): Float {
        val maxStart = (sensorSize - cropSize).coerceAtLeast(0f)
        return (centre - cropSize / 2f).coerceIn(0f, maxStart)
    }

    /**
     * 传感器旋转时，归一化框的横纵关系要跟着换。
     *
     * 检测器在**图像坐标系**里给框，而 Camera2 的 `SCALER_CROP_REGION` 用的是**横置传感器
     * 坐标**。横屏拍照（90° / 270°）时直接用会聚焦到错误的位置——不是偏一点，是转 90°
     * 之后的另一个物体。这个错误在真机上极难被发现，因为画面看起来"聚焦了"。
     */
    fun orientedBox(box: NormBox, rotationDegrees: Int): NormBox {
        val normalised = ((rotationDegrees % 360) + 360) % 360
        return when (normalised) {
            // 顺时针 90°：(x, y) -> (1 - y, x)
            90 -> NormBox(1f - box.bottom, box.left, 1f - box.top, box.right)
            // 180°：(x, y) -> (1 - x, 1 - y)
            180 -> NormBox(1f - box.right, 1f - box.bottom, 1f - box.left, 1f - box.top)
            // 逆时针 90°：(x, y) -> (y, 1 - x)
            270 -> NormBox(box.top, 1f - box.right, box.bottom, 1f - box.left)
            else -> box
        }
    }

    /**
     * [orientedBox] 的逆：把传感器归一化的点换回**竖持显示图**上的点。
     *
     * 抠图分割器吃的是按 EXIF 转正后的照片，而词片框是传感器坐标——把用户选中的物体
     * 换算成 MagicTouch 的 tap 点必须走这一步。方向写反的后果是「抠了杯子旁边那本书」，
     * 而且在横屏设备上看起来一切正常。
     */
    fun displayPointFromSensorNorm(x: Float, y: Float, rotationDegrees: Int): Pair<Float, Float> {
        val normalised = ((rotationDegrees % 360) + 360) % 360
        return when (normalised) {
            // 正向 sensor = (1 - imgY, imgX)：反解得 img = (sensorY, 1 - sensorX)
            90 -> y to (1f - x)
            180 -> (1f - x) to (1f - y)
            // 正向 sensor = (imgY, 1 - imgX)：反解得 img = (1 - sensorY, sensorX)
            270 -> (1f - y) to x
            else -> x to y
        }
    }

    /**
     * 整个框版本的 [displayPointFromSensorNorm]：传感器归一化框 → 竖持显示图归一化框。
     *
     * 旋转是轴对齐的，四个角各自反旋后仍是矩形，所以直接交换边界即可。
     */
    fun imageBoxFromSensorNorm(box: NormBox, rotationDegrees: Int): NormBox {
        val normalised = ((rotationDegrees % 360) + 360) % 360
        return when (normalised) {
            90 -> NormBox(box.top, 1f - box.right, box.bottom, 1f - box.left)
            // 180° 的逆与自身相同：正向是 (x,y)->(1-x,1-y)，反解即左=1-sensor右、上=1-sensor下。
            180 -> NormBox(1f - box.right, 1f - box.bottom, 1f - box.left, 1f - box.top)
            270 -> NormBox(1f - box.bottom, box.left, 1f - box.top, box.right)
            else -> box
        }
    }

    /** 归一化矩形（0..1）。自己的类型，不是 android.graphics.RectF——理由见文件头。 */
    data class NormBox(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val width: Float get() = (right - left).coerceAtLeast(0f)
        val height: Float get() = (bottom - top).coerceAtLeast(0f)
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f

        fun toPixelInt(sensorWidth: Int, sensorHeight: Int): SensorCropInt = SensorCropInt(
            left = (left * sensorWidth).roundToInt(),
            top = (top * sensorHeight).roundToInt(),
            right = (right * sensorWidth).roundToInt(),
            bottom = (bottom * sensorHeight).roundToInt(),
        )
    }

    /** 传感器像素坐标下的裁切区。 */
    data class SensorCrop(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val width: Float get() = (right - left).coerceAtLeast(0f)
        val height: Float get() = (bottom - top).coerceAtLeast(0f)
    }

    /** 整数像素的裁切区，供 Camera2 直接使用。 */
    data class SensorCropInt(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    /** 一次聚焦的结果。 */
    data class FocusCommand(
        val crop: SensorCrop,
        val zoomRatio: Float,
    ) {
        fun toIntCrop(): SensorCropInt = SensorCropInt(
            left = crop.left.roundToInt(),
            top = crop.top.roundToInt(),
            right = crop.right.roundToInt(),
            bottom = crop.bottom.roundToInt(),
        )
    }
}