// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.lookback

import android.content.ClipData
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.saveable.rememberSaveable
import com.ilyskyo.wordlens.data.model.EntryMood
import com.ilyskyo.wordlens.core.voice.PendingTake
import com.ilyskyo.wordlens.ui.components.OptionChip
import java.io.File
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ilyskyo.wordlens.R
import com.ilyskyo.wordlens.data.model.Entry
import com.ilyskyo.wordlens.data.model.EntrySource
import com.ilyskyo.wordlens.ui.components.PrimaryButton
import com.ilyskyo.wordlens.ui.theme.Scale
import com.ilyskyo.wordlens.ui.theme.pressable
import com.ilyskyo.wordlens.ui.theme.rememberReduceMotion
import com.ilyskyo.wordlens.ui.icons.WordLensIcons
import com.ilyskyo.wordlens.ui.nav.sharedEntryPhoto
import com.ilyskyo.wordlens.ui.theme.Space
import com.ilyskyo.wordlens.ui.theme.WordLensTheme
import com.ilyskyo.wordlens.vision.camera.CameraFocusMath.NormBox
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    /**
     * 现场那一段还没收下的录音，null 表示没有。它属于 ViewModel 而不属于日记文档：
     * 一段用户还没决定收不收下的声音不该写进 diary.json，但必须活得比这一页的界面长。
     */
    val take: PendingTake? = null,
    /**
     * 这条日记引用着的那段声音，**且磁盘上确有其文件**。
     *
     * 两者必须分开判：`diary.json` 会单独上云而人声不会（说明书 §8.7），换机之后完全可能读到
     * `audioPath` 而没有那个文件。这时候界面要说「这段声音不在这台设备上」，而不是画一条
     * 按下去没有反应的播放条。
     */
    val audioFile: File? = null,
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
    onDelete: () -> Unit = {},
    onSaveEditing: (title: String, summary: String, mood: EntryMood?) -> Unit = { _, _, _ -> },
    voice: VoiceMemoActions = VoiceMemoActions(),
    modifier: Modifier = Modifier,
) {
    var sheetOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // 编辑对话框和它的草稿一起住在外层，而不是住在对话框自己身上。放里面只能成立两件事中的一件：
    //
    // - `rememberSaveable` 的值在组合被拆掉之后**仍然留在注册表里**。草稿住在对话框里时，
    //   「取消」并没有把它作废：下一次长按 → 编辑，框里回来的是上一次那份被明确取消掉的内容，
    //   而它与当前条目的差别恰好只有用户自己知道。按下保存，日记里就多了一句他作废过的话。
    // - 反过来，`editing` 如果只是普通 remember，转屏会把对话框整块关掉：草稿确实被
    //   saveable 留住了，可它留在一扇已经关上的门上——「打到一半转屏不丢」这句话要成立，
    //   对话框本身也得活过这一次旋转。
    //
    // 播种放在「打开」这一刻（照当前条目取），作废放在「关闭」这一刻，两条就都成立了。
    var editing by rememberSaveable { mutableStateOf(false) }
    var editTitle by rememberSaveable { mutableStateOf("") }
    var editSummary by rememberSaveable { mutableStateOf("") }
    var editMood by rememberSaveable { mutableStateOf<EntryMood?>(null) }
    val clipboard = LocalClipboard.current
    val clipScope = rememberCoroutineScope()

    // 关闭即作废：只把 `editing` 拨回 false 是不够的，那三份草稿会一直躺在 saveable 注册表里，
    // 下一次打开时被原样取回来。清空之后，「取消」这句话才真的说完了。
    fun closeEditor() {
        editing = false
        editTitle = ""
        editSummary = ""
        editMood = null
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        // 不透明整页：关闭按钮不能压在状态栏时钟上，正文末尾也不能藏进手势条。
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
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

            // 主图先飞，落定之前不把文字摆出来：半空中有两组东西在抢注意力，看起来像加载
            // 而不是转场。三组各差一个短延迟，读起来是「照片到位，字陆续浮上来」。
            //
            // 系统开了「移除动画」时整条次序摘掉：三组文字是静态内容，先摆哪一组后摆哪一组
            // 不携带信息，而那几个 delay 是协程排的，框架的动效缩放管不到——留着它，界面
            // 照样一组一组往外蹦，只是每组不再滑。照片的共享元素转场不降：那是这次导航本身。
            //
            // 只改初值、不碰下面的进场曲线：初值就是最后一幕时，三个 AnimatedVisibility
            // 第一次组合就 visible，任何 enter 转场都不会跑。再给它们写一套「降级用的空转场」
            // 是一段永远走不到的分支。
            val reduceMotion = rememberReduceMotion()
            var stage by remember(state.entry.id) {
                mutableIntStateOf(if (reduceMotion) LAST_STAGE else 0)
            }
            LaunchedEffect(state.entry.id) {
                if (reduceMotion) {
                    stage = LAST_STAGE
                } else {
                    delay(HEADLINE_DELAY_MS)
                    stage = 1
                    delay(TAGS_DELAY_MS)
                    stage = 2
                    delay(COMPOSER_DELAY_MS)
                    stage = 3
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
                PhotoArea(
                    bitmap = state.photo,
                    objects = state.objects,
                    entryId = state.entry.id,
                    takenAt = state.entry.takenAt,
                    onLongPress = { sheetOpen = true },
                )

                AnimatedVisibility(
                    visible = stage >= 1,
                    enter = fadeIn(tween(HEADLINE_MS)) + slideInVertically(tween(HEADLINE_MS)) { it / 4 },
                    exit = fadeOut(tween(EXIT_MS)) + slideOutVertically(tween(EXIT_MS)) { it / 4 },
                ) {
                    EntryHeadline(state)
                }

                AnimatedVisibility(
                    visible = stage >= 2,
                    enter = scaleIn(
                        initialScale = 0.8f,
                        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
                    ) + fadeIn(tween(HEADLINE_MS)),
                    exit = fadeOut(tween(EXIT_MS)),
                ) {
                    EntryTags(state)
                }

                AnimatedVisibility(
                    visible = stage >= 3,
                    enter = slideInVertically(
                        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
                        initialOffsetY = { it },
                    ) + fadeIn(tween(HEADLINE_MS)),
                    exit = fadeOut(tween(EXIT_MS)),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                        // 声音排在「补一句」之前：它是这条记录**当时**的一部分，而事件是后来补的。
                        VoiceMemoSection(state = state, actions = voice)
                        EventComposer(
                            draft = state.eventDraft,
                            onDraftChange = onDraftChange,
                            onSave = onSaveEvent,
                        )
                    }
                }
            }

            // 长按大图 = 「我要对这张照片做点什么」。删除是不可逆的，所以它一定要经过
            // 一次确认，而不是一个直接的按钮。
            if (sheetOpen) {
                ModalBottomSheet(onDismissRequest = { sheetOpen = false }) {
                    Column(modifier = Modifier.padding(bottom = Space.lg)) {
                        SheetAction(
                            text = stringResource(R.string.detail_copy_text),
                            onClick = {
                                sheetOpen = false
                                val text = detailPlainText(state)
                                clipScope.launch {
                                    // Compose 1.11 起 Clipboard 只剩 setClipEntry：setText 被移除了。
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("WordLens", text)))
                                }
                            },
                        )
                        SheetAction(
                            text = stringResource(R.string.detail_edit),
                            onClick = {
                                sheetOpen = false
                                // 每次打开都照当前的条目重播一遍：框里的内容从此只有两个来源——
                                // 条目本来的样子，和这一次真正打过的字。
                                editTitle = state.entry.title.orEmpty()
                                editSummary = state.entry.summary.orEmpty()
                                editMood = state.entry.mood
                                editing = true
                            },
                        )
                        SheetAction(
                            text = stringResource(R.string.detail_delete),
                            destructive = true,
                            onClick = {
                                sheetOpen = false
                                confirmDelete = true
                            },
                        )
                    }
                }
            }

            if (editing) {
                EditEntryDialog(
                    title = editTitle,
                    summary = editSummary,
                    mood = editMood,
                    onTitleChange = { editTitle = it },
                    onSummaryChange = { editSummary = it },
                    onMoodChange = { editMood = it },
                    onSave = {
                        onSaveEditing(editTitle, editSummary, editMood)
                        closeEditor()
                    },
                    onDismiss = { closeEditor() },
                )
            }

            if (confirmDelete) {
                AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text(stringResource(R.string.detail_delete_confirm_title)) },
                    text = { Text(stringResource(R.string.detail_delete_confirm_body)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                confirmDelete = false
                                onDelete()
                            },
                        ) {
                            Text(
                                text = stringResource(R.string.selection_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmDelete = false }) {
                            Text(stringResource(R.string.selection_cancel))
                        }
                    },
                )
            }
        }
    }
}

