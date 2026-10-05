// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.theme

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 系统的「移除动画」开关有没有被打开。
 *
 * 只有**装饰性**动效允许读它并降级。判断标准只有一条：把这个动效拿掉之后，屏幕上的信息
 * 还完整吗？完整才许降，不完整就不是动效而是功能本身（各调用点附近都写了这条界线，
 * [com.ilyskyo.wordlens.ui.remember.RememberScreen] 的文件注释里是最全的一份）。
 *
 * ## 为什么读 `Settings.Global.ANIMATOR_DURATION_SCALE` 而不是 Compose 的 AccessibilityManager
 *
 * 本仓库解析到的是 compose BOM 2026.02.01：ui 1.11.2 / foundation 1.10.5 / animation-core 1.10.5。
 * 把这三个版本 classes.jar 里的每一个 class 都扫过一遍之后：
 *
 * - `androidx.compose.ui.platform.AccessibilityManager` 只剩 `calculateRecommendedTimeoutMillis`
 *   一个成员，**没有** `isReduceMotionEnabled`（`touchExplorationEnabled`、`fontScale` 这些
 *   也不在这个接口上）；
 * - `LocalAccessibilityManager` 虽然还在，但拿到的是同一个只有超时的接口；
 * - 整个 `androidx.compose.*` 依赖树里搜不到 `reduceMotion` 这个符号，也搜不到任何
 *   「读无障碍移除动画」的入口。
 *
 * 也就是说这条 API 在当前锁定的版本上根本不存在，硬编会得到一次编译失败；把它写成反射
 * 或者「等版本升上来再接」的兼容分支，则留下一段这台机器上永远验证不了的代码。
 *
 * 退回读全局设置，理由不是「凑合能用」：
 *
 * 1. **它就是这个开关真正写入的东西**。「设置 → 无障碍 → 移除动画」（Android 13 起）落地时
 *    是把 animator / window / transition 三个缩放一起置 0；系统至今没有公开一个「读取移除
 *    动画」的 API，各 ROM（小米、华为）做的也只有这一件事。所以除了这个值，没有别的可读。
 * 2. 只读 `ANIMATOR_DURATION_SCALE` 而不是「三个都要求为 0」：这一条管的就是属性动画与
 *    Compose 的动画循环，另外两条管窗口与转场。开发者选项里可以只关那两个而把这条留在 1——
 *    那种情况下我们的动画确实还在正常跑，用户要的不是把它停掉。
 * 3. minSdk 26 全覆盖（这个键 API 17 起就有），读全局设置不需要任何权限。
 * 4. Compose 自己观察的也是同一个键（`androidx.compose.ui.platform.MotionDurationScaleImpl`
 *    里那个 `startObservingSystemScaleFactor`）。我们和框架读到同一份真相，不会出现
 *    「系统认为动画已经关了，而界面里另有一套判断」这种两边不一致。
 *
 * ## 框架已经按这个缩放把动画压成 0 了，为什么还要自己降级
 *
 * 框架那条只做一件事：把**时长**缩放。它管不到三类东西，而这三类正好在本项目里：
 *
 * - `rememberInfiniteTransition` 的循环：时长为 0 不等于停下来，它还在每帧重画一条
 *   不呼吸了的线；
 * - `delay(index * 80ms)` 这种由协程排出来的**依次出场**：`delay` 不是动画，不受任何
 *   动效缩放影响，一行动一行落地还是「蹦出来的」；
 * - **该用哪一种进场**这个选择本身：把 `scaleIn` 的时长压成 0 只是让元素在 0 帧里从
 *   0.92 跳到 1，那一下缩放还是发生了。降级要的是把它从时序里摘掉，换成淡入。
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    val reduce = remember(context) { mutableStateOf(reduceMotionNow(context)) }
    DisposableEffect(context) {
        val resolver = context.contentResolver
        // 用户很可能就是站在设置页里把这个开关拨过来、再回来看效果，而我们的 Activity 不会
        // 因此重建。不接这次变更，降级就要等到进程重启才生效——那等于没有跟随系统设置。
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduce.value = reduceMotionNow(context)
            }
        }
        // 注册之前补读一次：从第一次组合到走到这里，中间也可能已经有人改过设置。
        reduce.value = reduceMotionNow(context)
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduce.value
}

/**
 * 0f 就是「移除动画」。
 *
 * 用 `<= 0f` 而不是 `== 0f`：个别 ROM 会把这个值写成负数或极小的浮点残差，语义上都是
 * 「关掉」。取不到时按 1f 处理——读不到设置就照常做动画，绝不能反过来把动效静默关掉。
 */
private fun reduceMotionNow(context: Context): Boolean =
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) <= 0f
