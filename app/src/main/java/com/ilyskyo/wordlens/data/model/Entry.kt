// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.data.model

import androidx.annotation.StringRes
import com.ilyskyo.wordlens.R
import kotlinx.serialization.Serializable

/**
 * 日记的一条记录——这个 App 的主角。
 *
 * ## 为什么 Entry 是根，WordCard 不是
 *
 * 一开始的设计是「一个物体 = 一张词卡」，词卡是主角、照片是道具。后来发现真正被保存的是
 * **一个瞬间**：照片、时间、当下那种感觉，以及顺手记下的几个词。词是注释，条目才是内容。
 *
 * 这不只是命名上的调整，它改变了几件事：
 *
 * - 回顾的主路径是「翻时间轴」，不是「刷复习队列」。复习退为功能之一。
 * - 一张照片上的多个词共享同一个 Entry，因此可以一起被展示、一起被导出、一起被删除。
 * - 界面能拿照片当回忆锚点。人回忆一个词靠的是画面，不是「cup /kʌp/ 杯子」这一行字。
 *
 * ## 私有
 *
 * 没有公开分享入口。照片比单词卡敏感得多（家、孩子、证件），而这本日记的价值完全建立在
 * 「它只在我的机器上」之上。加分享是另一个产品决策，会连带影响 [mood] 是否可空。
 */
@Serializable
data class Entry(
    val id: String,

    /** 照片文件名，相对 `filesDir/entries`。这是必填的——没有照片的日记不成立。 */
    val photoPath: String,

    /**
     * 一句当时的声音。文件名相对 `filesDir/audio`——**根与 [photoPath] 不同**，
     * 两者放在一起写就是为了不让人把 audioPath 当成 entries 下的一个名字去找。
     *
     * 名字按条目 id 定死（见 [com.ilyskyo.wordlens.core.voice.VoiceMemo]），所以这个字段
     * 可空只表示「没有被这条日记引用」，不表示「位置未知」：一段用户还没决定收不收下的
     * 录音占的是同一个位置。删除条目时那一份无论有没有被引用都会跟着走，
     * 这就是单分支级联能成立的原因。
     *
     * **不转文字。** 这个附件存在的理由只有这一条：自己说过一遍的词和读过一遍的词是两种
     * 记忆，而这本日记是由场景组成的。转成文字就把它变回文本——那是别的词汇应用已经在做
     * 的事。所以这里没有语音识别、没有云端、也没有后台服务：录、存、放、删。
     */
    val audioPath: String? = null,

    /**
     * 那段声音有多长（毫秒），0 表示没有。
     *
     * 存下来而不是每次现问播放器：时长得在**按下播放之前**就看得见——「一条 00:12 的声音」
     * 和「一条 01:30 的声音」是两种不同的要不要听，而问 MediaPlayer 要先把文件打开并准备，
     * 那是一次几十毫秒的异步，做不进一行列表读数。
     */
    val audioDurationMs: Long = 0L,

    /** 当天时刻（epoch millis）。日记天然按时间排序，所以它是主要索引。 */
    val takenAt: Long,

    /** 用户可选的一句话标题。留空时界面用日期代替。 */
    val title: String? = null,

    /**
     * 当时的感受。日记记录的是经历，不是标签，所以这一项是可空且轻量的。
     */
    val mood: EntryMood? = null,

    /** 自动识别出的场景类别 id，对应 `assets/scenes/scenes.json`，可为 null。 */
    val kind: String? = null,

    /** 场景名在各语言下的写法，来自场景词表。 */
    val kindLabel: Map<String, String> = emptyMap(),

    /**
     * 氛围词。这些词任何模型都检测不出来——它们不是视觉属性，而是人对画面的反应。
     * 它们是这个 App 里最不像机器产物、也最像「人」的部分。
     */
    val ambience: List<String> = emptyList(),

    /**
     * 一句话内容。来源有二：用户自己写的，或（启用了自带密钥的 AI 后端时）由模型根据
     * 用户口述的要点整理而成。
     *
     * 单独一个字段而不是复用 [title]：标题是给人看的短标签，摘要是给人读的完整句子，
     * 长度和用途都不同，混在一处迟早会出现「标题栏里塞了一整段话」。
     */
    val summary: String? = null,

    /**
     * 从 [summary] 里提炼出的关键词，显示在回看页照片下方。
     *
     * 存下来而不是每次现算，是因为回看是高频路径，而提炼要走模型。存一次、显示无数次。
     */
    val keywords: List<String> = emptyList(),

    /**
     * [summary] 是用户写的还是 AI 整理的。界面上要能区分：用户自己写的东西被 AI 改过，
     * 应当明确告知，而不是悄悄替换。
     */
    val summarySource: EntrySource? = null,

    /**
     * 检测到的物品类别名，连同它当时的框位置（归一化）。
     *
     * 存位置是为了让时间轴回看时能**在原图上重新把词长回物体上**，而不只是列一张清单。
     * 这条是「场景式」和普通日记的分界线。
     */
    val objects: List<EntryObject> = emptyList(),

    /** 用户自己加的标签。 */
    val tags: List<String> = emptyList(),

    /**
     * 检测器提出、但用户没有保存的词。留着是为了让「再看一次这些」成为可能，
     * 也为了以后能回答「当时它看到了什么」。
     */
    val declinedWords: List<String> = emptyList(),

    /** 识别来源，写进数据以便日后核对与调参。 */
    val detectedBy: EntrySource = EntrySource.ON_DEVICE,

    val createdAt: Long = System.currentTimeMillis(),

    val updatedAt: Long = System.currentTimeMillis(),

    val lastReviewedAt: Long = 0L,
) {
    /** 本地日期键 yyyy-MM-dd，用作时间轴的分组键。 */
    val dayKey: String
        get() = java.time.Instant.ofEpochMilli(takenAt)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .toString()

    /** 标题优先取用户写的，其次取场景名，最后由界面回退到日期。 */
    fun displayTitle(): String? = title?.takeIf { it.isNotBlank() }
        ?: kindLabel.values.firstOrNull()?.takeIf { it.isNotBlank() }

    companion object {
        fun newId(): String = WordCard.newId()
    }
}

