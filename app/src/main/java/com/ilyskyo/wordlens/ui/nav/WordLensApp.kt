// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.nav

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ilyskyo.wordlens.data.model.StudyMaterial
import com.ilyskyo.wordlens.srs.Fsrs
import com.ilyskyo.wordlens.ui.lookback.EntryCard
import com.ilyskyo.wordlens.ui.lookback.LookbackScreen
import com.ilyskyo.wordlens.ui.lookback.LookbackUiState
import com.ilyskyo.wordlens.ui.remember.RememberScreen
import com.ilyskyo.wordlens.ui.remember.RememberUiState

/**
 * 应用骨架。
 *
 * ## 层级
 *
 * 只有两个真正的页面（回看 / 记住），拍照与搜索是**常驻动作**而不是页面。
 *
 * 拍照不进标签组有两个原因：它是就地执行的操作，点完就回到原地，不会「停在拍照页」；
 * 而把它做成页签会让用户期待一个可以回退的界面，实际上没有东西可回退。
 *
 * ## 底部动作只在主页面出现
 *
 * [HomeBottomActions] 的 `visible` 由 [atTopLevel] 控制。进入某条日记的详情或复习会话
 * 之后它会消失：阅读时底部杵着一个「拍照」会把人从正在读的内容里拽走，而那正是他
 * 刚刚特意点进来的东西。
 */
@Composable
fun WordLensApp(
    modifier: Modifier = Modifier,
    requestedTab: HomeTab = HomeTab.LOOKBACK,
    /** 时间轴的滚动状态由宿主外提：详情页是 AnimatedContent 的另一个场景，本场景会被拆掉。 */
    lookbackListState: LazyListState = rememberLazyListState(),
    lookbackState: LookbackUiState = LookbackUiState(),
    rememberState: RememberUiState = RememberUiState(),
    onCapture: () -> Unit = {},
    onSearch: () -> Unit = {},
    onOpenEntry: (String) -> Unit = {},
    onLookbackSpeak: (EntryCard) -> Unit = {},
    onMaterialChange: (StudyMaterial) -> Unit = {},
    onReveal: () -> Unit = {},
    onGrade: (Fsrs.Rating) -> Unit = {},
    onReviewSpeak: () -> Unit = {},
    onMarkMastered: () -> Unit = {},
    onUnmark: (String) -> Unit = {},
) {
    var tab by remember { mutableStateOf(HomeTab.LOOKBACK) }
    // 外部要求换页（小组件点击）时跟随一次。key 是请求值而不是 tab，所以用户自己点页签
    // 不会被这条效果拽回去。
    LaunchedEffect(requestedTab) { tab = requestedTab }

    // 目前只有主页面，没有二级路由；一旦加了详情页，这里改成跟随导航栈深度。
    val atTopLevel = true

    // 悬浮页签不进 Scaffold.topBar（照片要从它下方穿过），代价是避让得自己算。
    // enableEdgeToEdge 之后状态栏真实高度只有 WindowInsets 知道——写死 dp 一定会被
    // 刘海屏和各家 ROM 的状态栏打穿。
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val tabTop = statusBar + TAB_TOP_GAP
    val contentTop = tabTop + TAB_PILL_HEIGHT + TAB_CONTENT_GAP

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // 顶部与底部控件都是浮在内容之上的，Scaffold 自己不消费内边距：
        // 由各页自行决定避让多少，避免「内容被切掉一截」。
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        bottomBar = {
            HomeBottomActions(
                onCapture = onCapture,
                onSearch = onSearch,
                visible = atTopLevel,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding()),
        ) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    // 200ms 淡入淡出，不做位移：这两个页签是平级关系，位移会被读成层级跳转。
                    fadeIn(tween(TAB_MS)) togetherWith fadeOut(tween(TAB_MS))
                },
                label = "homeTab",
                modifier = Modifier.fillMaxSize(),
            ) { current ->
                when (current) {
                    HomeTab.LOOKBACK -> LookbackScreen(
                        topInset = PaddingValues(top = contentTop),
                        bottomInset = PaddingValues(bottom = BOTTOM_INSET),
                        listState = lookbackListState,
                        state = lookbackState,
                        onOpenEntry = onOpenEntry,
                        onSpeak = onLookbackSpeak,
                        modifier = Modifier.fillMaxSize(),
                    )

                    HomeTab.REMEMBER -> RememberScreen(
                        topInset = PaddingValues(top = contentTop),
                        bottomInset = PaddingValues(bottom = BOTTOM_INSET),
                        state = rememberState,
                        onMaterialChange = onMaterialChange,
                        onReveal = onReveal,
                        onGrade = onGrade,
                        onSpeak = onReviewSpeak,
                        onMarkMastered = onMarkMastered,
                        onUnmark = onUnmark,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // 顶部页签浮在内容之上：照片从它下方穿过，而不是被一条横栏切开。
            HomeTopTabs(
                current = tab,
                onSelect = { tab = it },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = tabTop),
            )
        }
    }
}

private const val TAB_MS = 200

/** 页签胶囊距状态栏下沿的间隙。 */
private val TAB_TOP_GAP = 8.dp

/** 页签胶囊自身高度，用于推算内容起始线。 */
private val TAB_PILL_HEIGHT = 52.dp

/** 内容起始线在胶囊之下再留的呼吸距离。 */
private val TAB_CONTENT_GAP = 12.dp

/** 底部动作胶囊大致占的高度，供页面内容避让。 */
private val BOTTOM_INSET = 72.dp