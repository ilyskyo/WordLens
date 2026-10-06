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
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ilyskyo.wordlens.R
import com.ilyskyo.wordlens.data.model.Entry
import com.ilyskyo.wordlens.data.model.EntryMood
import com.ilyskyo.wordlens.ui.components.EmptyState
import com.ilyskyo.wordlens.ui.components.OutlinedAction
import com.ilyskyo.wordlens.ui.components.WordLensDialog
import com.ilyskyo.wordlens.ui.icons.WordLensIcons
import com.ilyskyo.wordlens.ui.nav.sharedEntryPhoto
import com.ilyskyo.wordlens.ui.theme.IpaTextStyle
import com.ilyskyo.wordlens.ui.theme.Scale
import com.ilyskyo.wordlens.ui.theme.Space
import com.ilyskyo.wordlens.ui.theme.WordLensTheme
import com.ilyskyo.wordlens.ui.theme.pressable
import com.ilyskyo.wordlens.ui.theme.softShadow
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** 回看页状态。 */
data class LookbackUiState(
    /** 按天倒序分组。组内也是倒序，所以最新一条永远在屏幕最上方。 */
    val groups: List<DayGroup> = emptyList(),
    val todayCount: Int = 0,
    /** 日历筛选中的那一天；null 表示显示全部。 */
    val selectedDay: String? = null,
    /** 有记录的日期集合，喂给月历画小红点。**不受筛选影响**，否则选完一天之后月历就只剩一个点。 */
    val daysWithEntries: Set<String> = emptySet(),
    /**
     * 还没显示出来的更早记录条数。时间轴只解码最近若干张（见 `HomeViewModel.TIMELINE_LIMIT`），
     * 这个数就是「被留在解码窗口之外的那部分」，界面上必须说出来：
     * 滚到列表末尾而一片空白，用户分不清是日记到头了还是记录丢了。
     */
    val olderCount: Int = 0,
)

/**
 * 时间轴的多选状态。
 *
 * 打包成一个对象而不是六个参数：这一页本来就要接住 onOpenEntry / onSpeak / topInset，
 * 再摊开一排回调就没法读了。[selection] 非空即代表处于多选模式，界面与返回键都只看它。
 */
