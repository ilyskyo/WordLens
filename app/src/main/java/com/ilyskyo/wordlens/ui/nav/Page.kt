// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.nav

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/**
 * 单 Activity 的页面。
 *
 * 没有 NavHost：主页、详情、取景、搜索、设置全部活在一个组合树里，由一个栈管理。
 * 这既符合「拍照是就地动作而不是导航目的地」的产品设定，也是共享元素转场的前提——
 * 两端必须在同一个 [SharedTransitionScope] 下同时存在。
 */
sealed interface Page {
    data object Home : Page
    data class Detail(val entryId: String) : Page
    data object Capture : Page
    data object Search : Page
    data object Settings : Page
}

/**
 * 页面栈的存取与存盘编码。
 *
 * ## 为什么要编码成字符串
 *
 * manifest 没有声明 `configChanges`，所以转屏必然重建 MainActivity。栈原先是
 * `remember { mutableStateListOf(Page.Home) }`——转一次屏，用户从详情页、设置页、取景页
 * 都会被扔回时间轴。这个项目里「转屏不许弄丢你正看着的东西」已经写进了好几处实现
 * （日期筛选提到 ViewModel、草稿用 rememberSaveable、列表滚动状态外提），
 * 页签与这条栈是那个标准上最后剩下的洞。
 *
 * 交给 ViewModel 也行，但「现在在哪一页」是界面状态而不是数据状态；而 Bundle 里塞
 * Serializable 的密封对象会在类被改名/移动时于**恢复时**才炸。这条栈只有五个固定名字
 * 加一个 id，字符串编码比任何框架机制都更便宜、也更能扛重构：认不出来的条目直接丢掉，
 * 整条栈因此不会消失。
 *
 * ## 进程被杀之后
 *
 * rememberSaveable 活得太久（跨进程死亡也恢复），而 ViewModel 不会。所以恢复出来的
 * `Detail(id)` 背后可能没有任何东西在装载——那一面由 `HomeViewModel.ensureEntryShown` 补，
 * 不是把栈退回主页：用户离开又回来，看到的应该还是他离开时那一页。
 */
object PageStack {

    val initial: List<Page> = listOf(Page.Home)

    fun push(stack: List<Page>, page: Page): List<Page> = stack + page

    /** 栈底不许弹空：弹到空栈意味着返回键会吃掉用户，而不是离开这一页。 */
    fun pop(stack: List<Page>): List<Page> = if (stack.size > 1) stack.dropLast(1) else stack

    fun encode(stack: List<Page>): List<String> = stack.map { page ->
        when (page) {
            Page.Home -> TAG_HOME
            Page.Capture -> TAG_CAPTURE
            Page.Search -> TAG_SEARCH
            Page.Settings -> TAG_SETTINGS
            is Page.Detail -> TAG_DETAIL + page.entryId
        }
    }

    fun decode(stored: List<String>): List<Page> {
        val out = stored.mapNotNull { raw ->
            when {
                raw == TAG_HOME -> Page.Home
                raw == TAG_CAPTURE -> Page.Capture
                raw == TAG_SEARCH -> Page.Search
                raw == TAG_SETTINGS -> Page.Settings
                raw.startsWith(TAG_DETAIL) && raw.length > TAG_DETAIL.length ->
                    Page.Detail(raw.substring(TAG_DETAIL.length))
                // 旧版本写下的、这一版认不出来的名字：丢掉它，但不要让整条栈一起没了。
                else -> null
            }
        }
        return out.ifEmpty { initial }
    }

    val saver: Saver<List<Page>, List<String>> = Saver(
        // 写成函数引用过不了编译：Saver 的 save 是 `SaverScope.(T) -> S?`，
        // 带接收者的 lambda 不是一个普通函数类型。
        save = { stack -> encode(stack) },
        restore = { stored -> decode(stored) },
    )

    private const val TAG_HOME = "home"
    private const val TAG_CAPTURE = "capture"
    private const val TAG_SEARCH = "search"
    private const val TAG_SETTINGS = "settings"
    private const val TAG_DETAIL = "detail:"
}

/**
 * 栈深度顺序，用来判断转场方向（进 = 更深，出 = 更浅）。
 *
 * 设置页比取景页更深：从取景页打开设置后按返回，回到的是取景页而不是主页。
 */
val Page.depth: Int
    get() = when (this) {
        Page.Home -> 0
        is Page.Detail -> 2
        Page.Search -> 2
        Page.Capture -> 3
        Page.Settings -> 4
    }

/** 共享元素作用域。为 null 表示调用点不在 SharedTransitionLayout 里（比如 Preview）。 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/** 当前场景的可见性作用域，来自 AnimatedContent 的内容 lambda。 */
val LocalPageVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * 条目照片的共享键。
 *
 * 用 id 而不是 Bitmap 引用：列表会随滚动重组、位图会被重新解码，而 id 是这条日记的身份。
 */
fun entryPhotoKey(entryId: String) = "entry-photo-$entryId"

/**
 * 照片飞行时的轨迹。
 *
 * 位置用临界阻尼弹簧（不来回弹），尺寸变化交给
 * [SharedTransitionScope.ResizeMode.ScaleToBounds]：卡片到全屏是约 3 倍的放大，
 * 任何「让内容跟着框拉伸」的做法都会在那 400ms 里把人脸拉成长条。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val SharedPhotoBounds: BoundsTransform = BoundsTransform { _, _ ->
    spring(
        dampingRatio = 1f,
        stiffness = Spring.StiffnessMediumLow,
    )
}

/**
 * 把一张条目照片标成共享元素。
 *
 * 不在 SharedTransitionLayout 之下时（Compose Preview、或者将来某个独立宿主）原样返回，
 * 不崩也不打日志刷屏——缺作用域只是没有转场，不是错误。
 */
@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
fun Modifier.sharedEntryPhoto(entryId: String): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val visibility = LocalPageVisibilityScope.current ?: return this
    return with(shared) {
        with(visibility) {
            this@sharedEntryPhoto
                .sharedBounds(
                    sharedContentState = rememberSharedContentState(key = entryPhotoKey(entryId)),
                    animatedVisibilityScope = this@with,
                    // 卡片到全屏是约 3 倍放大。ScaleToBounds 让内容整体缩放并裁切，
                    // 而不是把像素跟着框拉扁——后者会在半秒里把人脸拉成长条。
                    resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(
                        contentScale = ContentScale.Crop,
                    ),
                    boundsTransform = SharedPhotoBounds,
                )
                // 抬进 overlay：飞行途中不能从 Tab 胶囊或底部圆下面穿过去。
                // 第一个参数是 overlay 内的 z 序（Compose 未导出参数名，故按位置传）。
                .renderInSharedTransitionScopeOverlay(1f) { true }
        }
    }
}
