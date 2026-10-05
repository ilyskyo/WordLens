// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.capture

import android.graphics.Bitmap
import android.net.Uri
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
import com.ilyskyo.wordlens.data.model.Entry
import com.ilyskyo.wordlens.data.model.EntrySource
import com.ilyskyo.wordlens.data.model.LexiconEntry
import com.ilyskyo.wordlens.data.model.LexiconIndex
import com.ilyskyo.wordlens.data.model.SceneGuess
import com.ilyskyo.wordlens.data.repository.AppSettings
import com.ilyskyo.wordlens.vision.AmbienceScorer
import com.ilyskyo.wordlens.vision.PhotoEntryPipeline
import com.ilyskyo.wordlens.vision.SceneClassifier
import com.ilyskyo.wordlens.vision.ShotClassifier
import com.ilyskyo.wordlens.vision.camera.CameraFocusController
import com.ilyskyo.wordlens.vision.camera.CameraFocusMath.NormBox
import com.ilyskyo.wordlens.vision.camera.CameraFocusMath.SensorCrop
import com.ilyskyo.wordlens.vision.camera.GalleryPhoto
import com.ilyskyo.wordlens.vision.camera.OverlayGeometry
import com.ilyskyo.wordlens.vision.camera.PhotoDecoder
import com.ilyskyo.wordlens.vision.camera.PhotoTiming
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
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 取景页的控制器：检测循环、点词片推镜头、快门落库、相册导入。
 *
 * ## 落库二分（产品骨架，别改）
 *
 * 按快门时只有两种结果：
 * - 选了物品 → 抠贴纸、建词卡（进「记住」），**并且**这条记录仍然生成 Entry（照片与词片
 *   位置一起进「回看」）——贴纸属于卡片，瞬间属于日记。
 * - 没选 → 只存整张照片与全部词片标注进「回看」，不产生任何卡。
 *
 * 相册导入是同一个二分的第三种来源：没有点选这一步，于是由置信度替用户选那一个物品
 * （见 [PhotoEntryPipeline.Cutout.Best]），其余一切走同一条流水线。
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
        /**
         * 已落库，取景页可以关闭。savedCard=false 表示走的是「整张照片进回看」。
         *
         * [notice] 是**随保存一起走**的一句话：照片确实存下了，但这次有东西没成
         * （检测器不在、照片里没认出东西）。它必须在关闭之后由主页那层的提示通道说出来——
         * 取景页自己的 NoticeHost 随场景一起拆掉，在那里开口等于什么都没讲。
         */
        data class Saved(
            val entryId: String,
            val savedCard: Boolean,
            /**
             * 这条记录的日期键，来自 `Entry.dayKey`——不要在这里再算一遍，两处算法不一样
             * 就会出现「筛得到、却分不进任何一组」。主页需要它来决定要不要放开日历筛选：
             * 筛着上周三时存进一张今天的照片，新记录不在结果里，界面上就是「存了，但什么都没发生」。
             */
            val dayKey: String,
            val notice: String? = null,
        ) : Event
        data class Failed(val message: String) : Event

        /**
         * 抠图两条后端都没给出贴纸。词卡与照片**照常入库**，只是没有贴纸——
         * 用户点了词片这个动作不能白给（§4.2），但也不该被一个可选的视觉产物卡住。
         */
        data class StickerFailed(val message: String) : Event

        /**
         * 一句会自己消失的说明。发音没出声、手写收录成功或失败，都走它：
         * 这些都是「刚那一下的结果」，不值得为每种结果设计一种控件。
         */
        data class Notice(val message: String) : Event
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

    /**
     * 快门后 staged 的流水线产物，等用户按「保存」才入库；retake 整块丢弃并清理文件。
     *
     * 直接存 [PhotoEntryPipeline.Output] 而不是把 entry/card/文件名再拆出来复述一遍：
     * 复述一次就多一处会和流水线对不上的地方，而「重拍之后磁盘上少一个文件」这种差异
     * 只有翻文件才看得见。
     */
    private var staged: PhotoEntryPipeline.Output? = null

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

    // ── 快门与相册导入：两个入口，一条流水线 ─────────────────────────────────

    /**
     * 把取景页实时检测的那批物体交给流水线；抠哪一个由 [cutout] 决定。
     *
     * `rotationDegrees` 传的是**传感器旋转角**而不是照片的 EXIF 角：词片框归一化在传感器
     * 坐标系里，转正后的位图就是「显示图」，要换算的正是这个角度。两者只有一种情况会
     * 不一致——分析帧之后、快门之前把手机转了向，那时 EXIF 才是真相。真机上要盯的就是这一条。
     */
    private fun shutterRequest(
        entryId: String,
        photoFile: File,
        cutout: PhotoEntryPipeline.Cutout,
    ): PhotoEntryPipeline.Request {
        val settings = settingsFlow.value
        return PhotoEntryPipeline.Request(
            entryId = entryId,
            photoFile = photoFile,
            takenAt = System.currentTimeMillis(),
            rotationDegrees = rotationDegrees,
            targetLanguage = settings.targetLanguage,
            nativeLanguage = settings.nativeLanguage,
            analysis = PhotoEntryPipeline.Analysis.Live(
                subjects = live.map { it.toSubject() },
                scene = lastScene,
                ambience = lastAmbience,
            ),
            cutout = cutout,
            detectedBy = EntrySource.ON_DEVICE,
        )
    }

    /** 取景页的词片就是流水线眼里的一个物体：同一份数据，两种用途。 */
    private fun LiveObject.toSubject() = PhotoEntryPipeline.Subject(
        id = chip.key,
        word = chip.word,
        entry = lexiconEntry,
        score = score,
        box = chip.box,
    )

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
                val cutout = if (selected == null) {
                    PhotoEntryPipeline.Cutout.None
                } else {
                    PhotoEntryPipeline.Cutout.Keyed(selected.chip.key)
                }
                val output = container.photoPipeline.run(shutterRequest(entryId, photoFile, cutout))
                if (selected == null) {
                    // 场景路径：判帧结果已经在 Live 里，流水线不解码，直接落库并关闭。
                    container.photoPipeline.commit(output)
                    _ui.update { it.copy(analysing = false) }
                    _event.value = Event.Saved(entryId, savedCard = false, dayKey = output.entry.dayKey)
                } else {
                    stageObjectShot(output, selected)
                }
            } catch (e: Exception) {
                Log.e(TAG, "shutter failed", e)
                _ui.update { it.copy(analysing = false) }
                _event.value = Event.Failed(e.message ?: "capture failed")
            }
        }
    }

    private fun stageObjectShot(output: PhotoEntryPipeline.Output, selected: LiveObject) {
        if (PhotoEntryPipeline.Failure.StickerUnavailable in output.failures) {
            _event.value = Event.StickerFailed(textOf(R.string.capture_sticker_failed))
        }
        staged = output
        val card = output.card
        _ui.update {
            it.copy(
                analysing = false,
                sticker = output.sticker,
                headword = card?.headword ?: selected.chip.word,
                ipa = card?.ipa,
                gloss = card?.gloss(settingsFlow.value.nativeLanguage),
            )
        }
    }

    /**
     * 相册导入：把选中的那张照片走**完整**流水线，与拍照同一条路。
     *
     * ## 为什么先拷文件而不是直接读 URI
     *
     * 见 [GalleryPhoto]：一次性授权上的文件撑不起一本要翻三年的日记。
     *
     * ## 为什么这里重新跑一次检测
     *
     * 取景器那套实时检测对这张照片一无所知——它看的是镜头前面的场景，不是相册里的文件。
     * 所以由流水线在转正后的位图上现检（[PhotoEntryPipeline.Analysis.Photo]），
     * 检测、判场景、算氛围、抠贴纸用的是同一张位图，全程只解一次。
     */
    /**
     * 拷完文件、进流水线之前先取好的三样。
     *
     * 三个都要读磁盘（EXIF 头、方向角），所以一次放进 IO 派发器里取完：
     * 分散到流水线前后各读一次，等于把同一段 JPEG 头解析两遍，还各自占一次主线程序列。
     */
    private data class Imported(val file: File, val takenAt: Long, val exifDegrees: Int)

    fun onImportPhoto(uri: Uri) {
        if (_ui.value.analysing) return
        val entryId = Entry.newId()
        val photoFile = File(container.entryPhotoDir, "$entryId.jpg")
        _ui.update { it.copy(analysing = true, importing = true) }
        viewModelScope.launch {
            try {
                val settings = settingsFlow.value
                val prepared = withContext(Dispatchers.IO) {
                    val picked = GalleryPhoto.import(container.appContext.contentResolver, uri, photoFile)
                        ?: return@withContext null
                    Imported(
                        file = picked.file,
                        // 拍摄时刻来自照片本身，不是「现在」：见 PhotoTiming。
                        takenAt = PhotoTiming.takenAtOf(
                            exifDateTimeOriginal = GalleryPhoto.dateTimeOriginal(picked.file),
                            lastModifiedMs = picked.lastModifiedMs,
                            nowMs = System.currentTimeMillis(),
                        ),
                        // 这张照片没有传感器，转正角度就是它自己的 EXIF 角；流水线解码用的、
                        // 详情页长回词用的都是同一个取值，所以 sensor 与显示图之间的往返是精确的。
                        exifDegrees = PhotoDecoder.exifDegrees(picked.file),
                    )
                }
                if (prepared == null) {
                    // 连文件都没拿到：什么都不存，但必须说——静默的没有反应最容易被当成按钮坏了。
                    _event.value = Event.Notice(textOf(R.string.import_unreadable))
                    return@launch
                }
                val output = container.photoPipeline.run(
                    PhotoEntryPipeline.Request(
                        entryId = entryId,
                        photoFile = prepared.file,
                        takenAt = prepared.takenAt,
                        rotationDegrees = prepared.exifDegrees,
                        targetLanguage = settings.targetLanguage,
                        nativeLanguage = settings.nativeLanguage,
                        analysis = PhotoEntryPipeline.Analysis.Photo,
                        cutout = PhotoEntryPipeline.Cutout.Best,
                        detectedBy = EntrySource.ON_DEVICE,
                    ),
                )
                if (PhotoEntryPipeline.Failure.PhotoUnreadable in output.failures) {
                    // 解不出位图 = 认不出词、裁不出贴纸，而时间轴与详情页同样解不出这张文件。
                    // 存下一条只有坏照片的记录比不存更糟：删掉拷贝，把话说清楚。
                    container.photoPipeline.discard(output)
                    _event.value = Event.Notice(textOf(R.string.import_unreadable))
                    return@launch
                }
                container.photoPipeline.commit(output)
                _event.value = Event.Saved(
                    entryId,
                    savedCard = output.card != null,
                    dayKey = output.entry.dayKey,
                    notice = importNotice(output),
                )
            } catch (e: Exception) {
                Log.e(TAG, "gallery import failed", e)
                // 半途而废的文件不能留下：一条没人引用的照片比一次失败的导入更难发现。
                runCatching { photoFile.delete() }
                _event.value = Event.Failed(textOf(R.string.import_unreadable))
            } finally {
                _ui.update { it.copy(analysing = false, importing = false) }
            }
        }
    }

    /**
     * 导入的降级说明。这些都不是「失败」——照片**已经存进日记了**，只是这次少了一部分。
     *
     * 顺序有意义：检测器不在的时候物体必然是空的，先说原因（它现在给不出检测）比说现象
     * （这张照片里没认出东西）有用得多，后者听起来像是照片的问题。
     */
    private fun importNotice(output: PhotoEntryPipeline.Output): String? = when {
        PhotoEntryPipeline.Failure.DetectorUnavailable in output.failures ->
            textOf(R.string.import_no_detection)

        output.entry.objects.isEmpty() -> textOf(R.string.import_no_objects)

        // 贴纸没抠成但词卡照常入库，这句取景页已经在用：同一种取舍，同一句话。
        output.card != null && PhotoEntryPipeline.Failure.StickerUnavailable in output.failures ->
            textOf(R.string.capture_sticker_failed)

        else -> null
    }

    private fun textOf(resId: Int): String = container.appContext.getString(resId)

    /** 结果页「保存」：卡片进 deck，Entry 进 diary，然后关闭取景页。 */
    fun onSave() {
        val s = staged ?: return
        viewModelScope.launch {
            container.photoPipeline.commit(s)
            staged = null
            _event.value = Event.Saved(s.entry.id, savedCard = s.card != null, dayKey = s.entry.dayKey)
        }
    }

    fun onRetake() {
        staged?.let { container.photoPipeline.discard(it) }
        staged = null
        _ui.update { it.copy(analysing = false, sticker = null, headword = null, ipa = null, gloss = null) }
    }

    fun onSpeak() {
        val word = _ui.value.headword ?: return
        val lang = settingsFlow.value.targetLanguage
        if (container.speaker.speak(word, lang)) return
        val missing = lang in container.speaker.unsupportedLanguages.value
        val resId = if (missing) R.string.notice_tts_unsupported else R.string.notice_tts_silent
        _event.value = Event.Notice(container.appContext.getString(resId, lang.nativeName))
    }

    /**
     * 「认不出来时手写」：兑现 nomatch_body 里那句「你可以直接把它写下来，它会存进你的词典」。
     *
     * 那个面板此前只有文字承诺、没有任何输入控件——承诺了没做比没承诺更糟，
     * 因为它教会用户「这里的文案不作数」。
     *
     * 只收词典里查得到的词。查不到就明说，而不是硬造一张没有释义的卡：一张空释义的卡
     * 进了复习队列就是纯噪音，而 FSRS 会非常认真地把这种噪音排到未来。
     */
    fun onManualAdd() {
        val typed = _ui.value.manualWord.trim()
        if (typed.isEmpty()) return
        viewModelScope.launch {
            val settings = settingsFlow.value
            val exact = container.lexicon.search(typed, settings.targetLanguage).firstOrNull {
                it.headword.equals(typed, ignoreCase = true)
            }
            val card = exact?.let {
                container.cardFrom(it, settings.targetLanguage, settings.nativeLanguage, EntrySource.MANUAL)
            }
            if (card == null) {
                _event.value = Event.Notice(
                    container.appContext.getString(R.string.manual_not_found, typed),
                )
                return@launch
            }
            container.deck.add(card)
            _ui.update { it.copy(manualWord = "") }
            _event.value = Event.Notice(
                container.appContext.getString(R.string.manual_added, card.headword),
            )
        }
    }

    fun onManualWordChange(text: String) {
        _ui.update { it.copy(manualWord = text) }
    }

    fun acknowledgeEvent() {
        _event.value = null
    }

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
        // 解码尺寸（MAX_PHOTO_PX）跟着流水线走，不在这里重复一个数：
        // 两处同一个常量的下场是「改了一处、另一处的贴纸质量悄悄变了」。
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
