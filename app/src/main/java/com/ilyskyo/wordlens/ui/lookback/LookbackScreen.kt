// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.lookback

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ilyskyo.wordlens.R
import com.ilyskyo.wordlens.data.model.Entry
import com.ilyskyo.wordlens.data.model.EntryMood
import com.ilyskyo.wordlens.ui.components.EmptyState
import com.ilyskyo.wordlens.ui.theme.IpaTextStyle
import com.ilyskyo.wordlens.ui.theme.Space
import com.ilyskyo.wordlens.ui.theme.WordLensTheme
import com.ilyskyo.wordlens.ui.theme.stickerCorner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle

/** 回看页状态。 */
data class LookbackUiState(
    /** 按天倒序分组。组内也是倒序，所以最新一条永远在屏幕最上方。 */
    val groups: List<DayGroup> = emptyList(),
    val todayCount: Int = 0,
)

/**
 * 时间轴上的一天。
 *
 * [label] 是已经在仓库层本地化好的标题（今天 / 昨天 / 具体日期）。分组键 [day] 用 ISO
 * 日期而不是标签文本，因为标签会随语言变，而 `key` 变了会让 LazyColumn 整列重建。
 */
data class DayGroup(val day: String, val label: String, val cards: List<EntryCard>)

/**
 * 时间轴上的一张卡片。
 *
 * 照片与词分开持有而不是直接把 [Entry] 传下去：这一页需要按渲染进度延迟解码照片，
 * 而且词是「词典条目 + 展示文本」，与存储层的 [Entry] 不是一回事。
 */
data class EntryCard(
    val entry: Entry,
    val photo: android.graphics.Bitmap? = null,
    val sticker: android.graphics.Bitmap? = null,
    /** 由词库解析出的可展示词：单词、音标、释义。 */
    val words: List<CardWord> = emptyList(),
)

data class CardWord(
    val text: String,
    val ipa: String? = null,
    val gloss: String? = null,
    /** 该词实际所属语言（BCP-47 短 tag），发音按钮要用它选 TTS locale。 */
    val languageTag: String? = null,
)

/**
 * 回看页：按时间倒序的照片时间轴。
 *
 * ## 为什么首页是时间而不是队列
 *
 * 一个词汇 App 的首页是「今天要过 N 个」，因为它的用户目标是任务完成率。但这本日记的
 * 用户目标是**回看**——她想的是「上周在哪儿吃过那家面」。任务队列会把她的注意力锁在
 * 待办上，而她真正想做的事是翻照片。所以时间轴在首页，复习在「记住」页里。
 *
 * ## 两种卡片形态
 *
 * 用户点过物体 → 有抠图贴纸，浮在照片右下角；
 * 直接存场景 → 没有贴纸，右下角让给氛围词与 mood。
 *
 * 这一点必须区分：让「没点物体」的用户看到一张缺了角的卡片，会以为自己的记录不完整，
 * 而那其实是另一种玩法，而且更常见。
 */
@Composable
fun LookbackScreen(
    topInset: PaddingValues,
    bottomInset: PaddingValues,
    state: LookbackUiState = LookbackUiState(),
    onOpenEntry: (String) -> Unit = {},
    onSpeak: (EntryCard) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (state.groups.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(topInset),
        ) {
            EmptyState(
                emoji = "\uD83D\uDCF7",
                title = stringResource(R.string.lookback_empty_title),
                body = stringResource(R.string.lookback_empty_body),
                modifier = Modifier.align(Alignment.Center),
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Space.md,
            end = Space.md,
            // 静止时问候语不能被悬浮页签压住；滚动起来照片从页签下方穿过仍是想要的效果。
            top = topInset.calculateTopPadding() + Space.sm,
            bottom = bottomInset.calculateBottomPadding() + Space.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        item(key = "greeting") {
            Greeting(todayCount = state.todayCount, modifier = Modifier.padding(bottom = Space.sm))
        }
        state.groups.forEach { group ->
            // 分组头压在当天第一张卡上方：翻时间轴时「哪天」比「几点」更重要。
            item(key = "day-${group.day}") {
                DayHeader(group.label)
            }
            items(group.cards, key = { it.entry.id }) { card ->
                TimelineRow(card = card, onOpen = { onOpenEntry(card.entry.id) }, onSpeak = { onSpeak(card) })
            }
        }
    }
}

/** 一天的分组标题。挂在时间轴线的外侧，不与卡片抢注意力。 */
@Composable
private fun DayHeader(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Space.sm, start = TIMELINE_WIDTH + Space.sm),
    )
}

