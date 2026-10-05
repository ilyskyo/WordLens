// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.capture

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ilyskyo.wordlens.R
import com.ilyskyo.wordlens.core.AppContainer
import com.ilyskyo.wordlens.data.model.CardOrigin
import com.ilyskyo.wordlens.data.model.Entry
import com.ilyskyo.wordlens.data.model.EntryObject
import com.ilyskyo.wordlens.data.model.EntrySource
import com.ilyskyo.wordlens.data.model.Lang
import com.ilyskyo.wordlens.data.model.LexiconEntry
import com.ilyskyo.wordlens.data.model.LexiconIndex
import com.ilyskyo.wordlens.data.model.OverlayLayer
import com.ilyskyo.wordlens.data.model.SceneGuess
import com.ilyskyo.wordlens.data.model.WordCard
import com.ilyskyo.wordlens.data.repository.AppSettings
import com.ilyskyo.wordlens.vision.AmbienceScorer
import com.ilyskyo.wordlens.vision.SceneClassifier
import com.ilyskyo.wordlens.vision.ShotClassifier
import com.ilyskyo.wordlens.vision.camera.CameraFocusController
import com.ilyskyo.wordlens.vision.camera.CameraFocusMath
import com.ilyskyo.wordlens.vision.camera.CameraFocusMath.NormBox
import com.ilyskyo.wordlens.vision.camera.CameraFocusMath.SensorCrop
import com.ilyskyo.wordlens.vision.camera.OverlayGeometry
import com.ilyskyo.wordlens.vision.camera.PhotoDecoder
import com.ilyskyo.wordlens.vision.camera.YuvFrames
import com.ilyskyo.wordlens.vision.detection.DetectedObject
import com.ilyskyo.wordlens.vision.detection.dedupeOverlapping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 取景页的控制器：检测循环、点词片推镜头、快门落库。
 *
 * ## 落库二分（产品骨架，别改）
 *
 * 按快门时只有两种结果：
 * - 选了物品 → 抠贴纸、建词卡（进「记住」），**并且**这条记录仍然生成 Entry（照片与词片
 *   位置一起进「回看」）——贴纸属于卡片，瞬间属于日记。
 * - 没选 → 只存整张照片与全部词片标注进「回看」，不产生任何卡。
 *
 * ## 分析流坐标
 *
 * ImageAnalysis 的帧只覆盖**当前裁切区域**，所以检测器给的归一化框必须经
 * [OverlayGeometry.frameBoxToSensorNorm] 换算回整幅传感器；漏掉这一步的表现是
 * 「聚焦过一次之后，词片全部漂到别处」。
 */
class CaptureViewModel(private val container: AppContainer) : ViewModel() {

    /** 一帧里一个物体的完整结论。chip 是给界面看的，其余字段是保存时要用的。 */
    private data class LiveObject(
        val chip: WordChip,
        val lexiconEntry: LexiconEntry?,
        val score: Float,
    )

    sealed interface Event {
        /** 已落库，取景页可以关闭。savedCard=false 表示走的是「整张照片进回看」。 */
        data class Saved(val entryId: String, val savedCard: Boolean) : Event
        data class Failed(val message: String) : Event

        /**
         * 抠图两条后端都没给出贴纸。词卡与照片**照常入库**，只是没有贴纸——
         * 用户点了词片这个动作不能白给（§4.2），但也不该被一个可选的视觉产物卡住。
         */
        data class StickerFailed(val message: String) : Event
    }

    private val _ui = MutableStateFlow(CaptureUiState())
    val ui: StateFlow<CaptureUiState> = _ui.asStateFlow()

    private val _event = MutableStateFlow<Event?>(null)
    val event: StateFlow<Event?> = _event.asStateFlow()

    private val settingsFlow = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private var controller: CameraFocusController? = null
    private var imageCapture: ImageCapture? = null
    private var sensorWidth = 0
    private var sensorHeight = 0
    private var rotationDegrees = 90
    private var viewAspect = 3f / 4f

    @Volatile
    private var frameInFlight = false

    @Volatile
    private var live: List<LiveObject> = emptyList()

    private var lastScene = SceneGuess(null, 0f)
    private var lastAmbience: List<String> = emptyList()

    /** 快门后 staged 的结果，等用户按「保存」才落库；retake 整块丢弃并清理文件。 */
    private data class Staged(
        val entryId: String,
        val photoFile: String,
        val stickerFile: String?,
        val card: WordCard?,
        val entry: Entry,
    )

    private var staged: Staged? = null

    // ── 相机生命周期 ─────────────────────────────────────────────────────────

