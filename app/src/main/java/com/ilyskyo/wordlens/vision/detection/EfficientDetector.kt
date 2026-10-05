// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.Detection
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 物体检测：同时给出**是什么**和**在哪**。
 *
 * ## 为什么必须是带框的
 *
 * 早先只有图像分类（ML Kit image labeling），它只告诉你"这��图里有 Food"，不告诉你在哪。
 * 那样界面上只能列一列词，用户得自己在画面里找哪个是哪个——那不叫「看画面出词」。
 * 有了位置，词才能**长在物体上**，点一下就是那个东西。
 *
 * ## 为什么选 MediaPipe 而不是 ML Kit
 *
 * ML Kit 的目标检测基础模型只有五个粗类（Home good / Fashion / Food / Place / Plant），
 * 位置有了但类别没有用；而 EfficientDet-Lite0 给的是 COCO 80 个具体类别。更关键的是它跑在
 * **同一个 MediaPipe runtime** 里，而这个 runtime 已经为了抠图引入了——所以新增的是
 * 一个模型文件，不是新增一条依赖链。
 *
 * ## 运行模式
 *
 * 只用 [RunningMode.IMAGE]，由调用方以节流后的节奏驱动。这比 `LIVE_STREAM` 少一层
 * 原生回调与线程同步，而取景页真正需要的节奏是「每几百毫秒刷新一次词」，
 * 不是逐帧。逐帧只会让词一直抖，用户根本读不完。
 */
class EfficientDetector private constructor(
    private val detector: ObjectDetector,
) : AutoCloseable {

    /**
     * @param scoreThreshold 类别置信度下限。
     *
     * 默认 0.5 比分类引擎的 0.3 高得多：这里的结果会**直接变成用户看到的词**，而分类
     * 引擎的结果还要经过词典匹配与筛选。宁可少给几个候选，也不要在画面上飘一个
     * 「sports ball」然后让人点了才发现是张沙发照片。
     */
    suspend fun detect(bitmap: Bitmap): List<DetectedObject> = withContext(Dispatchers.Default) {
        var image: MPImage? = null
        try {
            image = BitmapImageBuilder(bitmap).build()
            val detections = detector.detect(image).detections()
            detections.mapNotNull { it.toDetectedObject(bitmap.width, bitmap.height) }
        } catch (e: Exception) {
            // 检测失败不该让拍照页崩掉：降级成"这一帧没有词"即可。
            Log.w(TAG, "detect failed", e)
            emptyList()
        } finally {
            runCatching { image?.close() }
        }
    }

    private fun Detection.toDetectedObject(srcW: Int, srcH: Int): DetectedObject? {
        // 每个检测通常带多个候选类别，只取最高分那个。
        val best = categories().maxByOrNull { it.score() } ?: return null
        val name = best.categoryName()?.takeIf { it.isNotBlank() } ?: return null
        val score = best.score()

        val raw: RectF = boundingBox() ?: return null
        // 模型给的是归一化框。这里夹紧到 [0,1]：EfficientDet 在边缘物体上会给出略微越界的
        // 坐标，直接用会让覆盖层的词片跑到屏幕外面去。
        val box = RectF(
            raw.left.coerceIn(0f, 1f),
            raw.top.coerceIn(0f, 1f),
            raw.right.coerceIn(0f, 1f),
            raw.bottom.coerceIn(0f, 1f),
        )
        if (box.width() <= 0f || box.height() <= 0f) return null

        return DetectedObject(
            categoryName = name,
            score = score,
            box = box,
            sourceWidth = srcW,
            sourceHeight = srcH,
            // 词典匹配交给上层：这里不该知道词典的存在，否则换词典要改模型封装。
            displayWord = null,
        )
    }

    override fun close() {
        runCatching { detector.close() }
    }

    companion object {
        private const val TAG = "EfficientDetector"

        /** 必须与 `app/src/main/assets/models/` 下的文件名一致。 */
        const val MODEL_PATH = "models/efficientdet_lite0.tflite"

        private const val SCORE_THRESHOLD = 0.5f

        /** 一次最多取 8 个。取更多会让取景页的词片互相压住，反而一个都读不到。 */
        private const val MAX_RESULTS = 8

        /**
         * 建立检测器。模型缺失或加载失败时返回 null 而不是抛异常——取景页会退化成
         * 「只显示词典里按场景挑的词」，仍然可用。
         */
        fun create(context: Context): EfficientDetector? = try {
            // ObjectDetectorOptions 是 ObjectDetector 的嵌套类，不是顶层类。
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_PATH).build())
                .setRunningMode(RunningMode.IMAGE)
                .setScoreThreshold(SCORE_THRESHOLD)
                .setMaxResults(MAX_RESULTS)
                .build()
            EfficientDetector(ObjectDetector.createFromOptions(context, options))
        } catch (e: Exception) {
            Log.e(TAG, "could not load $MODEL_PATH", e)
            null
        }
    }
}

/**
 * 合并同一次检测里高度重叠的重复框。
 *
 * EfficientDet 在物体堆叠时经常给出几个交并比很高的框。全都画到取景画面上，覆盖层会
 * 变成一团互相压着的词片——用户既读不清也点不准。保留分数最高的那个，丢掉其余。
 *
 * 单独一个纯函数：它是覆盖层可读性的关键，且完全可测。
 */
fun List<DetectedObject>.dedupeOverlapping(threshold: Float = DEFAULT_IOU_THRESHOLD): List<DetectedObject> {
    if (size <= 1) return this
    val kept = mutableListOf<DetectedObject>()
    for (candidate in sortedByDescending { it.score }) {
        // 与已保留的任何一个框高度重叠就丢弃，保留分数更高的那个。
        if (kept.none { it.iouWith(candidate) >= threshold }) kept += candidate
    }
    return kept
}

/** 交并比阈值。0.5 是常规取值：再高会漏掉真实的紧邻物体，再低会把两个物体合并成一个。 */
const val DEFAULT_IOU_THRESHOLD = 0.5f
