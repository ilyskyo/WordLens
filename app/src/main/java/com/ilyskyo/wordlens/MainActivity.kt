// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.ilyskyo.wordlens.ui.components.Notice
import com.ilyskyo.wordlens.ui.components.NoticeHost
import java.util.concurrent.atomic.AtomicLong
import com.ilyskyo.wordlens.ui.lookback.EntryDetailScreen
import com.ilyskyo.wordlens.ui.lookback.EntryDetailState
import com.ilyskyo.wordlens.ui.lookback.LookbackUiState
import com.ilyskyo.wordlens.ui.lookback.TimelineSelection
import com.ilyskyo.wordlens.ui.lookback.VoiceMemoActions
import com.ilyskyo.wordlens.ui.nav.HomeTab
import com.ilyskyo.wordlens.ui.nav.HomeViewModel
import com.ilyskyo.wordlens.ui.nav.LocalPageVisibilityScope
import com.ilyskyo.wordlens.ui.nav.LocalSharedTransitionScope
import com.ilyskyo.wordlens.ui.nav.Page
import com.ilyskyo.wordlens.ui.nav.PageStack
import com.ilyskyo.wordlens.ui.nav.TabRequest
import com.ilyskyo.wordlens.ui.nav.WordLensApp
import com.ilyskyo.wordlens.ui.nav.depth
import com.ilyskyo.wordlens.ui.remember.RememberUiState
import com.ilyskyo.wordlens.ui.search.SearchScreen
import com.ilyskyo.wordlens.ui.search.SearchViewModel
import com.ilyskyo.wordlens.ui.settings.SettingsScreen
import com.ilyskyo.wordlens.ui.settings.SettingsViewModel
import com.ilyskyo.wordlens.ui.theme.WordLensTheme
import com.ilyskyo.wordlens.ui.theme.hues

class MainActivity : ComponentActivity() {

    /**
     * 小组件点击带来的「要去复习」。
     *
     * 冷启动读 `onCreate` 的 intent，热启动由 `onNewIntent` 更新——两条路径都要接，否则
     * 「点完一张卡回到桌面再点小组件」会停在原来那一页。
     *
     * 它是**一次请求**而不是一个状态：请求里带序号，所以同一条路径连着点两次会是真的两次
     * 请求。原来存的是 `HomeTab` 本身，而第二次点小组件时那个值并没有变（已经是 REMEMBER），
     * 于是没有任何东西重算，界面一动不动——用户学到的结论是「这个小组件只有第一次有用」。
     */
    private val tabRequest = mutableStateOf(TabRequest())

    private var tabSequence = 0

    /**
     * 别的 App 分享进来的文本或图片。
     *
     * 和页签请求同一个理由：这些是**一次次的动作**，不是一个当前值。连着分享两次同一段文字
     * 或同一张图，第二次必须真的再发生一次；存成 String?/Uri? 会被相同值合流掉，
     * 于是「第二次分享没反应」——而 manifest 早就声明了这两个 intent-filter，
     * 用户是从系统的分享菜单里进来的，那里没有第二次机会解释为什么没反应。
     */
    private var shareSequence = 0
    private val sharedText = mutableStateOf(SharedQuery())
    private val sharedImage = mutableStateOf(SharedImage())

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        applyIntent(intent)
        applyShares(intent)
        val container = (application as WordLensApplication).container
        setContent {
            // 色系必须在第一帧就是用户选的那一套：让按钮先闪一下默认色系，
            // 比色系本身好不好看更值得在意。
            val palette by container.ratingPalette.collectAsStateWithLifecycle()
            WordLensTheme(ratingScheme = palette.hues()) {
                WordLensRoot(container, tabRequest, sharedText, sharedImage)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent)
        applyShares(intent)
    }

    private fun applyIntent(intent: Intent) {
        // 没有这个 extra 时**不发请求**：转屏会重建 Activity 并重新走一遍 onCreate 与
        // onNewIntent，若那时也发一条「去复习」，用户现在停在哪一页就被夺走，
        // rememberSaveable 把页签保住的力气全白费。
        if (!intent.getBooleanExtra(EXTRA_OPEN_REMEMBER, false)) return
        // 吃一次就抹掉：extra 跟着 intent 活，而 intent 比这次点击活得久。
        intent.removeExtra(EXTRA_OPEN_REMEMBER)
        tabSequence += 1
        tabRequest.value = TabRequest(HomeTab.REMEMBER, tabSequence)
        return
    }