data class TimelineSelection(
    val selection: Set<String> = emptySet(),
    val onLongPress: (String) -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onClear: () -> Unit = {},
    val onDelete: () -> Unit = {},
) {
    val active: Boolean get() = selection.isNotEmpty()
}

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
    /**
     * 由宿主外提。详情页与主页是 AnimatedContent 的两个场景，转场结束后旧场景会被拆掉；
     * 状态留在本文件里的话，从详情页返回时列表会跳回顶部——用户刚看的那条瞬间消失了。
     */
    listState: LazyListState = rememberLazyListState(),
    selection: TimelineSelection = TimelineSelection(),
    onOpenEntry: (String) -> Unit = {},
    onSpeak: (EntryCard) -> Unit = {},
    /** 月历点选某一天；传 null 取消筛选。 */
    onPickDay: (String?) -> Unit = {},
    /** 载入更早的记录。时间轴只解码最近一批，滚到底时这一句决定还有没有下一步可走。 */
    onLoadOlder: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var calendarOpen by remember { mutableStateOf(false) }

    if (state.groups.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(topInset),
        ) {
            if (state.selectedDay != null) {
                // 筛到某一天、而那一天的记录又被删光时，必须还能出来：
                // 空状态里没有「显示全部」就等于把人锁在一个看不见的筛选里。
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    Text(
                        text = stringResource(
                            R.string.calendar_empty_filtered,
                            dayKeyLabel(state.selectedDay, LocalContext.current),
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    OutlinedAction(
                        text = stringResource(R.string.calendar_clear),
                        onClick = { onPickDay(null) },
                        modifier = Modifier.width(220.dp),
                    )
                }
            } else {
                EmptyState(
                    emoji = "\uD83D\uDCF7",
                    title = stringResource(R.string.lookback_empty_title),
                    body = stringResource(R.string.lookback_empty_body),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Space.md,
                end = Space.md,
                // 静止时问候语不能被悬浮页签压住；滚动起来照片从页签下方穿过仍是想要的效果。
                // 避让搬进吸顶头部自己身上（见下面两个分支的 `top`）：`stickyHeader` 钉在
                // 视口的 y=0，**不吃 contentPadding**，这里再留一份会把整格顶到状态栏里。
                top = 0.dp,
                bottom = bottomInset.calculateBottomPadding() + Space.xl,
            ),
            // 间距交给每一类 item 自己带（见 TimelineRow 与 DayHeader 里的注释），
            // 这样左侧主干才能长满整行、不在行与行之间断开。
            verticalArrangement = Arrangement.Top,
        ) {
            // 吸顶而不是随列表滚走：这一格里是**字**（问候语、已选几项、清除筛选），
            // 字被悬浮页签齐头切掉半行会读成「渲染坏了」，而照片从页签下方穿过是想要的效果。
            // 同一个容器要同时做到两件事，只能让它待在页签下面不动。
            // 多选时这一点更重要：「已选 3 项 / 删除」是不可逆动作的上下文，
            // 滚一下就看不见等于让用户在不知道作用范围的情况下按删除。
            stickyHeader(key = "header") {
                // 多选时顶栏整个换掉：问候语在批量操作的语境里没有意义，而「已选几项」必须
                // 一眼看得到——不可逆的动作，上下文不能藏在别处。
                if (selection.active) {
                    SelectionBar(
                        count = selection.selection.size,
                        onSelectAll = selection.onSelectAll,
                        onClear = selection.onClear,
                        onDelete = selection.onDelete,
                        modifier = Modifier
                            .fillMaxWidth()
                            // 吸顶之后会有照片从底下经过，不透明底是必须的：没有它字会和照片叠在一起。
                            .background(MaterialTheme.colorScheme.background)
                            // 悬浮页签的避让由头部自己带：`stickyHeader` 钉在视口 y=0，
                            // 不吃列表的 contentPadding。
                            .padding(top = topInset.calculateTopPadding(), bottom = Space.sm),
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(top = topInset.calculateTopPadding(), bottom = Space.sm),
                    ) {
                        Greeting(
                            todayCount = state.todayCount,
                            onOpenCalendar = { calendarOpen = true },
                        )
                        state.selectedDay?.let { day ->
                            // 筛着的时候必须一直看得见「我在看那一天」，
                            // 否则列表少了内容会被读成「照片丢了」。
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(
                                        R.string.calendar_filtered,
                                        dayKeyLabel(day, LocalContext.current),
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(onClick = { onPickDay(null) }) {
                                    Text(stringResource(R.string.calendar_clear))
                                }
                            }
                        }
                    }
                }
            }
            state.groups.forEach { group ->
                // 分组头压在当天第一张卡上方：翻时间轴时「哪天」比「几点」更重要。
                item(key = "day-${group.day}") {
                    DayHeader(group.label)
                }
                items(group.cards, key = { it.entry.id }) { card ->
                    TimelineRow(
                        card = card,
                        selected = card.entry.id in selection.selection,
                        onOpen = {
                            // 多选模式下单击的含义是勾选，不是打开——两套语义不能同时生效。
                            if (selection.active) selection.onLongPress(card.entry.id) else onOpenEntry(card.entry.id)
                        },
                        onLongPress = { selection.onLongPress(card.entry.id) },
                        onSpeak = { onSpeak(card) },
                    )
                }
            }
            if (state.olderCount > 0) {
                // 用 `key` 而不是匿名 item：这一条出现/消失会改变列表尾部结构，没有 key 的话
                // LazyColumn 会把它的重组算到最后一张卡头上，滚到底那一下看起来像卡片自己闪了一下。
                item(key = "older") {
                    OutlinedAction(
                        text = pluralStringResource(R.plurals.lookback_load_older, state.olderCount, state.olderCount),
                        onClick = onLoadOlder,
                        modifier = Modifier
                            .padding(
                                start = Space.lg,
                                end = Space.lg,
                                top = Space.xs,
                                bottom = Space.md,
                        ),
                    )
                }
            }
        }

        if (calendarOpen) {
            MonthSheet(
                daysWithEntries = state.daysWithEntries,
                selectedDay = state.selectedDay,
                onPick = { day ->
                    onPickDay(day)
                    calendarOpen = false
                },
                onDismiss = { calendarOpen = false },
            )
        }
    }
}

