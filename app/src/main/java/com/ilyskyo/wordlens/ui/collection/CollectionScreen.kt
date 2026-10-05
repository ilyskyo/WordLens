// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.collection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ilyskyo.wordlens.R
import com.ilyskyo.wordlens.ui.components.EmptyState
import com.ilyskyo.wordlens.ui.components.StickerCard
import com.ilyskyo.wordlens.ui.icons.WordLensIcons
import com.ilyskyo.wordlens.ui.theme.Space
import com.ilyskyo.wordlens.ui.theme.WordLensTheme

/** 收藏页排序方式。 */
enum class CollectionFilter(val labelRes: Int) {
    ALL(R.string.collection_filter_all),
    RECENT(R.string.collection_filter_recent),
    ALPHABETICAL(R.string.collection_filter_alpha),
    PROGRESS(R.string.collection_filter_progress),
}

/** 网格里的一项。 */
data class StickerListItem(
    val id: String,
    val headword: String,
    val ipa: String?,
    val emoji: String?,
    /** 到期天数；null 表示新卡。 */
    val dueInDays: Int? = null,
    val reviewCount: Int = 0,
    val bitmapPath: String? = null,
)

data class CollectionUiState(
    val query: String = "",
    val filter: CollectionFilter = CollectionFilter.RECENT,
    val items: List<StickerListItem> = emptyList(),
)

/**
 * 收藏页（无状态）。
 *
 * 两列固定网格而不是瀑布流：贴纸卡片高度固定（4:3 图 + 两行字），瀑布流在这种统一尺寸下
 * 只会让同批卡片的高度抖动，反而更难扫读。
 *
 * 旋转角度由 id 派生（见 [rotationFor]）而不是存字段，这样从收藏页进详情再返回时卡片
 * 不会「转个角度」。
 */
@Composable
fun CollectionScreen(
    bottomInset: PaddingValues,
    state: CollectionUiState = CollectionUiState(),
    onQueryChange: (String) -> Unit = {},
    onFilterChange: (CollectionFilter) -> Unit = {},
    onClickItem: (StickerListItem) -> Unit = {},
    onLongPressItem: (StickerListItem) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var menuFor by remember { mutableStateOf<StickerListItem?>(null) }

    val visible = remember(state.query, state.filter, state.items) {
        applyFilter(state.items, state.query, state.filter)
    }
    val searching = state.query.isNotBlank()

    Column(modifier = modifier.fillMaxSize()) {
        CollectionHeader(
            query = state.query,
            onQueryChange = onQueryChange,
            filter = state.filter,
            onFilterChange = onFilterChange,
        )

        if (visible.isEmpty()) {
            EmptyState(
                emoji = if (searching) "\uD83D\uDD0D" else "\uD83D\uDCD2",
                title = stringResource(
                    if (searching) {
                        R.string.collection_search_empty_title
                    } else {
                        R.string.collection_empty_title
                    },
                ),
                body = stringResource(
                    if (searching) {
                        R.string.collection_search_empty_body
                    } else {
                        R.string.collection_empty_body
                    },
                ),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(
                    start = Space.md,
                    end = Space.md,
                    top = Space.sm,
                    bottom = bottomInset.calculateBottomPadding() + Space.lg,
                ),
                horizontalArrangement = Arrangement.spacedBy(Space.md),
                verticalArrangement = Arrangement.spacedBy(Space.lg),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(visible, key = { it.id }) { item ->
                    StickerCard(
                        bitmap = null,
                        headword = item.headword,
                        ipa = item.ipa,
                        emoji = item.emoji,
                        rotationDegrees = rotationFor(item.id),
                        onClick = { onClickItem(item) },
                        onLongClick = { menuFor = item },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Space.xs),
                    )
                }
            }
        }
    }

    // 长按菜单。放在 Composable 根部而不是项内部：项会被滚出屏幕，菜单不该跟着滚。
    menuFor?.let { target ->
        DropdownMenu(
            expanded = true,
            onDismissRequest = { menuFor = null },
            modifier = Modifier.semantics {
                contentDescription = ""
            },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.collection_action_edit)) },
                onClick = { menuFor = null; onLongPressItem(target) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.collection_action_review_now)) },
                onClick = { menuFor = null; onClickItem(target) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.collection_action_delete)) },
                onClick = { menuFor = null; onLongPressItem(target) },
            )
        }
    }
}

@Composable
private fun CollectionHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    filter: CollectionFilter,
    onFilterChange: (CollectionFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Space.md)
            .padding(top = Space.lg, bottom = Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(
            text = stringResource(R.string.collection_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = {
                Text(
                    text = stringResource(R.string.collection_search_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            leadingIcon = { Icon(WordLensIcons.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            imageVector = WordLensIcons.Close,
                            contentDescription = stringResource(R.string.collection_clear_search),
                        )
                    }
                }
            },
            singleLine = true,
            shape = CircleShape,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedBorderColor = Color.Transparent,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            items(CollectionFilter.entries.toList()) { f ->
                val selected = filter == f
                FilterChip(
                    selected = selected,
                    onClick = { onFilterChange(f) },
                    label = {
                        Text(
                            text = stringResource(f.labelRes),
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
}

private fun applyFilter(
    items: List<StickerListItem>,
    query: String,
    filter: CollectionFilter,
): List<StickerListItem> {
    val searched = if (query.isBlank()) {
        items
    } else {
        items.filter { it.headword.contains(query, ignoreCase = true) }
    }
    return when (filter) {
        CollectionFilter.ALL -> searched
        // 「最近」= 最紧急的排前面：已逾期的 dueInDays 为负，自然排最前。
        CollectionFilter.RECENT -> searched.sortedBy { it.dueInDays ?: Int.MAX_VALUE }
        CollectionFilter.ALPHABETICAL -> searched.sortedBy { it.headword.lowercase() }
        CollectionFilter.PROGRESS -> searched.sortedBy { it.reviewCount }
    }
}

/**
 * 由 id 派生的固定伪随机角度。
 *
 * 字符码求和后对 601 取模得到 -300..+300，除以 100 得到 -3°..+3°。刻意不用
 * `kotlin.random`：同一个 id 必须永远得到同一个角度，否则卡片在页面间会跳动。
 */
private fun rotationFor(id: String): Float {
    val sum = id.fold(0) { acc, c -> acc + c.code }
    return ((sum % 601) - 300) / 100f
}

@Preview(showBackground = true, backgroundColor = 0xFFFFF8F3, widthDp = 380, heightDp = 760)
@Composable
private fun CollectionPreview() {
    WordLensTheme {
        CollectionScreen(
            bottomInset = PaddingValues(0.dp),
            state = CollectionUiState(
                items = listOf(
                    StickerListItem("a1", "cup", "/k\u028Ap/", "\u2615", 0, 3),
                    StickerListItem("b2", "bridge", "/br\u026Ad\u0292/", "\uD83C\uDF09", 4, 1),
                    StickerListItem("c3", "bicycle", "/b\u02C8a\u026As\u026Akl/", "\uD83D\uDEF6", null, 0),
                    StickerListItem("d4", "apple", "/\u00C6pl/", "\uD83C\uDF4E", 12, 7),
                ),
            ),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFFF8F3, widthDp = 380, heightDp = 640)
@Composable
private fun CollectionEmptyPreview() {
    WordLensTheme {
        CollectionScreen(bottomInset = PaddingValues(0.dp), state = CollectionUiState())
    }
}