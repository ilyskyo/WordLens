// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision

import android.graphics.Bitmap
import android.util.Log
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
import com.ilyskyo.wordlens.data.repository.DeckRepository
import com.ilyskyo.wordlens.data.repository.DiaryRepository
import com.ilyskyo.wordlens.data.repository.LexiconRepository
import com.ilyskyo.wordlens.vision.camera.CameraFocusMath
import com.ilyskyo.wordlens.vision.camera.CameraFocusMath.NormBox
import com.ilyskyo.wordlens.vision.camera.DecodeSizing
import com.ilyskyo.wordlens.vision.camera.PhotoDecoder
import com.ilyskyo.wordlens.vision.camera.YuvFrames
import com.ilyskyo.wordlens.vision.detection.DetectedObject
import com.ilyskyo.wordlens.vision.detection.EfficientDetector
import com.ilyskyo.wordlens.vision.detection.dedupeOverlapping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * 「一张照片 → 一条日记条目」的唯一一条流水线。
 *
 * ## 为什么必须只有一个实现
 *
 * 快门落库和相册导入做的是同一件事：把一张已经在 `filesDir/entries` 里的照片，配上物体词、
 * 贴纸、场景与氛围词，写成一条 Entry（顺带一张词卡）。两处各写一遍的话，任何一处改动
 * （贴纸文件名规则、EntryObject 用的是哪个坐标系、卡片上那几个场景字段）都会让另一处
 * 悄悄落后，而表现是「导入的那条记录在时间轴上少了一个角」——那种差异没人能靠肉眼看出来，
 * 只会当成随机故障。所以这里只有一个 `run`，两个入口都走它。
 *
 * ## 唯一的差别写成了类型
 *
 * [Analysis]：取景页已经**实时**检测好了（`Analysis.Live`），相册里那张只能现检
 * （`Analysis.Photo`）。之所以不让导入也套 `Live`：现检需要那张转正后的位图，而抠贴纸
 * 用的正是同一张——两次解码就是两份 2560 长边的位图同时在堆上，这是这个项目里唯一真正
 * 会 OOM 的地方。
 *
 * ## 解码是按需的，不是每次都做
 *
 * `Live + Cutout.None`（按快门但没点词片）今天也不解码，直接写 Entry。保持原样：
 * 一次 2560 解码是 20MB 量级的峰值，为「什么都不用裁」的照片白付。
 *
 * ## 坐标系只在这里换算一次
 *
 * 存进 [EntryObject] 的框一律归一化在**原始像素网格**（=相机传感器、=EXIF 转正之前）上，
 * 因为详情页把词长回照片时走的是
 * [CameraFocusMath.imageBoxFromSensorNorm]（拿照片自己的 EXIF 角）。谁改成存显示图坐标，
 * 竖持拍的那批记录就会全体旋转 90°——而这张照片看起来仍然「有词」。
 */
