// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ilyskyo.wordlens.ui.theme.BottomSheetShape
import com.ilyskyo.wordlens.ui.theme.Space

/**
 * iOS 的 form sheet：一张从底部升起的表单，顶栏左「取消」右「完成」，中间整页可滚。
 *
 * ## 为什么「编辑一条日记」不该是一格 alert
 *
 * alert 的形状是「问一个是非」：一句标题、一句正文、两条出路。而编辑一条日记要摆两个输入框
 * 加一排心情胶囊——装机截图上看到的后果就是最后一排胶囊被下面的动作行**齐头切掉一半**，
 * 键盘弹起之后对话框被压扁，切得更狠。iOS 对「编辑一条记录」用的从来不是 alert，
 * 是这一种：出路待在顶栏里，内容区想多长滚多长。
 *
 * ## 顶栏为什么钉住，不跟着滚
 *
 * 和 [WordLensDialog] 里那条是同一件事：出路必须一直在眼前。滚走了「完成」，
 * 用户就只剩「往下找找看怎么提交」这一条路，而那是最容易被当成「没保存成功」的瞬间。
 *
 * ## 键盘
 *
 * 滚动区带 `imePadding()`：没有它，正在输入的那一格会被键盘压在下面，
 * 而这一页恰好全是「点一下就开始打字」的字段。
 *
 * 这里**不**管草稿的生命周期——那是调用方的事（见 `EntryDetailScreen` 里那段注释：
 * 草稿住在外层、关闭即作废）。换容器不该顺手改掉数据的归属。
 */
@Composable
fun WordLensFormSheet(
    title: String,
    cancelText: String,
    onCancel: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onCancel,
        // skipPartiallyExpanded：表单要的是「一升起来就是完整的一张」，而不是一半高度
        // 再让用户自己往上拽——他要拽的是键盘上面那一格，不是这张 sheet。
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = BottomSheetShape,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        modifier = modifier,
    ) {
        SheetContent(
            title = title,
            cancelText = cancelText,
            onCancel = onCancel,
            confirmText = confirmText,
            onConfirm = onConfirm,
            confirmEnabled = confirmEnabled,
            content = content,
        )
    }
}

/**
 * sheet 的正文。单独拆出来只为一件事：`ModalBottomSheet` 的内容槽是 [ColumnScope]，
 * 而我们要给调用方的 `content` 是一个不带作用域的 lambda——不然调用方里的 `Column`
 * 会套在两个作用域之间，读起来要先猜「这个 fillMaxWidth 是谁在管」。
 */
@Composable
private fun ColumnScope.SheetContent(
    title: String,
    cancelText: String,
    onCancel: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancel) {
            Text(
                text = cancelText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onConfirm, enabled = confirmEnabled) {
            Text(
                text = confirmText,
                // 「完成」比「取消」重一档：iOS 顶栏里右边那颗就是这个关系。
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    // 顶栏与表单之间一根发丝线。iOS 的导航栏下沿就是这个东西，没有它顶栏会浮在内容上。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.7.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(start = Space.lg, end = Space.lg, top = Space.md, bottom = Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        content()
    }
}
