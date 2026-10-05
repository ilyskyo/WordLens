// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.nav

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.BottomCenter
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.ilyskyo.wordlens.R
import com.ilyskyo.wordlens.ui.components.PillOption
import com.ilyskyo.wordlens.ui.components.PillSwitch
import com.ilyskyo.wordlens.ui.icons.WordLensIcons
import com.ilyskyo.wordlens.ui.theme.Haptic
import com.ilyskyo.wordlens.ui.theme.Motion
import com.ilyskyo.wordlens.ui.theme.Scale
import com.ilyskyo.wordlens.ui.theme.Space
import com.ilyskyo.wordlens.ui.theme.pressable
import com.ilyskyo.wordlens.ui.theme.softCircleShadow

/**
 * 一级页面的两个页签。这两个才是顶层。
 *
 * 「拍照」与「搜索」不在这里——它们是常驻动作而不是目的地，把它们塞进标签组会让用户
 * 以为点进去会到一个页面，而它们其实是「就地执行」的操作。
 */
enum class HomeTab(val route: String, @StringRes val labelRes: Int, val icon: ImageVector) {
    LOOKBACK("lookback", R.string.home_tab_lookback, WordLensIcons.Grid),
    REMEMBER("remember", R.string.home_tab_remember, WordLensIcons.StackedCards),
}

/**
 * 顶部页签：一颗悬浮的分段胶囊，选中背景是一块在两段之间滑动的胶囊。
 *
 * ## 为什么不是通栏
 *
 * 通栏会在内容上方压出一条硬边界，把界面切成「内容」和「导航」两块。悬浮胶囊让照片从
 * 标签栏下方穿过——回看页大量是照片，一条横栏穿过一张脸是很糟的观感。
 *
 * ## 选中态为什么不只靠颜色
 *
 * 主色留给了底部的拍照键。页签若也用主色表意，两处会互相削弱。所以选中态的主信号是
 * 「多了一个底」（滑动过来的那一块），颜色只是第二信号。
 *
 * ## 长按
 *
 * 只有「回看」挂了隐藏动作（随机漫步），所以只有它注册长按、也只有它会为长按而震。
 */
@Composable
fun HomeTopTabs(
    current: HomeTab,
    onSelect: (HomeTab) -> Unit,
    onLongPress: (HomeTab) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val tabs = HomeTab.entries
    PillSwitch(
        options = tabs.map { tab ->
            PillOption(
                key = tab.route,
                label = stringResource(tab.labelRes),
                icon = tab.icon,
                hasLongAction = tab == HomeTab.LOOKBACK,
            )
        },
        selectedIndex = tabs.indexOf(current).coerceAtLeast(0),
        onSelect = { index -> onSelect(tabs[index]) },
        onLongPress = { index -> onLongPress(tabs[index]) },
        modifier = modifier,
        height = BAR_HEIGHT,
    )
}

/**
 * 底部常驻动作：拍照在左，搜索在右，两个独立的悬浮圆。
 *
 * 刻意不放在同一个胶囊里。它们是就地执行的操作，不是第四个页签；连成一条会读成「更宽的
 * 导航栏」，而用户的直觉是「底部是导航，两侧是快捷动作」。
 *
 * [visible] 用来在一级页面之外隐藏它们：进入某条日记的详情页时，底部不该还留着「拍照」，
 * 因为那会把用户从「正在读的东西」里拽走。
 *
 * 隐藏是**收起来**而不是消失：缩放 + 淡出用弹簧，方向对称但手感不对称——出现时带一点点
 * 过冲（它落到屏幕上），消失时干脆（离开的东西不该占用注意力）。
 */
@Composable
fun HomeBottomActions(
    onCapture: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(Motion.bouncy, initialScale = 0.85f) + fadeIn(tween(120)),
        exit = scaleOut(Motion.press, targetScale = 0.85f) + fadeOut(tween(90)),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // 悬浮动作贴着屏幕底边，手势条会压在按钮上；让位之后再谈视觉间距。
                .navigationBarsPadding()
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
                    contentDescription = stringResource(R.string.tab_capture),
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary,
                    onClick = onCapture,
                )
                FloatingAction(
                    icon = WordLensIcons.Search,
                    contentDescription = stringResource(R.string.home_search),
                    container = MaterialTheme.colorScheme.surface,
                    content = MaterialTheme.colorScheme.onSurface,
                    // 搜索那颗是白底，阴影要更轻，否则会读成「一块灰色的圆盘」。
                    shadowIntensity = 0.55f,
                    onClick = onSearch,
                )
            }
        }
    }
}

/**
 * 悬浮圆形按钮。
 *
 * 上一版这里有一个 `animateFloatAsState(targetValue = 1f)` 的缩放动画——目标值恒为 1，
 * 也就是说那颗按钮**从来没有按下去的感觉**；当时靠的是 Surface 自带的水波纹，而波纹现在
 * 已经全局关掉了。所以这里显式接上 [pressable]：缩到 0.90 + 一次重触觉。
 */
@Composable
private fun FloatingAction(
    icon: ImageVector,
    contentDescription: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shadowIntensity: Float = 1f,
) {
    Box(
        modifier = modifier
            .size(ACTION_SIZE)
            .softCircleShadow(intensity = shadowIntensity)
            .pressable(
                onClick = onClick,
                pressedScale = Scale.Small,
                haptic = Haptic.Heavy,
            )
            .clearAndSetSemantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(ACTION_ICON),
        )
    }
}

private val BAR_HEIGHT = 48.dp
private val ACTION_SIZE = 56.dp
private val ACTION_ICON = 24.dp
private val ACTION_INSET = 20.dp