/**
 * 编辑这一条日记：标题、一句话、当时的感受。
 *
 * 编辑入口只存在于详情页，**不进拍照流程**（§4.2）：按下快门那一下必须仍然是完整的一个动作。
 * 想补什么随时回来补，但别让「拍完还要填表」变成放弃记录的理由。
 *
 * 三个字段是**受控**的：草稿住在调用方（见 [EntryDetailScreen] 里那段注释）。这里自己
 * `rememberSaveable` 一份的话，「取消」就只是关掉了对话框，草稿本身活得好好的，下一次打开
 * 会被原样填回去——用户明确作废过的一句话，就这样在另一次编辑里悄悄成了正文。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditEntryDialog(
    title: String,
    summary: String,
    mood: EntryMood?,
    onTitleChange: (String) -> Unit,
    onSummaryChange: (String) -> Unit,
    onMoodChange: (EntryMood?) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_edit_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = onTitleChange,
                    label = { Text(stringResource(R.string.detail_edit_field_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = summary,
                    onValueChange = onSummaryChange,
                    label = { Text(stringResource(R.string.detail_edit_field_summary)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.detail_edit_field_mood),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 再点一次已选中的那颗就是取消：心情这一项是「可选且轻量」的，
                // 只能选不能撤会把它变成一个新的枷锁。
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    EntryMood.entries.forEach { option ->
                        OptionChip(
                            label = "${option.emoji} ${stringResource(option.labelRes)}",
                            selected = mood == option,
                            onClick = { onMoodChange(if (mood == option) null else option) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) {
                Text(stringResource(R.string.capture_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.selection_cancel)) }
        },
    )
}

/** Sheet 里的一行操作。删除项用 error 色，和列表里的多选删除保持同一套语义。 */
@Composable
private fun SheetAction(text: String, onClick: () -> Unit, destructive: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick)
            .padding(horizontal = Space.lg, vertical = Space.md),
    )
}

