// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
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
import com.ilyskyo.wordlens.ui.theme.Motion
import com.ilyskyo.wordlens.ui.theme.Scale
import com.ilyskyo.wordlens.ui.theme.Space
import com.ilyskyo.wordlens.ui.theme.WordLensTheme
import com.ilyskyo.wordlens.ui.theme.pressFeedback
import com.ilyskyo.wordlens.ui.theme.pressable
import com.ilyskyo.wordlens.ui.theme.softShadow
import com.ilyskyo.wordlens.ui.theme.stickerCorner

/**
 * 主按钮。
 *
 * 三个变体共用一份手感：除了颜色，它们完全一样（20dp 超椭圆、48dp 最小高度、按压缩放）。
 * 分成三份写会让「按压反馈」在三处各自漂移。
 *
 * ## 为什么要把 interactionSource 显式传给 Button
 *
 * 缩放和触觉都读同一个 source 的 pressed 状态。以前这里另建了一个 source 挂在外面，
 * Button 内部用的是它自己那一个——于是外层永远读不到 pressed，缩放动画**一次都没跑过**，
 * 只剩波纹在假装「按到了」。波纹被关掉之后，这个 bug 就从「看不出来」变成「按下去没反应」。
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = modifier
            .pressFeedback(interaction, enabled = enabled)
            .heightIn(min = 48.dp)
            .fillMaxWidth(),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun TonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = modifier
            .pressFeedback(interaction, enabled = enabled)
            .heightIn(min = 48.dp)
            .fillMaxWidth(),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun OutlinedAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = modifier
            .pressFeedback(interaction, enabled = enabled)
            .heightIn(min = 48.dp)
            .fillMaxWidth(),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * iOS 的**填充式**输入框。
 *
 * ## 为什么不是 OutlinedTextField
 *
 * 描边加浮动标签是 Material 最有辨识度的一件事，也是这个 App 里最「安卓」的一块。iOS 的输入框
 * 是一个没有边框的浅灰填充块：标签老实待在框外上方，框内只有光标和一句占位。浮动标签还带来一个
 * 更实际的问题——标签在「有字/无字」之间会缩小并飘到上沿，那一格的高度因此变两次，而一个会
 * 自己变高的控件在任何列表里都不稳。
 *
 * ## 底色用 onSurface 的透明度而不是一个固定灰
 *
 * 这个控件要活在卡面（#FFFDFB）、窗口底（#FDF8F3）和深色夜纸（#241E1A）上。写死一个浅灰在其中
 * 两个上面会看不见或发脏；叠一层半透明墨色则在任何底色上都得到「比所在面暗一点」的同一读数，
 * 深色主题里自动反向变亮——这正是 iOS 自己那套 systemGray6 的做法。
 *
 * ## 为什么保留一圈焦点描边
 *
 * iOS 靠键盘弹起来说明「这里正在输入」，而 Android 上有外接键盘、手柄和读屏用户，他们看不见
 * 键盘。波纹已经被主题全局关掉，控件的可用状态必须由控件自己说出口，所以焦点态给一圈主色：
 * 它只在键盘焦点时出现，触屏用户点完就开始打字，基本不会看见它。
 */
@Composable
fun InsetField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    val shape = MaterialTheme.shapes.small
    val onSurface = MaterialTheme.colorScheme.onSurface
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val ringAlpha by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = Motion.press,
        label = "insetFieldRing",
    )

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = minLines,
            interactionSource = interaction,
            shape = shape,
            placeholder = placeholder?.let {
                { Text(it, color = onSurface.copy(alpha = PlaceholderAlpha)) }
            },
            leadingIcon = leadingIcon?.let { vector ->
                {
                    Icon(
                        imageVector = vector,
                        // 图标只是重复了占位文字已经说过的「这里搜什么」，读屏不该念两遍。
                        contentDescription = null,
                        tint = onSurface.copy(alpha = PlaceholderAlpha),
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
            // 不写 contentPadding：这个 String 重载没有这一参数，而 M3 会按「有没有标签」
            // 自己选填充——标签在框外，所以它选的就是不带标签那一档，正好是我们要的高度。
            colors = TextFieldDefaults.colors(
                // 焦点不改变底色：iOS 的框按下去不会换色，而 M3 默认的加深会在
                // 「聚焦/失焦」之间做出一次可见的跳动，读起来像控件坏了。
                focusedContainerColor = onSurface.copy(alpha = FieldFillAlpha),
                unfocusedContainerColor = onSurface.copy(alpha = FieldFillAlpha),
                disabledContainerColor = onSurface.copy(alpha = FieldFillAlpha / 2f),
                errorContainerColor = onSurface.copy(alpha = FieldFillAlpha),
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                errorIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = ringAlpha)), shape),
        )
    }
}

/**
 * 输入框底色：叠在任何一个表面上都只暗一档的墨量。
 *
 * 0.055 在模拟器上被证伪过一次——落在纯白对话框上时几乎看不出这是一格可以打字的地方，
 * 用户会往框外点。0.075 是「不描边也认得出是控件」的下限。
 */
private const val FieldFillAlpha = 0.075f

/** 占位文字要能被读作「这里可以写什么」，又不能和真正的内容抢对比度。 */
private const val PlaceholderAlpha = 0.38f

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
            // 三层暖灰阴影，强度取一半：贴纸只有几十 dp，全套距离会让它旁边挂一片灰雾。
            .softShadow(stickerCorner(STICKER_BOX_RADIUS), intensity = 0.5f)
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
                    Modifier.pressable(
                        onClick = { onClick?.invoke() },
                        onLongClick = onLongClick,
                        // 贴纸是小面积元素，缩多一点才读得出「按到了」。
                        pressedScale = Scale.Small,
                    )
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

    Surface(
        shape = CircleShape,
        color = container,
        modifier = modifier
            // 发音是最高频的操作，值得最重的一档缩量：56dp 的圆按下去缩 5.6dp，
            // 指尖不用看也知道「这一下点到了」。
            .pressable(
                onClick = onClick,
                enabled = enabled,
                role = Role.Button,
                pressedScale = Scale.Small,
            )
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