/**
 * 覆盖层的一层。
 *
 * 放在 `data.model` 而不是视觉包里，因为它是**要持久化进日记文件**的语义：回看时要靠它
 * 决定照片上哪些词可点。依赖方向保持 model ← vision，视觉层读模型，不反过来。
 */
enum class OverlayLayer {
    /** 锚在具体物体上，可点。点击会推动相机聚焦到那个物体。 */
    ITEM,

    /**
     * 描述氛围与感受，非交互。
     *
     * 这类词检测不出来——它们不是视觉属性，而是人对画面的反应。也正因为如此，它们是这本
     * 日记里最像「人」的部分，而不是机器识别的产物。
     */
    AMBIENCE,
}

/**
 * 一条记录里的一个物体。
 *
 * 位置用四角归一化坐标而不是中心点加尺寸，因为回看时需要在图上画出框，
 * 而不同宽高比下「中心 + 尺寸」与真实框的误差比四角直接存储大得多。
 */
@Serializable
data class EntryObject(
    val id: String,
    /** 展示用的词（通常是词典里的规范写法）。 */
    val word: String,
    /** 对应的词典条目 id，为 null 表示词典里没有这个词。 */
    val lexiconEntryId: String? = null,
    val score: Float = 0f,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** 从这条记录里抠出来的透明贴纸文件名，相对 `filesDir/entries`。可空：没点过就没有。 */
    val stickerPath: String? = null,
    /** 标注层：物品可点，氛围词不可点。 */
    val layer: OverlayLayer = OverlayLayer.ITEM,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val areaFraction: Float get() = width * height
}

