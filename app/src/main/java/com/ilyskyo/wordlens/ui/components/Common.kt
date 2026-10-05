// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ilyskyo.wordlens.ui.icons.WordLensIcons
import com.ilyskyo.wordlens.ui.theme.IpaTextStyle
import com.ilyskyo.wordlens.ui.theme.Space
import com.ilyskyo.wordlens.ui.theme.WordLensTheme
import com.ilyskyo.wordlens.ui.theme.stickerCorner

/** 按压缩放到的比例。规范给的是 0.96。 */
private const val PRESSED_SCALE = 0.96f

/**
 * 主按钮。
 *
 * 三个变体共用一份实现：除了颜色，它们完全一样（20dp 圆角、48dp 最小高度、按压缩放）。
 * 分成三份写会让「按压反馈」在三处各自漂移。
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    PressableScale(onClick = onClick, enabled = enabled, modifier = modifier) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = Modifier
                .heightIn(min = 48.dp)
                .fillMaxWidth(),
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun TonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    PressableScale(onClick = onClick, enabled = enabled, modifier = modifier) {
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            modifier = Modifier
                .heightIn(min = 48.dp)
                .fillMaxWidth(),
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun OutlinedAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = modifier
            .heightIn(min = 48.dp)
            .fillMaxWidth(),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * 按压缩放容器。
 *
 * 用弹簧而不是 tween：抬手时弹簧会自然回弹，而固定 100ms 的线性 tween 在快速连点时
 * 显得迟钝。缩放通过 [MutableInteractionSource] 读取，而不是自己记一个 pressed 状态，
 * 这样它和 Material 自带的 ripple 天然同步。
 */
@Composable
private fun PressableScale(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) PRESSED_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "pressScale",
    )
    Box(
        modifier = modifier
            .scale(scale)
            .then(
                Modifier.pointerInput(enabled) {
                    detectTapGestures(onTap = { if (enabled) onClick() })
                },
            ),
    ) {
        content()
    }
}

/**
 * 贴纸卡片。
 *
 * 三层视觉：柔和阴影、白描边、内容。描边是这张卡片成立的关键——透明底的物体浮在奶油白
 * 背景上，没有一圈白边会「化开」，尤其当物体本身偏浅色时。
 *
 * 旋转角度由调用方传入而不是随机生成：同一张卡片在收藏页、复习页、详情页必须是同一个
 * 角度，随机化会让它在页面间跳动。
 */
@Composable
fun StickerCard(
    bitmap: Bitmap?,
    headword: String,
    ipa: String?,
    modifier: Modifier = Modifier,
    rotationDegrees: Float = 0f,
    emoji: String? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val accents = WordLensTheme.accents

    Box(
        modifier = modifier
            // 阴影用暖灰而不是默认黑：黑色阴影落在奶油白背景上会显脏。
            .shadow(
                elevation = 6.dp,
                shape = stickerCorner(STICKER_BOX_RADIUS),
                ambientColor = accents.shadowTint,
                spotColor = accents.shadowTint,
            )
            .rotate(rotationDegrees)
            .clip(stickerCorner(STICKER_BOX_RADIUS))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = accents.stickerStrokeWidth,
                color = accents.stickerStroke,
                shape = stickerCorner(STICKER_BOX_RADIUS),
            )
            .then(
                if (onClick != null || onLongClick != null) {
                    Modifier.pointerInput(onClick, onLongClick) {
                        detectTapGestures(
                            onTap = { onClick?.invoke() },
                            onLongPress = { onLongClick?.invoke() },
                        )
                    }
                } else {
                    Modifier
                },
            )
            .semantics {
                contentDescription = buildString {
                    append(headword)
                    if (ipa != null) append(", ").append(ipa)
                }
            },
    ) {
        Column(modifier = Modifier.padding(Space.sm)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // 4:3 而不是 1:1：真实贴纸多为横向，且正方形会把竖长的物体裁掉。
                    .aspectRatio(4f / 3f)
                    .clip(stickerCorner(STICKER_BOX_RADIUS - 40f))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp),
                    )
                } else if (emoji != null) {
                    Text(text = emoji, fontSize = 44.sp)
                }
            }

            Text(
                text = headword,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Space.sm),
            )
            if (ipa != null) {
                Text(
                    text = ipa,
                    // 音标斜体是 IPA 的惯例；用等宽族保证重音符对齐。
                    style = IpaTextStyle.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 传给 [stickerCorner] 的名义边长，实际圆角由它按比例算出。 */
private const val STICKER_BOX_RADIUS = 160f

/**
 * 圆形发音按钮。
 *
 * 发音是这个应用里使用频率最高的操作——拍照后一次、复习时至少两次——所以它被做成一个独立的
 * 56dp 圆形目标，而不是塞在卡片角落的小图标。
 */
@Composable
fun SpeakButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 56.dp,
) {
    val container = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (enabled) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    PressableScale(onClick = onClick, enabled = enabled, modifier = modifier) {
        Surface(
            shape = CircleShape,
            color = container,
            modifier = Modifier
                .size(size)
                .semantics { this.contentDescription = contentDescription },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = WordLensIcons.Speaker,
                    // 语义已在 Surface 上合并，这里必须置空，否则读屏会重复。
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(size * 0.45f),
                )
            }
        }
    }
}

/** 空状态。不画插画，只用一枚 emoji 加文字，省一套插画资源且缩放无损。 */
@Composable
fun EmptyState(
    emoji: String,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.sm, Alignment.CenterVertically),
    ) {
        Text(text = emoji, fontSize = 56.sp)
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFFF8F3)
@Composable
private fun StickerCardPreview() {
    WordLensTheme {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StickerCard(
                bitmap = null,
                headword = "cup",
                ipa = "/k\u028Ap/",
                emoji = "\u2615",
                rotationDegrees = -3f,
                modifier = Modifier.size(width = 150.dp, height = 170.dp),
            )
            StickerCard(
                bitmap = null,
                headword = "bridge",
                ipa = "/br\u026Ad\u0292/",
                emoji = "\uD83C\uDF09",
                rotationDegrees = 2.5f,
                modifier = Modifier.size(width = 150.dp, height = 170.dp),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFFF8F3)
@Composable
private fun ButtonsPreview() {
    WordLensTheme {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrimaryButton("保存贴纸", {})
            TonalButton("记住了", {})
            OutlinedAction("重新拍摄", {})
            SpeakButton(onClick = {}, contentDescription = "播放发音")
        }
    }
}