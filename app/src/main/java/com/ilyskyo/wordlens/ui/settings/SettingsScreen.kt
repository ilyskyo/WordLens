// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.ilyskyo.wordlens.R
import com.ilyskyo.wordlens.data.model.Lang
import com.ilyskyo.wordlens.data.model.LexiconEntry
import com.ilyskyo.wordlens.data.model.RatingPalette
import com.ilyskyo.wordlens.data.model.StudyDirection
import com.ilyskyo.wordlens.core.reminder.ReminderPlan
import com.ilyskyo.wordlens.data.repository.AppSettings
import com.ilyskyo.wordlens.srs.Fsrs
import com.ilyskyo.wordlens.ui.components.InsetField
import com.ilyskyo.wordlens.ui.components.OptionChip
import com.ilyskyo.wordlens.ui.components.OutlinedAction
import com.ilyskyo.wordlens.ui.components.PrimaryButton
import com.ilyskyo.wordlens.ui.icons.WordLensIcons
import com.ilyskyo.wordlens.ui.theme.Space
import com.ilyskyo.wordlens.ui.theme.RatingColors
import com.ilyskyo.wordlens.ui.theme.WordLensTheme
import com.ilyskyo.wordlens.ui.theme.hues
import com.ilyskyo.wordlens.ui.theme.pressable
import com.ilyskyo.wordlens.ui.theme.tones

/**
 * 设置页。
 *
 * 与取景页、搜索页同一个理由：它是就地调整，不是导航目的地，所以做成盖住主页的浮层，
 * 左上角给一个明确的关闭键。
 *
 * ## 这里只放「真的会改变行为」的开关
 *
 * 每一项都能指到一个消费方：语言与方向 → 卡片渲染与排期；保持率 → `Fsrs`；评级配色 → 主题；
 * 云端 → 识别与上传路径。拨了没反应的开关比没有开关更糟，它教用户不信任这一整页。
 *
 * 「隐私」那一节因此**没有开关**，只有一句写死的事实陈述：照片交给云端识别时是重新编码过的
 * 位图，EXIF 与拍摄位置在那一步就没了。写成开关的话，它只能有两种状态——真的能关掉（等于
 * 给用户一个把位置发出去的选项，违背产品立场）或者关不掉（等于说谎）。两种都不可接受，
 * 所以这一节只陈述现状，现状由代码结构保证。
 */
@Composable
fun SettingsScreen(
    state: AppSettings,
    onClose: () -> Unit,
    onTargetLanguage: (Lang) -> Unit = {},
    onNativeLanguage: (Lang) -> Unit = {},
    onDirection: (StudyDirection) -> Unit = {},
    onRetention: (Double) -> Unit = {},
    onRatingPalette: (RatingPalette) -> Unit = {},
    onReminderEnabled: (Boolean) -> Unit = {},
    onReminderMinuteOfDay: (Int) -> Unit = {},
    engines: List<String> = emptyList(),
    missingLanguages: List<Lang> = emptyList(),
    onRecheckVoices: () -> Unit = {},
    onImport: (android.net.Uri) -> Unit = {},
    userWords: List<LexiconEntry> = emptyList(),
    lexiconWarnings: List<String> = emptyList(),
    onAddWord: (word: String, gloss: String) -> Unit = { _, _ -> },
    onRemoveWord: (id: String) -> Unit = {},
    onCloudEnabled: (Boolean) -> Unit = {},
    onCloudModel: (String) -> Unit = {},
    onCloudApiKey: (String) -> Unit = {},
    onClearCloudApiKey: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = WordLensIcons.Close,
                        contentDescription = stringResource(R.string.detail_close),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.lg, vertical = Space.md),
                verticalArrangement = Arrangement.spacedBy(Space.lg),
            ) {
                LanguageSection(
                    title = stringResource(R.string.settings_learn_language),
                    selected = state.targetLanguage,
                    onSelect = onTargetLanguage,
                )
                LanguageSection(
                    title = stringResource(R.string.settings_native_language),
                    selected = state.nativeLanguage,
                    onSelect = onNativeLanguage,
                )

                Section(title = stringResource(R.string.settings_direction_section)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        StudyDirection.entries.forEach { direction ->
                            OptionChip(
                                label = stringResource(
                                    if (direction == StudyDirection.RECOGNIZE) {
                                        R.string.settings_direction_recognize
                                    } else {
                                        R.string.settings_direction_recall
                                    },
                                ),
                                selected = state.direction == direction,
                                onClick = { onDirection(direction) },
                            )
                        }
                    }
                    Text(
                        text = stringResource(
                            if (state.direction == StudyDirection.RECOGNIZE) {
                                R.string.settings_direction_recognize_hint
                            } else {
                                R.string.settings_direction_recall_hint
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                ReviewSection(
                    retention = state.requestRetention,
                    palette = state.ratingPalette,
                    onRetention = onRetention,
                    onRatingPalette = onRatingPalette,
                )

                CloudSection(
                    state = state,
                    onCloudEnabled = onCloudEnabled,
                    onCloudModel = onCloudModel,
                    onCloudApiKey = onCloudApiKey,
                    onClearCloudApiKey = onClearCloudApiKey,
                )

                ReminderSection(
                    enabled = state.reminderEnabled,
                    minuteOfDay = state.reminderMinuteOfDay,
                    onEnabled = onReminderEnabled,
                    onMinuteOfDay = onReminderMinuteOfDay,
                )

                VoiceSection(
                    engines = engines,
                    missingLanguages = missingLanguages,
                    onRecheck = onRecheckVoices,
                )

                DataSection(onImport = onImport)

                MyWordsSection(
                    words = userWords,
                    warnings = lexiconWarnings,
                    onAdd = onAddWord,
                    onRemove = onRemoveWord,
                )

                PrivacySection()
            }
        }
    }
}

