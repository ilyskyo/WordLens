// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.review

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ilyskyo.wordlens.R
import com.ilyskyo.wordlens.srs.Fsrs
import com.ilyskyo.wordlens.ui.components.EmptyState
import com.ilyskyo.wordlens.ui.components.OutlinedAction
import com.ilyskyo.wordlens.ui.components.SpeakButton
import com.ilyskyo.wordlens.ui.components.TonalButton
import com.ilyskyo.wordlens.ui.theme.IpaTextStyle
import com.ilyskyo.wordlens.ui.theme.Space
import com.ilyskyo.wordlens.ui.theme.WordLensTheme

/** 复习页状态。 */
data class ReviewUiState(
    val done: Int = 0,
    val total: Int = 0,
    val headword: String? = null,
    val ipa: String? = null,
    val gloss: String? = null,
    val example: String? = null,
    /** 四个评级各自会产生的间隔天数，直接标在按钮上。 */
    val intervalPreview: Map<Fsrs.Rating, Int> = emptyMap(),
    val revealed: Boolean = false,
    val finished: Boolean = false,
    val streakDays: Int = 0,
    val speakEnabled: Boolean = true,
)

/**
 * 复习页。
 *
 * 关键设计：两个按钮上**直接写出这次评分会带来的间隔**（"模糊 2天 / 记住了 3周"）。
 * 间隔重复最反直觉的地方是「记住了」不等于「明天再见」，把数字摆出来，调度器才从
 * 黑箱变成可理解的选择。
 */
@Composable
fun ReviewScreen(
    bottomInset: PaddingValues,
    state: ReviewUiState = ReviewUiState(),
    onReveal: () -> Unit = {},
    onGrade: (Fsrs.Rating) -> Unit = {},
    onSpeak: () -> Unit = {},
    onBackToCollection: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Space.lg)
            .padding(top = Space.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ReviewProgress(done = state.done, total = state.total)

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            when {
                state.finished -> ReviewFinished(
                    streakDays = state.streakDays,
                    onBackToCollection = onBackToCollection,
                )

                state.headword == null -> EmptyState(
                    emoji = "🌱",
                    title = "现在没有到期的词",
                    body = "先去拍几个，复习队列会在合适的时候把你叫回来。",
                )

                else -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    FlipCard(
                        headword = state.headword,
                        ipa = state.ipa,
                        gloss = state.gloss,
                        example = state.example,
                        revealed = state.revealed,
                        onClick = onReveal,
                    )
                    SpeakButton(
                        onClick = onSpeak,
                        contentDescription = stringResource(R.string.capture_speak),
                        enabled = state.speakEnabled,
                    )
                }
            }
        }

        if (state.headword != null && !state.finished) {
            ReviewActions(
                revealed = state.revealed,
                intervals = state.intervalPreview,
                onGrade = onGrade,
                modifier = Modifier.padding(bottom = bottomInset.calculateBottomPadding() + Space.md),
            )
        } else {
            Spacer(Modifier.height(bottomInset.calculateBottomPadding() + Space.md))
        }
    }
}