class PhotoEntryPipeline(
    private val entryPhotoDir: File,
    private val stickerDir: File,
    private val vision: VisionRepository,
    private val lexicon: LexiconRepository,
    private val diary: DiaryRepository,
    private val deck: DeckRepository,
    private val detector: () -> EfficientDetector?,
) {

    /**
     * 一个物体的完整结论。
     *
     * [box] 必须在原始像素网格坐标系里（见类注释）；取景页的词片本来就是传感器坐标，
     * 导入的那批由 [analysePhoto] 换算好再进来。
     */
    data class Subject(
        /** 稳定 key：归一化后的类别名，与取景页 `WordChip.key` 同一条规则。 */
        val id: String,
        val word: String,
        /** 词典命中；null 表示「看到了但词典里没有」。 */
        val entry: LexiconEntry?,
        val score: Float,
        val box: NormBox,
    )

    /** 物体、场景、氛围词从哪来。 */
    sealed interface Analysis {
        /** 取景页实时算好的结果，原样使用。 */
        data class Live(
            val subjects: List<Subject>,
            val scene: SceneGuess,
            val ambience: List<String>,
        ) : Analysis

        /** 相册导入：在这张照片的转正位图上跑完整检测。 */
        object Photo : Analysis
    }

    /** 要不要抠贴纸、抠哪一个。 */
    sealed interface Cutout {
        /** 不抠：整张照片进「回看」，全部词记进 [Entry.declinedWords]。 */
        object None : Cutout

        /** 按 key 抠（取景页：用户点中的那颗词片）。 */
        data class Keyed(val id: String) : Cutout

        /** 抠分最高的那个（相册导入：没有点选这一步，只能由置信度替用户决定）。 */
        object Best : Cutout
    }

    data class Request(
        val entryId: String,
        /** 必须已经躺在 [entryPhotoDir] 里：Entry 只存相对文件名，删除才能按同一套约定级联。 */
        val photoFile: File,
        val takenAt: Long,
        /** 把归一化框换算到**转正后的显示图**所依据的角度。 */
        val rotationDegrees: Int,
        val targetLanguage: Lang,
        val nativeLanguage: Lang,
        val analysis: Analysis,
        val cutout: Cutout,
        val detectedBy: EntrySource,
    )

    /** 这次跑下来哪里没成。照片**照样**存得下来，所以它们是「说明」而不是异常。 */
    enum class Failure {
        /** 检测器现在给不出（冷加载失败或在 RetryGate 冷却窗口里）。 */
        DetectorUnavailable,

        /** 两个分割后端都没交出贴纸。 */
        StickerUnavailable,

        /** 这张照片根本解不出来：没位图就既不能检测也不能裁。 */
        PhotoUnreadable,
    }

    data class Output(
        val entry: Entry,
        val card: WordCard?,
        /** 贴纸位图，只给取景页的即时预览用。 */
        val sticker: Bitmap?,
        /** 已写盘的贴纸文件名；null 表示这次没有贴纸。 */
        val stickerName: String?,
        val photoFile: File,
        val failures: Set<Failure>,
    )

    /** 一次跑完的结果：文件已落盘，数据已备好，**还没入库**。 */
    suspend fun run(request: Request): Output = withContext(Dispatchers.Default) {
        val failures = mutableSetOf<Failure>()
        val needsDecode = request.cutout != Cutout.None || request.analysis is Analysis.Photo
        val upright = if (needsDecode) PhotoDecoder.decodeUpright(request.photoFile, MAX_PHOTO_PX) else null
        if (needsDecode && upright == null) failures += Failure.PhotoUnreadable

        val resolved = when (val source = request.analysis) {
            is Analysis.Live -> Resolved(source.subjects, source.scene, source.ambience)
            is Analysis.Photo -> analysePhoto(upright, request, failures)
        }
        val target = pickTarget(request.cutout, resolved.subjects)

        var sticker: Bitmap? = null
        var stickerName: String? = null
        if (target != null) {
            if (upright == null) {
                failures += Failure.StickerUnavailable
            } else {
                // 传感器框 → 显示图框：分割后端吃的是转正后的那张位图，坐标系也是它的。
                val imageBox = CameraFocusMath.imageBoxFromSensorNorm(target.box, request.rotationDegrees)
                sticker = cutoutOf(upright.bitmap, imageBox)
                if (sticker == null) {
                    failures += Failure.StickerUnavailable
                } else {
                    // 先编码、编码成功才认下这个文件名：编码失败时贴纸还在（预览照给），
                    // 只是没有文件可引用——今天的行为就是这样，两处保持一致。
                    //
                    // 也**不 recycle 这张解出来的位图**：贴纸是从它上面裁的，而
                    // `Bitmap.createBitmap(source, 整幅, …)` 在 Android 里会把 source 原样返回，
                    // 于是「源图」和「交给渲染线程的贴纸」可能是同一个对象。
                    // 项目里的硬约束是位图一律不显式释放（见 ui/common/ByteLruCache.kt 的文件注释）。
                    val png = encodePng(sticker)
                    if (png != null) {
                        stickerName = stickerFileName(request.entryId)
                        writeSticker(stickerName, png)
                    }
                }
            }
        }

        val card = target?.entry
            ?.toCard(request.targetLanguage, request.nativeLanguage, request.detectedBy)
            ?.copy(
                origin = CardOrigin.STICKER,
                stickerPath = stickerName,
                originalPhotoPath = request.photoFile.name,
                sceneId = resolved.scene.kind?.id,
                // 只取当前语言那一个，取不到就留 null。
                //
                // 原来这里跟着一个 `?: label.values.firstOrNull()`——那是「借任意一个语言」，
                // 而 `label` 是个 Map，顺序由词典里谁先出现决定。这个字段是**反规范化落进
                // deck.json** 的（`WordCard.sceneLabel` 的注释自己写着「让牌组墙不必加载每个
                // 场景」），所以借来的那一句会一直留在磁盘上：用户后来把系统语言或母语换掉，
                // 卡片上还印着别的语言的场景名，改设置不会回头修它。
                // 这和刚修掉的 `LexiconEntry.toCard`（把词头自己写成母语释义）是同一个形状——
                // **为了显示不难看而写的兜底，一旦被拿去写盘，就从「一次妥协」变成「永久谎言」**。
                // 留 null 才是可恢复的：界面随时可以按 `sceneId` 现查一次。
                sceneLabel = resolved.scene.kind?.label?.get(request.nativeLanguage.tag),
                sceneEmoji = resolved.scene.kind?.emoji,
            )

        val objects = resolved.subjects.map { subject ->
            EntryObject(
                id = subject.id,
                word = subject.word,
                lexiconEntryId = subject.entry?.id,
                score = subject.score,
                left = subject.box.left,
                top = subject.box.top,
                right = subject.box.right,
                bottom = subject.box.bottom,
                stickerPath = if (target != null && subject.id == target.id) stickerName else null,
                layer = OverlayLayer.ITEM,
            )
        }

        val entry = Entry(
            id = request.entryId,
            photoPath = request.photoFile.name,
            takenAt = request.takenAt,
            kind = resolved.scene.kind?.id,
            kindLabel = resolved.scene.kind?.label ?: emptyMap(),
            ambience = resolved.ambience,
            objects = objects,
            // 「没抠任何东西」时全部词都算被婉拒——留着才能回答「当时它看到了什么」。
            declinedWords = if (request.cutout == Cutout.None) resolved.subjects.map { it.word } else emptyList(),
            detectedBy = request.detectedBy,
        )

        Output(
            entry = entry,
            card = card,
            sticker = sticker,
            stickerName = stickerName,
            photoFile = request.photoFile,
            failures = failures.toSet(),
        )
    }

    /**
     * 入库：卡片进牌组、条目进日记，顺序与取景页一直以来的一致（先卡后条目）。
     *
     * 拆成单独一步而不是并进 [run]，是因为快门那条路在「按下快门」和「按下保存」之间
     * 还有一次反悔的机会（[discard]）；导入没有这一步，直接 run 完就 commit。
     */
    suspend fun commit(output: Output) {
        output.card?.let { deck.add(it) }
        diary.addEntry(output.entry)
    }

    /** 反悔了：删掉这次写盘的照片与贴纸，不留孤儿文件。 */
    fun discard(output: Output) {
        output.stickerName?.let { name ->
            runCatching { File(stickerDir, name).delete() }
            runCatching { File(entryPhotoDir, name).delete() }
        }
        runCatching { output.photoFile.delete() }
    }

    /** 贴纸文件名。拍照与导入共用这一个格式，删除级联才只需要认一种后缀。 */
    private fun stickerFileName(entryId: String): String = "st-$entryId.png"

    /** 只编码不缩放：白描边由覆盖层与时间轴画，不在这里烧进图里。 */
    private fun encodePng(bitmap: Bitmap): ByteArray? = runCatching {
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }.onFailure { Log.w(TAG, "PNG encode failed for sticker", it) }.getOrNull()

    // ── 内部 ────────────────────────────────────────────────────────────────

    /** 一轮分析读出来的东西：[run] 的中间结果，不对外暴露。 */
    private data class Resolved(
        val subjects: List<Subject>,
        val scene: SceneGuess,
        val ambience: List<String>,
    )

    private suspend fun analysePhoto(
        upright: PhotoDecoder.UprightImage?,
        request: Request,
        failures: MutableSet<Failure>,
    ): Resolved {
        val bitmap = upright?.bitmap ?: return Resolved(emptyList(), EMPTY_SCENE, emptyList())
        val taxonomy = vision.taxonomy.value
        val engine = detector()
        if (engine == null) failures += Failure.DetectorUnavailable
        val detected = engine?.detect(bitmap).orEmpty()
            .dedupeOverlapping()
            // 每个类别只留分最高的一个框——与取景页 `updateFromDetection` 同一条规则。
            // 两处不一致的话，导入的记录会比拍照的多出几个重复词。
            .sortedByDescending { it.score }
            .distinctBy { LexiconIndex.normalize(it.categoryName) }

        val labels = detected.map { it.categoryName to it.score }
        val scene = SceneClassifier(taxonomy).classify(labels)
        val luma = lumaOf(bitmap)
        // 氛围词只依赖亮度与冷暖，不依赖检测器：检测器不在的时候它也照样成立，
        // 所以导入的照片不会退化成「什么都没有」。
        val ambience = AmbienceScorer
            .rank(taxonomy.ambience, luma.brightness, luma.warmth, detected.size)
            // 只取母语那一个，取不到就**不要这一个词**——不写英文顶上。
            //
            // `?: it.word["en"]` 看着无害，但 `Entry.ambience` 是落进 diary.json 的：
            // 而当时 ambience.json 里 14 个词**全都**没有 ja 与 ko，所以日韩母语的用户
            // 每一条记录里的每一个氛围词都是英文——不是边角情况，是这两个语言包的默认结果。
            // 资产补齐之后这条兜底本就不该再被触发；留着它，下一次资产漏一种语言时
            // 又会安静地把别的语言写进用户的日记，而且这次连测试都不会红。
            // 少一个氛围词的代价是装饰少一点，写错语言的代价是这条记录从此说不清自己在说什么。
            .mapNotNull { it.word[request.nativeLanguage.tag] }

        val index = lexicon.index.value
        val subjects = detected.map { subjectOf(it, index, request) }
        return Resolved(subjects, scene, ambience)
    }

    private fun subjectOf(
        detected: DetectedObject,
        index: LexiconIndex,
        request: Request,
    ): Subject {
        val match = index.match(listOf(detected.categoryName to detected.score)).firstOrNull()
        return Subject(
            id = LexiconIndex.normalize(detected.categoryName),
            word = match?.entry?.words?.get(request.targetLanguage.tag)
                ?: match?.entry?.headword
                ?: detected.categoryName,
            entry = match?.entry,
            score = detected.score,
            // 检测器给的是**转正后显示图**上的框，存进日记要的是原始像素网格上的框：
            // 这里用的正是详情页那一步换算的逆函数，同一个 EXIF 角，往返精确。
            box = CameraFocusMath.orientedBox(
                NormBox(
                    detected.box.left,
                    detected.box.top,
                    detected.box.right,
                    detected.box.bottom,
                ),
                request.rotationDegrees,
            ),
        )
    }

    private fun pickTarget(cutout: Cutout, subjects: List<Subject>): Subject? = when (cutout) {
        Cutout.None -> null
        is Cutout.Keyed -> subjects.firstOrNull { it.id == cutout.id }
        // 相册没有「点哪颗词片」这一步，所以替用户挑**最确定的那一个**。
        // 理由：导入的条目必须在结构上和拍下来的一条等价——时间轴的卡片是靠贴纸撑出
        // 那一角的，缺了贴纸它就比拍照进来的矮一块，而这不是用户的选择造成的差异。
        Cutout.Best -> subjects.maxByOrNull { it.score }
    }

    /**
     * 拿一张**已经裁好、背景透明**的贴纸。
     *
     * 选后端按「谁能给出 cutout」而不是「谁是 automatic」：`automatic ?: manual` 在装了
     * Play 服务的机器上正好选中那个当时不给 cutout 的实现，本来能出图的那条路被跳过。
     */
    private suspend fun cutoutOf(bitmap: Bitmap, imageBox: NormBox): Bitmap? {
        val ordered = vision.segmenters.value.sortedBy { if (it.automatic) 0 else 1 }
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

    /**
     * 同一张贴纸两处各存一份：词卡以 `stickers/` 为根、条目以 `entries/` 为根——
     * 两者的删除语义不同，卡片要能活得比单条日记久。PNG 小，副本可接受。
     */
    private fun writeSticker(name: String, png: ByteArray) {
        runCatching {
            FileOutputStream(File(stickerDir, name)).use { out -> out.write(png) }
            FileOutputStream(File(entryPhotoDir, name)).use { out -> out.write(png) }
        }.onFailure { Log.w(TAG, "sticker write failed for $name", it) }
    }

    /**
     * 从位图上取平均亮度与冷暖，给氛围词评分。
     *
     * 先把长边降到 [LUMA_SAMPLE_EDGE] 再读像素：`getPixels` 得按整幅分配 IntArray，
     * 2560×1920 就是 19MB，而氛围词要的只是「亮/暗、暖/冷」这种粗信号
     * （[YuvFrames.luma] 本来就是每 8 个像素抽一个）。
     */
    private fun lumaOf(bitmap: Bitmap): YuvFrames.FrameLuma {
        val (w, h) = DecodeSizing.scaledSize(bitmap.width, bitmap.height, LUMA_SAMPLE_EDGE)
        val sample = runCatching { Bitmap.createScaledBitmap(bitmap, w, h, true) }.getOrNull() ?: return YuvFrames.FrameLuma(0f, 0f)
        val pixels = IntArray(sample.width * sample.height)
        runCatching {
            sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
        }.onFailure { Log.w(TAG, "cannot read pixels for ambience", it) }.getOrNull() ?: return YuvFrames.FrameLuma(0f, 0f)
        return YuvFrames.luma(pixels)
    }

    private companion object {
        const val TAG = "EntryPipeline"

        /**
         * 抠图用的最大边长。
         *
         * 这个数是跟着「贴纸是从这张图里**裁出来**的一块」定的，不是跟着屏幕定的：
         * 一个物体在 2560 宽的源图里占 30% 就是 ~768px，在 1280 的源图里只有 ~384px，
         * 而界面上一颗 240dp 的贴纸框在 3x 屏上正是 720px——384 就得放大近一倍，
         * 边缘会明显糊掉。die-cut 贴纸是这个产品的招牌，不值得为省一次解码牺牲它。
         *
         * 它同时是一道**上限**而不是「解全图」：两个分割后端各自还要往 1024/512 工作尺寸
         * 降采样，所以再高就只是多占内存。4032×3024 的传感器输出会在这里被正确降到 2560，
         * 相册里那些 12MP 的原图也一样。
         */
        const val MAX_PHOTO_PX = 2560

        /** 氛围词取样图的小边长。160 与分割掩码同一档：再大也不会更准，只是多算。 */
        const val LUMA_SAMPLE_EDGE = 160

        val EMPTY_SCENE = SceneGuess(null, 0f)
    }
}
