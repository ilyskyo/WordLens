// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset

/**
 * 全局动效规格。**位移、缩放、旋转一律只能引用这里的弹簧；淡入淡出一律只能引用
 * [enterFade] / [exitFade]。** 屏幕里出现新的 `spring(...)` 或裸的 `tween(数字)`
 * 就是 bug。
 *
 * 这条规则原先写作「任何动画只允许引用这里的规格」，那是过头的：它字面上把
 * `PillSwitch` 的颜色过渡和详情页的交错时长也判成了 bug，而那两个各自有交代过的理由。
 * 收窄成现在这样，是为了让它可以**被真的执行**——一个到处都不满足的规则比没有规则更糟，
 * 因为它教会人忽略它。
 *
 * ## 为什么只有五个弹簧
 *
 * 弹簧参数一旦每处自定，「手感」就会在屏幕上碎成几十种：同一层级的两个元素用不同刚度
 * 进出，读起来像没对齐的齿轮。收敛成五个之后，一个交互属于哪一档是**语义判断**而不是
 * 数值调参——快而确定的归 snappy，内容进出归 smooth，需要一点过冲的归 bouncy。
 *
 * ## 为什么都带 visibilityThreshold
 *
 * Float 弹簧默认会一直微幅收敛下去（`Float.DefaultVisibilityThreshold` 在小量级上偏粗、
 * 在大量级上偏细），显式给一个阈值才能让它**明确停下**而不是拖着一帧帧空转。
 * 空转的动画在低端机上就是掉帧，在电量上就是白烧 GPU。
 */
object Motion {

    /** Float 弹簧的统一停止阈值。1e-4 在 px / 比例 / 角度三种量纲上都不会看见台阶。 */
    private const val FLOAT_THRESHOLD = 1e-4f

    // 五档弹簧的参数只在这里出现一次。IntOffset 版本要从同一组数字构造，
    // 否则「同一档手感」会因为在两个文件里各写一遍而慢慢分家。
    private const val SNAPPY_DAMPING = 0.78f
    private const val SNAPPY_STIFFNESS = 550f
    private const val SMOOTH_DAMPING = 0.85f
    private const val SMOOTH_STIFFNESS = 320f

    /**
     * 入场缓动：起步快、尾巴长。
     *
     * (0.32, 0.72, 0, 1) 的末速度为 0，元素是「停」到目标位置的，不是撞上去的。
     * 只在淡入淡出与颜色过渡上用——位移一律走弹簧。
     */
    val enterEase = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

    /** 出场缓动：一开始就最快，因为离场的东西不该占用注意力。 */
    val exitEase = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    /**
     * 按压反馈。
     *
     * 0.9 的阻尼几乎不过冲——按下要的是「被受理」而不是「在弹」。抬起方向用它，
     * 下压方向用 [pressDown]（不对称是刻意的，见 [pressDown] 的注释）。
     */
    val press = spring(
        dampingRatio = 0.9f,
        stiffness = 1200f,
        visibilityThreshold = FLOAT_THRESHOLD,
    )

    /**
     * 下压：90ms 的缓出，不用弹簧。
     *
     * 手指按下是**一个位置已知、时间极短**的动作，弹簧在这里的唯一效果是过冲，
     * 而过冲会让元素在指尖下「抖」一下，读起来像没按实。抬起才交给弹簧——那里目标位置
     * 已知但过程是「释放」，需要惯性收尾。这条不对称是 iOS 按压感的分水岭。
     */
    val pressDown = tween<Float>(durationMillis = 90, easing = exitEase)

    /**
     * 标准交互：页签滑动、开关、筛选胶囊。
     *
     * 0.78 / 550 是「跟手且干脆」的组合：300dp 的滑动距离在约 260ms 内到位，
     * 末端只有一点点可以被感知、但不会被误认为「还没停」的过冲。
     */
    val snappy = spring(
        dampingRatio = SNAPPY_DAMPING,
        stiffness = SNAPPY_STIFFNESS,
        visibilityThreshold = FLOAT_THRESHOLD,
    )