@Composable
private fun ReviewProgress(done: Int, total: Int, modifier: Modifier = Modifier) {
    val fraction = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 8.dp)
                .clip(CircleShape),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            // 8dp 高圆角：M3 的默认是 4dp 圆角，8dp 会变成胶囊
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        Text(
            text = stringResource(R.string.review_progress, done, total),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 翻面卡片。
 *
 * 绕 Y 轴 180° 翻转。为了避免翻转过程中镜像的文字被看清，用 `cameraDistance` 调大
 * 视角并把两面都画在同一个旋转容器里——标准做法，但这里有个细节：文字在 90° 时正好
 * 侧对用户看不见，所以不额外做「过半就切换内容」的逻辑就不会有可读的错误状态。
 */
@Composable
private fun FlipCard(
    headword: String,
    ipa: String?,
    gloss: String?,
    example: String?,
    revealed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(revealed) {
        rotation.animateTo(
            targetValue = if (revealed) 180f else 0f,
            animationSpec = tween(FLIP_MS),
        )
    }

    val showBack = rotation.value > 90f
    val accents = WordLensTheme.accents

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(CARD_ASPECT)
            .shadow(8.dp, MaterialTheme.shapes.extraLarge, ambientColor = accents.shadowTint, spotColor = accents.shadowTint)
            .graphicsLayer {
                rotationY = rotation.value
                cameraDistance = 14f * density
            }
            .testTag("reviewCard")
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Space.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (showBack) {
                // 背面内容再转 180°，否则它是镜像的。
                Column(
                    modifier = Modifier.graphicsLayer { rotationY = 180f },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    gloss?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )
                    }
                    example?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                Text(
                    text = headword,
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                ipa?.let {
                    Text(
                        text = it,
                        style = IpaTextStyle.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.review_flip_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

private const val FLIP_MS = 400
private val CARD_ASPECT = 320f / 420f

/**
 * 四个评级按钮。
 *
 * 只在翻面后启用：先回忆再评分是间隔重复的基本要求，背面还没看就按「记住了」的自评
 * 几乎没有信息量，也会把调度器的输入污染掉。
 */
@Composable
private fun ReviewActions(
    revealed: Boolean,
    intervals: Map<Fsrs.Rating, Int>,
    onGrade: (Fsrs.Rating) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        // 「模糊」用 Outline + Secondary，因为它不是主要路径，但必须同样易点。
        OutlinedAction(
            text = gradeLabel("模糊", intervals[Fsrs.Rating.AGAIN]),
            onClick = { onGrade(Fsrs.Rating.AGAIN) },
            enabled = revealed,
            modifier = Modifier.weight(1f),
        )
        TonalButton(
            text = gradeLabel("记住了", intervals[Fsrs.Rating.GOOD]),
            onClick = { onGrade(Fsrs.Rating.GOOD) },
            enabled = revealed,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 把间隔天数压成一行短标签。
 *
 * 超过 60 天显示「2月」，30 天以上显示「N周」，更短显示「N天」——复习界面上
 * 「473」这种数字既占地方又读不出量级。
 */
internal fun formatInterval(days: Int): String = when {
    days <= 0 -> "现在"
    days < 30 -> "${days}天"
    days < 365 -> "${(days / 7.0).let { if (it < 10) Math.round(it).toInt().toString() else "${(it / 10.0).toInt() * 10}+" }}周"
    else -> "${(days / 30.4).toInt()}月"
}

private fun gradeLabel(base: String, days: Int?): String =
    if (days == null) base else "$base · ${formatInterval(days)}"

@Composable
private fun ReviewFinished(streakDays: Int, onBackToCollection: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Text(text = "🎉", fontSize = 64.sp)
        Text(
            text = stringResource(R.string.review_done_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(R.string.review_done_body, streakDays),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TonalButton(
            text = stringResource(R.string.review_back),
            onClick = onBackToCollection,
            modifier = Modifier.padding(top = Space.sm),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFFF8F3, widthDp = 380, heightDp = 760)
@Composable
private fun ReviewFrontPreview() {
    WordLensTheme {
        ReviewScreen(
            bottomInset = PaddingValues(0.dp),
            state = ReviewUiState(
                done = 3,
                total = 10,
                headword = "cup",
                ipa = "/kʌp/",
                revealed = false,
                intervalPreview = mapOf(Fsrs.Rating.AGAIN to 1, Fsrs.Rating.GOOD to 6),
            ),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFFF8F3, widthDp = 380, heightDp = 760)
@Composable
private fun ReviewBackPreview() {
    WordLensTheme {
        ReviewScreen(
            bottomInset = PaddingValues(0.dp),
            state = ReviewUiState(
                done = 4,
                total = 10,
                headword = "cup",
                ipa = "/kʌp/",
                gloss = "杯子；奖杯",
                example = "She drank a cup of tea.",
                revealed = true,
                intervalPreview = mapOf(Fsrs.Rating.AGAIN to 1, Fsrs.Rating.GOOD to 6),
            ),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFFF8F3, widthDp = 380, heightDp = 700)
@Composable
private fun ReviewFinishedPreview() {
    WordLensTheme {
        ReviewScreen(
            bottomInset = PaddingValues(0.dp),
            state = ReviewUiState(done = 10, total = 10, finished = true, streakDays = 12),
        )
    }
}
