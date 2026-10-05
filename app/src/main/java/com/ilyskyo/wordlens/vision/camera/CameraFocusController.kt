// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision.camera

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import com.ilyskyo.wordlens.vision.camera.CameraFocusMath.FocusCommand
import com.ilyskyo.wordlens.vision.camera.CameraFocusMath.NormBox
import com.ilyskyo.wordlens.vision.camera.CameraFocusMath.SensorCrop
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 「点一下物体 → 画面推过去」的执行器：把 [CameraFocusMath] 算出的裁切区域逐帧下发到相机。
 *
 * ## 动效为什么在这一层
 *
 * 变焦必须**逐帧连续**：每一帧都要重新计算并下发裁切区域。如果把插值放在 Composable 里，
 * 只能做到「每帧算一个值然后跳过去」，运动会出现台阶——而镜头推动的手感恰恰取决于这个连续性。
 * 在这里由一个协程按固定节奏推进，相机收到的裁切序列本身就是连续的。
 *
 * ## 为什么必须走 Camera2 interop
 *
 * `CameraControl.setZoomRatio` 只能**居中**放大：画面中心被固定在裁切中心，无法把物体推到
 * 画面中央。要移动裁切中心只能下发 `SCALER_CROP_REGION`，那是 Camera2 的能力，CameraX 通过
 * [Camera2CameraControl.setCaptureRequestOptions] 暴露。
 *
 * ## 状态为什么自己记
 *
 * CameraX 的 `zoomState` 只暴露倍率，**不暴露裁切矩形**。试过用「倍率与裁切尺寸成反比」反推
 * 起点，但那条路只在裁切居中时成立——我们自己的动画会把裁切推偏，反推立刻失真，于是
 * 「连续聚焦第二个物体」时会从错误位置开始移动。所以直接维护状态，不猜。
 *
 * ## CameraX 1.6 的 API 形状
 *
 * 这一版没有 `Camera.sensorInfo`，传感器有效阵列要通过
 * [Camera2CameraInfo.getCameraCharacteristic] 查 `SENSOR_INFO_ACTIVE_ARRAY_SIZE`；
 * `zoomState` 是 LiveData 而非 StateFlow。这些都不是文档里一眼能看到的东西，所以记在这里。
 */