    /** 由 [CaptureCamera] 在 bindToLifecycle 成功后调用。 */
    fun onCameraReady(camera: Camera, capture: ImageCapture) {
        val ctrl = CameraFocusController(camera)
        controller = ctrl
        imageCapture = capture
        val (w, h) = ctrl.sensorSize
        if (w > 0 && h > 0) {
            sensorWidth = w
            sensorHeight = h
        } else {
            // 拿不到 active array 就无法换算坐标——宁可没有词片，也不要飘错位置的词片。
            Log.w(TAG, "sensor active array unavailable; chips disabled")
        }
        rotationDegrees = ctrl.sensorRotation
        publishFrame()
    }

    fun onCameraReleased() {
        controller = null
        imageCapture = null
    }

    /** 取景控件尺寸变了要重报：推近的幅度按画面实际宽高比算。 */
    fun updateViewAspect(widthPx: Int, heightPx: Int) {
        if (widthPx > 0 && heightPx > 0) viewAspect = widthPx.toFloat() / heightPx
    }

    // ── 检测循环 ────────────────────────────────────────────────────────────

    /**
     * 分析帧入口。在回调线程上只做平面拷贝与 proxy 关闭，重活扔进 Default。
     *
     * ImageProxy 必须在每一条路径上关死（包括忙不过来的帧），否则 CameraX 会停止出帧——
     * 这是这类「取景页静止不动」问题最常见的来源。
     */
    fun onImageProxy(proxy: ImageProxy) {
        val ready = sensorWidth > 0 && !frameInFlight &&
            !_ui.value.analysing && _ui.value.sticker == null
        if (!ready) {
            proxy.close()
            return
        }
        frameInFlight = true
        try {
            val width = proxy.width
            val height = proxy.height
            val yPlane = proxy.planes[0]
            val uPlane = proxy.planes[1]
            val vPlane = proxy.planes[2]
            val frame = FramePlanes(
                y = yPlane.buffer.toByteArray(),
                yRowStride = yPlane.rowStride,
                yPixelStride = yPlane.pixelStride,
                u = uPlane.buffer.toByteArray(),
                v = vPlane.buffer.toByteArray(),
                uvRowStride = uPlane.rowStride,
                uvPixelStride = uPlane.pixelStride,
                width = width,
                height = height,
                crop = controller?.currentCropSnapshot()
                    ?: SensorCrop(0f, 0f, sensorWidth.toFloat(), sensorHeight.toFloat()),
            )
            proxy.close()
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    processFrame(frame)
                } catch (e: Exception) {
                    Log.w(TAG, "frame dropped", e)
                } finally {
                    frameInFlight = false
                }
            }
        } catch (e: Exception) {
            frameInFlight = false
            proxy.close()
            Log.w(TAG, "frame extraction failed", e)
        }
    }

    private data class FramePlanes(
        val y: ByteArray,
        val yRowStride: Int,
        val yPixelStride: Int,
        val u: ByteArray,
        val v: ByteArray,
        val uvRowStride: Int,
        val uvPixelStride: Int,
        val width: Int,
        val height: Int,
        val crop: SensorCrop,
    )

    private suspend fun processFrame(frame: FramePlanes) {
        val detector = container.detectorOrNull() ?: return
        val pixels = YuvFrames.toPixels(
            frame.y, frame.yRowStride, frame.yPixelStride,
            frame.u, frame.v, frame.uvRowStride, frame.uvPixelStride,
            frame.width, frame.height,
        )
        val bitmap = Bitmap.createBitmap(pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
        try {
            val objects = detector.detect(bitmap).dedupeOverlapping()
            updateFromDetection(objects, pixels, frame)
        } finally {
            bitmap.recycle()
        }
    }

    private fun updateFromDetection(
        objects: List<DetectedObject>,
        pixels: IntArray,
        frame: FramePlanes,
    ) {
        val settings = settingsFlow.value
        val lexicon = container.lexicon.index.value
        val taxonomy = container.vision.taxonomy.value

        // 每个类别只留分最高的一个框——同类两个词片只会互相遮挡。
        val best = objects
            .sortedByDescending { it.score }
            .distinctBy { LexiconIndex.normalize(it.categoryName) }

        val objectsNew = best.map { obj ->
            val normFrame = NormBox(obj.box.left, obj.box.top, obj.box.right, obj.box.bottom)
            val sensorBox = OverlayGeometry.frameBoxToSensorNorm(normFrame, frame.crop, sensorWidth, sensorHeight)
            val match = lexicon.match(listOf(obj.categoryName to obj.score)).firstOrNull()
            LiveObject(
                chip = WordChip(
                    key = LexiconIndex.normalize(obj.categoryName),
                    word = match?.entry?.words?.get(settings.targetLanguage.tag)
                        ?: match?.entry?.headword
                        ?: obj.categoryName,
                    box = sensorBox,
                    known = match != null,
                ),
                lexiconEntry = match?.entry,
                score = obj.score,
            )
        }
        live = objectsNew

        val labelPairs = best.map { it.categoryName to it.score }
        val sceneGuess = SceneClassifier(taxonomy).classify(labelPairs)
        val verdict = ShotClassifier.classify(
            stats = null, // 取景阶段没有分割掩码，判定退化为纯标签路径
            labels = labelPairs,
            scenePrior = sceneGuess.kind != null,
        )
        lastScene = sceneGuess

        val luma = YuvFrames.luma(pixels)
        val ranked = AmbienceScorer.rank(taxonomy.ambience, luma.brightness, luma.warmth, objects.size)
        lastAmbience = ranked.mapNotNull { it.word[settings.nativeLanguage.tag] ?: it.word["en"] }

        publishFrame()
        _ui.update { state ->
            val keys = objectsNew.map { it.chip.key }
            state.copy(
                chips = objectsNew.map { it.chip },
                ambience = lastAmbience,
                shotKind = verdict.kind,
                shotReason = verdict.reason,
                rawLabels = labelPairs.map { it.first },
                // 选中的物体这一帧没再检测到：不清选中（变焦推近后物体本来就会占满画面、
                // 反而可能被裁出检测窗），只有用户主动取消或按快门才结束这次选择。
                selectedChipKey = state.selectedChipKey,
            ).let { if (state.selectedChipKey != null && state.selectedChipKey !in keys) it.copy(selectedChipKey = null) else it }
        }
    }

    private fun publishFrame() {
        val frame = if (sensorWidth > 0 && sensorHeight > 0) {
            CameraFrame(
                sensorWidth = sensorWidth,
                sensorHeight = sensorHeight,
                rotationDegrees = rotationDegrees,
                crop = controller?.currentCropSnapshot()
                    ?: SensorCrop(0f, 0f, sensorWidth.toFloat(), sensorHeight.toFloat()),
            )
        } else null
        _ui.update { it.copy(cameraFrame = frame) }
    }

    // ── 选词片 → 推镜头 ─────────────────────────────────────────────────────

    fun onChipSelect(key: String?) {
        _ui.update { it.copy(selectedChipKey = key) }
        val ctrl = controller ?: return
        viewModelScope.launch {
            if (key == null) {
                ctrl.reset()
            } else {
                val obj = live.firstOrNull { it.chip.key == key } ?: return@launch
                ctrl.focusOn(
                    CameraFocusController.FocusRequest(box = obj.chip.box, explicit = true),
                    viewAspect = viewAspect,
                )
            }
            // 动画结束后裁切区变了；覆盖层要按新裁切重算词片位置。
            publishFrame()
        }
    }

    fun onTapSubject() {
        // 检测器不可用时的兜底入口：等价于取消选择，让快门走「整张照片进回看」。
        onChipSelect(null)
    }

    // ── 快门 ────────────────────────────────────────────────────────────────

    fun onShutter() {
        val capture = imageCapture ?: return
        if (_ui.value.analysing) return
        val selected = _ui.value.selectedChipKey?.let { key -> live.firstOrNull { it.chip.key == key } }
        val entryId = Entry.newId()
        val photoFile = File(container.entryPhotoDir, "$entryId.jpg")

        _ui.update { it.copy(analysing = true) }
        viewModelScope.launch {
            try {
                capture.savePhoto(photoFile)
                if (selected == null) {
                    // 场景路径：判帧、建 Entry，直接落库并关闭。
                    val entry = buildEntry(entryId, photoFile.name, selected = null, stickerFile = null)
                    container.diary.addEntry(entry)
                    _ui.update { it.copy(analysing = false) }
                    _event.value = Event.Saved(entryId, savedCard = false)
                } else {
                    stageObjectShot(entryId, photoFile, selected)
                }
            } catch (e: Exception) {
                Log.e(TAG, "shutter failed", e)
                _ui.update { it.copy(analysing = false) }
                _event.value = Event.Failed(e.message ?: "capture failed")
            }
        }
    }

    /** 抠图阶段的一次性产物：给 view 显示的贴纸位图 + 给落盘的 PNG 字节。 */
    private data class CutResult(val sticker: Bitmap?, val png: ByteArray?) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private suspend fun stageObjectShot(entryId: String, photoFile: File, selected: LiveObject) {
        val cut: CutResult = withContext(Dispatchers.Default) {
            val upright = decodeUpright(photoFile)
            if (upright == null) {
                CutResult(null, null)
            } else {
                val bitmap = upright.bitmap
                // 词片框归一化在传感器坐标系里；先换算到这张**转正后的显示图**，再取中心做点选。
                val imageBox = CameraFocusMath.imageBoxFromSensorNorm(selected.chip.box, upright.rotationDegrees)
                val sticker = segmentSticker(bitmap, imageBox)
                bitmap.recycle()
                CutResult(sticker, sticker?.let { encodePng(it) })
            }
        }
        if (cut.sticker == null) {
            _event.value = Event.StickerFailed(container.appContext.getString(R.string.capture_sticker_failed))
        }

        val stickerName = if (cut.png != null) "st-$entryId.png" else null
        if (stickerName != null && cut.png != null) {
            val png = cut.png
            runCatching {
                // 同一张贴纸两处各存一份：WordCard 以 stickers/ 为根、EntryObject 以 entries/
                // 为根——它们的删除语义不同，卡片要能活得比单条日记久。PNG 小，副本可接受。
                FileOutputStream(File(container.stickerDir, stickerName)).use { out -> out.write(png) }
                FileOutputStream(File(container.entryPhotoDir, stickerName)).use { out -> out.write(png) }
            }
        }

        val settings = settingsFlow.value
        val card = selected.lexiconEntry?.let { entry ->
            container.cardFrom(entry, settings.targetLanguage, settings.nativeLanguage, EntrySource.ON_DEVICE)
                ?.copy(
                    id = WordCard.newId(),
                    origin = CardOrigin.STICKER,
                    stickerPath = stickerName,
                    originalPhotoPath = photoFile.name,
                    sceneId = lastScene.kind?.id,
                    sceneLabel = lastScene.kind?.label?.get(settings.nativeLanguage.tag)
                        ?: lastScene.kind?.label?.values?.firstOrNull(),
                    sceneEmoji = lastScene.kind?.emoji,
                )
        }

        val entry = buildEntry(entryId, photoFile.name, selected, stickerName)
        staged = Staged(entryId, photoFile.name, stickerName, card, entry)

        _ui.update {
            it.copy(
                analysing = false,
                sticker = cut.sticker,
                headword = card?.headword ?: selected.chip.word,
                ipa = card?.ipa,
                gloss = card?.gloss(settings.nativeLanguage),
            )
        }
    }

    /**
     * 拿一张**已经裁好、背景透明**的贴纸。坐标系由后端负责，这里不换算——
     * 之前正是这一层拿原图像素尺度的矩形去裁工作尺度的位图，越界异常被吞掉后贴纸永远是空的。
     *
     * 选后端的顺序按「谁能给出 cutout」，不是按「谁是 automatic」：`automatic ?: manual`
     * 在装了 Play 服务的机器上正好选中那个当时不给 cutout 的实现，而本来能出图的那条路被跳过。
     */
    private suspend fun segmentSticker(bitmap: Bitmap, imageBox: NormBox): Bitmap? {
        val ordered = container.vision.segmenters.value.sortedBy { if (it.automatic) 0 else 1 }
        if (ordered.isEmpty()) {
            Log.w(TAG, "no segmentation backend available on this device")
            return null
        }
        val tap = imageBox.centerX to imageBox.centerY
        for (backend in ordered) {
            val result = runCatching { backend.segment(bitmap, if (backend.automatic) null else tap) }
                .onFailure { Log.w(TAG, "segmenter ${backend.displayName} threw", it) }
                .getOrNull()
            result?.cutout?.let { return it }
            Log.w(TAG, "segmenter ${backend.displayName} produced no cutout")
        }
        return null
    }

    /** 只编码不缩放：白描边由覆盖层与时间轴画，不在这里烧进图里。 */
    private fun encodePng(bitmap: Bitmap): ByteArray? = runCatching {
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }.onFailure { Log.w(TAG, "PNG encode failed for sticker", it) }.getOrNull()

    private fun buildEntry(
        entryId: String,
        photoFile: String,
        selected: LiveObject?,
        stickerFile: String?,
    ): Entry {
        val objects = live.map { obj ->
            EntryObject(
                id = obj.chip.key,
                word = obj.chip.word,
                lexiconEntryId = obj.lexiconEntry?.id,
                score = obj.score,
                left = obj.chip.box.left,
                top = obj.chip.box.top,
                right = obj.chip.box.right,
                bottom = obj.chip.box.bottom,
                stickerPath = if (selected != null && obj.chip.key == selected.chip.key) stickerFile else null,
                layer = OverlayLayer.ITEM,
            )
        }
        return Entry(
            id = entryId,
            photoPath = photoFile,
            takenAt = System.currentTimeMillis(),
            kind = lastScene.kind?.id,
            kindLabel = lastScene.kind?.label ?: emptyMap(),
            ambience = lastAmbience,
            objects = objects,
            // 「没选物品」时全部词片都算被婉拒——留着才能回答「当时它看到了什么」。
            declinedWords = if (selected == null) live.map { it.chip.word } else emptyList(),
            detectedBy = EntrySource.ON_DEVICE,
        )
    }

    /** 结果页「保存」：卡片进 deck，Entry 进 diary，然后关闭取景页。 */
    fun onSave() {
        val s = staged ?: return
        viewModelScope.launch {
            s.card?.let { container.deck.add(it) }
            container.diary.addEntry(s.entry)
            staged = null
            _event.value = Event.Saved(s.entryId, savedCard = s.card != null)
        }
    }

    fun onRetake() {
        staged?.let { s ->
            // 放弃这一步：把已经写盘的照片与贴纸删掉，不留孤儿文件。
            runCatching { File(container.entryPhotoDir, s.photoFile).delete() }
            s.stickerFile?.let {
                runCatching { File(container.stickerDir, it).delete() }
                runCatching { File(container.entryPhotoDir, it).delete() }
            }
        }
        staged = null
        _ui.update { it.copy(analysing = false, sticker = null, headword = null, ipa = null, gloss = null) }
    }

    fun onSpeak() {
        val word = _ui.value.headword ?: return
        container.speaker.speak(word, settingsFlow.value.targetLanguage)
    }

    fun acknowledgeEvent() {
        _event.value = null
    }

    // ── 照片解码 ────────────────────────────────────────────────────────────

    private data class UprightPhoto(val bitmap: Bitmap, val rotationDegrees: Int)

    /**
     * 解码并按 EXIF 转正，交给 [PhotoDecoder]（时间轴与详情页用的是同一个解码器，照片才不会一处正一处歪）。
     *
     * 坐标映射用的是**传感器旋转角**而不是 EXIF 角：词片的框归一化在传感器坐标系里，转正后的位图
     * 就是「显示图」，`imageBoxFromSensorNorm` 要换算的正是这个角度。两者只有一种情况会不一致——
     * 分析帧之后、快门之前把手机转了向，那时 EXIF 才是真相。真机上要盯的就是这一条。
     */
    private fun decodeUpright(file: File): UprightPhoto? =
        PhotoDecoder.decodeUpright(file, MAX_PHOTO_PX)?.let { UprightPhoto(it.bitmap, rotationDegrees) }

    private suspend fun ImageCapture.savePhoto(file: File) =
        suspendCancellableCoroutine { cont ->
            val options = ImageCapture.OutputFileOptions.Builder(file).build()
            takePicture(
                options,
                ContextCompat.getMainExecutor(container.appContext),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        cont.resume(Unit)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        cont.resumeWithException(exception)
                    }
                },
            )
        }

    private companion object {
        const val TAG = "CaptureVM"

        /**
         * 抠图用的最大边长。
         *
         * 这个数是跟着「贴纸是从这张图里**裁出来**的一块」定的，不是跟着屏幕定的：
         * 一个物体在 2560 宽的源图里占 30% 就是 ~768px，在 1280 的源图里只有 ~384px，
         * 而界面上一颗 240dp 的贴纸框在 3x 屏上正是 720px——384 就得放大近一倍，
         * 边缘会明显糊掉。die-cut 贴纸是这个产品的招牌，不值得为省一次解码牺牲它。
         *
         * 它同时是一道**上限**而不是「解全图」：两个分割后端各自还要往 1024/512 工作尺寸降采样，
         * 所以再高就只是多占内存。4032×3024 的传感器输出在这里会被正确降到 2560，
         * 而修掉两步降采样之前它压根不会降（`inSampleSize` 卡在 1，一张 48MB 的位图）。
         */
        const val MAX_PHOTO_PX = 2560
    }
}

private fun ByteBuffer.toByteArray(): ByteArray {
    val copy = ByteArray(remaining())
    mark()
    get(copy)
    reset()
    return copy
}

fun captureViewModelFactory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
    initializer { CaptureViewModel(container) }
}
