// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.capture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ilyskyo.wordlens.data.model.ShotKind
import com.ilyskyo.wordlens.ui.theme.Space
import com.ilyskyo.wordlens.ui.theme.WordLensTheme
import com.ilyskyo.wordlens.vision.camera.CameraFocusMath.NormBox
import com.ilyskyo.wordlens.vision.camera.CameraFocusMath.SensorCrop
import com.ilyskyo.wordlens.vision.camera.OverlayGeometry
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 覆盖层上的一枚「词片」：长在物体上的可点贴纸。
 *
 * @param key 稳定标识；选中状态与聚焦都靠它对齐。
 * @param box 归一化到**整幅传感器、传感器方向**的框。竖持时图像坐标与传感器坐标差一次
 *   旋转（见 [CameraFocusMath.orientedBox]），换算发生在上游，这里只吃最终值。
 * @param known 词典里有没有这个词。没有也要显示——「看到了但不认识」比静默漏掉诚实。
 */
data class WordChip(
    val key: String,
    val word: String,
    val box: NormBox,
    val known: Boolean = true,
)

/**
 * 取景画面的几何快照，覆盖层据此把传感器坐标换算成屏幕像素。
 *
 * 单独一个类型而不是散着传四个参数：这几样必须**同一时刻**取值——裁切区是变焦动画逐帧
 * 变的，取错一拍，词片就会在画面上抖。
 */
data class CameraFrame(
    val sensorWidth: Int,
    val sensorHeight: Int,
    val rotationDegrees: Int,
    /** 当前裁切区域（传感器像素）。未变焦时传整幅传感器。 */
    val crop: SensorCrop,
)

/**
 * 取景器覆盖层：物品词片（可点、可聚焦）+ 氛围词（非交互）。
 *
 * ## 为什么两类词要分轻重
 *
 * 全画面上去一样醒目就是一锅粥。判帧结果（[ShotKind]）在这里只用来分配**视觉权重**：
 * 主体饱满时物品词最大、氛围词缩到角落；画面是环境时氛围词铺开、物品词降为点缀。
 * 判不清就取中性权重——用户点哪个本身就是最终裁决。
 *
 * ## 词片为什么锚在框上方而不是中心
 *
 * 见 [OverlayGeometry.chipAnchor]：词压在物体正中间会挡住用户真正在看的东西。
 */
@Composable
fun ViewfinderOverlay(
    chips: List<WordChip>,
    ambience: List<String>,
    frame: CameraFrame,
    shotKind: ShotKind,
    selectedKey: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier = modifier.fillMaxSize().onSizeChanged { sizePx = it }) {
        if (sizePx.width <= 0 || sizePx.height <= 0) return@Box
        val view = OverlayGeometry.ViewSpec(
            viewWidth = sizePx.width.toFloat(),
            viewHeight = sizePx.height.toFloat(),
            sensorWidth = frame.sensorWidth,
            sensorHeight = frame.sensorHeight,
            rotationDegrees = frame.rotationDegrees,
        )

        // 先画选中框：词片叠在框线之上，选中时框是背景信息、词片是前景结论。
        val selected = chips.firstOrNull { it.key == selectedKey }
        if (selected != null) {
            val rect = OverlayGeometry.map(selected.box, view, frame.crop)
            FocusCorners(
                rect = rect,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.matchParentSize(),
            )
        }

        val ambienceCount = when (shotKind) {
            ShotKind.SCENE -> min(ambience.size, MAX_AMBIENCE_SCENE)
            ShotKind.OBJECT -> min(ambience.size, MAX_AMBIENCE_OBJECT)
            ShotKind.UNCLEAR -> min(ambience.size, MAX_AMBIENCE_NEUTRAL)
        }
        AmbienceLayer(
            words = ambience.take(ambienceCount),
            spread = shotKind != ShotKind.OBJECT,
        )

        val density = LocalDensity.current
        val chipHeightPx = with(density) { CHIP_HEIGHT.toPx() }
        val edgeMarginPx = with(density) { CHIP_EDGE_MARGIN.toPx().roundToInt() }
        val chipMaxWidthPx = with(density) { CHIP_MAX_WIDTH.toPx().roundToInt() }
        chips.forEach { chip ->
            val rect = OverlayGeometry.map(chip.box, view, frame.crop)
            val (ax, ay) = OverlayGeometry.chipAnchor(rect, chipHeightPx)
            val visible = rect.visibleFraction(view.viewWidth, view.viewHeight)
            ChipSticker(
                chip = chip,
                selected = chip.key == selectedKey,
                onClick = { onSelect(if (chip.key == selectedKey) null else chip.key) },
                // 推出画面的词片淡出而不是硬切：半截贴纸比突然消失更不突兀。
                alpha = 0.15f + 0.85f * visible,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .anchoredCenter(ax, ay, edgeMarginPx, sizePx.width, chipMaxWidthPx),
            )
        }
    }
}

