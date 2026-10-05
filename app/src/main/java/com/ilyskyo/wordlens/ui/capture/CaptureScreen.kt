// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ilyskyo.wordlens.R
import com.ilyskyo.wordlens.data.model.ShotKind
import com.ilyskyo.wordlens.ui.components.OutlinedAction
import com.ilyskyo.wordlens.ui.components.SpeakButton
import com.ilyskyo.wordlens.ui.components.TonalButton
import com.ilyskyo.wordlens.ui.icons.WordLensIcons
import com.ilyskyo.wordlens.ui.theme.BottomSheetShape
import com.ilyskyo.wordlens.ui.theme.IpaTextStyle
import com.ilyskyo.wordlens.ui.theme.Space
import com.ilyskyo.wordlens.ui.theme.WordLensTheme

/**
 * 拍照页状态。
 *
 * 抽成 data class 而不是直接绑 ViewModel，是为了让整页可以 `@Preview`——这一页有五个互斥
 * 阶段，任何一个阶段写错都很难在真机上稳定复现。
 */
data class CaptureUiState(
    val analysing: Boolean = false,
    /** 抠图结果。null 表示还在取景或分析中。 */
    val sticker: Bitmap? = null,
    val headword: String? = null,
    val ipa: String? = null,
    val gloss: String? = null,
    /** 判帧结果，驱动顶部提示与是否要求「先点一下主体」。 */
    val shotKind: ShotKind = ShotKind.UNCLEAR,
    val shotReason: String = "",
    /** 模型原始标签。识别不到词时也要展示：用户看到具体标签才知道该输什么。 */
    val rawLabels: List<String> = emptyList(),
)

/**
 * 拍照页（无状态）。
 *
 * 阶段：
 * 1. 无权限：说明 + 授权按钮
 * 2. 有权限取景：取景器（由调用方注入）+ 快门 + 判帧提示
 * 3. 分析中：贴纸预览 + 进度环
 * 4. 有结果：底部信息面板（单词 / 音标 / 释义 / 发音）
 * 5. 无结果：展示模型看到的标签，而不是一句「识别失败」
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    bottomInset: PaddingValues,
    state: CaptureUiState = CaptureUiState(),
    onShutter: () -> Unit = {},
    onRetake: () -> Unit = {},
    onSave: () -> Unit = {},
    onSpeak: () -> Unit = {},
    onTapSubject: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
    previewContent: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { /* 权限结果由调用方在 onResume 里重新读取 */ },
    )
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

    Box(modifier = modifier) {
        if (!granted) {
            PermissionRationale(
                onGrant = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(Space.lg),
            )
        } else {
            // 取景器由调用方注入：真机上是一块 CameraX PreviewView 的 AndroidView。
            // 这里给一块底色，保证预览里的布局尺寸与真机一致。
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                previewContent()
            }

            CaptureTopBar(
                onOpenSettings = onOpenSettings,
                shotKind = state.shotKind,
                reason = state.shotReason,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            CaptureBottomControls(
                analysing = state.analysing,
                bottomInset = bottomInset,
                onShutter = onShutter,
                onTapSubject = onTapSubject,
                shotKind = state.shotKind,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        AnimatedVisibility(
            visible = state.sticker != null,
            enter = fadeIn() + slideInVertically { it / 8 },
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            CaptureResult(
                state = state,
                onSpeak = onSpeak,
                onRetake = onRetake,
                onSave = onSave,
                bottomInset = bottomInset,
            )
        }
    }
}

@Composable
private fun PermissionRationale(onGrant: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Text(text = "\uD83D\uDCF7", fontSize = 48.sp)
        Text(
            text = stringResource(R.string.capture_permission_needed),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        TonalButton(
            text = stringResource(R.string.capture_grant),
            onClick = onGrant,
            modifier = Modifier.width(220.dp),
        )
    }
}

@Composable
private fun CaptureTopBar(
    onOpenSettings: () -> Unit,
    shotKind: ShotKind,
    reason: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // 顶部渐变遮罩：保证白色文字在亮天空上也读得清。
            .background(Brush.verticalGradient(listOf(Color(0xB3000000), Color(0x00000000))))
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.testTag("settings"),
            ) {
                Icon(
                    imageVector = WordLensIcons.Settings,
                    contentDescription = stringResource(R.string.capture_settings),
                    tint = Color.White,
                )
            }
        }
        if (reason.isNotBlank()) {
            ShotKindChip(kind = shotKind, reason = reason)
        }
    }
}

/**
 * 判帧结果胶囊。
 *
 * 把「系统认为这是单物体还是场景」讲出来，而不是默默替用户决定：判错了用户还能改，
 * 看不到就无从纠正。
 */
@Composable
private fun ShotKindChip(kind: ShotKind, reason: String, modifier: Modifier = Modifier) {
    val tint = when (kind) {
        ShotKind.OBJECT -> MaterialTheme.colorScheme.primaryContainer
        ShotKind.SCENE -> MaterialTheme.colorScheme.secondaryContainer
        ShotKind.UNCLEAR -> MaterialTheme.colorScheme.surfaceVariant
    }
    val text = when (kind) {
        ShotKind.OBJECT -> stringResource(R.string.capture_shot_object)
        ShotKind.SCENE -> stringResource(R.string.capture_shot_scene)
        ShotKind.UNCLEAR -> reason
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        modifier = modifier
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.92f))
            .padding(horizontal = Space.md, vertical = Space.xs),
    )
}

