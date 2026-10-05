// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.remember

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ilyskyo.wordlens.R
import com.ilyskyo.wordlens.data.model.ReviewItem
import com.ilyskyo.wordlens.data.model.ReviewSource
import com.ilyskyo.wordlens.data.model.StudyMaterial
import com.ilyskyo.wordlens.srs.Fsrs
import com.ilyskyo.wordlens.ui.components.EmptyState
import com.ilyskyo.wordlens.ui.components.OutlinedAction
import com.ilyskyo.wordlens.ui.components.SpeakButton
import com.ilyskyo.wordlens.ui.components.TonalButton
import com.ilyskyo.wordlens.ui.theme.IpaTextStyle
import com.ilyskyo.wordlens.ui.theme.Space
import com.ilyskyo.wordlens.ui.theme.WordLensTheme

/** 「记住」页状态。 */
data class RememberUiState(
    val material: StudyMaterial = StudyMaterial.WORDS_AND_EVENTS,
    val done: Int = 0,
    val total: Int = 0,
    val current: RememberCard? = null,
    val revealed: Boolean = false,
    val finished: Boolean = false,
    val streakDays: Int = 0,
    val speakEnabled: Boolean = true,
    /** 四个评级各自会产生的间隔天数，直接印在按钮上。 */
    val intervals: Map<Fsrs.Rating, Int> = emptyMap(),
    /** 用户手动「标记已掌握」而移出队列的卡。它们不再被调度，但可以取消。 */
    val archived: List<ReviewItem> = emptyList(),
)

/**
 * 一张待复习的卡片。
 *
 * 词汇与事件是同一个类型：它们共用 FSRS 队列、共用翻面交互，差别只在卡片上摆什么。
 * 强行拆成两个 Composable 只会让翻面动画、按钮布局、无障碍语义各写一遍。
 */
data class RememberCard(
    val item: ReviewItem,
    /** 正面主标题：单词或事件的一句话。 */
    val prompt: String,
    val ipa: String? = null,
    val gloss: String? = null,
    val example: String? = null,
    val photo: android.graphics.Bitmap? = null,
    val source: ReviewSource = ReviewSource.USER,
    /** 事件所属日期，事件卡上显示。 */
    val dayLabel: String? = null,
)

/**
 * 「记住」页。
 *
 * ## 为什么 EASY 不是「归档」
 *
 * FSRS 里 EASY 的意思是**间隔会变得很长**，不是「学完了」。如果把它做成"标记并归档"，
 * 这张卡就永远不再被调度，用户损失的是三个月后那次复习——而那恰恰是他已经投入成本
 * 想要保住的东西。彻底不想再见某张卡，应该放在卡片的长按菜单里作为一个独立的显式动作，
 * 语义是「别再给我看它」，与「我记得很牢」是两件事。
 *
 * ## AI 来源标记
 *
 * 复习是把内容反复巩固的过程。模型编错的一条事件会被 FSRS 忠实地刻进长期记忆，而用户
 * 永远不会知道。所以 AI 生成的卡片在正面就带上来源提示，而不是等用户答错时才发现。
 */
@Composable
fun RememberScreen(
    bottomInset: PaddingValues,
    state: RememberUiState = RememberUiState(),
    onMaterialChange: (StudyMaterial) -> Unit = {},
    onReveal: () -> Unit = {},
    onGrade: (Fsrs.Rating) -> Unit = {},
    onSpeak: () -> Unit = {},
    onMarkMastered: () -> Unit = {},
    onUnmark: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Space.lg)
            .padding(top = Space.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MaterialRow(
            material = state.material,
            onMaterialChange = onMaterialChange,
        )
        state.total.let { RememberProgress(done = state.done, total = it) }

        // 归档是唯一会永久改变队列的动作，所以它必须始终可撤销——入口在进度条下面常驻，
        // 而不是只在空状态里出现（否则刚归档完、队列还有下一张时就没有反悔的地方）。
        if (state.archived.isNotEmpty()) {
            var archivedOpen by remember { mutableStateOf(false) }
            TextButton(onClick = { archivedOpen = true }) {
                Text(
                    text = stringResource(R.string.review_archived_count, state.archived.size),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (archivedOpen) {
                ArchivedDialog(
                    items = state.archived,
                    onUnmark = onUnmark,
                    onDismiss = { archivedOpen = false },
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            when {
                state.finished -> RememberFinished(
                    streakDays = state.streakDays,
                    modifier = Modifier.fillMaxWidth(),
                )

                state.current == null -> EmptyState(
                    emoji = "\uD83C\uDF31",
                    title = stringResource(R.string.review_empty_title),
                    body = stringResource(R.string.review_empty_body),
                )

                else -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    var menuOpen by remember(state.current.item.id) { mutableStateOf(false) }
                    Box {
                        FlipCard(
                            card = state.current,
                            revealed = state.revealed,
                            onClick = onReveal,
                            onLongClick = { menuOpen = true },
                        )
                        // 归档动作只放这里，不进评级按钮行：「别再给我看它」和「我记得很牢」
                        // 必须在界面上分开，否则用户会把还没记住的词当成学完了。
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.review_mark_mastered)) },
                                onClick = {
                                    menuOpen = false
                                    onMarkMastered()
                                },
                            )
                        }
                    }
                    if (state.current.item is ReviewItem.Word) {
                        // 只有词汇才有得读；事件是一句话，发音没有意义。
                        SpeakButton(
                            onClick = onSpeak,
                            contentDescription = stringResource(R.string.capture_speak),
                            enabled = state.speakEnabled && state.current.ipa != null,
                        )
                    }
                }
            }
        }

        if (state.current != null && !state.finished) {
            GradeRow(
                revealed = state.revealed,
                intervals = state.intervals,
                onGrade = onGrade,
                modifier = Modifier.padding(bottom = bottomInset.calculateBottomPadding() + Space.md),
            )
        } else {
            Box(Modifier.height(bottomInset.calculateBottomPadding() + Space.md))
        }
    }
}

