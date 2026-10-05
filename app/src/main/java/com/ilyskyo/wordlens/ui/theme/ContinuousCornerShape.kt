// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * 超椭圆的采样几何。纯 Kotlin、不碰任何 Android 类型，所以每一条性质都能在 JVM 测试里断言。
 *
 * 单独拆出来的唯一理由：这类代码失败时不会崩，只会画出一个「说不上哪里怪」的形状，而 CI 对
 * 像素一无所知。象限搞错（把右上角的弧画到左上）在屏幕上是个风车，在测试里是三条不变量同时挂。
 *
 * ## 角度约定（务必看清，这里是 y 轴向下的屏幕坐标）
 *
 * `θ = 0` 在圆心的**右**侧，`θ = 90°` 在**下**侧，`θ = 180°` 在左，`θ = 270°` 在上。
 * 角度增大方向与视觉上的顺时针一致。四个角顺时针各扫 90°：
 *
 * | 角 | 圆心 | 入点 | 出点 | fromAngle |
 * |---|---|---|---|---|
 * | 右上 | (w−r, r) | (w−r, 0) | (w, r) | 270 |
 * | 右下 | (w−r, h−r) | (w, h−r) | (w−r, h) | 0 |
 * | 左下 | (r, h−r) | (r, h) | (0, h−r) | 90 |
 * | 左上 | (r, r) | (0, r) | (r, 0) | 180 |
 */
object SquircleGeometry {

    /**
     * 每个角采几个点。
     *
     * 12 段在 3x 屏、28dp 圆角下每段弦高误差不到 0.3px，已经看不出多边形感；再翻倍只是白烧
     * CPU，因为这条路径每帧不重算（`clip` / `shadow` 的 ModifierNode 会缓存算好的 Outline）。
     */
    const val CORNER_STEPS = 12

    /** [smoothing] → 超椭圆指数 n：`n = 2 + 3·smoothing`。0 退化成普通圆角，1 接近极限超椭圆。 */
    fun exponent(smoothing: Float): Float = 2f + 3f * smoothing.coerceIn(0f, 1f)

    /**
     * 单个角的采样点，返回扁平的 `[x0, y0, x1, y1, …]`，**不含**入点（入点由前一段直边给出）。
     *
     * 超椭圆参数解：`|x/a|^n + |y/b|^n = 1` ⟹ `x = a·sign(cosθ)·|cosθ|^(2/n)`。
     * 指数写成 `2/n` 是因为参数方程里代的是单位圆的 cos/sin，而非真实坐标。
     */
    fun cornerPoints(
        centreX: Float,
        centreY: Float,
        radius: Float,
        fromAngleDeg: Float,
        exponent: Float,
        steps: Int = CORNER_STEPS,
    ): FloatArray {
        val k = 2f / exponent
        val out = FloatArray(steps * 2)
        for (i in 1..steps) {
            val theta = (fromAngleDeg + 90f * i / steps) * DEG_TO_RAD
            val cx = cos(theta).toFloat()
            val cy = sin(theta).toFloat()
            out[(i - 1) * 2] = centreX + radius * signOf(cx) * abs(cx).pow(k)
            out[(i - 1) * 2 + 1] = centreY + radius * signOf(cy) * abs(cy).pow(k)
        }
        return out
    }

    /**
     * 闭合轮廓的顶点序列（顺时针，首尾同点）。
     *
     * 四角半径各自独立：BottomSheet 的「只有上面圆」就是把下面两个半径写成 0 得到的，
     * 不需要额外的分支——半径 0 时 12 个采样点全部重合在角点上，路径自然退化成直角。
     */
    fun outlinePoints(
        width: Float,
        height: Float,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        smoothing: Float,
    ): FloatArray {
        val n = exponent(smoothing)
        val pts = ArrayList<Float>(64)

        fun corner(centreX: Float, centreY: Float, r: Float, fromAngle: Float) {
            for (value in cornerPoints(centreX, centreY, r, fromAngle, n)) pts.add(value)
        }

        // 起点：上边左端。最后一段弧会回到这一点，所以路径天然是闭合的。
        pts.add(topStart); pts.add(0f)
        // 上边 → 右上角
        pts.add(width - topEnd); pts.add(0f)
        corner(width - topEnd, topEnd, topEnd, 270f)
        // 右边 → 右下角
        pts.add(width); pts.add(height - bottomEnd)
        corner(width - bottomEnd, height - bottomEnd, bottomEnd, 0f)
        // 下边 → 左下角
        pts.add(bottomStart); pts.add(height)
        corner(bottomStart, height - bottomStart, bottomStart, 90f)
        // 左边 → 左上角，回到起点
        pts.add(0f); pts.add(topStart)
        corner(topStart, topStart, topStart, 180f)
        return pts.toFloatArray()
    }

