// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ilyskyo.wordlens.ui.theme.DialogShape
import com.ilyskyo.wordlens.ui.theme.Motion
import com.ilyskyo.wordlens.ui.theme.Space
import com.ilyskyo.wordlens.ui.theme.pressable
import com.ilyskyo.wordlens.ui.theme.softShadow

/**
 * iOS 的 alert。
 *
 * ## 为什么不用 Material 的 AlertDialog
 *
 * 差别不在圆角，在**动作的排布**。Material 把动作排成右下角一小排文字按钮，靠容器高度撑起
 * 点击区域；iOS 把它们排成**整宽、竖叠、由一根发丝线隔开**的一列。右下角那排文字在奶油底上
 * 读起来像网页的链接，而不像「这是两条出路」——而确认框唯一的工作就是把两条出路摆清楚。
 * 另外它的标题左对齐、正文左对齐，iOS 两者都居中；居中这一件事对「系统在问我话」的读感贡献最大。
 *
 * ## 为什么最下面那颗字更粗
 *
 * 这不是随手加的层级：iOS 加粗的是**默认可以从这里离开**的那一颗（通常是 Cancel 或唯一的
 * 「好」）。粗体的含义是「按下去你不会失去任何东西」，而不是「这是主操作」。所以删除确认里
 * 红色那颗不加重、下面的取消加重——和 iOS 完全一致。反过来说，把主操作做成实心大按钮会把
 * 这个语义盖掉，而确认框恰恰最不该让用户「顺手按下最亮的那颗」。
 *
 * ## 竖排而不是横排
 *
 * iOS 在两个短标签时用横排，标签长或超过两个时改竖排。这里恒定竖排，因为文案来自四个语言包：
 * 「保存」在英文里是 Save，在韩文里可能被挤成两行，横排的半宽对不可预知的长度没有把握。
 * 一根发丝线加一颗按不满的按钮，好过一处被裁掉的动词。
 *
 * @param destructive 主操作会**毁掉**东西。红色、不加粗——颜色已经足够说明它是谁。
 * @param content 正文下面的一块（输入框、选项……）。它和标题正文一起滚动，动作行不跟着滚：
 *   内容再长，出路也得一直在眼前，否则用户滚走了就找不到「取消」。
 */
@Composable
fun WordLensDialog(
    title: String,
    onDismiss: () -> Unit,
    primaryText: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
    destructive: Boolean = false,
    content: (@Composable () -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = DialogShape,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            // 阴影由 [softShadow] 画：Surface 自己的 elevation 会在卡片外再糊一层系统灰，
            // 两套阴影叠在一起的边缘比任何一套都脏。
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
            modifier = modifier
                .padding(horizontal = Space.lg)
                .widthIn(max = DialogWidth)
                .fillMaxWidth()
                .softShadow(DialogShape, intensity = 1f),
        ) {
            Column {
                Column(
                    modifier = Modifier
                        // 上限而不是死值：内容短时对话框就该矮，写死高度会在下面留出一片
                        // 谁也不认识的空白。
                        .heightIn(max = ContentMaxHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(
                            start = Space.md + Space.xs,
                            end = Space.md + Space.xs,
                            top = Space.lg,
                            bottom = Space.md,
                        ),
                    verticalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (message != null) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            // iOS 的正文与标题同色，只靠字号分层；用 secondary 会让一句
                            // 要紧的话读起来像备注。
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    content?.invoke()
                }

                Hairline()
                DialogAction(
                    text = primaryText,
                    onClick = onPrimary,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    emphasized = secondaryText == null,
                )
                if (secondaryText != null && onSecondary != null) {
                    Hairline()
                    DialogAction(
                        text = secondaryText,
                        onClick = onSecondary,
                        color = MaterialTheme.colorScheme.primary,
                        emphasized = true,
                    )
                }
            }
        }
    }
}

/**
 * 发丝线。
 *
 * 0.7dp 而不是 1dp：密度 3 的屏上 1dp 是三个物理像素，在超椭圆的边缘里会明显发暗，
 * 读成一条描边而不是一条分隔。iOS 的分隔线本来就比 1px 更轻。
 */
@Composable
private fun Hairline(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(HairlineHeight)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/** 一行整宽的动作。按下时整行沉下去一点灰，这是 iOS 的反馈方式——不是波纹，也不是缩放。 */
@Composable
private fun DialogAction(
    text: String,
    onClick: () -> Unit,
    color: Color,
    emphasized: Boolean,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val wash by animateFloatAsState(
        targetValue = if (pressed) PressedWash else 0f,
        animationSpec = Motion.press,
        label = "dialogActionWash",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = wash))
            // 不缩放：一整行跟着手指抖一下会让整个对话框晃，而对话框里正放着用户还没打完的字。
            // 缩放在这里被关掉，触觉与「没有波纹」仍由 pressable 统一负责。
            .pressable(
                onClick = onClick,
                role = Role.Button,
                pressedScale = 1f,
                interactionSource = interaction,
            )
            .height(ActionHeight),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = color,
            textAlign = TextAlign.Center,
        )
    }
}

/** iOS 的 alert 是 270pt 定宽；在 Android 上给一个上限，窄屏靠 padding 留出边距。 */
private val DialogWidth = 320.dp

/** 正文区的高度上限。超过这个高度说明内容该换成一个页面，而不是一格对话框。 */
private val ContentMaxHeight = 420.dp

private val ActionHeight = 52.dp
private val HairlineHeight = 0.7.dp

/** 按下去的灰度。0.09 在奶油底上刚好能被读成「这一行被按住了」，又不至于像被禁用。 */
private const val PressedWash = 0.09f
