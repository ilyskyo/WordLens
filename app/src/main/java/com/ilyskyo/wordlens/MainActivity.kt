package com.ilyskyo.wordlens

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import com.ilyskyo.wordlens.ui.nav.HomeViewModel
import com.ilyskyo.wordlens.ui.nav.WordLensApp
import com.ilyskyo.wordlens.ui.search.SearchScreen
import com.ilyskyo.wordlens.ui.search.SearchViewModel
import com.ilyskyo.wordlens.ui.theme.WordLensTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as WordLensApplication).container
        setContent {
            WordLensTheme {
                var showCapture by remember { mutableStateOf(false) }
                var showSearch by remember { mutableStateOf(false) }
                val home: HomeViewModel = viewModel(factory = HomeViewModel.factory(container))
                val lookback by home.lookback.collectAsStateWithLifecycle()
                val rememberState by home.remember.collectAsStateWithLifecycle()
                val detail by home.detail.collectAsStateWithLifecycle()
                // 详情页与搜索页都是浮层而不是路由，所以返回键要自己接：不接的话系统返回会直接退出应用。
                // 两个 BackHandler 的 enabled 互斥，保证同时只有一个生效。
                androidx.activity.compose.BackHandler(enabled = detail != null, onBack = home::onCloseEntry)
                androidx.activity.compose.BackHandler(
                    enabled = detail == null && showSearch,
                    onBack = { showSearch = false },
                )
                Box(Modifier.fillMaxSize()) {
                    WordLensApp(
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
                        CaptureHost(container, onDismiss = { showCapture = false })
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
 * 取景页宿主：全屏浮在主界面之上。
 *
 * 拍照是**就地动作**而不是导航目的地（见 [com.ilyskyo.wordlens.ui.nav.WordLensApp] 的注释），
 * 所以这里用组合内条件渲染而不是 NavHost。ViewModel 不挂 key：每次进入都想要一台干净的
 * 相机与一份新的暂存状态，回到主页后再进来不应残留上一次的贴纸。
 */
@Composable
private fun CaptureHost(container: AppContainer, onDismiss: () -> Unit) {
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
            null -> Unit
        }
    }

    CaptureScreen(
        bottomInset = PaddingValues(0.dp),
        state = state,
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