/** 复制的是「这条记录能被说出来的部分」：标题、摘要、画面上的词。不含内部 id。 */
private fun detailPlainText(state: EntryDetailState): String = buildString {
    state.entry.title?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
    state.entry.summary?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
    state.objects.map { it.word }.distinct().takeIf { it.isNotEmpty() }?.let { appendLine(it.joinToString(" · ")) }
    state.ambience.takeIf { it.isNotEmpty() }?.let { append(it.joinToString(" · ")) }
}.trim()

/** 照片 + 压在物体上方的词片。词片锚在上沿，不压住物体本身。 */
@Composable
private fun PhotoArea(
    bitmap: Bitmap?,
    objects: List<ObjectPlace>,
    entryId: String,
    takenAt: Long,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
            .clip(MaterialTheme.shapes.large)
            // 与时间轴卡片上是同一张位图、同一个键：飞过去的不是「另一张相似的照片」。
            .sharedEntryPhoto(entryId)
            .pressable(onClick = {}, onLongClick = onLongPress, pressedScale = Scale.Large),
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            // 全屏照片不是一个装饰：读屏用户只能靠这一句知道「这是哪一天拍的照片」。
            // 词片本身另有一串文字节点可读，所以描述里给日期而不是复述物体。
            contentDescription = stringResource(R.string.lookback_photo_desc, formatDay(takenAt, LocalContext.current)),
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

/** 标题与摘要。交错入场的第一组：主图落定的那一刻浮上来。 */
@Composable
private fun EntryHeadline(state: EntryDetailState, modifier: Modifier = Modifier) {
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
    }
}

/** mood、氛围词与被婉拒的词。交错入场的第二组，比标题晚一步。 */
@Composable
private fun EntryTags(state: EntryDetailState, modifier: Modifier = Modifier) {
    val entry = state.entry
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
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

// ── 交错入场的节奏 ────────────────────────────────────────────────────────
//
// 主图由共享元素负责飞（约 400ms 的弹簧），文字在三段短延迟后依次浮上来。
// 数字是这么定的：图片起步 150ms 后已经走完大半程，此时开始摆文字不会觉得在抢位置；
// 组间 80/100ms 的间隔刚好能感知成「陆续」而不是「同时」，再长就开始显得拖沓。
private const val HEADLINE_DELAY_MS = 150L
private const val TAGS_DELAY_MS = 80L
/** 三步走完的那一幕。开了「移除动画」就直接从这一幕开始。 */
private const val LAST_STAGE = 3

private const val COMPOSER_DELAY_MS = 100L
private const val HEADLINE_MS = 220
private const val EXIT_MS = 140
