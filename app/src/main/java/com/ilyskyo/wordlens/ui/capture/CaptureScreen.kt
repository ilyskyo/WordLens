// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import com.ilyskyo.wordlens.ui.theme.Haptic
import com.ilyskyo.wordlens.ui.theme.IpaTextStyle
import com.ilyskyo.wordlens.ui.theme.Motion
import com.ilyskyo.wordlens.ui.theme.Scale
import com.ilyskyo.wordlens.ui.theme.Space
import com.ilyskyo.wordlens.ui.theme.WordLensTheme
import com.ilyskyo.wordlens.ui.theme.pressable
import com.ilyskyo.wordlens.vision.camera.CameraFocusMath.NormBox
import com.ilyskyo.wordlens.vision.camera.CameraFocusMath.SensorCrop

/**
 * 拍照页状态。
 *
 * 抽成 data class 而不是直接绑 ViewModel，是为了让整页可以 `@Preview`——这一页有五个互斥
 * 阶段，任何一个阶段写错都很难在真机上稳定复现。
 */
data class CaptureUiState(
    val analysing: Boolean = false,
    /**
     * 正在处理相册里的那张照片。
     *
     * 单独一个布尔而不是复用 [analysing]：快门与分析共用 `analysing`，而这一颗按钮需要
     * 知道自己那件事在不在跑，才能把图标换成进度环、把上面那句话说成「正在读这张照片」。
     */
    val importing: Boolean = false,
    /** 抠图结果。null 表示还在取景或分析中。 */
    val sticker: Bitmap? = null,
    val headword: String? = null,
    val ipa: String? = null,
    val gloss: String? = null,
    /** 判帧结果。现在只驱动覆盖层的视觉权重，不再是模式门。 */
    val shotKind: ShotKind = ShotKind.UNCLEAR,
    val shotReason: String = "",
    /** 模型原始标签。识别不到词时也要展示：用户看到具体标签才知道该输什么。 */
    val rawLabels: List<String> = emptyList(),
    /** 取景中的物品词片（检测器产出，已换算到传感器坐标）。 */
    val chips: List<WordChip> = emptyList(),
    /** 氛围词候选，数量按 [shotKind] 截断。 */
    val ambience: List<String> = emptyList(),
    /** 当前选中的词片 key；null 表示用户没点任何东西——按快门就存整张照片进「回看」。 */
    val selectedChipKey: String? = null,
    /** 「认不出来时手写」的输入内容。放在 state 里，转屏不会丢。 */
    val manualWord: String = "",
    /**
     * 手写词的释义。词典里没有这个词时它是**唯一**能把它变成一条用户词条的东西，
     * 而「存进你的词典」这句话（`nomatch_body`）全靠它才不是空话。
     */
    val manualMeaning: String = "",
    /** 取景几何快照。null 表示相机还没就绪（无权限 / 绑定中），此时不画覆盖层。 */
    val cameraFrame: CameraFrame? = null,
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
    onClose: () -> Unit = {},
    onShutter: () -> Unit = {},
    /** 从相册挑一张照片，走与快门同一条流水线。选择器由调用方持有（要 Activity 结果回调）。 */
    onImportFromGallery: () -> Unit = {},
    onRetake: () -> Unit = {},
    onSave: () -> Unit = {},
    onSpeak: () -> Unit = {},
    onTapSubject: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    /** 点词片：传 null 表示取消选中。调用方通常同时把镜头推过去（CameraFocusController）。 */
    onChipSelect: (String?) -> Unit = {},
    /** 「认不出来时手写」：输入与收录。 */
    onManualWordChange: (String) -> Unit = {},
    onManualMeaningChange: (String) -> Unit = {},
    onManualAdd: () -> Unit = {},
    /** null = 自己查 Context。Preview 里查不到运行时权限，传 true 才能看到取景态。 */
    cameraGranted: Boolean? = null,
    modifier: Modifier = Modifier,
    previewContent: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    // 用户答完授权弹窗后，Compose 不会因为权限变了而重组——必须自己制造一次状态变化。
    // 拿这个 tick 参与 granted 的计算，比在 onResume 里重读更可靠（对话框回调不一定走 onResume）。
    var permissionTick by remember { mutableIntStateOf(0) }
    var permanentlyDenied by remember { mutableStateOf(false) }
    val activity = LocalContext.current as? Activity
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { grantedResult ->
            permissionTick++
            // 唯一可靠的判别：还能弹 rationale 就还能再问；弹不出来而权限仍没有，就是永久拒绝。
            // 第一次进来时 rationale 也是 false，所以这个判断只在**用户答过之后**才做。
            if (!grantedResult) {
                permanentlyDenied = activity?.shouldShowRequestPermissionRationale(
                    Manifest.permission.CAMERA,
                ) != true
            }
        },
    )
    val granted = cameraGranted ?: (permissionTick >= 0 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED)

    Box(modifier = modifier) {
        if (!granted) {
            PermissionRationale(
                permanentlyDenied = permanentlyDenied,
                onGrant = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onOpenAppSettings = {
                    // 「去授权」在权限已被永久拒绝之后只剩一条路：应用详情页。
                    // 继续重复 launch 只会拿到同一个静默的 false，用户则在原地第三次点同一个按钮。
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    }
                },
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

                // 词片与氛围词浮在预览之上。分析中不画：那一刻用户该看结果而不是继续选。
                val frame = state.cameraFrame
                if (frame != null && !state.analysing && state.sticker == null) {
                    ViewfinderOverlay(
                        chips = state.chips,
                        ambience = state.ambience,
                        frame = frame,
                        shotKind = state.shotKind,
                        selectedKey = state.selectedChipKey,
                        onSelect = onChipSelect,
                    )
                }
            }

            CaptureTopBar(
                onClose = onClose,
                onOpenSettings = onOpenSettings,
                shotKind = state.shotKind,
                reason = state.shotReason,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            CaptureBottomControls(
                state = state,
                bottomInset = bottomInset,
                onShutter = onShutter,
                onImportFromGallery = onImportFromGallery,
                onTapSubject = onTapSubject,
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
                onManualWordChange = onManualWordChange,
                onManualMeaningChange = onManualMeaningChange,
                onManualAdd = onManualAdd,
                bottomInset = bottomInset,
            )
        }
    }
}