/**
 * 氛围词。
 *
 * 只有十来个值，不是数据表而是**一组刻度**：日记里的感受不需要 300 个同义词，
 * 需要的是 6-10 个互不重叠、随便勾一个都成立的选项。多一个就开始稀释，多一个的边际
 * 价值低于零。
 */
enum class EntryMood(val key: String, @StringRes val labelRes: Int, val emoji: String) {
    QUIET("quiet", R.string.mood_quiet, "🤫"),
    WARM("warm", R.string.mood_warm, "🧸"),
    CROWDED("crowded", R.string.mood_crowded, "🫧"),
    MESSY("messy", R.string.mood_messy, "🌀"),
    BRIGHT("bright", R.string.mood_bright, "☀️"),
    GREY("grey", R.string.mood_grey, "☁️"),
    BUSY("busy", R.string.mood_busy, "⏳"),
    ORDINARY("ordinary", R.string.mood_ordinary, "🌱"),
    ;

    companion object {
        fun fromKey(key: String?): EntryMood? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 场景分类词表的一项。
 *
 * [words] 是词典 key 而非内联释义：词典里改进一个词，所有提到它的场景都跟着变好。
 */
@Serializable
data class SceneKind(
    val id: String,

    val label: Map<String, String> = emptyMap(),

    val emoji: String? = null,

    val tags: List<String> = emptyList(),

    /** 能为本场景投票的字符串（分类器输出、同义词、近似说法），已小写。 */
    val labelAliases: List<String> = emptyList(),

    /** 该场景的典型词，词典 key，最有代表性的排前面。 */
    val words: List<String> = emptyList(),

    /** 打破平局时用；并列时优先取先验高的。 */
    val prior: Float = 1.0f,
)

/** 场景词表文件：`assets/scenes/scenes.json`。 */
@Serializable
data class SceneTaxonomy(
    val schemaVersion: Int = 1,
    val scenes: List<SceneKind> = emptyList(),
    /**
     * 氛围词表：`assets/scenes/ambience.json`。
     *
     * 与场景分开存放，因为它们的来源完全不同——场景来自检测器的类别名（可对齐），
     * 氛围词来自人对画面的描述（无法对齐，只能靠编辑）。混在一个文件里会让人误以为
     * 氛围词也是检测出来的。
     */
    val ambience: List<AmbienceWord> = emptyList(),
) {
    private val byId: Map<String, SceneKind> = scenes.associateBy { it.id }

    fun byId(id: String?): SceneKind? = id?.let { byId[it] }

    val size: Int get() = scenes.size
}

/** `assets/scenes/ambience.json` 的文件外壳。单独一层而不是裸数组：给 schemaVersion 留位。 */
@Serializable
data class AmbienceFile(
    val schemaVersion: Int = 1,
    val ambience: List<AmbienceWord> = emptyList(),
)

/**
 * 一个氛围词。
 *
 * [cue] 是「什么时候这个词说得通」的判据，例如 warm 需要画面里有暖光、食物或织物。
 * 检测器不产出氛围词，但**拍摄环境**可以：亮度、色温、画面复杂度都是现成的信号，
 * 用它们把最说得通的那几个氛围词优先推上去，比让用户从一长串里挑准得多。
 */
@Serializable
data class AmbienceWord(
    val id: String,
    val word: Map<String, String>,
    val emoji: String? = null,
    /** 触发条件，留空表示任何时候都成立。 */
    val cue: AmbienceCue? = null,
)

/** 氛围词的可计算触发条件。全部来自取景阶段就能拿到、不需要额外模型的量。 */
@Serializable
data class AmbienceCue(
    /** 平均亮度 0..1 的下限。 */
    val minBrightness: Float? = null,
    val maxBrightness: Float? = null,
    /** 平均色温倾向：负值偏冷（阴天、青色），正值偏暖。 */
    val minWarmth: Float? = null,
    val maxWarmth: Float? = null,
    /** 画面复杂度（检测到的物体数）区间，用于区分「空荡」与「拥挤」。 */
    val minObjects: Int? = null,
    val maxObjects: Int? = null,
)