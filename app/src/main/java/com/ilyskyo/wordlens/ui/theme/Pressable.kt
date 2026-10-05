// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role

/**
 * 按压力度档位。
 *
 * 数字是两头挤出来的，不是拍的：
 *
 * - 小于 0.98 在大面积元素上几乎读不出来——指尖会挡住被按的那一角，缩放量必须大到能从
 *   **没被挡住**的部分看出来。
 * - 大于 0.94 会让元素像被捏扁；而缩得太多时，元素会退进自己的阴影里，读起来像消失了。
 * - 面积越小需要越大的相对缩量：同一个 0.97 用在 48dp 的 FAB 上只缩 1.4dp，等于没缩。
 */
object Scale {

    /** 卡片、照片这类大面积可点元素：320dp 宽按下去缩掉约 10dp。 */
    const val Large = 0.97f

    /** 按钮、胶囊、页签。 */
    const val Medium = 0.96f

    /** FAB 与图标按钮：小东西要多缩一点才有「按到了」。 */
    const val Small = 0.90f

    /** 按下时的不透明度。0.92 读作「压下去了一点」，再低就开始像 disabled。 */
    const val PressedAlpha = 0.92f
}

/**
 * 按压反馈：缩放 + 透明度 + 触觉，不管点击。
 *
 * 挂到**已经自己处理点击**的元素上（M3 的 Button、Surface(onClick=…) 这类），只需把它的
 * [interactionSource] 传进来。这里必须共享同一个 source：各建一个的话按下状态永远读不到，
 * 于是「有缩放动画的按钮」实际上从没缩过——本项目的 PrimaryButton 就这么空转过。
 *
 * ## 不对称是这段代码的全部意义
 *
 * 下压走 [Motion.pressDown]（90ms 缓出），抬起走 [Motion.press]（弹簧）。按下是**目标已知、
 * 时间极短**的动作，弹簧在这里只会多一个过冲，读起来像没按实；抬起是「释放」，需要惯性收尾。
 * 全程同一个 tween 会得到一个匀速的、不属于任何物理世界的动作。
 *
 * ## 只碰 graphicsLayer
 *
 * 缩放、透明度、变换原点都落在 `graphicsLayer`：它只改 RenderNode 的矩阵与图层透明度，
 * 不触发测量与布局。动画 padding / size 会把每一次按压变成一次全子树重排。
 *
 * ## 缩放原点取触点
 *
 * iOS 的按压是「你按的那一点沉下去」，不是整块居中缩小。居中缩放会让卡片右上角在按下时
 * 明显朝左下漂移，读起来像位移而不是按压。
 *
 * @param haptic 按下瞬间发哪一种触觉。默认 [Haptic.Tick]——按下去不震一下的元素，
 *   在关掉波纹之后就没有任何「受理了」的信号了。传 null 给那些自己发触觉的场景（滑动评级）。
 */
@Composable
fun Modifier.pressFeedback(
    interactionSource: InteractionSource,
    pressedScale: Float = Scale.Medium,
    pressedAlpha: Float = Scale.PressedAlpha,
    haptic: Haptic? = Haptic.Tick,
    enabled: Boolean = true,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val active = pressed && enabled
    val fireHaptic = rememberHaptic()
    // 触觉贴着按下瞬间发，而不是等 onClick 跑完：反馈要和手指的动作同帧，
    // 慢那十几毫秒就足够让它读成「卡顿之后的一声响」。
    // disabled 时不发：不能按下去震一下、然后什么也不发生。
    LaunchedEffect(active) {
        if (active && haptic != null) fireHaptic(haptic)
    }
    // 上升段用弹簧、下降段用短 tween：动画规格跟着「正在按」还是「正在放」切换，
    // animateFloatAsState 会从**当前值**继续，不会跳回起点。
    val spec = if (active) Motion.pressDown else Motion.press
    val scale by animateFloatAsState(
        targetValue = if (active) pressedScale else 1f,
        animationSpec = spec,
        label = "pressScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (active) pressedAlpha else 1f,
        animationSpec = spec,
        label = "pressAlpha",
    )
    val origin = rememberPressOrigin()
    return this
        .then(origin.tracker)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.alpha = alpha
            // 在 layer 块里读 State：状态变化只会让图层重新求值，不会重组子树。
            transformOrigin = origin.state.value
        }
}

/**
 * 统一的「可点」手感：按压反馈 + 触觉 + 可选长按，且**没有波纹**。
 *
 * 全 App 的可点元素都从这里过，理由和 [Motion] 一样——手感一致性只能靠单一实现保证。
 *
 * @param onLongClick 传 null 表示这个元素没有长按语义，于是它**也不会为长按而震**。
 *   触觉是在承诺「这里有隐藏动作」；震了却什么都不发生，比不震更伤信任。
 * @param haptic 按下时的触觉，默认 [Haptic.Tick]。传 null 给那些自己在拖拽里发触觉的元素
 *   （复习卡片就是：滑动越过阈值时才该震）。
 * @param interactionSource 需要在外层读 pressed（驱动颜色动画）时传自己的进来。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    role: Role? = null,
    pressedScale: Float = Scale.Medium,
    enabled: Boolean = true,
    haptic: Haptic? = Haptic.Tick,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
): Modifier {
    val systemHaptics = LocalHapticFeedback.current

    return this
        .combinedClickable(
            interactionSource = interactionSource,
            // 主题里已经把 LocalIndication 换成 [NoIndication]，这里再显式写一次 null：
            // 这一行就是在声明「此处无波纹」，读代码的人不必去猜主题做了什么。
            indication = null,
            enabled = enabled,
            role = role,
            onClick = onClick,
            onLongClick = onLongClick?.let { action ->
                {
                    // 长按用系统通道而不是自定义波形：它是「进入另一个模式」的确认，
                    // 系统会把它映射到该机型统一的长按反馈（部分机型还带一声轻响），
                    // 而辅助功能里「关闭触摸振动」的用户应当一起关掉——不该由我们绕过。
                    systemHaptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    action()
                }
            },
        )
        .pressFeedback(interactionSource, pressedScale, haptic = haptic)
}

/** 触点原点：一个只在「有新按下」时写入的 State，加挂它的观察者。 */
private class PressOrigin(val state: State<TransformOrigin>, val tracker: Modifier)

/**
 * 触点原点观察者。
 *
 * 常驻 `awaitPointerEventScope` 而不是自己去认一个 tap：我们要的只是「看见 down」，
 * 消费事件的任何一部分都会让下层的点击或拖拽手势变得不可预测。
 * `PointerEventPass.Initial` 让它在子节点之前拿到事件，且不 consume，因此与
 * [combinedClickable]、与复习卡片的滑动评级互不干扰。
 */
@Composable
private fun rememberPressOrigin(): PressOrigin {
    val state = remember { mutableStateOf(TransformOrigin.Center) }
    val tracker = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val down = event.changes.firstOrNull { it.pressed && !it.previousPressed }
                if (down != null && size.width > 0 && size.height > 0) {
                    state.value = TransformOrigin(
                        pivotFractionX = (down.position.x / size.width).coerceIn(0f, 1f),
                        pivotFractionY = (down.position.y / size.height).coerceIn(0f, 1f),
                    )
                }
            }
        }
    }
    return PressOrigin(state, tracker)
}