    /** 四角统一半径的便捷入口。 */
    fun outlinePoints(
        width: Float,
        height: Float,
        radius: Float,
        smoothing: Float,
    ): FloatArray = outlinePoints(width, height, radius, radius, radius, radius, smoothing)

    /**
     * 把四角半径缩放到放得下。
     *
     * 每条边只看自己两端之和：上边要求 `topStart + topEnd ≤ width`。按最小的那个比例**整体**缩放，
     * 而不是逐角夹到 min(w,h)/2——后者会让一个本该一致的圆角在长边上变成几种不同的弧度。
     * 返回的顺序与入参一致。
     */
    fun clampRadii(
        width: Float,
        height: Float,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
    ): FloatArray {
        val positive = topStart + topEnd
        val negative = bottomStart + bottomEnd
        val verticalTop = topEnd + bottomEnd
        val verticalBottom = topStart + bottomStart
        val scale = listOf(
            1f,
            if (positive > 0f) width / positive else 1f,
            if (negative > 0f) width / negative else 1f,
            if (verticalTop > 0f) height / verticalTop else 1f,
            if (verticalBottom > 0f) height / verticalBottom else 1f,
        ).min()
        return floatArrayOf(
            topStart * scale,
            topEnd * scale,
            bottomEnd * scale,
            bottomStart * scale,
        )
    }

    private fun signOf(value: Float): Float = when {
        value > 0f -> 1f
        value < 0f -> -1f
        else -> 0f
    }

    private const val DEG_TO_RAD = PI / 180.0
}

/**
 * 超椭圆（squircle）形状。
 *
 * ## 为什么不是普通圆角
 *
 * 圆弧与直边的切点处，曲率从 0 **突跳**到 1/r。人眼对这件事是有感知的：大圆角 + 强阴影时，
 * 直边和圆角的交界处会有一道说不出来的「折」，读起来像贴上去的圆角而不是长出来的边。
 * 超椭圆让曲率从 0 连续爬升再连续回零，这就是 iOS 的 continuous corner。
 *
 * ## 为什么自己采样，而不是抄一份贝塞尔常数
 *
 * 常见的「四段三次贝塞尔近似」依赖一组别人推导的常数。抄对了看不出来，抄错了会得到一个
 * 「差一点点」的形状——比不做更糟，因为它看起来像做错了而不是像没做。
 * 超椭圆的定义本身就是一行方程，直接采样即可，不需要任何我不打算背书的魔法数字。
 * 数学与角度约定都在 [SquircleGeometry]，并由单元测试逐点校验。
 *
 * ## 为什么继承 [CornerBasedShape] 而不是只实现 `Shape`
 *
 * M3 的 `Shapes` 五个槽位收的是 `CornerBasedShape`，只实现 `Shape` 的超椭圆**进不了主题**。
 * 那样一来 `MaterialTheme.shapes.large` 还是圆角矩形，时间轴卡片、搜索条目、对话框全都得逐个
 * 手写形状，「圆角是超椭圆」这条要求在 App 里就只覆盖一半。
 * 继承它需要逐角半径，于是这里就按逐角建模——顺带 BottomSheet 的「只圆上面」也不需要特殊分支了。
 *
 * @param radius 四角共用的半径。
 * @param bottomRadius 下两角半径，默认与 [radius] 相同；给 `0.dp` 就是 Sheet 形状。
 * @param smoothing 0 是普通圆角，1 接近极限超椭圆。默认 0.6（n ≈ 3.8）：明显不是圆角矩形，
 *   但也没方到认不出是卡片——这是 iOS 卡片的观感。
 */
