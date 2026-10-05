// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ilyskyo.wordlens.core.AppContainer
import com.ilyskyo.wordlens.ui.capture.CaptureCamera
import com.ilyskyo.wordlens.ui.capture.CaptureScreen
import com.ilyskyo.wordlens.ui.capture.CaptureViewModel
import com.ilyskyo.wordlens.ui.capture.captureViewModelFactory
import com.ilyskyo.wordlens.ui.lookback.EntryDetailScreen
import com.ilyskyo.wordlens.ui.lookback.EntryDetailState
import com.ilyskyo.wordlens.ui.lookback.LookbackUiState
import com.ilyskyo.wordlens.ui.lookback.TimelineSelection
import com.ilyskyo.wordlens.ui.nav.HomeTab
import com.ilyskyo.wordlens.ui.nav.HomeViewModel
import com.ilyskyo.wordlens.ui.nav.LocalPageVisibilityScope
import com.ilyskyo.wordlens.ui.nav.LocalSharedTransitionScope
import com.ilyskyo.wordlens.ui.nav.Page
import com.ilyskyo.wordlens.ui.nav.WordLensApp
import com.ilyskyo.wordlens.ui.nav.depth
import com.ilyskyo.wordlens.ui.remember.RememberUiState
import com.ilyskyo.wordlens.ui.search.SearchScreen
import com.ilyskyo.wordlens.ui.search.SearchViewModel
import com.ilyskyo.wordlens.ui.settings.SettingsScreen
import com.ilyskyo.wordlens.ui.settings.SettingsViewModel
import com.ilyskyo.wordlens.ui.theme.WordLensTheme

class MainActivity : ComponentActivity() {