@Stable
class CameraFocusController internal constructor(
    private val camera: Camera,
) {
    /** 聚焦动画进行中。界面用它禁用重复点击。 */
    val isAnimating: MutableState<Boolean> = mutableStateOf(false)

    private val camera2Control: Camera2CameraControl? =
        Camera2CameraControl.from(camera.cameraControl)

    private val camera2Info: Camera2CameraInfo? = Camera2CameraInfo.from(camera.cameraInfo)

    /**
     * 传感器有效阵列。
     *
     * 注意类型：`SENSOR_INFO_ACTIVE_ARRAY_SIZE` 在 Camera2 里是 `Key<Rect>` 而不是 `Size`
     * （`Size` 的是 `SENSOR_INFO_PIXEL_ARRAY_SIZE`）。容易记混，所以这里直接用 `Rect` 存，
     * 尺寸从它的宽高取，省掉一次转换。
     *
     * 查一次就缓存：这个 characteristic 不会变，而每次查询都要付一次反射开销。
     */
    private var cachedArray: Rect? = null

    private val activeArray: Rect?
        get() = cachedArray ?: camera2Info
            ?.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?.also { cachedArray = it }

    private val sensorWidth: Int get() = activeArray?.width() ?: 0
    private val sensorHeight: Int get() = activeArray?.height() ?: 0

    private val rotationDegrees: Int
        get() = camera.cameraInfo.sensorRotationDegrees

    private val maxZoomRatio: Float
        get() = max(
            camera.cameraInfo.zoomState.value?.maxZoomRatio ?: CameraFocusMath.MIN_ZOOM,
            CameraFocusMath.MIN_ZOOM,
        )

    /**
     * 我们自己下发过的最后一个裁切区域。null 表示相机仍在原生全视野。
     */
    private var lastCrop: Rect? = null

    /**
     * 把计算层的整数裁切区转成 Camera2 需要的 `Rect`。
     *
     * 转换只在这一处发生：`CameraFocusMath` 全程用纯 Kotlin 数据类型（原因见该文件头），
     * 而 `SCALER_CROP_REGION` 要的必须是 `android.graphics.Rect`。
     */
    private fun CameraFocusMath.SensorCropInt.toRect(): Rect = Rect(left, top, right, bottom)

    /** 一次聚焦请求。 */
    data class FocusRequest(
        /** 目标框：归一化、**图像坐标系**（检测器直接给出的那个）。 */
        val box: NormBox,
        /**
         * 用户是否明确点了某个物体。
         *
         * 影响两点：目标倍率是否作为下限（连点时不缩回原视野），以及动画时长
         * （隐式聚焦更短，否则每次识别到新物体画面都会晃一下，很吵）。
         */
        val explicit: Boolean = true,
    )

    /** 传感器信息不可用时为 true——此时聚焦会静默失效，界面可以据此给出替代交互。 */
    val isUsable: Boolean get() = camera2Control != null && sensorWidth > 0 && sensorHeight > 0

    /**
     * 把镜头推到 [request] 指定的物体上。
     *
     * @param viewAspect 取景控件的宽高比。传错会让推近程度不对，必须实时取。
     * @param durationMs 动画时长。
     */
    suspend fun focusOn(request: FocusRequest, viewAspect: Float, durationMs: Int = DEFAULT_MS) {
        if (!isUsable) return
        val control = camera2Control ?: return

        // 检测器给的是图像坐标，Camera2 要的是横置传感器坐标，横屏拍照时两者差 90°。
        // 这一步漏掉会聚焦到画面里另一个位置上那个物体，而且看起来"聚焦成功了"。
        val oriented = CameraFocusMath.orientedBox(request.box, rotationDegrees)
        val target = CameraFocusMath.focusOn(
            target = oriented,
            sensorWidth = sensorWidth,
            sensorHeight = sensorHeight,
            viewAspect = viewAspect,
            maxZoomRatio = maxZoomRatio,
            currentZoomRatio = if (request.explicit) CameraFocusMath.MIN_ZOOM else currentZoom(),
        )
        animate(control, target, durationMs)
    }

    /** 回到全视野。用于「取消选中」。 */
    suspend fun reset(durationMs: Int = DEFAULT_MS) {
        if (!isUsable) return
        val control = camera2Control ?: return
        val wide = FocusCommand(
            crop = SensorCrop(0f, 0f, sensorWidth.toFloat(), sensorHeight.toFloat()),
            zoomRatio = CameraFocusMath.MIN_ZOOM,
        )
        animate(control, wide, durationMs)
    }

    /**
     * 逐步推进裁切区域。
     *
     * 固定步长而不是「按时间比例插值」：后者的步长取决于设备实际帧间隔，慢机器上会明显卡顿。
     * 固定步长让所有设备上的运动速度一致。
     */
    private suspend fun animate(control: Camera2CameraControl, target: FocusCommand, durationMs: Int) {
        val from = currentCrop()
        val to = target.toIntCrop().toRect()
        val steps = (durationMs / STEP_MS).coerceAtLeast(1)

        if (from == to) {
            lastCrop = to
            return
        }

        isAnimating.value = true
        try {
            for (step in 1..steps) {
                val t = step.toFloat() / steps
                // smoothstep：两端速度为 0、中间最快。比线性更接近「镜头被推动」的手感。
                val eased = t * t * (3f - 2f * t)
                applyCrop(control, lerpRect(from, to, eased))
                delay(STEP_MS.toLong())
            }
            // 结束时再下发一次终值：最后一帧是浮点插值结果，残留一个像素的偏差会让
            // 「再点一次同一个物体」时起点不对。
            applyCrop(control, to)
        } catch (e: Exception) {
            // 动画被打断（用户切页、相机被别的应用抢占）不应该崩，取景页会继续用现状渲染。
            Log.w(TAG, "focus animation interrupted", e)
        } finally {
            lastCrop = to
            isAnimating.value = false
        }
    }

    private fun applyCrop(control: Camera2CameraControl, rect: Rect) {
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.SCALER_CROP_REGION, rect)
            .build()
        control.setCaptureRequestOptions(options)
    }

    /** 当前裁切区域。首次调用时相机处于原生全视野，返回整幅传感器。 */
    private fun currentCrop(): Rect = lastCrop ?: Rect(0, 0, sensorWidth, sensorHeight)

    /** 由裁切宽度反推倍率：倍率 = 传感器宽 / 裁切宽。 */
    private fun currentZoom(): Float =
        lastCrop
            ?.takeIf { it.width() > 0 }
            ?.let { sensorWidth.toFloat() / it.width() }
            ?: CameraFocusMath.MIN_ZOOM

    private fun lerpRect(from: Rect, to: Rect, t: Float): Rect {
        fun mix(a: Int, b: Int) = (a + (b - a) * t).roundToInt()
        return Rect(
            mix(from.left, to.left),
            mix(from.top, to.top),
            mix(from.right, to.right),
            mix(from.bottom, to.bottom),
        )
    }

    companion object {
        private const val TAG = "CameraFocus"

        /** 320ms：足够看清镜头在动，又不至于让人觉得慢。 */
        const val DEFAULT_MS = 320

        /** 隐式聚焦（识别到新物体而非用户点击）用的时长，明显短于主动聚焦。 */
        const val IMPLICIT_MS = 180

        /** 一帧。固定步长保证不同帧率的设备上运动速度一致。 */
        private const val STEP_MS = 16
    }
}