    /**
     * manifest 里那两个分享入口真正的接收端。
     *
     * 之前它们只是**声明着**：在别的 App 里选中一段文字，分享菜单里有「见词」，点了之后
     * 应用打开、什么都没有发生。承诺了没做比不承诺更糟，因为它教会用户「这里的菜单不作数」，
     * 而这一次他连搜索页都不会打开。
     */
    private fun applyShares(intent: Intent) {
        val text = SearchViewModel.queryFromShared(
            intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT)
                ?: intent.getStringExtra(Intent.EXTRA_TEXT),
        )
        if (text != null) {
            shareSequence += 1
            sharedText.value = SharedQuery(text, shareSequence)
        }
        val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        // 只接图片：filter 声明的就是 image/*，而分享里挂着的类型由发送方说了算，
        // 非图片的流交给导入那条路只会在解码那一步失败——在这里挡掉比在解码器里挡掉诚实。
        if (stream != null && intent.type?.startsWith("image/") == true) {
            shareSequence += 1
            sharedImage.value = SharedImage(stream, shareSequence)
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
private fun WordLensRoot(
    container: AppContainer,
    tabRequest: MutableState<TabRequest>,
    sharedText: MutableState<SharedQuery>,
    sharedImage: MutableState<SharedImage>,
) {
    // 转屏不许弄丢「你在哪一页」。栈编码成字符串存进 savedState，理由写在 PageStack 的注释里；
    // 这里用不可变的 List 而不是 SnapshotStateList，是因为后者接不进 rememberSaveable 的 Saver。
    var stack by rememberSaveable(stateSaver = PageStack.saver) { mutableStateOf(PageStack.initial) }
    val top = stack.lastOrNull() ?: Page.Home
    val home: HomeViewModel = viewModel(factory = HomeViewModel.factory(container))
    val lookback by home.lookback.collectAsStateWithLifecycle()
    val rememberState by home.remember.collectAsStateWithLifecycle()
    val selectedIds by home.selected.collectAsStateWithLifecycle()
    val homeNotice by home.notice.collectAsStateWithLifecycle()

    // 时间轴的滚动状态提在这里：详情页属于另一个场景，本场景在转场结束后会被拆掉。
    // 状态留在 LookbackScreen 内部的话，返回时列表会跳回顶部——用户刚看的那条瞬间消失了。
    val lookbackListState = rememberLazyListState()

    // 进程被杀再回来：savedState 里的 Detail 恢复了，而 ViewModel 是全新的，selectedEntryId 是空的。
    // 不补这一步，用户看到的是自己离开时那一页的壳子，里面什么都没有。
    LaunchedEffect(top) {
        val detail = top as? Page.Detail ?: return@LaunchedEffect
        home.ensureEntryShown(detail.entryId)
    }

    // 从分享菜单进来：直接把对应页面打开，而不是让用户自己找第二次。
    // 已经在页面上时不再压一层——同一个页面在栈里出现两次，返回键就要多按一次才能出来。
    LaunchedEffect(sharedText.value) {
        if (sharedText.value.query != null && top != Page.Search) {
            stack = PageStack.push(stack, Page.Search)
        }
    }
    LaunchedEffect(sharedImage.value) {
        if (sharedImage.value.uri != null && top != Page.Capture) {
            stack = PageStack.push(stack, Page.Capture)
        }
    }

    applyLightStatusBar(darkSurface = top == Page.Capture)

    // 返回键的优先级：先退出多选，再弹页面栈。多选是一种「模式」而不是一个页面，
    // 但用户按返回时的意图是一样的——先回到没有模式的状态。
    BackHandler(enabled = stack.size > 1 || selectedIds.isNotEmpty()) {
        if (selectedIds.isNotEmpty() && top is Page.Home) {
            home.onClearSelection()
        } else {
            stack = pop(stack, home)
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
                            tabRequest = tabRequest.value,
                            lookbackListState = lookbackListState,
                            selectedIds = selectedIds,
                            onLongPressEntry = home::onLongPressEntry,
                            onSelectAllEntries = home::onSelectAllEntries,
                            onClearSelection = home::onClearSelection,
                            onDeleteSelected = home::onDeleteSelected,
                            notice = homeNotice,
                            onDismissNotice = home::acknowledgeNotice,
                            // 没有可去的过去时，VM 会把「日记还太空」这条提示放进 notice：
                            // 页签震了一下却什么都没发生，读起来像 bug。
                            onRandomWalk = {
                                home.onRandomWalk()?.let { stack = openEntry(stack, home, it) }
                            },
                            onCapture = { stack = PageStack.push(stack, Page.Capture) },
                            onSearch = { stack = PageStack.push(stack, Page.Search) },
                            onOpenEntry = { id -> openEntry(stack, home, id) },
                        )

                        is Page.Detail -> DetailScene(home = home, onBack = { stack = pop(stack, home) })

                        Page.Capture -> CaptureHost(
                            container = container,
                            onDismiss = { stack = pop(stack, home) },
                            onOpenSettings = { stack = PageStack.push(stack, Page.Settings) },
                            // 导入完成之后取景页就没了，那句「这次少了一部分」必须说在主页这层。
                            onReportNotice = home::showNotice,
                            onEntrySaved = home::revealSavedEntry,
                            sharedImage = sharedImage.value,
                        )

                        Page.Search -> SearchHost(
                            container = container,
                            shared = sharedText.value,
                            onClose = { stack = pop(stack, home) },
                            onOpenEntry = { id -> openEntry(stack, home, id) },
                        )

                        Page.Settings -> SettingsHost(
                            container = container,
                            onClose = { stack = pop(stack, home) },
                        )
                    }
                }
            }
        }
    }
}

