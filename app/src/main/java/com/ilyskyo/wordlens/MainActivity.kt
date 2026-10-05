package com.ilyskyo.wordlens

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ilyskyo.wordlens.core.AppContainer
import com.ilyskyo.wordlens.ui.capture.CaptureCamera
import com.ilyskyo.wordlens.ui.capture.CaptureScreen
import com.ilyskyo.wordlens.ui.capture.CaptureViewModel
import com.ilyskyo.wordlens.ui.capture.captureViewModelFactory
import com.ilyskyo.wordlens.ui.lookback.EntryDetailScreen
import com.ilyskyo.wordlens.ui.nav.HomeTab
import com.ilyskyo.wordlens.ui.nav.HomeViewModel
import com.ilyskyo.wordlens.ui.nav.WordLensApp
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
                var showCapture by remember { mutableStateOf(false) }
                var showSearch by remember { mutableStateOf(false) }
                var showSettings by remember { mutableStateOf(false) }
                val home: HomeViewModel = viewModel(factory = HomeViewModel.factory(container))
                val lookback by home.lookback.collectAsStateWithLifecycle()
                val rememberState by home.remember.collectAsStateWithLifecycle()
                val detail by home.detail.collectAsStateWithLifecycle()
                // 详情页、搜索页、设置页都是浮层而不是路由，所以系统返回键要自己接：
                // 不接的话一次返回直接退出应用。三者的 enabled 互斥，同时只有一个生效，
                // 顺序与层叠一致（详情 > 设置 > 搜索）。
                androidx.activity.compose.BackHandler(enabled = detail != null, onBack = home::onCloseEntry)
                androidx.activity.compose.BackHandler(
                    enabled = detail == null && showSettings,
                    onBack = { showSettings = false },
                )
                androidx.activity.compose.BackHandler(
                    enabled = detail == null && !showSettings && showSearch,
                    onBack = { showSearch = false },
                )
                Box(Modifier.fillMaxSize()) {
                    WordLensApp(
                        requestedTab = requestedTab.value,
                        lookbackState = lookback,
                        rememberState = rememberState,
                        onMaterialChange = home::onMaterialChange,
                        onReveal = home::onReveal,
                        onGrade = home::onGrade,
                        onReviewSpeak = home::onSpeak,
                        onMarkMastered = home::onMarkMastered,
                        onUnmark = home::onUnmark,
                        onLookbackSpeak = home::onEntrySpeak,
                        onOpenEntry = home::onOpenEntry,
                        onCapture = { showCapture = true },
                        onSearch = { showSearch = true },
                    )
                    if (showSearch) {
                        SearchHost(container, onClose = { showSearch = false }, onOpenEntry = home::onOpenEntry)
                    }
                    if (showCapture) {
                        CaptureHost(
                            container = container,
                            onDismiss = { showCapture = false },
                            onOpenSettings = { showSettings = true },
                        )
                    }
                    if (showSettings) {
                        SettingsHost(container, onClose = { showSettings = false })
                    }
                    detail?.let { state ->
                        EntryDetailScreen(
                            state = state,
                            onBack = home::onCloseEntry,
                            onDraftChange = home::onEventDraftChange,
                            onSaveEvent = home::onSaveEvent,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
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
 * 搜索 / 添加页宿主。
 *
 * 与取景页同一个理由：它是就地动作，不是导航目的地。VM 挂在这里而不是 HomeViewModel 上，
 * 是因为搜索状态（输入框里那几个字）应该在离开时清空，不该跟着主页活一辈子。
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
 * 设置页宿主。取景页的齿轮与主页都可能拉起它，所以它盖在它们之上。
 */
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

/**
 * 取景页宿主：全屏浮在主界面之上。
 *
 * 拍照是**就地动作**而不是导航目的地（见 [com.ilyskyo.wordlens.ui.nav.WordLensApp] 的注释），
 * 所以这里用组合内条件渲染而不是 NavHost。ViewModel 不挂 key：每次进入都想要一台干净的
 * 相机与一份新的暂存状态，回到主页后再进来不应残留上一次的贴纸。
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

    androidx.compose.runtime.LaunchedEffect(event) {
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
        bottomInset = PaddingValues(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
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