    /**
     * 小组件点击带来的「要去复习」。
     *
     * 冷启动读 `onCreate` 的 intent，热启动由 `onNewIntent` 更新——两条路径都要接，否则
     * 「点完一张卡回到桌面再点小组件」会停在原来那一页。
     */
    private val requestedTab = mutableStateOf(HomeTab.LOOKBACK)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        applyIntent(intent)
        val container = (application as WordLensApplication).container
        setContent {
            WordLensTheme {
                WordLensRoot(container, requestedTab)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyIntent(intent)
    }

    private fun applyIntent(intent: Intent) {
        requestedTab.value = if (intent.getBooleanExtra(EXTRA_OPEN_REMEMBER, false)) {
            HomeTab.REMEMBER
        } else {
            HomeTab.LOOKBACK
        }
    }

    companion object {
        /** 小组件点击：直接落到「记住」页。 */
        const val EXTRA_OPEN_REMEMBER = "com.ilyskyo.wordlens.OPEN_REMEMBER"
    }
}

/**
 * 单 Activity 的页面栈。
 *
 * 没有 NavHost：主页、详情、取景、搜索、设置都在同一棵组合树里，由一个栈管理。这既符合
 * 「拍照是就地动作而不是导航目的地」的产品设定，也是共享元素转场的前提——照片的两端必须
 * 同时存在于同一个 [SharedTransitionLayout] 之下。NavHost 的片段式生命周期让这件事变脆，
 * 多场景同时组合也更容易在低端机上掉帧。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun WordLensRoot(container: AppContainer, requestedTab: MutableState<HomeTab>) {
    val stack = remember { mutableStateListOf<Page>(Page.Home) }
    val top = stack.lastOrNull() ?: Page.Home
    val home: HomeViewModel = viewModel(factory = HomeViewModel.factory(container))
    val lookback by home.lookback.collectAsStateWithLifecycle()
    val rememberState by home.remember.collectAsStateWithLifecycle()
    val selectedIds by home.selected.collectAsStateWithLifecycle()

    // 时间轴的滚动状态提在这里：详情页属于另一个场景，本场景在转场结束后会被拆掉。
    // 状态留在 LookbackScreen 内部的话，返回时列表会跳回顶部——用户刚看的那条瞬间消失了。
    val lookbackListState = rememberLazyListState()

    applyLightStatusBar(darkSurface = top == Page.Capture)

    // 返回键的优先级：先退出多选，再弹页面栈。多选是一种「模式」而不是一个页面，
    // 但用户按返回时的意图是一样的——先回到没有模式的状态。
    BackHandler(enabled = stack.size > 1 || selectedIds.isNotEmpty()) {
        if (selectedIds.isNotEmpty() && top is Page.Home) {
            home.onClearSelection()
        } else {
            pop(stack, home)
        }
    }

    SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
            AnimatedContent(
                targetState = top,
                transitionSpec = { pageTransition(from = initialState.depth, to = targetState.depth) },
                contentKey = { it },
                label = "page",
            ) { page ->
                CompositionLocalProvider(LocalPageVisibilityScope provides this) {
                    when (page) {
                        Page.Home -> HomeScene(
                            home = home,
                            lookback = lookback,
                            rememberState = rememberState,
                            requestedTab = requestedTab.value,
                            lookbackListState = lookbackListState,
                            selectedIds = selectedIds,
                            onLongPressEntry = home::onLongPressEntry,
                            onSelectAllEntries = home::onSelectAllEntries,
                            onClearSelection = home::onClearSelection,
                            onDeleteSelected = home::onDeleteSelected,
                            onCapture = { stack.add(Page.Capture) },
                            onSearch = { stack.add(Page.Search) },
                            onOpenEntry = { id -> openEntry(stack, home, id) },
                        )

                        is Page.Detail -> DetailScene(home = home, onBack = { pop(stack, home) })

                        Page.Capture -> CaptureHost(
                            container = container,
                            onDismiss = { pop(stack, home) },
                            onOpenSettings = { stack.add(Page.Settings) },
                        )

                        Page.Search -> SearchHost(
                            container = container,
                            onClose = { pop(stack, home) },
                            onOpenEntry = { id -> openEntry(stack, home, id) },
                        )

                        Page.Settings -> SettingsHost(
                            container = container,
                            onClose = { pop(stack, home) },
                        )
                    }
                }
            }
        }
    }
}

private fun openEntry(stack: SnapshotStateList<Page>, home: HomeViewModel, id: String) {
    home.onOpenEntry(id)
    stack.add(Page.Detail(id))
}

private fun pop(stack: SnapshotStateList<Page>, home: HomeViewModel) {
    if (stack.lastOrNull() is Page.Detail) home.onCloseEntry()
    if (stack.size > 1) stack.removeAt(stack.lastIndex)
}

/**
 * 场景之间的过渡。
 *
 * 非共享的内容只做淡入淡出，不做位移——照片已经承担了全部运动，整页再滑一次就是两个互相
 * 竞争的动作，读起来像卡顿而不是流畅。进入比退出略长：用户对「出现」比对「消失」更敏感。
 */
private fun pageTransition(from: Int, to: Int): ContentTransform {
    val forward = to > from
    return ContentTransform(
        targetContentEnter = fadeIn(tween(if (forward) ENTER_MS else EXIT_MS)),
        initialContentExit = fadeOut(tween(if (forward) EXIT_MS else ENTER_MS)),
        // 进入中的场景画在旧场景之上：详情页要盖住时间轴，而不是被它压住。
        targetContentZIndex = if (forward) 1f else 0f,
    )
}

private const val ENTER_MS = 220
private const val EXIT_MS = 160

/**
 * 取景页是暗画面，状态栏图标要翻成浅色；其余页面是奶油白底，保持深色图标。
 *
 * 只在页面切换时改一次：`isAppearanceLightStatusBars` 每次赋值都会走一次系统窗口属性写入。
 */
@Composable
private fun applyLightStatusBar(darkSurface: Boolean) {
    val window = (LocalContext.current as? ComponentActivity)?.window ?: return
    val view = LocalView.current
    LaunchedEffect(darkSurface) {
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkSurface
    }
}

@Composable
private fun HomeScene(
    home: HomeViewModel,
    lookback: LookbackUiState,
    rememberState: RememberUiState,
    requestedTab: HomeTab,
    lookbackListState: LazyListState,
    selectedIds: Set<String>,
    onLongPressEntry: (String) -> Unit,
    onSelectAllEntries: () -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onCapture: () -> Unit,
    onSearch: () -> Unit,
    onOpenEntry: (String) -> Unit,
) {
    WordLensApp(
        modifier = Modifier.fillMaxSize(),
        requestedTab = requestedTab,
        lookbackListState = lookbackListState,
        lookbackState = lookback,
        selection = TimelineSelection(
            selection = selectedIds,
            onLongPress = onLongPressEntry,
            onSelectAll = onSelectAllEntries,
            onClear = onClearSelection,
            onDelete = onDeleteSelected,
        ),
        rememberState = rememberState,
        onMaterialChange = home::onMaterialChange,
        onReveal = home::onReveal,
        onGrade = home::onGrade,
        onReviewSpeak = home::onSpeak,
        onMarkMastered = home::onMarkMastered,
        onUnmark = home::onUnmark,
        onLookbackSpeak = home::onEntrySpeak,
        onOpenEntry = onOpenEntry,
        onCapture = onCapture,
        onSearch = onSearch,
    )
}

/**
 * 详情页场景。
 *
 * 返回时 VM 里的 detail 会先变 null，而退出动画还要画最后一帧——所以这里留一份最后一次
 * 非空的快照给退场用。不这么做的话照片会在按返回的瞬间消失，共享元素也就没有回程终点。
 */
@Composable
private fun DetailScene(home: HomeViewModel, onBack: () -> Unit) {
    val live by home.detail.collectAsStateWithLifecycle()
    var shown by remember { mutableStateOf<EntryDetailState?>(null) }
    LaunchedEffect(live) {
        if (live != null) shown = live
    }
    val state = shown ?: return
    EntryDetailScreen(
        state = state,
        onBack = onBack,
        onDraftChange = home::onEventDraftChange,
        onSaveEvent = home::onSaveEvent,
        // 删完要离开这一页：条目已经不在了，留在详情页等于看着一条空记录。
        onDelete = {
            home.onDeleteEntries(setOf(state.entry.id))
            onBack()
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * 搜索 / 添加页宿主。
 *
 * VM 独立于 HomeViewModel：输入框里那几个字应该在离开时清空，不该跟着主页活一辈子。
 */
@Composable
private fun SearchHost(container: AppContainer, onClose: () -> Unit, onOpenEntry: (String) -> Unit) {
    val vm: SearchViewModel = viewModel(factory = SearchViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    SearchScreen(
        state = state,
        onQueryChange = vm::onQueryChange,
        onClose = onClose,
        onCollect = vm::onCollect,
        onSpeak = vm::onSpeak,
        onOpenEntry = onOpenEntry,
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * 取景页宿主。
 *
 * ViewModel 不挂 key：每次进入都想要一台干净的相机与一份新的暂存状态，回到主页后再进来
 * 不应残留上一次的贴纸。
 */
@Composable
private fun CaptureHost(
    container: AppContainer,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm: CaptureViewModel = viewModel(factory = captureViewModelFactory(container))
    val state by vm.ui.collectAsStateWithLifecycle()
    val event by vm.event.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(event) {
        when (val e = event) {
            is CaptureViewModel.Event.Saved -> {
                vm.acknowledgeEvent()
                onDismiss()
            }

            is CaptureViewModel.Event.Failed -> {
                vm.acknowledgeEvent()
                Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
            }

            // 抠图失败不影响保存，只说明一句：贴纸是这份记录的加分项，不是必要条件。
            is CaptureViewModel.Event.StickerFailed -> {
                vm.acknowledgeEvent()
                Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
            }

            null -> Unit
        }
    }

    CaptureScreen(
        // 快门是这页唯一必须够得着的控件：手势条压在它上面就按不到了。
        bottomInset = PaddingValues(
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        ),
        state = state,
        // 左上角的关闭键：取景页是盖在主页上的一层，不接就等于把用户关在里面。
        onClose = onDismiss,
        onOpenSettings = onOpenSettings,
        onShutter = vm::onShutter,
        onRetake = vm::onRetake,
        onSave = vm::onSave,
        onSpeak = vm::onSpeak,
        onTapSubject = vm::onTapSubject,
        onChipSelect = vm::onChipSelect,
        modifier = Modifier.fillMaxSize(),
        previewContent = { CaptureCamera(vm) },
    )
}

/** 设置页宿主。从取景页打开时它压在取景页之上。 */
@Composable
private fun SettingsHost(container: AppContainer, onClose: () -> Unit) {
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        onClose = onClose,
        onTargetLanguage = vm::onTargetLanguage,
        onNativeLanguage = vm::onNativeLanguage,
        onDirection = vm::onDirection,
        onCloudEnabled = vm::onCloudEnabled,
        onCloudModel = vm::onCloudModel,
        onCloudApiKey = vm::onCloudApiKey,
        onClearCloudApiKey = vm::onClearCloudApiKey,
        modifier = Modifier.fillMaxSize(),
    )
}
