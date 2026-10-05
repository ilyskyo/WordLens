// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ilyskyo.wordlens.ui.theme.Motion
import com.ilyskyo.wordlens.ui.theme.Space
import com.ilyskyo.wordlens.ui.theme.softShadow
import kotlinx.coroutines.delay

/**
 * 一条会自己消失的低调提示。
 *
 * ## 为什么不用 Toast
 *
 * Toast 是 Android 的系统外观：深色方块、系统字体、位置由 ROM 决定，在浅色奶油底上
 * 像一块贴歪的胶布。这个 App 从头到尾自己在画每一个表面，最后由一条系统 Toast 报信，
 * 等于在门口挂了一块别人的门牌。更重要的是它**不在合成树里**，读屏会念但念在错误的时机，
 * 也不会跟随主题切换。
 *
 * ## 三条设计约束
 *
 * 1. **不抢操作**：它浮在内容上、可点掉、也会自己走。绝不让用户必须看完它才能继续
 *    （这是这个项目对动效的硬要求之一）。
 * 2. **进出不对称**：进用带一点点过冲的弹簧（它是来报告的），出用干脆的淡出（它不该占用注意力）。
 * 3. **读屏要能听见**：`liveRegion` 让文本出现时被播报一次，而不是等用户摸到它。
 *    关掉波纹、改用视觉提示之后，听觉通道必须自己补上，不然无障碍只是句空话。
 */
@Composable
fun NoticeHost(
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    durationMillis: Long = DEFAULT_VISIBLE_MS,
) {
    // 同一句话连续出现两次也要重新计时，所以用 text 做 key 而不是只看可见性。
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(message) {
        if (message == null) {
            visible = false
        } else {
            visible = true
            delay(durationMillis)
            onDismiss()
            visible = false
        }
    }

    val lift by animateFloatAsState(
        targetValue = if (message != null) 1f else 0f,
        animationSpec = Motion.snappy,
        label = "noticeLift",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .systemBarsPadding()
            .padding(bottom = Space.lg),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = message != null,
            enter = scaleIn(Motion.bouncy, initialScale = 0.92f) + fadeIn(tween(120)),
            exit = scaleOut(Motion.press, targetScale = 0.96f) + fadeOut(tween(90)),
        ) {
            Surface(
                onClick = onDismiss,
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(horizontal = Space.screen)
                    .softShadow(MaterialTheme.shapes.medium, intensity = 0.7f * lift)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(
                    text = message.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.sm),
                )
            }
        }
    }
}

/** 提示停留时长。2.8s 是「读完一句中文」的量级，再短会读不完，再长就开始挡事。 */
private const val DEFAULT_VISIBLE_MS = 2_800L
