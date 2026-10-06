// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.ilyskyo.wordlens.ui.theme.Motion
import com.ilyskyo.wordlens.ui.theme.Scale
import com.ilyskyo.wordlens.ui.theme.pressable
import com.ilyskyo.wordlens.ui.theme.softShadow

/**
 * 胶囊式分段切换（iOS 的 segmented control）。顶部页签与复习页的素材筛选共用它。
 *
 * ## 为什么选中背景是一个会滑动的独立矩形
 *
 * 每段各自换背景色，读起来是「一灰一亮」的**状态跳变**；把选中背景做成一块在段之间滑动的
 * 胶囊，眼睛能跟着它走，才知道「你刚把选择从左边挪到了右边」。
 *
 * ## 只动画 translationX
 *
 * 指示条的宽度**不参与动画**：所有段等宽（`weight(1f)`），指示条就是一段那么宽，只在 X 上移动。
 * 一旦动画宽度（或者用 scaleX 去拉一个宽度不同的胶囊），圆角会被横向拉扁，滑动过程中它会
 * 像一个被捏变形的椭圆——比不做动画更糟的味道。等宽的另一个副作用是好事：切换时整块控件不抖。
 *
 * 上一版这里是在动画**内边距 Dp**（选中段变宽、未选中段变窄），那是规范红线里明确禁止的
 * layout 动画：每次切换都会重排整行，而快速连点时宽度会来回抖。
 *
 * @param onLongPress 给需要「按住出隐藏菜单」的场景（顶部页签的随机漫步）。
 *   传 null 就完全不注册长按，也就不会为长按而震。
 */
@Composable
fun PillSwitch(
    options: List<PillOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    onLongPress: ((Int) -> Unit)? = null,
) {
    if (options.isEmpty()) return

    // 段宽只有测到真实值才有意义：首帧还没测量时不画指示条，否则会在 (0,0) 闪一块胶囊。
    var segmentWidth by remember { mutableFloatStateOf(0f) }
    val index = selectedIndex.coerceIn(0, options.lastIndex)
    // 测量给的是像素，宽度约束要 Dp。写死 1f 当 density 会让指示条在 3x 屏上只有实际宽度的
    // 三分之一——这是「看起来像 bug」最经典的一种。
    val density = LocalDensity.current

    Box(
        modifier = modifier
            // 是**下限**而不是定值：系统字号放大时轨道要自己长高，否则段的文字会被裁掉半行。
            // 写成 height() 的话调用方给的 48dp 就成了硬上限，放大字号恰好是最需要它看得清的时候。
            .heightIn(min = height)
            // 这一行是修一次整屏事故加的：只有下限的 Box 会把父级给的最大高度原样传下去，
            // 而里面的 Row 是 fillMaxSize()、段是 fillMaxHeight()——「填满」在没有上限的
            // 约束里等于填到屏幕底。于是两颗页签长成了两块盖住全部内容的大胶囊。
            // IntrinsicSize.Min 让轨道的高度由**内容**定（文字行高 + 内边距），
            // 上面那条下限仍然兜住 48dp，两件事同时成立。
            .height(IntrinsicSize.Min)
            .softShadow(CircleShape, intensity = 0.35f)
            .background(MaterialTheme.colorScheme.surface, CircleShape)
            .padding(TRACK_INSET),
    ) {
        val translation by animateFloatAsState(
            targetValue = segmentWidth * index,
            animationSpec = Motion.snappy,
            label = "pillIndicator",
        )
        if (segmentWidth > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(with(density) { segmentWidth.toDp() })
                    .graphicsLayer { translationX = translation }
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { segmentWidth = it.width.toFloat() / options.size },
        ) {
            options.forEachIndexed { position, option ->
                PillSegment(
                    option = option,
                    selected = position == index,
                    onClick = { onSelect(position) },
                    // 只有真的挂了隐藏动作的段才注册长按。每一段都注册会得到「按下去震一下、
                    // 然后什么也不发生」——而触觉是在承诺这里有动作，空口承诺比不承诺更伤信任。
                    onLongClick = if (option.hasLongAction) {
                        onLongPress?.let { handler -> { handler(position) } }
                    } else {
                        null
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

/** 一个分段的内容：图标 + 文字。选中色走 animateColor——颜色过渡本来就该用补间而不是弹簧。 */
@Composable
private fun PillSegment(
    option: PillOption,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val content by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        // 颜色过渡是那条「位移一律走弹簧」规则的例外之一：弹簧插值颜色会在中途得到
        // 一个既不前也不后的中间色，而这里要的只是「亮起来」。
        animationSpec = tween(durationMillis = COLOR_MS, easing = Motion.enterEase),
        label = "pillContentColor",
    )
    Row(
        modifier = modifier
            .pressable(
                onClick = onClick,
                onLongClick = onLongClick,
                role = Role.Tab,
                pressedScale = Scale.Large,
            )
            .padding(horizontal = SEGMENT_H_PADDING, vertical = SEGMENT_V_PADDING)
            // 合并语义：读屏念一次「回看，标签页，未选中」，而不是把图标和文字分开念两遍。
            // 这件事必须在控件内部做——调用方重写语义会连带把 role 弄丢，而选中状态是
            // 分段控件唯一的信息来源（颜色不是）。
            .clearAndSetSemantics {
                contentDescription = option.label
                this.selected = selected
                this.role = Role.Tab
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        option.icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = content,
                modifier = Modifier.width(ICON_SIZE),
            )
        }
        if (option.label.isNotEmpty()) {
            // 有图标时补一个间隙：Icon 自带的宽度不含文字前距，贴在一起会读成一个字。
            if (option.icon != null) Box(modifier = Modifier.width(ICON_GAP))
            Text(
                text = option.label,
                style = MaterialTheme.typography.labelLarge,
                color = content,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                // `fill = false` 是关键。上一版这里是 `fillMaxWidth()`：它把整段的宽度全部
                // 吃掉，于是外层 Row 的 `Arrangement.Center` 无从生效，而 Text 自己默认左对齐
                // ——三个标签就这样齐齐贴在每一段的左边，看着像没排版。
                // weight(1f, fill = false) 给的是「最多可以用到这些」而不是「必须占满」：
                // 短标签按自身宽度被居中，长标签（德语式的世界杯、四语里最长的那一条）仍然
                // 被限制在段内并走 ellipsis，不会把相邻段顶开。
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/**
 * 一个可选项。
 *
 * [key] 是给调用方做状态与测试断言的稳定标识；label 会随语言变化，永远不要拿它当 id。
 * [hasLongAction] 决定这一段的长按是否注册——只有真的挂了隐藏动作的段才该震。
 */
data class PillOption(
    val key: String,
    val label: String,
    val icon: ImageVector? = null,
    val hasLongAction: Boolean = false,
)

// ── 这一个控件的全部几何。放在这里而不是全局 Space，因为它们是分段控件的内部形状：
//    提到全局刻度会让「Space.md = 16」这种语义变模糊，散到调用点则会漂出十几种胶囊。 ──

/** 轨道内边距：胶囊与外壳之间要露出一圈纸白，否则选中块会顶到边。 */
private val TRACK_INSET = 4.dp

/** 分段文字左右留白。英文标签最长的一条靠它撑住，不至于被 ellipsis 截断。 */
private val SEGMENT_H_PADDING = 10.dp
private val SEGMENT_V_PADDING = 8.dp
private val ICON_SIZE = 20.dp
private val ICON_GAP = 6.dp

/** 颜色过渡时长。200ms 是「跟得上页签滑动」与「读起来像淡入」的交点。 */
private const val COLOR_MS = 200