@Composable
private fun Greeting(todayCount: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Column {
            Text(
                text = stringResource(timeOfDayGreeting().labelRes()),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (todayCount > 0) {
                Text(
                    text = stringResource(R.string.lookback_today_count, todayCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 时间轴的一行：左侧竖线 + 节点圆点，右侧卡片。
 *
 * 竖线用 `Canvas` 画而不是给每一行都画一个上下半截——那样行与行之间的接缝会在滚动时
 * 露出细缝，而整页一条线只在首尾做渐隐。
 */
@Composable
private fun TimelineRow(
    card: EntryCard,
    onOpen: () -> Unit,
    onSpeak: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        // ── 左侧时间轴 ──────────────────────────────────────────────
        Column(
            modifier = Modifier.width(TIMELINE_WIDTH),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .width(TIMELINE_STROKE)
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.outline),
            )
            Box(
                Modifier
                    .size(TIMELINE_NODE)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = CircleShape,
                    ),
            )
            Box(
                Modifier
                    .width(TIMELINE_STROKE)
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.outline),
            )
        }

        // ── 卡片 ────────────────────────────────────────────────────
        EntryTimelineCard(
            card = card,
            onOpen = onOpen,
            onSpeak = onSpeak,
            modifier = Modifier
                .weight(1f)
                .padding(start = Space.sm),
        )
    }
}

/**
 * 日记卡片。
 *
 * 照片是主体，词是注释——所以照片占满卡片上部，词排在下方的纸面上。这个比例不能反：
 * 反过来就成了「一个词配一张图」，那是词汇卡，不是日记。
 */
@Composable
private fun EntryTimelineCard(
    card: EntryCard,
    onOpen: () -> Unit,
    onSpeak: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accents = WordLensTheme.accents
    val entry = card.entry

    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        tonalElevation = 1.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            // ── 照片区（含右下角贴纸或 mood） ────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(entryAspectRatio),
            ) {
                if (card.photo != null) {
                    Image(
                        bitmap = card.photo.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                }

                // 日期标签压在照片左上角：白字 + 轻微压暗，保证在浅色照片上也读得清。
                Surface(
                    shape = CircleShape,
                    color = androidx.compose.ui.graphics.Color(0x66000000),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(Space.sm),
                ) {
                    Text(
                        text = formatDay(entry.takenAt, LocalContext.current),
                        style = MaterialTheme.typography.labelSmall,
                        color = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.padding(horizontal = Space.sm, vertical = Space.xs),
                    )
                }

                // 有贴纸就浮贴纸，没有就把这个位置让给 mood——不留空缺。
                if (card.sticker != null) {
                    Image(
                        bitmap = card.sticker.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(STICKER_SIZE)
                            .rotate(stickerTilt(entry.id))
                            .padding(Space.sm),
                    )
                } else if (entry.mood != null) {
                    MoodBadge(mood = entry.mood, modifier = Modifier.align(Alignment.BottomEnd))
                }
            }

            // ── 词与文本区 ────────────────────────────────────────────
            EntryTextBlock(card = card)
        }
    }
}

/**
 * 卡片上的文字区：标题、摘要，以及长在画面上的词。
 *
 * 三者全空时**整段不占位**。留着它会剩下一块 16+16dp 的纯白，而「什么都没点、直接按快门」
 * 恰恰是这个产品里最完整、也最常见的一个动作——每张卡都带一块空洞，比少一行字糟得多。
 */