@Composable
private fun LanguageSection(title: String, selected: Lang, onSelect: (Lang) -> Unit) {
    Section(title = title) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            Lang.entries.forEach { lang ->
                OptionChip(
                    label = lang.nativeName,
                    selected = lang == selected,
                    onClick = { onSelect(lang) },
                )
            }
        }
    }
}

/**
 * 复习节奏：目标保持率 + 评级配色。
 *
 * 保持率是这一个 App 里唯一真正会改变「每天要面对多少张卡」的旋钮，所以它值得一个滑块
 * 而不只是一个数字：`Fsrs` 在算间隔时读的就是它。
 */
@Composable
private fun ReviewSection(
    retention: Double,
    palette: RatingPalette,
    onRetention: (Double) -> Unit,
    onRatingPalette: (RatingPalette) -> Unit,
) {
    // 拖动过程中先写本地草稿，抬手才落盘：滑块一次拖拽会产生几十次事件，
    // 每一个都去写 DataStore 既浪费 IO，也让「回弹到已提交值」的抖动变得可见。
    var draft by remember(retention) { mutableFloatStateOf(retention.toFloat()) }

    Section(title = stringResource(R.string.settings_review_section)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_retention),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${(draft * 100).toInt()}%",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onRetention(draft.toDouble()) },
            valueRange = Fsrs.MIN_REQUEST_RETENTION.toFloat()..Fsrs.MAX_REQUEST_RETENTION.toFloat(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.settings_retention_looser),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.settings_retention_tighter),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.settings_retention_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PalettePicker(selected = palette, onSelect = onRatingPalette)
    }
}

/**
 * 评级配色选择器。
 *
 * 每一档前面放四个真实色块，让用户**看着选**而不是读名字猜：色系的差别本来就是颜色的差别,
 * 用文字描述「暖调 / 冷调」远不如一眼看出来。名字仍然保留——色觉障碍用户与读屏依赖它，
 * 颜色在这个 App 里永远不是唯一的信息渠道。
 */