    /**
     * 内容进出：卡片换页、列表项入场、统计数字出现。
     *
     * 比 snappy 软一档（320），因为这里移动的是**有信息量的东西**，太快会让人来不及读完。
     * 0.85 的阻尼保证它不弹——内容弹一下会读成「加载出错重试」。
     */
    val smooth = spring(
        dampingRatio = SMOOTH_DAMPING,
        stiffness = SMOOTH_STIFFNESS,
        visibilityThreshold = FLOAT_THRESHOLD,
    )

    /**
     * [smooth] 的 IntOffset 版本，给 `slideInVertically` / `slideOutVertically` 用。
     *
     * 这两个 API 要的是 `FiniteAnimationSpec<IntOffset>`，装不进 Float 弹簧。参数必须与
     * [smooth] 同源：两处各写一份数字，早晚会滑出两种手感，而「两种手感」正是这一整个文件
     * 存在的理由。IntOffset 是整数像素量纲，不需要 float 的停止阈值。
     */
    fun smoothOffset(): SpringSpec<IntOffset> = spring(
        dampingRatio = SMOOTH_DAMPING,
        stiffness = SMOOTH_STIFFNESS,
    )

    /** [snappy] 的 IntOffset 版本（页面位移、卡片翻页一类）。 */
    fun snappyOffset(): SpringSpec<IntOffset> = spring(
        dampingRatio = SNAPPY_DAMPING,
        stiffness = SNAPPY_STIFFNESS,
    )

    /**
     * 弹性反馈：翻卡、选中胶囊的呼吸、卡堆顶上。
     *
     * 0.52 会明显过冲一次再收回。这个「多余」的一步是有用途的：它把「这个元素是活的、
     * 有物理存在」这件事传达给用户。用错地方（比如文字淡入）就会显得廉价，所以只给
     * 有体积的东西用。
     */
    val bouncy = spring(
        dampingRatio = 0.52f,
        stiffness = 380f,
        visibilityThreshold = FLOAT_THRESHOLD,
    )

    /**
     * 拖拽回弹：滑动评级没过阈值时把卡片送回原位。
     *
     * 0.72 / 260——比 smooth 更慢更软，因为它是**从用户手指的速度接管**的：弹簧前半段
     * 要接住那个惯性，太硬会读成「被弹回去」而不是「自己滑回去」。
     */
    val settle = spring(
        dampingRatio = 0.72f,
        stiffness = 260f,
        visibilityThreshold = FLOAT_THRESHOLD,
    )

    /** 呼吸类循环动画的周期：2.4s 慢到不抢注意力，又快到「还在等答案」时能被看见一次。 */
    const val BREATHING_PERIOD_MS = 2400

    // ── 淡入淡出的两档时长 ─────────────────────────────────────────
    //
    // 弹簧管的是**有体积的东西怎么动**（位移、缩放、旋转）；纯 alpha 过渡没有惯性可言，
    // 它需要的只是一个时长。所以这两档不是「第六个弹簧」，而是同一件事的另一半：
    // 屏幕上只允许存在这两个淡入时长和这两个淡出时长。
    //
    // 为什么不对称、而且差得这么多：离场的东西不该占用注意力，进场则要让人跟得上。
    // 为什么是 120/90 而不是一个数：装机对比过，进出同速会得到一个「闪一下」而不是
    // 「来了一下、走掉了」的观感。

    /** 淡入时长。所有 `fadeIn` 都用它。 */
    const val FADE_IN_MS = 120

    /** 淡出时长。所有 `fadeOut` 都用它，比淡入快一档。 */
    const val FADE_OUT_MS = 90

    /** 淡入。带 [enterEase]：起步快、尾巴长，末速度为 0。 */
    fun enterFade(): FiniteAnimationSpec<Float> = tween(FADE_IN_MS, easing = enterEase)

    /** 淡出。带 [exitEase]：一开始就最快。 */
    fun exitFade(): FiniteAnimationSpec<Float> = tween(FADE_OUT_MS, easing = exitEase)
}
