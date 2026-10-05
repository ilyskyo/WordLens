// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring

/**
 * 长按的统一手感：一次触觉脉冲 + 一个带阻尼的回弹缩放。
 *
 * 所有长按场景都走这里，因为手感一致性只能靠单一实现保证：三处各写一遍
 * `combinedClickable`，迟早会出现一处震两处不震、一处缩 0.97 一处缩 0.95。
 *
 * 缩放取自 `interactionSource` 的按下状态，所以**短按也会有同样的回弹**——这是有意的：
 * 只有长按才缩放，会让卡片在两种手势下表现不一致，用户会以为短按没被受理。
 * 0.97 的幅度小到不会干扰正常点击，又足够让「按到了」这件事被看见。
 *
 * ## 关于「手指移出就不该触发长按」
 *
 * `detectTapGestures` 的语义是：达到长按时长**之前**移出触点范围会取消；达到时长之后长按
 * 已经触发，再移出不会撤回它。这不是缺陷——撤回一个已经给过触觉反馈的动作，比让用户
 * 多按了一下更让人困惑。要「按住拖到别处」那种交互得另写 `pointerInput`。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.longPressable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    role: Role? = null,
    pressedScale: Float = 0.97f,
    enabled: Boolean = true,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        // 0.75 阻尼比：按下去立刻有反应，松手回弹一次就停，不来回晃。
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
        label = "longPressScale",
    )
    return this
        .combinedClickable(
            interactionSource = interactionSource,
            indication = ripple(color = MaterialTheme.colorScheme.primary),
            enabled = enabled,
            role = role,
            onClick = onClick,
            // 没有长按语义的元素不该震：触觉是在承诺「这里有隐藏动作」，
            // 震了却什么都不发生，比不震更伤信任。
            onLongClick = onLongClick?.let { action ->
                {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    action()
                }
            },
        )
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
}