@Composable
private fun PalettePicker(selected: RatingPalette, onSelect: (RatingPalette) -> Unit) {
    val dark = isSystemInDarkTheme()
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text(
            text = stringResource(R.string.settings_rating_colors),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            RatingPalette.entries.forEach { palette ->
                OptionChip(
                    label = stringResource(palette.labelRes),
                    selected = palette == selected,
                    onClick = { onSelect(palette) },
                    leading = { SwatchRow(palette.hues().tones(dark)) },
                )
            }
        }
        Text(
            text = stringResource(R.string.settings_rating_colors_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 四档的一瞥：用按钮真正的底色，而不是抽象的色相——选的就是「按下去长什么样」。 */
@Composable
private fun SwatchRow(colors: RatingColors) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(colors.again, colors.hard, colors.good, colors.easy).forEach { tone ->
            // 小色块不给阴影也不各自取整圆角：4 个连成一排才读得出「这是一套」。
            Surface(
                shape = CircleShape,
                color = tone.hue,
                modifier = Modifier.size(SWATCH),
            ) {}
        }
    }
}

/**
 * 每日复习提醒。
 *
 * 开关与时间**只写设置**；排期由 `AppContainer` 订阅设置流去做。两处都能写就会变成
 * 「改了设置但排期没跟上」，那是这个项目里最难查的一类不一致。
 *
 * 通知权限是**在用户拨开开关的那一刻**才请求的。清单里早就声明了 POST_NOTIFICATIONS，
 * 在没有实现的时候索要权限，等于让隐私声明里的权限列表变成谎话。
 */
@Composable
private fun ReminderSection(
    enabled: Boolean,
    minuteOfDay: Int,
    onEnabled: (Boolean) -> Unit,
    onMinuteOfDay: (Int) -> Unit,
) {
    val context = LocalContext.current
    // 读系统的真实状态，而不是自己记一个布尔：用户可能从通知设置里关掉了这个应用，
    // 只有 NotificationManagerCompat 知道此刻到底能不能发出去。
    var allowed by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> allowed = granted }
    var pickerOpen by remember { mutableStateOf(false) }

    Section(title = stringResource(R.string.settings_reminder_section)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_reminder_switch),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = enabled,
                onCheckedChange = { next ->
                    onEnabled(next)
                    if (next && !allowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        allowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
                    }
                },
            )
        }

        if (enabled) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressable(onClick = { pickerOpen = true })
                    .padding(vertical = Space.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_reminder_time),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = ReminderPlan.formatMinuteOfDay(minuteOfDay),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // 只在真的有问题时才说问题：一条「通知未开启」常驻在已经正常工作的人面前，
        // 只会让他们以为这个功能坏了。
        if (enabled && !allowed) {
            Text(
                text = stringResource(R.string.settings_reminder_blocked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = stringResource(R.string.settings_reminder_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (pickerOpen) {
        ReminderTimeDialog(
            minuteOfDay = minuteOfDay,
            onDismiss = { pickerOpen = false },
            onConfirm = { minute ->
                onMinuteOfDay(minute)
                pickerOpen = false
            },
        )
    }
}

@Composable
private fun ReminderTimeDialog(
    minuteOfDay: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = minuteOfDay / 60,
        initialMinute = minuteOfDay % 60,
        // 四种语言都按 24 小时制呈现：AM/PM 在 CJK 里读起来是外来词，而「下午 8 点」
        // 又要在脑子里换算一次。
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_reminder_time)) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) {
                Text(stringResource(R.string.capture_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.selection_cancel))
            }
        },
    )
}

/**
 * 发音引擎的现状。
 *
 * 这是 §6.3 里那个 voiceHint 的兑现：与其在用户点喇叭听不到时冒一句「失败」，
 * 不如在设置页把「现在这个引擎念日文是什么样、怎么改善」一次说清楚。
 * 缺哪些语言直接列出来——「不支持」是一个词，「缺日语和韩语」是一个可以行动的事实。
 */