/**
 * 词片贴纸。
 *
 * 视觉规范与 [com.ilyskyo.wordlens.ui.components.StickerCard] 同源：白描边 + 暖灰阴影 +
 * 轻微旋转。旋转角由 key 的哈希**确定性地**推出（±3°），同一枚词片在取景、保存、回看
 * 里必须是同一个角度，随机化会让它页面间跳动。
 */
@Composable
private fun ChipSticker(
    chip: WordChip,
    selected: Boolean,
    onClick: () -> Unit,
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    val accents = WordLensTheme.accents
    val rotation = chipRotation(chip.key)
    val container = if (!chip.known) Color(0xE6FFF8F3) else Color(0xF2FFFFFF)
    val contentColor = if (chip.known) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .shadow(
                elevation = if (selected) 10.dp else 6.dp,
                shape = CircleShape,
                ambientColor = accents.shadowTint,
                spotColor = accents.shadowTint,
            )
            // alpha 放在 shadow 之后：描边与文字随推出画面淡出，阴影保留
            // （否则淡出的词片会突然"落"下去）。
            .alpha(alpha)
            .rotate(rotation)
            .border(
                width = if (selected) accents.stickerStrokeWidth + 1.dp else accents.stickerStrokeWidth,
                color = if (selected) MaterialTheme.colorScheme.primary else accents.stickerStroke,
                shape = CircleShape,
            )
            .background(container, CircleShape)
            .padding(horizontal = Space.md, vertical = Space.xs)
            .semantics { contentDescription = chip.word }
            .pointerInput(chip.key) { detectTapGestures { onClick() } },
    ) {
        Text(
            text = if (chip.known) chip.word else "? ${chip.word}",
            style = MaterialTheme.typography.titleSmall,
            color = contentColor,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/** 确定性旋转角：-3..3 度。 */
internal fun chipRotation(key: String): Float {
    val h = abs(key.hashCode())
    return -3f + (h % 61) / 10f
}

/**
 * 把子内容锚到画布坐标 (anchorX, anchorY)：水平居中于该点、顶边落在该点，并夹在画面内。
 *
 * 用 [Modifier.layout] 而不是 offset + 预估宽度：词片宽度随文字变化，两帧测量会让它
 * 第一帧跳到错误位置。这里在测量阶段就拿到真实宽度，一步放对。
 */
private fun Modifier.anchoredCenter(
    anchorX: Float,
    anchorY: Float,
    edgeMarginPx: Int,
    canvasWidthPx: Int,
    maxWidthPx: Int,
): Modifier = layout { measurable, constraints ->
    val maxW = min(constraints.maxWidth - 2 * edgeMarginPx, maxWidthPx).coerceAtLeast(1)
    val placeable = measurable.measure(
        constraints.copy(minWidth = 0, minHeight = 0, maxWidth = maxW),
    )
    val x = (anchorX - placeable.width / 2f).roundToInt()
        .coerceIn(edgeMarginPx, (canvasWidthPx - edgeMarginPx - placeable.width).coerceAtLeast(edgeMarginPx))
    val y = anchorY.roundToInt().coerceAtLeast(edgeMarginPx)
    layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.placeRelative(x, y)
    }
}

/** 氛围词层：非交互，只负责让画面「有空气」。 */
@Composable
private fun BoxScope.AmbienceLayer(
    words: List<String>,
    spread: Boolean,
) {
    if (words.isEmpty()) return
    // 槽位是手挑的：避开中央主体区与底部快门区。spread=false 时只用最靠边的几个。
    val slots = if (spread) AMBIENCE_SLOTS else AMBIENCE_SLOTS.filter { it.edge }
    words.forEachIndexed { i, word ->
        val slot = slots[i % slots.size]
        Text(
            text = word,
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic,
            color = Color.White.copy(alpha = 0.78f),
            fontSize = 14.sp,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.TopStart)
                .anchoredCenteredAtFraction(slot.fx, slot.fy)
                .rotate(slot.rotation),
        )
    }
}

