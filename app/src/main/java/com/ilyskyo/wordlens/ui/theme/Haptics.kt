// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.theme

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 触觉的种类。名字描述的是**触感像什么**，不是 API 常量——调用点该说的是语义。
 */
enum class Haptic {
    /** 极轻的一下：任何按钮按下、页签切换。 */
    Tick,

    /** 短促有弹性的一下：翻卡到背面、选中胶囊弹起。 */
    Pop,

    /** 闷而重：评级「忘了」。 */
    Thud,

    /** 实：评级「好」「简单」、快门与 FAB 按下。 */
    Heavy,

    /** 两下组合（先重后轻）：完成一组复习。 */
    Success,
}

/**
 * 触觉系统。
 *
 * ## 两套 API，别混用
 *
 * Android 的振动有两族常量，长得像但完全不同，用错一档就是**静默没有手感**：
 *
 * - `VibrationEffect.EFFECT_*`：预置**效果**，交给 `createPredefined`。只有四个
 *   （TICK 是 API 29，CLICK / DOUBLE_CLICK / HEAVY_CLICK 是 API 30），且**没有**查询接口——
 *   只能按版本号信任它们。
 * - `VibrationEffect.Composition.PRIMITIVE_*`：可组合**原语**，交给
 *   `startComposition().addPrimitive(...)`。API 31 起才有，一共只有八个（CLICK / TICK /
 *   LOW_TICK / THUD / SPIN / SLOW_RISE / QUICK_RISE / QUICK_FALL），并且可以用
 *   `areAllPrimitivesSupported` 逐台设备确认。**没有** POP，也**没有** HEAVY_CLICK 原语——
 *   规范里写的 `createPredefined(EFFECT_POP)` 两边都不存在，照抄会得到一次静默的失败。
 *
 * 把 `EFFECT_TICK` 传给 `addPrimitive` 不会崩，只会静默什么都不震；反过来把
 * `PRIMITIVE_POP` 传给 `createPredefined` 也一样。所以每一档都走自己那一族，
 * 并且都留一条 `createOneShot` 的降级路径。
 *
 * ## 降级顺序为什么是「预置 → 原语 → 单脉冲」
 *
 * 时长与强度照预置效果之间的相对关系排（tick 最短最轻、thud 最长最重），这样
 * **降级之后几档触觉的轻重顺序仍然成立**——用户至少不会觉得「忘了」比「好」更响。
 *
 * ## 为什么每一档之前要先问系统的「触摸时振动」
 *
 * 这一族效果全部直接调用 [Vibrator]，而没有走 `View.performHapticFeedback`——走 View 的话
 * 只有四个粗粒度常量，拿不到原语组合，也就拿不到上面那套轻重顺序。代价是框架替调用方
 * 核对的那个全局开关（设置 → 声音 → 触摸时振动，`Settings.System.HAPTIC_FEEDBACK_ENABLED`）
 * 也不再自动生效了：不自己问，用户已经在全局关掉振动之后，本 App 的每一个按钮仍会各震一下。
 * 那不是「手感更用心」，那是不听话。读法与 `ui/theme/ReduceMotion.kt` 一致：读一次挂在
 * ContentObserver 上，不在每次按下时现问——设置读取要走 SettingsProvider 一次跨进程调用，
 * 而这一条发生在按下的那一帧里。
 */
object Haptics {

    @Volatile
    private var vibrator: Vibrator? = null

    @Volatile
    private var resolved = false

    /** 系统的「触摸时振动」。读不到就按开着处理：缺省不该把用户本来有的手感拿走。 */
    @Volatile
    private var touchVibrationOn = true

    /** 强引用：观察器只被 resolver 弱持有，不留字段的话它会被回收，开关从此不再跟。 */
    @Volatile
    private var observer: ContentObserver? = null