@Composable
private fun VoiceSection(
    engines: List<String>,
    missingLanguages: List<Lang>,
    onRecheck: () -> Unit,
) {
    Section(title = stringResource(R.string.settings_voice_section)) {
        Text(
            text = if (engines.isEmpty()) {
                stringResource(R.string.settings_voice_engines_none)
            } else {
                stringResource(R.string.settings_voice_engines, engines.joinToString(SEPARATOR))
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (engines.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (engines.isNotEmpty() && missingLanguages.isNotEmpty()) {
            Text(
                text = stringResource(
                    R.string.settings_voice_missing,
                    missingLanguages.joinToString(SEPARATOR) { it.nativeName },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.settings_voice_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onRecheck) {
            Text(stringResource(R.string.settings_voice_recheck))
        }
    }
}

/**
 * 导入词卡。
 *
 * 类型过滤器用通配：deck.json 在不少 ROM 的文件管理器里被报成「未知类型」，
 * 收窄成 application/json 会让一部分用户根本找不到自己导出的那个文件。
 */
@Composable
private fun DataSection(onImport: (android.net.Uri) -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImport)
    }
    Section(title = stringResource(R.string.settings_data_section)) {
        OutlinedAction(
            text = stringResource(R.string.settings_import),
            onClick = { launcher.launch(arrayOf("*/*")) },
        )
        Text(
            text = stringResource(R.string.settings_import_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val SEPARATOR = "  ·  "

/**
 * 「我的词条」：把设备认不出的那个词变成一次录入。
 *
 * 这一节的分量不在表单，在它后面那句是真的：写进去之后 `LexiconRepository.reload` 把它并进索引，
 * 取景页手输同一个词就不再撞 `manual_not_found`。词典文件本来就是一个人可以打开、diff、分享的
 * 纯文本（§8.6），所以这里不需要「导出」这个动作，也不该造一个。
 *
 * 两个框都填了才按得下去：`LexiconEntry.userEntry` 对空白返回 null，而「按下没反应」是这个项目
 * 里最不能出现的一种失败。
 */
@Composable
private fun MyWordsSection(
    words: List<LexiconEntry>,
    warnings: List<String>,
    onAdd: (word: String, gloss: String) -> Unit,
    onRemove: (id: String) -> Unit,
) {
    var word by remember { mutableStateOf("") }
    var meaning by remember { mutableStateOf("") }
    Section(title = stringResource(R.string.settings_my_words_section)) {
        if (words.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_my_words_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            words.forEach { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = entry.headword, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            // 取「有的那一条」而不是按母语键取：同一条可能先为别的语言补过释义。
                            text = entry.glosses.values.firstOrNull { it.isNotBlank() }.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onRemove(entry.id) }) {
                        Icon(
                            imageVector = WordLensIcons.Close,
                            contentDescription = stringResource(R.string.settings_word_remove_desc),
                        )
                    }
                }
            }
        }
        InsetField(
            value = word,
            onValueChange = { word = it },
            label = stringResource(R.string.settings_word_label),
        )
        InsetField(
            value = meaning,
            onValueChange = { meaning = it },
            label = stringResource(R.string.settings_meaning_label),
        )
        PrimaryButton(
            text = stringResource(R.string.settings_word_add),
            onClick = {
                onAdd(word, meaning)
                // 清空而不是留着：留着的话再按一次是把同一个词又写一遍（同 id 是覆盖），
                // 看上去像「加了两次」，而刚按下的人无从分辨。
                word = ""
                meaning = ""
            },
            enabled = word.isNotBlank() && meaning.isNotBlank(),
        )
        if (warnings.isNotEmpty()) {
            // 手改 JSON 最常见的结果是多一个逗号，而那份文件会被整份跳过、界面安静地退回内置词典。
            // 不说的话，用户得到的体验是「我加的词 App 不认」，真正的原因却在十个屏幕之外。
            // 文件名不翻译：那是磁盘上的路径片段，翻了就找不到文件了。
            Text(
                text = pluralStringResource(
                    R.plurals.settings_lexicon_warnings,
                    warnings.size,
                    warnings.size,
                    warnings.joinToString(", "),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * 隐私那一节：只有两行陈述，没有开关。
 *
 * 这里原本是一个「上传前剥离照片信息」的开关，而没有任何代码读它——发出去的是
 * `CloudVisionEngine.encodeImage` 里 `Bitmap.compress` 重新编码出来的位图，本来就不含 EXIF，
 * 开关拨到哪都一样。原文案还写着「关掉之后，原文件会原样发送」：那是一句关于代码的承诺，
 * 而代码做不到。删掉开关，把真实的那两件事写在这里。
 */
@Composable
private fun PrivacySection() {
    Section(title = stringResource(R.string.settings_privacy_section)) {
        PrivacyFact(stringResource(R.string.settings_privacy_reencode))
        PrivacyFact(stringResource(R.string.settings_privacy_backup))
    }
}

@Composable
private fun PrivacyFact(text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        // 前面挂一个点，不是为了好看：两句话并排时需要一个「这是两条独立的事实」的视觉断点，
        // 否则它们会读成一段话的两行。
        Text(
            text = "·",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CloudSection(
    state: AppSettings,
    onCloudEnabled: (Boolean) -> Unit,
    onCloudModel: (String) -> Unit,
    onCloudApiKey: (String) -> Unit,
    onClearCloudApiKey: () -> Unit,
) {
    Section(title = stringResource(R.string.settings_vision_section)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_cloud_switch),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = state.cloudEnabled, onCheckedChange = onCloudEnabled)
        }

        if (state.cloudEnabled) {
            InsetField(
                value = state.cloudModel,
                onValueChange = onCloudModel,
                label = stringResource(R.string.settings_cloud_model),
            )
            InsetField(
                value = state.cloudApiKey,
                onValueChange = onCloudApiKey,
                label = stringResource(R.string.settings_cloud_key),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        when {
                            state.cloudReady -> R.string.settings_cloud_state_ready
                            state.cloudApiKey.isBlank() -> R.string.settings_cloud_state_no_key
                            else -> R.string.settings_cloud_state_ready
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (state.cloudApiKey.isNotBlank()) {
                    TextButton(onClick = onClearCloudApiKey) {
                        Text(stringResource(R.string.settings_cloud_key_clear))
                    }
                }
            }
            Text(
                text = stringResource(R.string.settings_cloud_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        content()
    }
}

/** 设置页里色系的显示名。名字要短，因为胶囊要在一行里排下三个。 */
private val RatingPalette.labelRes: Int
    get() = when (this) {
        RatingPalette.WARM -> R.string.settings_palette_warm
        RatingPalette.COOL -> R.string.settings_palette_cool
        RatingPalette.MUTED -> R.string.settings_palette_muted
    }

private val SWATCH = 10.dp

@Preview(showBackground = true, backgroundColor = 0xFFFFFDFB, widthDp = 380, heightDp = 900)
@Composable
private fun SettingsPreview() {
    WordLensTheme {
        SettingsScreen(
            state = AppSettings(cloudEnabled = true),
            onClose = {},
        )
    }
}