/** 按画布宽高比例放置，水平垂直都居中于该点。 */
private fun Modifier.anchoredCenteredAtFraction(fx: Float, fy: Float): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        layout(w, h) {
            placeable.placeRelative(
                (w * fx - placeable.width / 2f).roundToInt(),
                (h * fy - placeable.height / 2f).roundToInt(),
            )
        }
    }

/** 选中物体的四角括号框：比整圈描边更轻，不遮挡物体本身。 */
@Composable
private fun FocusCorners(
    rect: OverlayGeometry.ScreenRect,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val strokePx = with(density) { 3.dp.toPx() }
    val armCapPx = with(density) { 28.dp.toPx() }
    Canvas(modifier = modifier) {
        val arm = min(min(rect.width, rect.height) * 0.28f, armCapPx)
        val l = rect.left.coerceAtLeast(0f)
        val t = rect.top.coerceAtLeast(0f)
        val r = rect.right.coerceAtMost(size.width)
        val b = rect.bottom.coerceAtMost(size.height)
        // 每个角：顶点 + 水平臂方向 + 垂直臂方向（臂总是朝框内伸）。
        val corners = listOf(
            floatArrayOf(l, t, 1f, 1f),
            floatArrayOf(r, t, -1f, 1f),
            floatArrayOf(l, b, 1f, -1f),
            floatArrayOf(r, b, -1f, -1f),
        )
        corners.forEach { (cx, cy, sx, sy) ->
            drawLine(color, Offset(cx, cy), Offset(cx + arm * sx, cy), strokePx, cap = StrokeCap.Round)
            drawLine(color, Offset(cx, cy), Offset(cx, cy + arm * sy), strokePx, cap = StrokeCap.Round)
        }
    }
}

private data class AmbienceSlot(val fx: Float, val fy: Float, val rotation: Float, val edge: Boolean)

/** 顶部与两侧的留白位。中央留给主体，底部留给快门。 */
private val AMBIENCE_SLOTS = listOf(
    AmbienceSlot(0.14f, 0.08f, -2f, edge = true),
    AmbienceSlot(0.86f, 0.12f, 2f, edge = true),
    AmbienceSlot(0.10f, 0.26f, 1.5f, edge = true),
    AmbienceSlot(0.90f, 0.34f, -1.5f, edge = true),
    AmbienceSlot(0.50f, 0.10f, -1f, edge = false),
    AmbienceSlot(0.16f, 0.48f, 2f, edge = true),
    AmbienceSlot(0.84f, 0.56f, -2f, edge = true),
)

private val CHIP_HEIGHT = 34.dp
private val CHIP_MAX_WIDTH = 200.dp
private val CHIP_EDGE_MARGIN = 10.dp

private const val MAX_AMBIENCE_SCENE = 5
private const val MAX_AMBIENCE_OBJECT = 2
private const val MAX_AMBIENCE_NEUTRAL = 3
