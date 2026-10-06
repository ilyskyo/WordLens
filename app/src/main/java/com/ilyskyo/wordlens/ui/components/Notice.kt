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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ilyskyo.wordlens.ui.theme.Motion
import com.ilyskyo.wordlens.ui.theme.rememberReduceMotion
import com.ilyskyo.wordlens.ui.theme.Space
import com.ilyskyo.wordlens.ui.theme.softShadow
import kotlinx.coroutines.delay

/**
 * 一次提示：那句话说什么，以及它是第几次说。
 *
 * `id` 存在的唯一理由是 `StateFlow` 按**值相等**去重：连着两次同样的文案，在下游是同一次变化，
 * 于是 `LaunchedEffect` 不重启、计时器不重来、`liveRegion` 也不再念第二遍。用户连点两次发音
 * 得到的就是一条越来越短、越来越不像「回答了我这一下」的提示，而界面上看不出任何区别。
 *
 * 修它的办法不是想办法让文案每次都不同，而是把「这是一次事件」写进类型里：文案相同而 id 不同，
 * 就是两次要说的事。与 `MainActivity` 里 `SharedQuery` / `PageStack` 的 sequence 同一个做法。
 */
data class Notice(val text: String, val id: Long)

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
 *    系统开了「移除动画」时两条都退回纯淡入淡出——这里降的是「怎么出现」，不是「出不出现」。
 * 3. **读屏要能听见**：`liveRegion` 让文本出现时被播报一次，而不是等用户摸到它。
 *    关掉波纹、改用视觉提示之后，听觉通道必须自己补上，不然无障碍只是句空话。
 */
@Composable
fun NoticeHost(
    notice: Notice?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    durationMillis: Long = DEFAULT_VISIBLE_MS,
) {
    // 计时挂在这一次事件上：同一句话再来一遍要重新计时，也要重新被读屏念一遍。
    LaunchedEffect(notice) {
        if (notice == null) return@LaunchedEffect
        delay(durationMillis)
        onDismiss()
    }

    // 提示的进出是装饰性的，它要讲的那句话本身是完整的（而且同时走了 liveRegion），
    // 所以系统开了「移除动画」时把缩放与浮起都摘掉，只留淡入淡出——位置与对比度不变，
    // 只有「怎么出现」变了。
    val reduceMotion = rememberReduceMotion()
    val lift: Float = if (reduceMotion) {
        if (notice != null) 1f else 0f
    } else {
        val animated by animateFloatAsState(
            targetValue = if (notice != null) 1f else 0f,
            animationSpec = Motion.snappy,
            label = "noticeLift",
        )
        animated
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .systemBarsPadding()
            .padding(bottom = Space.lg),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = notice != null,
            enter = if (reduceMotion) {
                fadeIn(tween(Motion.FADE_IN_MS))
            } else {
                scaleIn(Motion.bouncy, initialScale = 0.92f) + fadeIn(Motion.enterFade())
            },
            exit = if (reduceMotion) {
                fadeOut(tween(Motion.FADE_OUT_MS))
            } else {
                scaleOut(Motion.press, targetScale = 0.96f) + fadeOut(Motion.exitFade())
            },
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
                    text = notice?.text.orEmpty(),
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
