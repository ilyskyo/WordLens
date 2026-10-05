// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.BottomCenter
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.ilyskyo.wordlens.ui.icons.WordLensIcons
import com.ilyskyo.wordlens.ui.theme.Space
import com.ilyskyo.wordlens.ui.theme.WordLensTheme

/**
 * 一级页面的两个页签。这两个才是顶层。
 *
 * 「拍照」与「搜索」不在这里——它们是常驻动作而不是目的地，把它们塞进标签组会让用户
 * 以为点进去会到一个页面，而它们其实是「就地执行」的操作。
 */
enum class HomeTab(val route: String, val label: String, val icon: ImageVector) {
    LOOKBACK("lookback", "回看", WordLensIcons.Grid),
    REMEMBER("remember", "记住", WordLensIcons.StackedCards),
}

/**
 * 顶部页签：一个内缩的圆角胶囊，内含两个页签，选中项有自己的浅色指示胶囊。
 *
 * ## 为什么不是通栏
 *
 * 通栏会在内容上方压出一条硬边界，把界面切成"内容"和"导航"两块。悬浮胶囊让照片从
 * 标签栏下方穿过——回看页大量是照片，一条横栏穿过一张脸是很糟的观感。
 *
 * ## 选中态为什么不只靠颜色
 *
 * 主色留给了底部的拍照键。如果页签也用主色表意，两处会互相削弱。所以选中态的主信号是
 * 「多了一个底」，颜色只作为第二信号，颜色不是唯一的信息渠道。
 */
@Composable
fun HomeTopTabs(
    current: HomeTab,
    onSelect: (HomeTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = BAR_ELEVATION,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(CAPSULE_PADDING),
            horizontalArrangement = Arrangement.spacedBy(CAPSULE_PADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HomeTab.entries.forEach { tab ->
                TabItem(
                    tab = tab,
                    selected = tab == current,
                    onClick = { onSelect(tab) },
                )
            }
        }
    }
}

@Composable
private fun TabItem(
    tab: HomeTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val indicator by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "indicator",
    )
    val content by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "label",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.06f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "iconScale",
    )
    // 指示胶囊的横向内边距跟着选中态变化：切换时胶囊是「长大」的，不是一层淡入。
    val indicatorWidth by animateDpAsState(
        targetValue = if (selected) INDICATOR_SELECTED_PAD else INDICATOR_UNSELECTED_PAD,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "indicatorWidth",
    )

    Row(
        modifier = modifier
            // 88dp 下限保证两字标签有足够的触摸面积与视觉平衡。
            .widthIn(min = ITEM_MIN_WIDTH)
            .clip(CircleShape)
            .background(indicator)
            .padding(horizontal = indicatorWidth, vertical = Space.sm)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            // 合并语义：读屏念一次「记住，标签页，已选中」，而不是把图标与文字分开念。
            .clearAndSetSemantics {
                contentDescription = tab.label
                this.selected = selected
                this.role = Role.Tab
            },
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier
                .size(22.dp)
                .scale(iconScale),
        )
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            maxLines = 1,
        )
    }
}

/**
 * 底部常驻动作：拍照在左，搜索在右，两个独立的悬浮圆。
 *
 * 刻意不放在同一个胶囊里。它们是就地执行的操作，不是第四个页签；连成一条会读成「更宽的
 * 导航栏」，而用户的直觉是「底部是导航，中间/两侧是快捷动作」。
 *
 * [visible] 用来在一级页面之外隐藏它们：进入某条日记的详情页时，底部不该还留着「拍照」，
 * 因为那会把用户从「正在读的东西」里拽走。
 */
@Composable
fun HomeBottomActions(
    onCapture: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    if (!visible) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ACTION_INSET, vertical = Space.sm)
            .padding(bottom = Space.sm),
        contentAlignment = BottomCenter,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FloatingAction(
                icon = WordLensIcons.Camera,
                contentDescription = "拍照",
                container = MaterialTheme.colorScheme.primary,
                content = MaterialTheme.colorScheme.onPrimary,
                onClick = onCapture,
            )
            FloatingAction(
                icon = WordLensIcons.Search,
                contentDescription = "搜索",
                container = MaterialTheme.colorScheme.surface,
                content = MaterialTheme.colorScheme.onSurface,
                onClick = onSearch,
            )
        }
    }
}

/**
 * 悬浮圆形按钮。
 *
 * 用 [Surface] 的 onClick 版本而不是 Box + clickable：它自带 pressed 状态，
 * 无障碍语义与水波纹都更完整，不需要额外接线。
 */
@Composable
private fun FloatingAction(
    icon: ImageVector,
    contentDescription: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "action",
    )
    val accents = WordLensTheme.accents

    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = container,
        shadowElevation = BAR_ELEVATION,
        modifier = modifier
            .size(ACTION_SIZE)
            .scale(scale)
            .clearAndSetSemantics { this.contentDescription = contentDescription },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(ACTION_ICON),
            )
        }
    }
}

private val BAR_ELEVATION = 6.dp
private val ACTION_SIZE = 56.dp
private val ACTION_ICON = 24.dp
private val ACTION_INSET = 20.dp
private val CAPSULE_PADDING = 6.dp
private val INDICATOR_SELECTED_PAD = 14.dp
private val INDICATOR_UNSELECTED_PAD = 10.dp
private val ITEM_MIN_WIDTH = 88.dp