/** 一次「把这段文字带进搜索」的请求。query 为 null 表示没有请求。 */
private data class SharedQuery(val query: String? = null, val sequence: Int = 0)

/** 一次「把这张图导进日记」的请求。uri 为 null 表示没有请求。 */
private data class SharedImage(val uri: Uri? = null, val sequence: Int = 0)

private fun openEntry(stack: List<Page>, home: HomeViewModel, id: String): List<Page> {
    home.onOpenEntry(id)
    return PageStack.push(stack, Page.Detail(id))
}

private fun pop(stack: List<Page>, home: HomeViewModel): List<Page> {
    // 离开详情页要顺手把 VM 里那份装载好的详情放掉：它带着一次 1440px 的解码，
    // 留着就是「翻过一条很长的日记之后内存下不来」。
    if (stack.lastOrNull() is Page.Detail) home.onCloseEntry()
    return PageStack.pop(stack)
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
    tabRequest: TabRequest,
    lookbackListState: LazyListState,
    selectedIds: Set<String>,
    onLongPressEntry: (String) -> Unit,
    onSelectAllEntries: () -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onRandomWalk: () -> Unit,
    onCapture: () -> Unit,
    onSearch: () -> Unit,
    onOpenEntry: (String) -> Unit,
    notice: Notice?,
    onDismissNotice: () -> Unit,
) {
    WordLensApp(
        modifier = Modifier.fillMaxSize(),
        tabRequest = tabRequest,
        lookbackListState = lookbackListState,
        notice = notice,
        onDismissNotice = onDismissNotice,
        onPickDay = home::onPickDay,
        onLoadOlder = home::onLoadOlder,
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
        onRandomWalk = onRandomWalk,
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
        onSaveEditing = home::onSaveEditing,
        voice = VoiceMemoActions(
            onStartTake = home::onStartTake,
            onStopTake = home::onStopTake,
            onCommitTake = home::onCommitTake,
            onDiscardTake = home::onDiscardTake,
            onHandOffTake = home::onTakeHandedOff,
            onDetachAudio = home::onDetachAudio,
            onPlaybackProblem = home::onAudioPlaybackFailed,
        ),
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * 搜索 / 添加页宿主。
 *
 * VM 独立于 HomeViewModel：输入框里那几个字应该在离开时清空，不该跟着主页活一辈子。
 */
@Composable
private fun SearchHost(
    container: AppContainer,
    shared: SharedQuery,
    onClose: () -> Unit,
    onOpenEntry: (String) -> Unit,
) {
    val vm: SearchViewModel = viewModel(factory = SearchViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()

    // 分享进来的文字就是这次的查询。带序号的请求体保证同一句话分享两次会填两次。
    LaunchedEffect(shared) {
        shared.query?.let(vm::onQueryChange)
    }
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
    onReportNotice: (String) -> Unit,
    /** 存好了一条记录之后交给主页它的日期键：主页据此决定要不要放开日历筛选。 */
    onEntrySaved: (String) -> Unit,
    /** 从别的 App「分享到见词」的那张图片：与相册导入同一条路。 */
    sharedImage: SharedImage,
) {
    val vm: CaptureViewModel = viewModel(factory = captureViewModelFactory(container))
    val state by vm.ui.collectAsStateWithLifecycle()
    val event by vm.event.collectAsStateWithLifecycle()
    // 取景页整体是暗的，一条系统 Toast 在这里既压不住快门按钮也跟不上主题：
    // 用应用自己的提示层，消息与消失都由这一层负责。
    // 取景页这层提示装的也是「第几次说」而不是「当前那句话」：连续两次同样的失败
    // （两次都没认出词）在下游是一次变化，提示条只缩短不重播。
    var notice by remember { mutableStateOf<Notice?>(null) }
    val noticeSeq = remember { AtomicLong() }

    // 相册入口用系统的照片选择器：挑几张就授权几张，不需要任何存储权限，
    // 也就不会出现「一个 READ_MEDIA_IMAGES 换来整个相册」这种与本产品的立场相反的事。
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        // null 是用户按了返回键。这不是失败，不该报任何东西——他没选，就是不想记这一张。
        if (uri != null) vm.onImportPhoto(uri)
    }

    // 「分享图片给见词」走的就是相册导入那一条路：拷进私有目录、现检、抠图、写成条目。
    // 分享过来的是一张已经在别人那里的照片，与用户自己挑一张在数据上没有区别，
    // 所以这里不该出现第二条流水线。
    LaunchedEffect(sharedImage) {
        sharedImage.uri?.let(vm::onImportPhoto)
    }

    LaunchedEffect(event) {
        when (val e = event) {
            is CaptureViewModel.Event.Saved -> {
                vm.acknowledgeEvent()
                // 先投递再关页：这条提示要说给「关掉取景页之后的那个界面」，
                // 顺序反过来它就会随场景一起被拆掉。
                e.notice?.let(onReportNotice)
                // 放开日历筛选放在最后：两个提示共用一个槽位，后写的赢。
                // 「你现在看的已经不是全部了」是这一对里更该被说出口的一句——它是 App
                // 动了用户自己设的条件，而少抠了一张贴纸说的是照片的内容。
                onEntrySaved(e.dayKey)
                onDismiss()
            }

            is CaptureViewModel.Event.Failed -> {
                vm.acknowledgeEvent()
                notice = Notice(e.message, noticeSeq.incrementAndGet())
            }

            // 抠图失败不影响保存，只说明一句：贴纸是这份记录的加分项，不是必要条件。
            is CaptureViewModel.Event.StickerFailed -> {
                vm.acknowledgeEvent()
                notice = Notice(e.message, noticeSeq.incrementAndGet())
            }

            is CaptureViewModel.Event.Notice -> {
                vm.acknowledgeEvent()
                notice = Notice(e.message, noticeSeq.incrementAndGet())
            }

            null -> Unit
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
            onImportFromGallery = {
                importLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onRetake = vm::onRetake,
            onSave = vm::onSave,
            onSpeak = vm::onSpeak,
            onTapSubject = vm::onTapSubject,
            onChipSelect = vm::onChipSelect,
        onManualWordChange = vm::onManualWordChange,
        onManualMeaningChange = vm::onManualMeaningChange,
        onManualAdd = vm::onManualAdd,
            modifier = Modifier.fillMaxSize(),
            previewContent = { CaptureCamera(vm) },
        )
        NoticeHost(notice = notice, onDismiss = { notice = null })
    }
}

/** 设置页宿主。从取景页打开时它压在取景页之上。 */
@Composable
private fun SettingsHost(container: AppContainer, onClose: () -> Unit) {
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    val engines by vm.engines.collectAsStateWithLifecycle()
    val missingLanguages by vm.missingLanguages.collectAsStateWithLifecycle()
    val settingsNotice by vm.notice.collectAsStateWithLifecycle()
    val userWords by vm.userWords.collectAsStateWithLifecycle()
    val lexiconWarnings by vm.lexiconWarnings.collectAsStateWithLifecycle()
    Box(modifier = Modifier.fillMaxSize()) {
        SettingsScreen(
            state = state,
            onClose = onClose,
            onTargetLanguage = vm::onTargetLanguage,
            onNativeLanguage = vm::onNativeLanguage,
            onDirection = vm::onDirection,
            onRetention = vm::onRetention,
            onRatingPalette = vm::onRatingPalette,
            onReminderEnabled = vm::onReminderEnabled,
            onReminderMinuteOfDay = vm::onReminderMinuteOfDay,
            engines = engines,
            missingLanguages = missingLanguages,
            onRecheckVoices = vm::reprobeVoices,
            onImport = vm::onImportDeck,
            userWords = userWords,
            lexiconWarnings = lexiconWarnings,
            onAddWord = vm::onAddUserWord,
            onRemoveWord = vm::onRemoveUserWord,
            onCloudEnabled = vm::onCloudEnabled,
            onCloudModel = vm::onCloudModel,
            onCloudApiKey = vm::onCloudApiKey,
            onClearCloudApiKey = vm::onClearCloudApiKey,
            modifier = Modifier.fillMaxSize(),
        )
        // 导入的结果必须有下文：文件选择器关掉之后，用户手里只剩下一个「好像成功了」的猜测。
        NoticeHost(notice = settingsNotice, onDismiss = vm::acknowledgeNotice)
    }
}
