// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.settings

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ilyskyo.wordlens.R
import com.ilyskyo.wordlens.data.model.Lang
import com.ilyskyo.wordlens.data.model.RatingPalette
import com.ilyskyo.wordlens.data.model.StudyDirection
import com.ilyskyo.wordlens.data.repository.AppSettings
import com.ilyskyo.wordlens.srs.Fsrs
import com.ilyskyo.wordlens.ui.components.OptionChip
import com.ilyskyo.wordlens.ui.icons.WordLensIcons
import com.ilyskyo.wordlens.ui.theme.Space
import com.ilyskyo.wordlens.ui.theme.RatingColors
import com.ilyskyo.wordlens.ui.theme.WordLensTheme
import com.ilyskyo.wordlens.ui.theme.hues
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
 * 云端与脱敏 → 识别与上传路径。拨了没反应的开关比没有开关更糟，它教用户不信任这一整页。
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
    onRedactBeforeUpload: (Boolean) -> Unit = {},
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

                PrivacySection(
                    redact = state.redactBeforeUpload,
                    onRedact = onRedactBeforeUpload,
                )
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

@Composable
private fun PrivacySection(redact: Boolean, onRedact: (Boolean) -> Unit) {
    Section(title = stringResource(R.string.settings_privacy_section)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_redact),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = redact, onCheckedChange = onRedact)
        }
        Text(
            text = stringResource(R.string.settings_redact_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            OutlinedTextField(
                value = state.cloudModel,
                onValueChange = onCloudModel,
                label = { Text(stringResource(R.string.settings_cloud_model)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.cloudApiKey,
                onValueChange = onCloudApiKey,
                label = { Text(stringResource(R.string.settings_cloud_key)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
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
