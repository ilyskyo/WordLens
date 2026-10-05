// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import com.ilyskyo.wordlens.R
import com.ilyskyo.wordlens.data.model.Lang
import com.ilyskyo.wordlens.data.model.StudyDirection
import com.ilyskyo.wordlens.data.repository.AppSettings
import com.ilyskyo.wordlens.ui.icons.WordLensIcons
import com.ilyskyo.wordlens.ui.theme.Space
import com.ilyskyo.wordlens.ui.theme.WordLensTheme

/**
 * 设置页。
 *
 * 与取景页、搜索页同一个理由：它是就地调整，不是导航目的地，所以做成盖住主页的浮层，
 * 左上角给一个明确的关闭键。
 */
@Composable
fun SettingsScreen(
    state: AppSettings,
    onClose: () -> Unit,
    onTargetLanguage: (Lang) -> Unit = {},
    onNativeLanguage: (Lang) -> Unit = {},
    onDirection: (StudyDirection) -> Unit = {},
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
                            FilterChip(
                                selected = state.direction == direction,
                                onClick = { onDirection(direction) },
                                label = {
                                    Text(
                                        text = stringResource(
                                            if (direction == StudyDirection.RECOGNIZE) {
                                                R.string.settings_direction_recognize
                                            } else {
                                                R.string.settings_direction_recall
                                            },
                                        ),
                                    )
                                },
                                shape = CircleShape,
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

                CloudSection(
                    state = state,
                    onCloudEnabled = onCloudEnabled,
                    onCloudModel = onCloudModel,
                    onCloudApiKey = onCloudApiKey,
                    onClearCloudApiKey = onClearCloudApiKey,
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
                FilterChip(
                    selected = lang == selected,
                    onClick = { onSelect(lang) },
                    label = { Text(text = lang.nativeName) },
                    shape = CircleShape,
                )
            }
        }
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

@Preview(showBackground = true, backgroundColor = 0xFFFFF8F3, widthDp = 380, heightDp = 760)
@Composable
private fun SettingsPreview() {
    WordLensTheme {
        SettingsScreen(
            state = AppSettings(cloudEnabled = true),
            onClose = {},
        )
    }
}