class ContinuousCornerShape private constructor(
    private val smoothing: Float,
    topStart: CornerSize,
    topEnd: CornerSize,
    bottomEnd: CornerSize,
    bottomStart: CornerSize,
) : CornerBasedShape(
    topStart = topStart,
    topEnd = topEnd,
    bottomEnd = bottomEnd,
    bottomStart = bottomStart,
) {

    constructor(
        radius: Dp,
        bottomRadius: Dp = radius,
        smoothing: Float = DEFAULT_SMOOTHING,
    ) : this(
        smoothing = smoothing,
        topStart = CornerSize(radius),
        topEnd = CornerSize(radius),
        bottomEnd = CornerSize(bottomRadius),
        bottomStart = CornerSize(bottomRadius),
    )

    private val clampSmoothing = smoothing.coerceIn(0f, 1f)

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline {
        if (size.width <= 0f || size.height <= 0f) {
            // 零尺寸也要给一个合法轮廓：抛异常会让一次布局抖动直接崩掉整屏。
            return Outline.Rectangle(Rect(Offset.Zero, size))
        }
        val radii = SquircleGeometry.clampRadii(
            width = size.width,
            height = size.height,
            topStart = topStart,
            topEnd = topEnd,
            bottomEnd = bottomEnd,
            bottomStart = bottomStart,
        )
        val points = SquircleGeometry.outlinePoints(
            width = size.width,
            height = size.height,
            topStart = radii[0],
            topEnd = radii[1],
            bottomEnd = radii[2],
            bottomStart = radii[3],
            smoothing = clampSmoothing,
        )
        val path = Path()
        path.moveTo(points[0], points[1])
        var index = 2
        while (index < points.size) {
            path.lineTo(points[index], points[index + 1])
            index += 2
        }
        path.close()
        return Outline.Generic(path)
    }

    /**
     * 逐角复制。半径可能来自百分比（`CornerSize(50)`），所以这里保留 [CornerSize] 而不是换算成 dp
     * ——换成 dp 会让一个圆形贴纸在复制一次之后变成方角。
     */
    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize,
    ): ContinuousCornerShape = ContinuousCornerShape(clampSmoothing, topStart, topEnd, bottomEnd, bottomStart)

    override fun equals(other: Any?): Boolean =
        this === other || (other is ContinuousCornerShape &&
            other.clampSmoothing == clampSmoothing &&
            other.topStart == topStart &&
            other.topEnd == topEnd &&
            other.bottomEnd == bottomEnd &&
            other.bottomStart == bottomStart)

    override fun hashCode(): Int {
        var result = clampSmoothing.hashCode()
        result = 31 * result + topStart.hashCode()
        result = 31 * result + topEnd.hashCode()
        result = 31 * result + bottomEnd.hashCode()
        result = 31 * result + bottomStart.hashCode()
        return result
    }

    companion object {
        const val DEFAULT_SMOOTHING = 0.6f
    }
}

/**
 * 尺寸规范里的四档超椭圆。
 *
 * 放在这里而不是 Token 里，是因为形状和它的半径本来就是一个东西，拆成两处迟早会出现
 * 「Token 写 28dp、Squircle 写 24dp」。smoothing 全部用默认值：四档只差半径，
 * 让某一档更方会让整屏的圆角看起来不像同一套系统。
 */
object Squircle {

    /** 卡片 28dp：大半径才有「贴纸」感，同时也留出足够弧长，让曲率连续这件事真的看得见。 */
    val card = ContinuousCornerShape(28.dp)

    /** 按钮 16dp：比卡片小一档，同一屏里层级立刻分明。 */
    val button = ContinuousCornerShape(16.dp)

    /** 小标签 10dp：再大的圆角会让标签像个缩小版卡片，失去「标记」该有的轻量感。 */
    val chip = ContinuousCornerShape(10.dp)

    /** Sheet 顶角 36dp：只有两角有弧度，视觉上需要比卡片更圆才不别扭。 */
    val sheet = ContinuousCornerShape(36.dp, bottomRadius = 0.dp)
}
