package com.ilyskyo.wordlens

import android.app.Application
import android.util.Log
import com.ilyskyo.wordlens.core.AppContainer

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
        Log.i(TAG, "WordLens started, filesDir=$filesDir")
    }

    private companion object {
        const val TAG = "WordLensApp"
    }
}