@Composable
private fun PermissionRationale(
    permanentlyDenied: Boolean,
    onGrant: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        // 永久拒绝之后再点「授权」，系统只会静默回一个 false：用户会以为按钮坏了。
        // 这时候唯一的出路是把人送到应用详情页，并且明说要去那里打开。
        TonalButton(
            text = stringResource(if (permanentlyDenied) R.string.capture_open_settings else R.string.capture_grant),
            onClick = if (permanentlyDenied) onOpenAppSettings else onGrant,
            modifier = Modifier.width(220.dp),
        )
        if (permanentlyDenied) {
            Text(
                text = stringResource(R.string.capture_permission_permanently_denied),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CaptureTopBar(
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    shotKind: ShotKind,
    reason: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // 顶部渐变遮罩：保证白色文字在亮天空上也读得清。遮罩要一直铺到屏幕顶端，
            // 避让放在它之后——否则状态栏那一条既没有遮罩也没有留白，标题会压在时钟上。
            .background(Brush.verticalGradient(listOf(Color(0xB3000000), Color(0x00000000))))
            .statusBarsPadding()
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 退出与设置分列两侧：取景页是浮在主界面之上的一层，没有返回键就等于把用户
            // 关在里面——除了按下快门保存之外无路可走。
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.testTag("close"),
                ) {
                    Icon(
                        imageVector = WordLensIcons.Close,
                        contentDescription = stringResource(R.string.capture_close),
                        tint = Color.White,
                    )
                }
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
            }
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
    state: CaptureUiState,
    bottomInset: PaddingValues,
    onShutter: () -> Unit,
    onImportFromGallery: () -> Unit,
    onTapSubject: () -> Unit,
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
        // 快门上方一句话讲清「按下去会发生什么」：选了物品存进「记住」，没选存整张进「回看」。
        // 这条二分是整个产品的骨架，不该让用户猜。
        val selectedWord = state.chips.firstOrNull { it.key == state.selectedChipKey }?.word
        val hint = when {
            // 导入在跑的时候这句话优先：那一刻用户唯一想知道的就是「这一下有没有在动」。
            state.importing -> stringResource(R.string.capture_importing)
            selectedWord != null -> stringResource(R.string.capture_selected_hint, selectedWord)
            state.chips.isNotEmpty() -> stringResource(R.string.capture_scene_hint)
            state.shotKind == ShotKind.UNCLEAR && !state.analysing ->
                stringResource(R.string.capture_tap_subject)
            else -> null
        }
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0x66000000))
                    .then(
                        // 没有任何词片时才把提示本身做成按钮（走手动抠主流）；
                        // 有词片时选择靠点词片完成，提示只是陈述。
                        if (selectedWord == null && state.chips.isEmpty() && !state.importing) {
                            Modifier.pressable(onClick = onTapSubject)
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = Space.md, vertical = Space.sm),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左边垫的空白**正好等于右边那颗按钮**：这样中间那块的重心就是屏幕的重心，
            // 快门仍然在正中央。相册入口是同一个动作的另一个来源，不该把主角挤离位置。
            Spacer(Modifier.size(IMPORT_BUTTON))
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                ShutterButton(enabled = !state.analysing, onClick = onShutter)
            }
            GalleryImportButton(
                enabled = !state.analysing,
                importing = state.importing,
                onClick = onImportFromGallery,
            )
        }
    }
}