@Composable
private fun EntryTextBlock(card: EntryCard, modifier: Modifier = Modifier) {
    val entry = card.entry
    val title = entry.displayTitle()
    val summary = entry.summary?.takeIf { it.isNotBlank() }
    if (title == null && summary == null && card.words.isEmpty()) return

    Column(
        modifier = modifier.padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        title?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        summary?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = SUMMARY_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (card.words.isNotEmpty()) {
            Text(
                text = card.words.joinToString("  ·  ") { it.text },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            card.words.firstNotNullOfOrNull { it.ipa }?.let { ipa ->
                Text(
                    text = ipa,
                    style = IpaTextStyle.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            card.words.firstNotNullOfOrNull { it.gloss }?.let { gloss ->
                Text(
                    text = gloss,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MoodBadge(mood: EntryMood, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        modifier = modifier.padding(Space.sm),
    ) {
        Text(
            text = "${mood.emoji} ${stringResource(mood.labelRes)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = Space.sm, vertical = Space.xs),
        )
    }
}

// ── 时间与格式化 ──────────────────────────────────────────────────────

private val ZONE: ZoneId get() = ZoneId.systemDefault()

/** 一天里的五个时段。选哪一段是逻辑，怎么念出来是资源——分开才能跟着系统语言走。 */
internal enum class DayGreeting { MORNING, NOON, AFTERNOON, EVENING, LATE_NIGHT }

/** 问候语按时段选，不查网络也不要定位。 */
internal fun timeOfDayGreeting(now: Instant = Instant.now()): DayGreeting {
    return when (now.atZone(ZONE).hour) {
        in 5..10 -> DayGreeting.MORNING
        in 11..13 -> DayGreeting.NOON
        in 14..17 -> DayGreeting.AFTERNOON
        in 18..22 -> DayGreeting.EVENING
        else -> DayGreeting.LATE_NIGHT
    }
}

@StringRes
internal fun DayGreeting.labelRes(): Int = when (this) {
    DayGreeting.MORNING -> R.string.greeting_morning
    DayGreeting.NOON -> R.string.greeting_noon
    DayGreeting.AFTERNOON -> R.string.greeting_afternoon
    DayGreeting.EVENING -> R.string.greeting_evening
    DayGreeting.LATE_NIGHT -> R.string.greeting_late_night
}

/**
 * 条目的日期标签：今年内省略年份，跨年才带上。
 *
 * 月份名走 CLDR（`Month.getDisplayName`），日期的**排列顺序**交给字符串资源：
 * 中文是「10月5日」，英文是「Oct 5」，韩文是「10월 5일」——同一份代码，四种语序。
 */
internal fun formatDay(epochMillis: Long, context: Context): String {
    val date = Instant.ofEpochMilli(epochMillis).atZone(ZONE).toLocalDate()
    val locale = context.resources.configuration.locales[0]
    val month = date.month.getDisplayName(TextStyle.SHORT, locale)
    return if (date.year == LocalDate.now(ZONE).year) {
        context.getString(R.string.date_in_year, month, date.dayOfMonth)
    } else {
        context.getString(R.string.date_with_year, month, date.dayOfMonth, date.year)
    }
}

/**
 * 时间轴的分组标题：今天 / 昨天 / 具体日期。
 *
 * 翻日记时「哪天」比「几点」更重要，而昨天那天的日期数字其实不携带信息——所以前两天
 * 直接用相对说法，第三条起才回到 `formatDay`。
 */
internal fun dayLabel(epochMillis: Long, context: Context): String {
    val date = Instant.ofEpochMilli(epochMillis).atZone(ZONE).toLocalDate()
    val today = LocalDate.now(ZONE)
    return when (date) {
        today -> context.getString(R.string.day_today)
        today.minusDays(1) -> context.getString(R.string.day_yesterday)
        else -> formatDay(epochMillis, context)
    }
}

/**
 * 贴纸的固定倾角。
 *
 * 同一张照片在时间轴与详情页必须是同一个角度，否则切换页面时它会「转一下」。
 * 按 id 求和取模，保证稳定且分散。
 */
internal fun stickerTilt(id: String): Float = ((id.fold(0) { a, c -> a + c.code } % 601) - 300) / 100f

private val TIMELINE_WIDTH = 28.dp
private val TIMELINE_STROKE = 1.dp
private val TIMELINE_NODE = 10.dp
private val STICKER_SIZE = 72.dp
private const val SUMMARY_MAX_LINES = 2

/** 照片区比例。3:2 接近手机原生画幅，裁切损失最小。 */
private const val entryAspectRatio = 3f / 2f

@Preview(showBackground = true, backgroundColor = 0xFFFFF8F3, widthDp = 380, heightDp = 800)
@Composable
private fun LookbackPreview() {
    WordLensTheme {
        LookbackScreen(
            topInset = PaddingValues(0.dp),
            bottomInset = PaddingValues(0.dp),
            state = LookbackUiState(
                todayCount = 2,
                groups = listOf(
                    DayGroup(
                        day = "2026-10-05",
                        label = "今天",
                        cards = listOf(
                            EntryCard(
                                entry = Entry(
                                    id = "aaa1",
                                    photoPath = "a.jpg",
                                    takenAt = Instant.now().minusSeconds(3600).toEpochMilli(),
                                    title = "楼下那家面馆",
                                    summary = "汤头很清亮，第二次来还是点了同样的面。",
                                    keywords = listOf("broth", "noodle"),
                                    mood = EntryMood.QUIET,
                                ),
                                words = listOf(
                                    CardWord("broth", "/br\u0258\u03B8/", "汤"),
                                    CardWord("noodle", "/ˈnuːdl/", "面条"),
                                ),
                            ),
                        ),
                    ),
                    DayGroup(
                        day = "2026-10-02",
                        label = "10月2日",
                        cards = listOf(
                            EntryCard(
                                entry = Entry(
                                    id = "bbb2",
                                    photoPath = "b.jpg",
                                    takenAt = Instant.now().minusSeconds(86400 * 3).toEpochMilli(),
                                ),
                                words = listOf(CardWord("counter", null, "柜台")),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFFF8F3, widthDp = 380, heightDp = 600)
@Composable
private fun LookbackEmptyPreview() {
    WordLensTheme {
        LookbackScreen(
            topInset = PaddingValues(0.dp),
            bottomInset = PaddingValues(0.dp),
            state = LookbackUiState(),
        )
    }
}