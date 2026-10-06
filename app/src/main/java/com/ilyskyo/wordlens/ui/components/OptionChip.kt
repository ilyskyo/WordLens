// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ilyskyo.wordlens.ui.theme.Scale
import com.ilyskyo.wordlens.ui.theme.pressFeedback

/**
 * 选择胶囊。
 *
 * M3 的 `FilterChip` 在语义上是对的——选中态、`Role.Checkbox`、键盘导航、
 * TalkBack 念「已选中」都免费。它缺的只有按压手感：波纹在全局换成空指示器之后，
 * 一个既不缩也不震的胶囊读起来像「没按到」。
 *
 * 所以这里**不重写一个 Chip**，只把它的 `interactionSource` 借出来接到 [pressFeedback] 上：
 * 语义交给 Material，手感交给我们自己。自己写 Chip 会立刻丢掉上面那一整串无障碍行为，
 * 而那些东西恰恰是「看起来一样」和「真的能用」的分界线。
 *
 * ## 外观是填充式，和 `InsetField` 同一档墨量
 *
 * M3 默认那颗描边，iOS 那一套是「灰底，选中换成主色底」。底色用 `onSurface` 的透明度而不是
 * 写死一个灰，理由和 `InsetField` 一样：它要同时活在卡面、窗口底、对话框和夜纸上。
 * 选中态底色与文字**同时**变，所以色盲与低亮度下仍然分得出来。
 *
 * 这里也没有勾选图标：Material3 1.4 起 `FilterChip` 只在调用方自己传 `leadingIcon` /
 * `trailingIcon` 时才画图标，我们不传，于是没有勾。这恰好是对的——勾选框是「复选」的形状，
 * 而用它的每一排都是**单选且可再点一次取消**（心情那一项），画个勾会让人以为还要点「完成」提交。
 *
 * @param leading 左侧自定义内容（比如评级配色的小色块）。M3 对 leading 的尺寸有内部约束，
 *   所以外面套一个固定 18dp 的盒子里，让调用方决定画什么而不必猜可用空间。
 */
@Composable
fun OptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val onSurface = MaterialTheme.colorScheme.onSurface
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = CircleShape,
        // 必须显式传 null：`FilterChip` 的 border **默认值就是一圈描边**
        // （`FilterChipDefaults.filterChipBorder(...)`），不传得到的正是要去掉的那件事。
        border = null,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = onSurface.copy(alpha = UnselectedAlpha),
            labelColor = onSurface,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            disabledContainerColor = onSurface.copy(alpha = UnselectedAlpha / 2f),
            disabledLabelColor = onSurface.copy(alpha = DisabledAlpha),
            disabledSelectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(
                alpha = DisabledAlpha,
            ),
        ),
        // 自定义内容放进 label 而不是 leadingIcon：后者要求传一个可空的 @Composable lambda，
        // 而 Kotlin 里 `if (x == null) null else @Composable { … }` 推断出来的是 `Unit?`，
        // 只能再写一层辅助函数去迁就类型。label 本身就是 composable 槽位，
        // 在里面自己排版既少一个坑，也更可控（色块与文字之间的间隙是我说了算的）。
        label = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(LEADING_GAP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) {
                    Box(modifier = Modifier.size(LEADING_BOX)) { leading() }
                }
                Text(label)
            }
        },
        modifier = modifier.pressFeedback(interaction, pressedScale = Scale.Small),
    )
}

/** leading 的可用边长。18dp 与 M3 Chip 默认勾选图标同尺寸，换成别的会让整排胶囊高度不一致。 */
private val LEADING_BOX = 18.dp

/** 色块与文字之间只需要一口气，给多了这颗胶囊会读成两个控件。 */
private val LEADING_GAP = 6.dp

/** 未选中的底。与 `InsetField` 的填充同一档，两者并排时读起来是一块东西。 */
private const val UnselectedAlpha = 0.075f

/** 禁用态的墨量。用半分而不是另一个灰：它要读作「同一颗，只是现在不能按」。 */
private const val DisabledAlpha = 0.38f