/**
 * 相册入口。
 *
 * 造型**照抄快门**（描边 + 半透明底 + `pressable(Scale.Small)` + 进度环），只小一档：
 * 这一条上没有任何阴影，加一颗带阴影的按钮会比快门还抢眼；而它值得和快门同一套手感语言，
 * 因为按下去做的是同一件事——把这一刻记下来，只是照片来自相册而不是镜头。
 *
 * 没有波纹：主题装的 `NoIndication` 已经全局关掉，这里也不重新引一次。
 */
@Composable
private fun GalleryImportButton(
    enabled: Boolean,
    importing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(IMPORT_BUTTON)
            .clip(CircleShape)
            .background(Color(0x4D000000))
            .border(
                width = 2.dp,
                color = Color.White.copy(alpha = if (enabled) 0.72f else 0.32f),
                shape = CircleShape,
            )
            .pressable(
                onClick = onClick,
                enabled = enabled,
                role = Role.Button,
                pressedScale = Scale.Small,
                haptic = Haptic.Pop,
            )
            .testTag("import"),
        contentAlignment = Alignment.Center,
    ) {
        if (importing) {
            CircularProgressIndicator(
                modifier = Modifier.size(IMPORT_BUTTON * 0.52f),
                color = Color.White,
                strokeWidth = 2.5.dp,
            )
        } else {
            Icon(
                imageVector = WordLensIcons.Gallery,
                contentDescription = stringResource(R.string.capture_import),
                tint = Color.White.copy(alpha = if (enabled) 0.92f else 0.45f),
                modifier = Modifier.size(IMPORT_BUTTON * 0.48f),
            )
        }
    }
}

/**
 * 快门：白色外圈 + 主色内圈。
 *
 * 不可用时缩到 0.92。分析中时中间换成进度环而不是「转圈图标」，让「正在做什么」与
 * 「做完会得到什么」出现在同一个位置。
 *
 * 这一条**不跟随系统的「移除动画」**：它看着像装饰性的缩放，其实是「现在能不能按」的
 * 读数之一（另一个是外圈透明度）。降级只降拿掉之后信息仍然完整的动效，这条不是。
 * 开关打开时框架会把时长压成 0，它自己就变成瞬时的了，不需要我们再插手。
 */
