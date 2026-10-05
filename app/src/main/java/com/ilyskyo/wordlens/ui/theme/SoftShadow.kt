// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 多层柔和阴影。
 *
 * ## 为什么不是单层 elevation
 *
 * Material 默认的单层阴影在浅色背景上最难看：一层硬边、纯黑、边界清晰，卡片像是**贴**在
 * 屏幕上而不是**浮**在屏幕上。真实世界里一个 12mm 厚的卡片投影是三段东西叠出来的——
 * 紧贴边缘的一圈深色（接触）、中等范围的柔和过渡（半影）、以及铺得很开几乎看不见的
 * 一层（环境光遮蔽）。分三层各画一次，人眼只会读到「软」。
 *
 * ## 为什么叠 [Modifier.shadow] 而不是 drawBehind + 高斯模糊
 *
 * 看起来更「手工」的做法是自己画一层色块再 `RenderEffect.createBlurEffect` 模糊，但它有两个
 * 绕不过去的代价：
 *
 * 1. `renderEffect` 是挂在 `graphicsLayer` 上的，会模糊**整个节点**的内容，包括卡片本身。
 *    要只模糊阴影就得把阴影单独拆成一个 composable 层，多一次离屏合成。
 * 2. `RenderEffect` 要 API 31+，`Modifier.blur(forced = true)` 的低版本走软件渲染，
 *    在低端机上会直接掉帧——而本项目 minSdk 是 26。
 *
 * `Modifier.shadow` 走的是 `RenderNode` 的原生阴影（ambient + spot 两套梯度），GPU 上做，
 * 支持任意 `Outline`（所以超椭圆的轮廓和裁切一定一致），API 26 起就是硬件加速路径。
 * 叠三次仍然只多三个 render node，比一次离屏模糊便宜。
 *
 * ## 用法顺序有讲究
 *
 * `softShadow` 必须排在 `.clip(...)` / 背景填充**之前**：阴影是画在节点外的，排后面会被
 * 自己的内容盖住。父容器若有 `clipToBounds` 会切掉外扩的模糊范围，卡片四周至少要留出
 * [SoftShadow.reach] 的空白。
 */
object SoftShadow {

    /**
     * 边缘感：elevation 1dp、alpha 0.10。
     *
     * 这层负责「卡片有厚度」。它几乎没有位移，所以读起来像描边而不是投影；
     * alpha 比另外两层高，但范围极窄，叠在一起不会让整体变脏。
     */
    val edge = Layer(elevation = 1.dp, alpha = 0.10f)

    /**
     * 接触阴影：elevation 6dp、alpha 0.07。
     *
     * 6dp 是 Material 里 Card 的常用高度，正好落在「看得见半影、又还没飞起来」的区间。
     */
    val contact = Layer(elevation = 6.dp, alpha = 0.07f)

    /**
     * 环境遮蔽：elevation 22dp、alpha 0.05。
     *
     * 铺得最开、最淡的一层，决定「离屏幕多远」。再高就会开始像悬浮窗而不是纸片。
     */
    val ambient = Layer(elevation = 22.dp, alpha = 0.05f)

    val all: List<Layer> = listOf(ambient, contact, edge)

    /** 需要给阴影留出的最小空白：最开的一层的半径。布局上卡片之间至少要有这个间距。 */
    val reach: Dp = ambient.elevation

    data class Layer(val elevation: Dp, val alpha: Float)
}

/**
 * 三层柔和阴影。
 *
 * @param shape 阴影轮廓，和裁切用同一个 Shape——分成两套迟早出现「卡片被裁成超椭圆而阴影还是圆角矩形」。
 * @param tint 阴影色，**只取它的 RGB**，透明度由三层各自决定。默认取主题的
 *   [WordLensAccents.shadowTint]：Android 的 elevation 阴影硬编码为黑色，暖色背景上纯黑影会
 *   发灰发脏，所以这里显式给暖灰。
 * @param intensity 整体强度。小元素（贴纸、标签）用 0.5：三层的**距离和透明度一起**缩，
 *   只缩距离会让一圈淡到看不见的灰雾留在旁边，只缩透明度则还是一个大范围的黑边。
 * @param pressed 按下态。True 时三层同时收缩到 [PRESSED_SCALE]，卡片「贴回」屏幕——
 *   阴影比缩放更早地告诉用户「这个按钮真的按下去了」。
 */
@Composable
fun Modifier.softShadow(
    shape: Shape = Squircle.card,
    tint: Color = WordLensTheme.accents.shadowTint,
    intensity: Float = 1f,
    pressed: Boolean = false,
): Modifier {
    val distanceScale = (if (pressed) PRESSED_SCALE else 1f) * intensity
    val alphaScale = (if (pressed) PRESSED_ALPHA_SCALE else 1f) * intensity
    var modifier: Modifier = this
    SoftShadow.all.forEach { layer ->
        modifier = modifier.shadow(
            elevation = layer.elevation * distanceScale,
            shape = shape,
            ambientColor = tint.copy(alpha = layer.alpha * alphaScale),
            spotColor = tint.copy(alpha = layer.alpha * alphaScale),
            clip = false,
        )
    }
    return modifier
}

/** 圆形的三层阴影，FAB 与发音按钮用。形状换成圆形之外的一切与卡片一致。 */
@Composable
fun Modifier.softCircleShadow(
    intensity: Float = 1f,
    pressed: Boolean = false,
    tint: Color = WordLensTheme.accents.shadowTint,
): Modifier = softShadow(CircleShape, tint, intensity, pressed)

private const val PRESSED_SCALE = 0.45f

/**
 * 按下时三层同时变淡的倍率。
 *
 * 距离收缩之外还必须收透明度：卡片贴回屏幕时接触面变小，物理上影子是变浅而不是变浓的。
 * 只缩距离会得到一个「又近又浓」的黑边，读起来像按下去之后卡住了。
 */
private const val PRESSED_ALPHA_SCALE = 0.7f