/**
 * 月历筛选：只看某一天。
 *
 * 有记录的格子可点，没记录的格子仍可看见但点不动——筛到一个空的日子里，
 * 用户看到的只是「什么都没变」，而时间轴其实已经被换成空的了。
 *
 * 「下一月」在当月就停住：未来那个月不可能有记录，翻过去得到的是一整张点不开的格子。
 *
 * 格子算术全在 MonthGrid（纯函数、有 JVM 测试）里：月初不是周一起始时前面补几格、
 * 闰年二月几天、周起点跟着谁的地区设置走。这些错只在特定的月份才暴露，而一个月只来一次。
 */
@Composable
private fun MonthSheet(
    daysWithEntries: Set<String>,
    selectedDay: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val today = LocalDate.now()
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    val firstDay = remember { MonthGrid.defaultFirstDayOfWeek() }
    val weekdays = remember(firstDay) { MonthGrid.weekdayNames(firstDay) }
    val weeks = remember(month, daysWithEntries, firstDay) {
        MonthGrid.weeks(MonthGrid.days(month, daysWithEntries, firstDay))
    }
    // 完整日期给读屏念：只念一个「5」没人知道这是哪天。formatter 在组合作用域里取，
    // 理由同月份名——换语言时进程不死，文件级常量会停在旧 locale 上。
    val fullDate = remember {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(Locale.getDefault())
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.sm),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Text(
                text = stringResource(R.string.calendar_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { month = month.minusMonths(1) }) {
                    Icon(
                        imageVector = WordLensIcons.ChevronLeft,
                        contentDescription = stringResource(R.string.calendar_prev),
                    )
                }
                // 月份名交给 DateTimeFormatter：pattern 里的 MMMM/yyyy 由 locale 决定展开成
                // 「October 2026」还是「2026年10月」，比维护四套月份字符串可靠。
                // formatter 必须在组合作用域里造：换语言时进程不死，放到文件级 val 就会
                // 永远停在旧 locale 上。
                Text(
                    text = remember(month) { monthTitle(month) },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                IconButton(
                    onClick = { month = month.plusMonths(1) },
                    // 理由写在 MonthSheet 上面那段：翻进未来只能得到一张点不开的格子。
                    enabled = month < YearMonth.from(today),
                ) {
                    Icon(
                        imageVector = WordLensIcons.ChevronRight,
                        contentDescription = stringResource(R.string.calendar_next),
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                weekdays.forEach { name ->
                    Text(
                        text = name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            weeks.forEach { week ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    week.forEach { cell ->
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            if (cell != null) {
                                val isSelected = cell.dayKey == selectedDay
                                Surface(
                                    onClick = { if (cell.hasEntries) onPick(cell.dayKey) },
                                    enabled = cell.hasEntries,
                                    shape = CircleShape,
                                    color = when {
                                        isSelected -> MaterialTheme.colorScheme.primary
                                        cell.hasEntries -> MaterialTheme.colorScheme.primaryContainer
                                        else -> Color.Transparent
                                    },
                                    contentColor = when {
                                        isSelected -> MaterialTheme.colorScheme.onPrimary
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                    modifier = Modifier
                                        .size(DAY_CELL)
                                        .semantics {
                                            contentDescription =
                                                fullDate.format(LocalDate.parse(cell.dayKey))
                                            this.selected = isSelected
                                        },
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center,
                                    ) {
                                        Text(
                                            text = cell.dayOfMonth.toString(),
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                        // 用点而不是整格底色：那天有没有记录是次要信息，
                                        // 但它必须在不进那一天的情况下就看得见。
                                        if (cell.hasEntries && !isSelected) {
                                            Box(
                                                modifier = Modifier
                                                    .size(DAY_DOT)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.primary),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (selectedDay != null) {
                TextButton(onClick = { onPick(null) }) {
                    Text(stringResource(R.string.calendar_clear))
                }
            }
        }
    }
}

/**
 * 月份的本地化全称。
 *
 * 交给 CLDR 而不是自己排「X 年 X 月 / X Month YYYY」：中日韩的年月写法与欧美的顺序不同，
 * 而这一条差异用 locale 展开就能覆盖，四套字符串反而是会写错的那一个。
 */
private fun monthTitle(month: YearMonth): String =
    DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()).format(month.atDay(1))

private val DAY_CELL = 44.dp
private val DAY_DOT = 5.dp

/**
 * 多选顶栏：数量、全选、取消、删除。
 *
 * 删除一定要二次确认，而且确认文案必须说清「词卡会留下」——用户删的是照片，
 * 但这个词他已经花过复习时间，静默一起删掉等于偷走他的学习记录。
 */
@Composable
private fun SelectionBar(
    count: Int,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirm by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(
            text = stringResource(R.string.selection_count, count),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onSelectAll) {
            Text(stringResource(R.string.selection_select_all))
        }
        TextButton(onClick = onClear) {
            Text(stringResource(R.string.selection_cancel))
        }
        TextButton(onClick = { confirm = true }) {
            Text(
                text = stringResource(R.string.selection_delete),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (confirm) {
        WordLensDialog(
            title = pluralStringResource(R.plurals.selection_delete_title, count, count),
            message = stringResource(R.string.selection_delete_body),
            onDismiss = { confirm = false },
            primaryText = stringResource(R.string.selection_delete),
            onPrimary = {
                confirm = false
                onDelete()
            },
            secondaryText = stringResource(R.string.selection_cancel),
            onSecondary = { confirm = false },
            destructive = true,
        )
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
            // `bottom` 是这一格自己带着的空隙（见 TimelineRow 里那条注释）：整页不再用
            // `verticalArrangement` 统一留白，所以每一类 item 都要自己补上。
            .padding(top = Space.sm, start = TIMELINE_WIDTH + Space.sm, bottom = Space.md),
    )
}

@Composable
private fun Greeting(
    todayCount: Int,
    onOpenCalendar: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                    text = pluralStringResource(R.plurals.lookback_today_count, todayCount, todayCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // 日历按钮挨着问候语放在右上角：它是「换个看法」而不是一个新的目的地，
        // 放到别处会让人以为要点进去找一个叫日历的页面。
        IconButton(onClick = onOpenCalendar) {
            Icon(
                imageVector = WordLensIcons.Calendar,
                contentDescription = stringResource(R.string.calendar_title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    selected: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onSpeak: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        // 这一行必须有一个确定的高度基准，否则左侧那根主干**从来画不出来**：
        // LazyColumn 的 item 拿到的是无界高度约束，而 Column 里的 weight(1f) 需要
        // 「剩余高度」这个数——无界时它算出 0，于是节点上下两段线都是零像素高，
        // 整页一条线只剩下几颗孤立的圆点。IntrinsicSize.Max 让行高由卡片自己撑出来，
        // 再把有界的约束交给那一列。代价是每行多一次固有高度测量（位图早已解好，不重解码）。
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Max),
    ) {
        // ── 左侧时间轴 ──────────────────────────────────────────────
        // 主干的颜色不能用 `outline`：浅色方案里它是最低对比的那一档，2dp 画出来在
        // 真机与模拟器上都完全看不见——「整页一条线」只剩下几颗孤立的节点。
        // 取 onSurface 那一族的低透明度：跟着明暗走，又稳定读得出来。
        val spine = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.24f)
        Column(
            modifier = Modifier.width(TIMELINE_WIDTH),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .width(TIMELINE_STROKE)
                    .weight(1f)
                    .background(spine),
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
                    .background(spine),
            )
        }

        // ── 卡片 ────────────────────────────────────────────────────
        EntryTimelineCard(
            card = card,
            selected = selected,
            onOpen = onOpen,
            onLongPress = onLongPress,
            onSpeak = onSpeak,
            modifier = Modifier
                .weight(1f)
                // 行间距吃在**卡片自己**的 bottom padding 里，而不是 LazyColumn 的
                // `verticalArrangement`：那样一来这一行的高度就包含这段空隙，左侧那根
                // 主干跟着长满，卡片之间不再出现断口。装机看到的「线是断的」就是
                // 空隙不属于任何一行导致的。
                .padding(start = Space.sm, bottom = Space.md),
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
    selected: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onSpeak: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = card.entry

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = modifier
            .fillMaxWidth()
            // 单层 elevation 阴影在奶油白上会硬成一块灰：换成三层柔和阴影，
            // 轮廓仍然是卡片自己的超椭圆，所以裁切与阴影必然一致。
            .softShadow(MaterialTheme.shapes.large)
            // 读屏要能念出「这一张已经选上了」。多选模式里唯一的数量读数在顶栏那句「已选 N 项」，
            // 而滚过一张张卡片时用户需要知道的是**哪几张**在里面——照片本身没有可辨的标题。
            // 月历的格子早就做了同一件事。这里的 `this.` 不能省：这个函数的参数把接收者上那个
            // 同名扩展属性挡住了，写成 `selected = selected` 编出来是「给参数赋值」。
            .semantics { this.selected = selected }
            // 点击与长按共用一个手势识别器：长按触发后不会再补一次 onClick。
            .pressable(onClick = onOpen, onLongClick = onLongPress, pressedScale = Scale.Large),
    ) {
        Column {
            // ── 照片区（含右下角贴纸或 mood） ────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(entryAspectRatio)
                    // 共享节点是**这一整块**，不是里面那张位图：与详情页那边对齐（那边框住的是
                    // 照片加词片）。挂在位图上时，位图飞到共享层里，压在日期标签之上——
                    // 真机截图上「日期不见了」就是这么来的，而它其实是自己的照片盖住了自己。
                    // 与详情页是同一个键、同一张照片：飞过去的不是「另一张相似的照片」。
                // 静止时它不该在 overlay 里——那件事由 `sharedEntryPhoto` 里的默认
                // `renderInOverlay = { isTransitionActive }` 保证，见那里的注释。
                .sharedEntryPhoto(entry.id)
                    // 圆角必须自己裁，而且要放在共享修饰符**内侧**：共享层画的是这个节点
                    // 的内容，外面的 clip 它不认。放在外侧时截图上仍然是四个直角。
                    .clip(MaterialTheme.shapes.large),
            ) {
                if (card.photo != null) {
                    Image(
                        bitmap = card.photo.asImageBitmap(),
                        // 时间轴上的照片必须能被念出来是哪一天的；不然读屏用户滚过的
                        // 就是一串没有上下文的图片。贴纸是同一个词的重复表达，
                        // 词本身已经在旁边的文字里读得到，所以刻意留空。
                        contentDescription = stringResource(
                            R.string.lookback_photo_desc,
                            formatDay(card.entry.takenAt, LocalContext.current),
                        ),
                        contentScale = ContentScale.Crop,
                        // 点开的详情页用的就是这一张位图（同一个实例，不二次解码），
                        // 所以飞过去的画面和原地看到的是同一份像素。
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
                    color = Color(0x66000000),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(Space.sm),
                ) {
                    Text(
                        text = formatDay(entry.takenAt, LocalContext.current),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
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

                if (selected) {
                    // 遮罩压在整张照片上，勾选框走 Material 3 自己的复选框画法是明确的
                    // 「可多选」信号。只靠描边不够：照片本身可能是任何颜色。
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.26f)),
                    )
                    // 自己画这颗勾，不用 M3 的 Checkbox：它的轨道是一个**圆角方块**，
                    // 外面再套一圈白底圆，就成了「圆包里一个方块」——装机截图上很难看，
                    // 而且方块的四角会把白圈切成一圈窄边。iOS 的选中是一颗实心圆 + 白勾。
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(Space.sm)
                            .size(30.dp)
                            .clip(CircleShape)
                            // 白圈是必须的：选中时整张卡被压上一层珊瑚，勾要在这层色上
                            // 仍然读得出边界。
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                            .padding(3.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = WordLensIcons.Check,
                                // 选中状态已经由整张卡的 `selected` 语义合并上报（见 #33），
                                // 这里再给描述就是念两遍。
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
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
 * 把日历的分组键（ISO 日期）念成人话。
 *
 * 筛选条上直接写 `2026-10-05` 是最省事的做法，也是最糟的：用户在自己的日记里看到一串
 * 数据库格式。这里绕回 [formatDay]，让它和卡片上的日期标签完全同源——两处各排一次格式，
 * 早晚会出现「卡片写 10月5日、筛选条写 2026-10-05」这种自相矛盾的画面。
 */
internal fun dayKeyLabel(dayKey: String, context: Context): String {
    val startOfDay = LocalDate.parse(dayKey).atStartOfDay(ZONE)
    return formatDay(startOfDay.toInstant().toEpochMilli(), context)
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
/**
 * 时间轴主干的粗细。
 *
 * 1dp 在 420dpi 上是 2.6 个物理像素，而 `outline` 本来就是浅色系里最浅的那一档——
 * 真机与模拟器上截出来都是「只有节点，没有线」，整页一条线的设计意图直接消失。
 * 2dp 是能稳定看见的最小值，再粗就开始和卡片抢注意力。
 */
private val TIMELINE_STROKE = 2.dp
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