@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scale by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.92f,
        animationSpec = Motion.smooth,
        label = "shutterAvailability",
    )
    Box(
        modifier = modifier
            .size(SHUTTER_OUTER)
            .scale(scale)
            .clip(CircleShape)
            .border(3.dp, Color.White.copy(alpha = if (enabled) 1f else 0.5f), CircleShape)
            // 快门是全 App 最该「按下去有回声」的一颗按钮：缩到 0.90 并发一次重触觉。
            .pressable(
                onClick = onClick,
                enabled = enabled,
                pressedScale = Scale.Small,
                haptic = Haptic.Heavy,
            ),
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

/**
 * 相册入口的直径。
 *
 * 52 而不是 48：48 是触控下限，而这一颗挨着 72dp 的快门，太小的话两者会被读成
 * 「一个是主、一个是附属说明」——它是并列的另一个来源，不是脚注。
 * 再大就开始和快门争主次，72 的那一圈是留给快门的。
 */
private val IMPORT_BUTTON = 52.dp

/** 识别结果面板：贴纸 + 单词 + 音标 + 释义 + 发音 + 两个动作。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptureResult(
    state: CaptureUiState,
    onSpeak: () -> Unit,
    onRetake: () -> Unit,
    onSave: () -> Unit,
    onManualWordChange: (String) -> Unit,
    onManualMeaningChange: (String) -> Unit,
    onManualAdd: () -> Unit,
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
                NoMatchPanel(
                    state = state,
                    onManualWordChange = onManualWordChange,
                    onManualMeaningChange = onManualMeaningChange,
                    onManualAdd = onManualAdd,
                )
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
private fun NoMatchPanel(
    state: CaptureUiState,
    onManualWordChange: (String) -> Unit,
    onManualMeaningChange: (String) -> Unit,
    onManualAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        // 那句话承诺过的东西，这里就是它的入口。释义这一格是入口的另一半：
        // 只有词没有意思，存进去的就是一张空释义的卡，而 FSRS 会非常认真地把噪音排到未来。
        OutlinedTextField(
            value = state.manualWord,
            onValueChange = onManualWordChange,
            label = { Text(stringResource(R.string.manual_field_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.manualMeaning,
            onValueChange = onManualMeaningChange,
            label = { Text(stringResource(R.string.nomatch_meaning_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TonalButton(
            text = stringResource(R.string.manual_add),
            onClick = onManualAdd,
            enabled = state.manualWord.isNotBlank(),
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
private fun CaptureViewfinderPreview() {
    WordLensTheme {
        CaptureScreen(
            bottomInset = PaddingValues(0.dp),
            cameraGranted = true,
            state = CaptureUiState(
                shotKind = ShotKind.SCENE,
                chips = listOf(
                    WordChip("cup", "cup", NormBox(0.15f, 0.35f, 0.35f, 0.55f)),
                    WordChip("plant", "plant", NormBox(0.60f, 0.20f, 0.75f, 0.40f)),
                    WordChip("strange thing", "strange thing", NormBox(0.40f, 0.60f, 0.55f, 0.72f), known = false),
                ),
                ambience = listOf("warm", "quiet", "afternoon light", "cozy", "slow"),
                selectedChipKey = "cup",
                cameraFrame = CameraFrame(
                    sensorWidth = 4032,
                    sensorHeight = 3024,
                    rotationDegrees = 90,
                    crop = SensorCrop(0f, 0f, 4032f, 3024f),
                ),
            ),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF6B7B6B, widthDp = 380, heightDp = 720)
@Composable
private fun CaptureImportingPreview() {
    // 导入在跑的那一眼：快门与相册入口同时变成进度环，上面那句话说明此刻在做什么。
    // 这一屏是「用户会不会以为卡住了」的全部答案，所以它值得单独一个预览。
    WordLensTheme {
        CaptureScreen(
            bottomInset = PaddingValues(0.dp),
            cameraGranted = true,
            state = CaptureUiState(
                analysing = true,
                importing = true,
                chips = listOf(
                    WordChip("cup", "cup", NormBox(0.15f, 0.35f, 0.35f, 0.55f)),
                ),
            ),
        )
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
            cameraGranted = true,
            state = CaptureUiState(
                shotKind = ShotKind.SCENE,
                shotReason = "The subject is only 1% of the photo.",
                rawLabels = listOf("Food", "Home good", "Plant"),
            ),
        )
    }
}