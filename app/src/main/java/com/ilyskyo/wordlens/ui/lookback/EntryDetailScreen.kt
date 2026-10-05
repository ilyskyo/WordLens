// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.lookback

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ilyskyo.wordlens.R
import com.ilyskyo.wordlens.data.model.Entry
import com.ilyskyo.wordlens.data.model.EntrySource
import com.ilyskyo.wordlens.ui.components.PrimaryButton
import com.ilyskyo.wordlens.ui.icons.WordLensIcons
import com.ilyskyo.wordlens.ui.theme.Space
import com.ilyskyo.wordlens.ui.theme.WordLensTheme
import com.ilyskyo.wordlens.vision.camera.CameraFocusMath.NormBox

/**
 * 详情页上长在物体中的一个词。
 *
 * [box] 已经是**显示图**的归一化坐标（仓库层换算过一次），界面只管把它乘上图片尺寸。
 * 换算放在 VM 而不是这里，是为了让那条坐标链路能被 JVM 测试覆盖。
 */
data class ObjectPlace(
    val id: String,
    val word: String,
    val gloss: String?,
    val box: NormBox,
    val hasSticker: Boolean,
)

/** 条目详情状态。照片为 null 时页面照常工作——云备份换机只带回文字。 */
data class EntryDetailState(
    val entry: Entry,
    val photo: Bitmap?,
    val objects: List<ObjectPlace> = emptyList(),
    val ambience: List<String> = emptyList(),
    val moodLabel: String? = null,
    val eventDraft: String = "",
    val eventCount: Int = 0,
)

/**
 * 条目详情：**在原图上把词重新长回物体上**。
 *
 * 这是这本日记和「一张照片 + 一行单词」的分界线。时间轴上的卡片太小，撑不住这件事，
 * 所以点进来之后照片升到全屏，词片回到它们当时的位置，样式与取景器里一致（白描边 + 阴影
 * + 轻微旋转）——用户当时看到的就是这个形状，回看时换形状会被读成「不是同一个东西」。
 */
@Composable
fun EntryDetailScreen(
    state: EntryDetailState,
    onBack: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSaveEvent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = WordLensIcons.Close,
                        contentDescription = stringResource(R.string.detail_close),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (state.eventCount > 0) {
                    Text(
                        text = stringResource(R.string.detail_event_count, state.eventCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = Space.md),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.lg)
                    .padding(bottom = Space.xl),
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                PhotoArea(state.photo, state.objects)
                EntryBody(state)
                EventComposer(
                    draft = state.eventDraft,
                    onDraftChange = onDraftChange,
                    onSave = onSaveEvent,
                )
            }
        }
    }
}

/** 照片 + 压在物体上方的词片。词片锚在上沿，不压住物体本身。 */
@Composable
private fun PhotoArea(bitmap: Bitmap?, objects: List<ObjectPlace>, modifier: Modifier = Modifier) {
    if (bitmap == null) {
        // 换机后文字带回来了、照片没回来。这时正文照常显示，不能整页空白。
        Box(
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(3f / 2f)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.detail_photo_missing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
            .clip(MaterialTheme.shapes.large),
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.matchParentSize(),
        )
        val imageWidth = maxWidth
        val imageHeight = maxHeight
        // 上限先夹到不小于下限：否则极窄布局下 coerceIn 会因 min > max 直接抛异常。
        val maxChipWidth = (imageWidth * 0.7f).coerceAtLeast(MIN_CHIP_WIDTH)
        objects.forEach { place ->
            val chipWidth = (imageWidth * place.box.width).coerceIn(MIN_CHIP_WIDTH, maxChipWidth)
            val x = (imageWidth * place.box.left).coerceIn(0.dp, (imageWidth - chipWidth).coerceAtLeast(0.dp))
            // 锚在框的**上方**：词压在物体正中会挡住用户真正在看的东西。
            val y = (imageHeight * place.box.top - CHIP_LIFT).coerceAtLeast(0.dp)
            GrownWord(place = place, width = chipWidth, modifier = Modifier.offset(x = x, y = y))
        }
    }
}

/** 一枚长回画面上的词片。样式对齐取景器：白底、描边、阴影、±3° 微旋转。 */
@Composable
private fun GrownWord(place: ObjectPlace, width: Dp, modifier: Modifier = Modifier) {
    val accents = WordLensTheme.accents
    val shape = CircleShape
    Box(
        modifier = modifier
            .width(width)
            .shadow(elevation = 6.dp, shape = shape, ambientColor = accents.shadowTint, spotColor = accents.shadowTint)
            .rotate(stickerTilt(place.id))
            .border(BorderStroke(accents.stickerStrokeWidth, accents.stickerStroke), shape)
            .background(Color(0xF2FFFFFF), shape)
            .padding(horizontal = Space.sm, vertical = Space.xs),
    ) {
        Column {
            Text(
                text = place.word,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            place.gloss?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 文字区：标题、摘要（含 AI 来源提示）、mood、氛围词、被婉拒的词。 */
@Composable
private fun EntryBody(state: EntryDetailState, modifier: Modifier = Modifier) {
    val entry = state.entry
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(
            text = entry.title?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.detail_untitled),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        entry.summary?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        // 用户自己写的东西被 AI 改过要明确告知，不能悄悄替换（§8.1）。
        // 摘要目前只有自带密钥的云端后端会写，所以判 CLOUD。
        if (entry.summarySource == EntrySource.CLOUD) {
            Text(
                text = stringResource(R.string.review_verify_source),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        val chips = buildList {
            state.moodLabel?.let { add(it) }
            addAll(state.ambience)
        }
        if (chips.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
                modifier = Modifier.fillMaxWidth(),
            ) {
                chips.forEach { word ->
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = word,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = Space.sm, vertical = Space.xs),
                        )
                    }
                }
            }
        }

        if (entry.declinedWords.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(
                    text = stringResource(R.string.detail_declined),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = entry.declinedWords.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 「补一句当时发生了什么」。
 *
 * 这是事件卡唯一的诞生地。写下来的这句话按自己的间隔回到复习队列里，和词卡共用同一个调度器
 * ——所以界面这里只负责「记下」，不解释排期。
 */
@Composable
private fun EventComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = { Text(stringResource(R.string.detail_event_hint)) },
            minLines = 2,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton(
            text = stringResource(R.string.detail_event_save),
            onClick = onSave,
            enabled = draft.isNotBlank(),
        )
    }
}

private val MIN_CHIP_WIDTH = 64.dp
private val CHIP_LIFT = 30.dp
