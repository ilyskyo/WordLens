// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ilyskyo.wordlens.R
import com.ilyskyo.wordlens.data.model.WordCard
import com.ilyskyo.wordlens.ui.components.EmptyState
import com.ilyskyo.wordlens.ui.icons.WordLensIcons
import com.ilyskyo.wordlens.ui.theme.Space

/**
 * 搜索 / 添加页。三区共用一个输入框：牌组、词典、日记。
 *
 * 结果区分层不是装饰——「我已经收进来的」「词典里还没有的」「某天见过它的」是三件不同的事，
 * 混在一个列表里用户就得不到「还差哪些」这个信息。
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    onCollect: (String) -> Unit,
    onSpeak: (String, String?) -> Unit,
    onOpenEntry: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        // 整页不透明，内容一律退到系统栏之内（背景仍铺满，避免状态栏下露出色块边界）。
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.md, vertical = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = WordLensIcons.Close,
                        contentDescription = stringResource(R.string.detail_close),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val blank = state.query.isBlank()
            if (!blank && state.collected.isEmpty() && state.suggestions.isEmpty() && state.diaryHits.isEmpty()) {
                EmptyState(
                    emoji = "\uD83D\uDD0D",
                    title = stringResource(R.string.search_empty_title),
                    body = stringResource(R.string.search_empty_body),
                    modifier = Modifier.fillMaxSize(),
                )
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = Space.lg, vertical = Space.sm),
                verticalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                if (state.suggestions.isNotEmpty()) {
                    item(key = "h-suggest") {
                        SectionTitle(stringResource(R.string.search_section_suggest))
                    }
                    items(state.suggestions, key = { "s-" + it.entry.id }) { s ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Space.xs),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Space.sm),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(s.headword, style = MaterialTheme.typography.titleSmall)
                                s.gloss?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            SpeakIcon { onSpeak(s.headword, s.entry.primaryLanguage) }
                            TextButton(
                                onClick = { onCollect(s.entry.id) },
                                enabled = s.entry.id !in state.justAdded,
                            ) {
                                Text(
                                    text = stringResource(
                                        if (s.entry.id in state.justAdded) R.string.search_collected_badge
                                        else R.string.search_collect,
                                    ),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }
                }

                if (state.collected.isNotEmpty()) {
                    item(key = "h-collected") {
                        SectionTitle(
                            stringResource(
                                if (blank) R.string.search_section_recent else R.string.search_section_collected,
                            ),
                        )
                    }
                    items(state.collected, key = { "c-" + it.id }) { card ->
                        CollectedRow(card = card, onSpeak = { onSpeak(card.headword, card.language) })
                    }
                }

                if (state.diaryHits.isNotEmpty()) {
                    item(key = "h-diary") { SectionTitle(stringResource(R.string.search_section_diary)) }
                    items(state.diaryHits, key = { "d-" + it.id }) { entry ->
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenEntry(entry.id) },
                        ) {
                            Column(
                                modifier = Modifier.padding(Space.md),
                                verticalArrangement = Arrangement.spacedBy(Space.xs),
                            ) {
                                Text(
                                    text = entry.title?.takeIf { it.isNotBlank() }
                                        ?: stringResource(R.string.detail_untitled),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                entry.summary?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectedRow(card: WordCard, onSpeak: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(card.headword, style = MaterialTheme.typography.titleSmall)
            card.glosses.values.firstOrNull()?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        SpeakIcon(onClick = onSpeak)
    }
}

@Composable
private fun SpeakIcon(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(
            imageVector = WordLensIcons.Speaker,
            contentDescription = stringResource(R.string.capture_speak),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Space.sm)
            .padding(horizontal = Space.xs),
    )
}
