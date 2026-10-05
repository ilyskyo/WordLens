// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.capture

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 取景页的相机层：PreviewView + 分析流 + 拍照流，绑定在宿主生命周期上。
 *
 * ## 为什么 scaleType 是 FILL_CENTER
 *
 * [com.ilyskyo.wordlens.vision.camera.OverlayGeometry] 的词片映射按「铺满、多余侧居中裁掉」
 * 推导（见其 transformFor）。这里若改成 FIT_CENTER，词片会整体偏移——两处必须一致，
 * 所以这个选择写死并解释，不给「顺手改一下」留空间。
 *
 * ## 为什么分析流只要 640x480
 *
 * EfficientDet-lite0 内部就缩到 320；给 4K 帧只是让 YUV 转换白白多洗十倍像素。
 * 词片的精度诉求在归一化坐标里，与分辨率无关。
 *
 * ## COMPATIBLE 模式
 *
 * PreviewView 默认的 PERFORMANCE 模式在部分国产 ROM 上首帧黑屏；COMPATIBLE 慢 1~2 帧
 * 但几乎所有设备都能出图。宁可启动慢半拍。
 */
@Composable
fun CaptureCamera(
    viewModel: CaptureViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = rememberPreviewView(context)
    androidx.compose.foundation.layout.Box(modifier = modifier) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { previewView },
            modifier = Modifier
                .matchParentSize()
                // 推镜头的目标倍率按取景控件的真实宽高比算，尺寸必须实时上报。
                .onSizeChanged { viewModel.updateViewAspect(it.width, it.height) },
        )
    }

    LaunchedEffect(lifecycleOwner, previewView) {
        try {
            val provider = context.awaitCameraProvider()
            provider.unbindAll()

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(ANALYSIS_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                        )
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { proxy ->
                viewModel.onImageProxy(proxy)
            }

            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val camera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
                capture,
            )
            viewModel.onCameraReady(camera, capture)
        } catch (e: Exception) {
            // 相机被别的 App 占用、无后摄、provider 初始化失败——都降级为「没有取景器」，
            // 页面外壳仍然能显示权限说明，而不是崩。
            Log.w(TAG, "camera bind failed", e)
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.onCameraReleased() }
    }
}

@Composable
private fun rememberPreviewView(context: Context): PreviewView {
    return androidx.compose.runtime.remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
}

private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            runCatching { future.get() }.fold(
                onSuccess = { cont.resume(it) },
                onFailure = { cont.resumeWithException(it) },
            )
        }, ContextCompat.getMainExecutor(this))
    }

private const val TAG = "CaptureCamera"

/** 分析帧目标尺寸。见类头「为什么分析流只要 640x480」。 */
private val ANALYSIS_SIZE = Size(640, 480)
