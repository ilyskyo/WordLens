// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.data.model

/**
 * 照片是「一个物体」还是「一个场景」。
 *
 * ## 它现在管什么
 *
 * 最初这里是用来**选模式**的：判成物体就走抠图贴纸流，判成场景就走场景词表流。后来产品
 * 改成「两类词同时浮在画面上，点不点、点哪个本身就是决定」，模式分支消失了，于是这个
 * 判定**降级为覆盖层的排布信号**：
 *
 * - 物体占比小、前景碎成多块 → 说明画面本身没有主角，氛围词应该铺得更开、占更大面积，
 *   物品词则压低权重；
 * - 主体饱满且集中 → 说明画面在讲一件事，物品词应该更醒目，氛围词退到边缘。
 *
 * 也就是说它现在决定的是**视觉权重**，而不是**功能分支**。这也是它值得保留的原因：
 * 两种词并排出现时，没有一个东西来分配注意力，画面会变成一锅粥。
 *
 * @see com.ilyskyo.wordlens.vision.ShotClassifier
 */
enum class ShotKind {
    /** 画面有明确主角：物品词靠前，氛围词收敛。 */
    OBJECT,

    /** 画面在讲一个环境：氛围词铺开，物品词作为点缀。 */
    SCENE,

    /**
     * 无法判断（没有分割可用，或构图本身两可）。
     *
     * 这种情况**不做猜测**，而是让两种词按中性权重并排出现。用户点哪个就是答案——
     * 他举起相机时本来就是因为不知道该记什么，替他决定只会错。
     */
    UNCLEAR,
}

/** 场景判定的结果，附带依据，便于界面上讲清楚「为什么这么排」。 */
data class SceneGuess(
    /** 命中的场景类别；null 表示没有可信的匹配。 */
    val kind: SceneKind?,
    /** 0..1。低于 [SceneTaxonomyAccept.FLOOR] 时 [kind] 必然为 null。 */
    val confidence: Float,
    /** 为这个场景投票的检测器标签，用于「为什么这么判」的提示。 */
    val evidence: List<String> = emptyList(),
)

/** 场景分类的接受阈值，集中放在一处便于调参与测试。 */
object SceneTaxonomyAccept {
    /** 低于此置信度视为「不认识」，此时 [SceneGuess.kind] 为 null 而不是硬猜一个。 */
    const val FLOOR = 0.30f

    /** 两个场景分差小于此值时视为难分，不再宣称确定。 */
    const val AMBIGUITY_MARGIN = 0.06f
}
