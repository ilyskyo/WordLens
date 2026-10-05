package com.ilyskyo.wordlens

import android.app.Application
import android.util.Log
import com.ilyskyo.wordlens.core.AppContainer
import com.ilyskyo.wordlens.ui.theme.Haptics
import kotlinx.coroutines.launch

/**
 * 进程级入口：持有 [AppContainer]，并在冷启动就预热所有 JSON 文档。
 *
 * 命名为 WordLensApplication 而不是 WordLensApp，是为了避开同包里那个同名的
 * @Composable（ui.nav.WordLensApp）——两者会在 MainActivity 里同文件出现。
 */
class WordLensApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.warmUp()
        // 分割模型要在第一次按下快门之前就在后台躺好：主线程加载它是最典型的 ANR 形状。
        // 这里不 await——取景页只 collect 结果，早到晚到都不影响它能拿到什么。
        container.applicationScope.launch { container.vision.prepareSegmentation() }
        // 振动器在第一次 vibrate 之前要过一次 IPC，那一下正好落在用户第一次按下按钮时——
        // 触觉慢半拍比没有触觉更明显。所以在这里提前解析，代价是一次系统服务查询。
        Haptics.init(this)
        Log.i(TAG, "WordLens started, filesDir=$filesDir")
    }

    private companion object {
        const val TAG = "WordLensApp"
    }
}