@Composable
private fun CaptureBottomControls(
    analysing: Boolean,
    bottomInset: PaddingValues,
    onShutter: () -> Unit,
    onTapSubject: () -> Unit,
    shotKind: ShotKind,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0x00000000), Color(0xB3000000))))
            .padding(
                start = Space.md,
                end = Space.md,
                top = Space.xl,
                bottom = bottomInset.calculateBottomPadding() + Space.md,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        // 判帧不明确时先让用户点一下主体：这一步同时完成分割与判帧。
        if (shotKind == ShotKind.UNCLEAR && !analysing) {
            Text(
                text = stringResource(R.string.capture_tap_subject),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0x66000000))
                    .clickable(onClick = onTapSubject)
                    .padding(horizontal = Space.md, vertical = Space.sm),
            )
        }
        ShutterButton(enabled = !analysing, onClick = onShutter)
    }
}

/**
 * 快门：白色外圈 + 主色内圈。
 *
 * 按下缩到 0.92。分析中时中间换成进度环而不是「转圈图标」，让「正在做什么」与
 * 「做完会得到什么」出现在同一个位置。
 */
@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (enabled) 1f else 0.92f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
        ),
        label = "shutter",
    )
    Box(
        modifier = modifier
            .size(SHUTTER_OUTER)
            .scale(scale)
            .clip(CircleShape)
            .border(3.dp, Color.White.copy(alpha = if (enabled) 1f else 0.5f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (enabled) {
            Box(
                Modifier
                    .size(SHUTTER_INNER)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(SHUTTER_INNER * 0.6f),
                color = Color.White,
                strokeWidth = 3.dp,
            )
        }
    }
}

private val SHUTTER_OUTER = 72.dp
private val SHUTTER_INNER = 60.dp

/** 识别结果面板：贴纸 + 单词 + 音标 + 释义 + 发音 + 两个动作。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptureResult(
    state: CaptureUiState,
    onSpeak: () -> Unit,
    onRetake: () -> Unit,
    onSave: () -> Unit,
    bottomInset: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onRetake,
        sheetState = sheetState,
        shape = BottomSheetShape,
        modifier = modifier.testTag("wordInfoSheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg)
                .padding(bottom = bottomInset.calculateBottomPadding() + Space.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            val headword = state.headword
            if (headword != null) {
                Text(
                    text = headword,
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                state.ipa?.let { ipa ->
                    Text(
                        text = ipa,
                        style = IpaTextStyle.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.gloss?.let { gloss ->
                    Text(
                        text = gloss,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                }
                SpeakButton(
                    onClick = onSpeak,
                    contentDescription = stringResource(R.string.capture_speak),
                    enabled = !state.analysing,
                )
            } else {
                NoMatchPanel(state = state)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                OutlinedAction(
                    text = stringResource(R.string.capture_retake),
                    onClick = onRetake,
                    modifier = Modifier.weight(1f),
                )
                TonalButton(
                    text = stringResource(R.string.capture_save),
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 「认不出来」的诚实面板。
 *
 * 展示模型实际看到的标签，而不是一句「识别失败」。这很实际：用户看到 "Coffee cup" 会立刻
 * 知道该输什么；看到「失败」只会重拍一百次。
 */
@Composable
private fun NoMatchPanel(state: CaptureUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(
            text = stringResource(R.string.nomatch_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (state.rawLabels.isNotEmpty()) {
            Text(
                text = stringResource(R.string.nomatch_labels_seen),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = state.rawLabels.joinToString(SEPARATOR),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            text = stringResource(R.string.nomatch_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 标签之间的分隔符。用间隔点而不是逗号，避免与英文标签自身的标点混在一起。 */
private const val SEPARATOR = "  \u00B7  "

@Preview(showBackground = true, backgroundColor = 0xFFFFF8F3)
@Composable
private fun CapturePermissionPreview() {
    WordLensTheme {
        CaptureScreen(bottomInset = PaddingValues(0.dp))
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF6B7B6B, widthDp = 380, heightDp = 720)
@Composable
private fun CaptureFoundPreview() {
    WordLensTheme {
        CaptureScreen(
            bottomInset = PaddingValues(0.dp),
            state = CaptureUiState(
                headword = "cup",
                ipa = "/k\u028Ap/",
                gloss = "\u676F\u5B50",
                shotKind = ShotKind.OBJECT,
                shotReason = "One object filling 31% of the frame.",
            ),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF6B7B6B, widthDp = 380, heightDp = 720)
@Composable
private fun CaptureNoMatchPreview() {
    WordLensTheme {
        CaptureScreen(
            bottomInset = PaddingValues(0.dp),
            state = CaptureUiState(
                shotKind = ShotKind.SCENE,
                shotReason = "The subject is only 1% of the photo.",
                rawLabels = listOf("Food", "Home good", "Plant"),
            ),
        )
    }
}