    /** 幂等：只有第一次真的去拿振动器。Application 与 [rememberHaptic] 各调一次都安全。 */
    fun init(context: Context) {
        if (resolved) return
        val app = context.applicationContext
        vibrator = runCatching {
            val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                app.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                app.getSystemService(Vibrator::class.java)
            }
            v?.takeIf { it.hasVibrator() }
        }.getOrNull()
        resolved = true
        watchTouchVibration(app)
    }

    fun fire(kind: Haptic) {
        if (!resolved) return
        if (!touchVibrationOn) return
        val vibrator = vibrator ?: return
        runCatching { vibrator.vibrate(effectFor(kind)) }
    }

    private fun watchTouchVibration(app: Context) {
        touchVibrationOn = readTouchVibration(app)
        val uri = Settings.System.getUriFor(Settings.System.HAPTIC_FEEDBACK_ENABLED) ?: return
        val listener = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChanging: Boolean, uri: Uri?) {
                touchVibrationOn = readTouchVibration(app)
            }
        }
        // 注册不成功（各家 ROM 对这个 key 的实现不一致）只影响一件事：中途改设置要等下次进 App
        // 才生效。字段已经用真值初始化过，所以不会出现「一开机就没有手感」那种更糟的长相。
        if (runCatching { app.contentResolver.registerContentObserver(uri, false, listener) }.isSuccess) {
            observer = listener
        }
    }

    @Suppress("DEPRECATION")
    private fun readTouchVibration(app: Context): Boolean = runCatching {
        Settings.System.getInt(
            app.contentResolver,
            Settings.System.HAPTIC_FEEDBACK_ENABLED,
            1,
        )
    }.getOrDefault(1) == 1

    private fun effectFor(kind: Haptic): VibrationEffect = when (kind) {
        // Tick 与 Heavy 有预置效果，优先用：它们由厂商调过，比我们能猜的时长更接近本机手感。
        Haptic.Tick -> predefined(VibrationEffect.EFFECT_TICK, minApi = 29, ms = 8, amplitude = 60)
        Haptic.Heavy ->
            predefined(VibrationEffect.EFFECT_HEAVY_CLICK, minApi = 30, ms = 18, amplitude = 190)
        // Pop 没有对应的预置效果，也没有 PRIMITIVE_POP——可组合原语只有八个，
        // 里面最接近「短促有弹性」的是 PRIMITIVE_CLICK。用它，退路是单脉冲。
        Haptic.Pop -> primitive(VibrationEffect.Composition.PRIMITIVE_CLICK, ms = 14, amplitude = 130)
        Haptic.Thud -> primitive(VibrationEffect.Composition.PRIMITIVE_THUD, ms = 24, amplitude = 220)
        Haptic.Success -> success()
    }

    /**
     * 预置效果：只能用版本号判断。
     *
     * 没有 `isPredefinedEffectSupported` 这样的查询接口，因为预置效果随框架提供，
     * 厂商可以改波形但不能改常量。低于 minApi 时退到单脉冲。
     */
    private fun predefined(effect: Int, minApi: Int, ms: Long, amplitude: Int): VibrationEffect =
        if (Build.VERSION.SDK_INT >= minApi) predefinedOf(effect) else oneShot(ms, amplitude)

    /**
     * 单独抽出来只为承载这个抑制：调用方已经用版本号保证了可用性，
     * 而 lint 看不懂动态判断，只会看到「API 29 的方法出现在 minSdk 26 的项目里」。
     */
    @Suppress("NewApi")
    private fun predefinedOf(effect: Int): VibrationEffect = VibrationEffect.createPredefined(effect)

    /** 单个原语组成。设备明确说不支持这个原语时退到单脉冲，而不是赌一把。 */
    private fun primitive(primitiveId: Int, ms: Long, amplitude: Int): VibrationEffect {
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            vibrator?.areAllPrimitivesSupported(primitiveId) == true
        return if (supported) singlePrimitive(primitiveId) else oneShot(ms, amplitude)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun singlePrimitive(primitiveId: Int): VibrationEffect =
        VibrationEffect.startComposition()
            // scale 1.0：原语的强度由厂商定义，我们只负责挑对原语，不再二次缩放。
            .addPrimitive(primitiveId, 1.0f)
            .compose()

    /**
     * 完成一组的仪式：一记闷响打头，40ms 后一记轻音收尾。
     *
     * 这个顺序读起来是「结束」，反过来像「出错了」。三级降级：
     * API 31+ 且设备承认这两个原语 → 组合；否则 API 30 的 DOUBLE_CLICK（两下快击，语义上
     * 最接近「收尾」）；再否则一记较长的单脉冲。
     */
    private fun success(): VibrationEffect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val supported = vibrator?.areAllPrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_THUD,
                VibrationEffect.Composition.PRIMITIVE_TICK,
            ) == true
            if (supported) return composedSuccess()
        }
        if (Build.VERSION.SDK_INT >= 30) {
            return predefinedOf(VibrationEffect.EFFECT_DOUBLE_CLICK)
        }
        return oneShot(22, 200)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun composedSuccess(): VibrationEffect = VibrationEffect.startComposition()
        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1.0f)
        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.8f, 40)
        .compose()

    private fun oneShot(ms: Long, amplitude: Int): VibrationEffect =
        VibrationEffect.createOneShot(ms, amplitude)
}

/**
 * 组合作用域里的触觉入口。
 *
 * 包成 lambda 而不是让调用点写 `Haptics.fire(Haptic.Tick)`：触觉是副作用，
 * 让它出现在类型上，测试与 Preview 里也才能整层替换掉。
 */
@Composable
fun rememberHaptic(): (Haptic) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { kind ->
            Haptics.init(context)
            Haptics.fire(kind)
        }
    }
}