@Composable
private fun MaterialRow(material: StudyMaterial, onMaterialChange: (StudyMaterial) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        StudyMaterial.entries.forEach { m ->
            val selected = material == m
            FilterChip(
                selected = selected,
                onClick = { onMaterialChange(m) },
                label = {
                    Text(
                        text = stringResource(m.titleRes),
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                shape = CircleShape,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selected,
                    borderColor = MaterialTheme.colorScheme.outline,
                ),
            )
        }
    }
}

@Composable
private fun RememberProgress(done: Int, total: Int, modifier: Modifier = Modifier) {
    val fraction = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 8.dp)
                .clip(CircleShape),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            // 8dp 高的圆角条要配 Round 端点，否则两端是平的，胶囊感出不来。
            strokeCap = StrokeCap.Round,
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
 * 绕 Y 轴 180°，背面内容再转 180° 回来。不做「过半就切内容」的处理：旋转到 90° 时文字
 * 正好侧对用户看不见，所以不存在会被读到的错误状态。
 */
@Composable
private fun FlipCard(
    card: RememberCard,
    revealed: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotation = remember(card.item.id) { Animatable(0f) }
    LaunchedEffect(revealed, card.item.id) {
        rotation.animateTo(if (revealed) 180f else 0f, animationSpec = tween(FLIP_MS))
    }
    val showBack = rotation.value > 90f
    val accents = WordLensTheme.accents

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(FLIP_CARD_ASPECT)
            .shadow(
                elevation = 8.dp,
                shape = MaterialTheme.shapes.extraLarge,
                ambientColor = accents.shadowTint,
                spotColor = accents.shadowTint,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .graphicsLayer {
                rotationY = rotation.value
                cameraDistance = 14f * density
            }
            .semantics { contentDescription = "" },
    ) {
        AnimatedContent(
            targetState = showBack,
            transitionSpec = { fadeIn(tween(FLIP_MS / 2)) togetherWith fadeOut(tween(FLIP_MS / 2)) },
            label = "cardSide",
        ) { back ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Space.lg)
                    .then(if (back) Modifier.graphicsLayer { rotationY = 180f } else Modifier),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (back) {
                    CardBack(card = card)
                } else {
                    CardFront(card = card)
                }
            }
        }
    }
}

/**
 * 已归档清单：长按「标记已掌握」之后唯一能把卡捞回来的地方。
 *
 * 只做两件事——列出来、取消标记。不在这上面做复习或编辑，那是另一个页面该负的责任。
 */
@Composable
private fun ArchivedDialog(
    items: List<ReviewItem>,
    onUnmark: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.review_archived_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                items.forEach { item ->
                    val label = when (item) {
                        is ReviewItem.Word -> item.card.headword
                        is ReviewItem.Event -> item.card.text
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onUnmark(item.id) }) {
                            Text(
                                text = stringResource(R.string.review_unmaster),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.review_archived_close))
            }
        },
    )
}

@Composable
private fun CardFront(card: RememberCard, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.sm, Alignment.CenterVertically),
    ) {
        // 事件卡带照片，因为「那天」本身就是记忆线索；纯文字卡不带，免得空一大块。
        if (card.photo != null) {
            Image(
                bitmap = card.photo.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(96.dp)
                    .clip(MaterialTheme.shapes.medium),
            )
        }
        card.dayLabel?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = card.prompt,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        card.ipa?.let {
            Text(
                text = it,
                style = IpaTextStyle.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SourceBadge(source = card.source, modifier = Modifier.padding(top = Space.xs))
        Text(
            text = stringResource(R.string.review_flip_hint),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun CardBack(card: RememberCard, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.sm, Alignment.CenterVertically),
    ) {
        card.gloss?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
        card.example?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** AI 生成的卡片带一个明确标记。用户有权知道这段文字是谁写的。 */
@Composable
private fun SourceBadge(source: ReviewSource, modifier: Modifier = Modifier) {
    if (source != ReviewSource.AI) return
    Text(
        text = stringResource(R.string.review_verify_source),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.tertiary,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f))
            .padding(horizontal = Space.sm, vertical = Space.xs),
    )
}

@Composable
private fun GradeRow(
    revealed: Boolean,
    intervals: Map<Fsrs.Rating, Int>,
    onGrade: (Fsrs.Rating) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        OutlinedAction(
            text = gradeLabel(
                base = stringResource(R.string.review_fuzzy),
                days = intervals[Fsrs.Rating.AGAIN],
            ),
            onClick = { onGrade(Fsrs.Rating.AGAIN) },
            enabled = revealed,
            modifier = Modifier.weight(1f),
        )
        TonalButton(
            text = gradeLabel(
                base = stringResource(R.string.review_got_it),
                days = intervals[Fsrs.Rating.GOOD],
            ),
            onClick = { onGrade(Fsrs.Rating.GOOD) },
            enabled = revealed,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 把间隔天数压成一行短标签。
 *
 * 超过一年显示「1年+」，而不是 473 天——复习界面上一个三位数既占地方又读不出量级，
 * 而「1年+」直接告诉用户这张卡已经稳了。
 */
internal fun formatInterval(days: Int): String = when {
    days <= 0 -> "\u73B0\u5728"
    days == 1 -> "1\u5929"
    days < 30 -> "${days}\u5929"
    days < 365 -> "${formatWeeks(days)}\u5468"
    days < 730 -> "1\u5E74"
    else -> "1\u5E74+"
}

/**
 * 周数化。10 周以上不再给精确值，改成「10+」——复习界面上一个三位数既占地方又读不出
 * 量级，而「10+周」已经足够传达「这张卡已经稳了」这个信息。
 */
private fun formatWeeks(days: Int): String {
    val weeks = days / 7.0
    return if (weeks < 10.0) {
        Math.round(weeks).toInt().coerceAtLeast(1).toString()
    } else {
        "${(weeks / 10.0).toInt() * 10}+"
    }
}

private fun gradeLabel(base: String, days: Int?): String =
    if (days == null) base else "$base \u00B7 ${formatInterval(days)}"

@Composable
private fun RememberFinished(streakDays: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Text(text = "\uD83C\uDF89", fontSize = 64.sp)
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
    }
}

private const val FLIP_MS = 400

/** 320x420 的比例，取自设计规范。 */
private const val FLIP_CARD_ASPECT = 320f / 420f

@Preview(showBackground = true, backgroundColor = 0xFFFFF8F3, widthDp = 380, heightDp = 800)
@Composable
private fun RememberWordPreview() {
    WordLensTheme {
        RememberScreen(
            bottomInset = PaddingValues(0.dp),
            state = RememberUiState(
                done = 3,
                total = 10,
                current = RememberCard(
                    item = ReviewItem.Word(
                        com.ilyskyo.wordlens.data.model.WordCard(
                            id = "w1",
                            headword = "cup",
                            language = "en",
                        ),
                    ),
                    prompt = "cup",
                    ipa = "/k\u028Ap/",
                    gloss = "\u676F\u5B50",
                ),
                intervals = mapOf(Fsrs.Rating.AGAIN to 1, Fsrs.Rating.GOOD to 27),
            ),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFFF8F3, widthDp = 380, heightDp = 800)
@Composable
private fun RememberEventPreview() {
    WordLensTheme {
        RememberScreen(
            bottomInset = PaddingValues(0.dp),
            state = RememberUiState(
                material = StudyMaterial.EVENTS,
                done = 6,
                total = 10,
                revealed = true,
                current = RememberCard(
                    item = ReviewItem.Event(
                        com.ilyskyo.wordlens.data.model.EventCard(
                            id = "e1",
                            text = "\u548C\u670B\u53CB\u5728\u90a3\u5bb6\u9762\u9986\u5403\u4e86\u9762",
                            happenedAt = System.currentTimeMillis(),
                            source = ReviewSource.AI,
                        ),
                    ),
                    prompt = "\u90a3\u5929\u7684\u996d",
                    gloss = "\u548C\u670b\u53CB\u5728\u90a3\u5bb6\u9762\u9986\u5403\u4e86\u9762",
                    dayLabel = "3\u67085\u65E5",
                    source = ReviewSource.AI,
                ),
            ),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFFF8F3, widthDp = 380, heightDp = 700)
@Composable
private fun RememberEmptyPreview() {
    WordLensTheme {
        RememberScreen(bottomInset = PaddingValues(0.dp), state = RememberUiState())
    }
}