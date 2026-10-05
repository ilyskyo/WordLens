// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.lookback

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ilyskyo.wordlens.R
import com.ilyskyo.wordlens.core.voice.MicPermission
import com.ilyskyo.wordlens.core.voice.PendingTake
import com.ilyskyo.wordlens.core.voice.TakeAction
import com.ilyskyo.wordlens.core.voice.TakePhase
import com.ilyskyo.wordlens.core.voice.VoiceMemo
import com.ilyskyo.wordlens.ui.components.OutlinedAction
import com.ilyskyo.wordlens.ui.components.PrimaryButton
import com.ilyskyo.wordlens.ui.icons.WordLensIcons
import com.ilyskyo.wordlens.ui.theme.Motion
import com.ilyskyo.wordlens.ui.theme.Scale
import com.ilyskyo.wordlens.ui.theme.Space
import com.ilyskyo.wordlens.ui.theme.pressable
import com.ilyskyo.wordlens.ui.theme.rememberReduceMotion
import java.io.File
import kotlinx.coroutines.delay

/**
 * 详情页语音那几行的动作。
 *
 * 打包成一个对象而不是摊开六个参数：参照 `TimelineSelection` 的做法——这一节本来就要接住
 * 「录 / 停 / 收 / 丢 / 摘」五个动作加一条播放失败的报告，摊开会让 [EntryDetailScreen] 的
 * 签名读不动。
 *
 * 全部指向 ViewModel：录音机、文件、日记都在那边，这一层只负责按下。
 */
data class VoiceMemoActions(
    val onStartTake: (entryId: String) -> Unit = {},
    val onStopTake: () -> Unit = {},
    val onCommitTake: (entryId: String) -> Unit = {},
    val onDiscardTake: () -> Unit = {},
    val onHandOffTake: () -> Unit = {},
    val onDetachAudio: (entryId: String) -> Unit = {},
    val onPlaybackProblem: () -> Unit = {},
)

/**
 * 详情页上「一句当时的声音」那一节。
 *
 * ## 四种面貌，互斥
 *
 * 没录过 → 一个录音键；正在录 → 秒表与「停下」；录完没收下 → 「收下 / 丢弃」；已经收下 → 播放条
 * 加「重录 / 删除」。之所以不做成可以同时存在的几个控件，是因为这四种状态各自只有一件该做的事，
 * 摆在一起就是把「现在到底在录没有」变成用户要自己找的事。
 *
 * ## 麦克风不能跟着界面一起被拆掉
 *
 * 三个交还麦克风的钩子都在这里挂上，而它们各自的时机不同，少一个都会留下「人已经走了而录音机
 * 还在跑」：
 *
 * - **退到后台**（`ON_STOP`）：Android 14 起系统会直接掐掉后台的麦克风权限，那时候继续录只是
 *   在写一段静音；先把已经说出来的那部分留在盘上，比留一段假的完整录音诚实。
 * - **这一节离开组合**：包括按返回离开这一条、也包括转屏。它不能是唯一的钩子——转屏时
 *   ViewModel 活着，把那段声音留在 VM 里等用户回来决定就好。
 * - **VM 自己被清掉**：那是真的没人回来了，[HomeViewModel.onCleared] 处理。
 *
 * 三个钩子都走「正常结束」那一条路（那一段降级成未收下，等用户决定），只有出错才删文件——
 * 理由写在 [VoiceMemo.transition]。
 *
 * ## 权限在这按下去的时候才要
 *
 * 不在启动时要、不在进入详情页时要：RECORD_AUDIO 是运行时权限里最容易被读成「它要偷听」的一个，
 * 而它在这里只服务于用户主动按下的那一次。判别永久拒绝的位置与取景页同一条规矩——
 * `shouldShowRequestPermissionRationale` 只在用户**答过**之后才可信。
 */
@Composable
fun VoiceMemoSection(
    state: EntryDetailState,
    actions: VoiceMemoActions,
    modifier: Modifier = Modifier,
) {
    val entry = state.entry
    val entryId = entry.id
    val take = state.take
    val context = LocalContext.current
    val activity = LocalContext.current as? Activity

    // 用户答完授权框之后 Compose 不会因为权限变了而重组，必须自己制造一次状态变化：
    // 让 tick 参与这一次读取，比在 onResume 里重读可靠（回调不一定走 onResume）。
    var permissionTick by remember { mutableIntStateOf(0) }
    var foreverDenied by remember { mutableStateOf(false) }
    var askPermission by remember { mutableStateOf(false) }
    var confirmDetach by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }

    val permission: MicPermission = remember(permissionTick, foreverDenied) {
        when {
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManagerGranted -> MicPermission.GRANTED

            foreverDenied -> MicPermission.DENIED_FOREVER
            else -> MicPermission.ASK
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            permissionTick++
            // 唯一可靠的判别：还能弹出 rationale 就还能再问；弹不出来而权限仍然没有，就是永久拒绝。
            // 第一次走到这里时 rationale 也是 false，所以这条判断只在用户答过之后才做。
            if (!granted) {
                // 这一次按下到此为止。留着它的话下一条 effect 会卡在 ASK 那一格——权限没变，
                // key 就没变，于是按钮看起来还在，再按一次什么都不发生。
                askPermission = false
                foreverDenied = activity?.shouldShowRequestPermissionRationale(
                    Manifest.permission.RECORD_AUDIO,
                ) != true
            }
        },
    )

    /**
     * 按下「录一段」这一次意图，活到权限有答案为止。
     *
     * 原来这里先 `askPermission = false` 再去 launch：系统框答「允许」之后权限确实变成了 GRANTED，
     * 而这条 effect 的 key 已经不再变化——那一次按下就被吞掉了，用户看到的是「第一下没反应，
     * 得按第二下」。这正是这个功能最不该有的手感：录音键按下去不录音，而麦克风权限明明是刚给出去的。
     *
     * 所以意图要在问权限那一步**留着**：答完 `permissionTick++` → `permission` 变 GRANTED →
     * key 变 → 这条重跑 → 那一下真的开录，然后才清掉。被拒时由 onResult 收尾（见上）。
     */
    LaunchedEffect(askPermission, permission) {
        if (!askPermission) return@LaunchedEffect
        when (permission) {
            MicPermission.GRANTED -> {
                askPermission = false
                actions.onStartTake(entryId)
            }

            MicPermission.ASK -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)

            // 永久拒绝：再 launch 只会拿到同一个静默的 false，把人送到应用详情页才有出路。
            MicPermission.DENIED_FOREVER -> {
                askPermission = false
                openAppSettings(context)
            }
        }
    }

    // owner 必须在组合过程里取：LocalLifecycleOwner 是个 composition local，在 DisposableEffect
    // 的块里读它拿到的是「编译期允许、运行期没有」的东西，而这一节挂的三个钩子全都依赖它。
    val owner = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(actions, entryId) {
        val observer = LifecycleEventObserver { _, event ->
            // 只认 ON_STOP：它是「用户已经看不见这一页」的最早一刻。ON_PAUSE 会被通知栏下拉、
            // 半透明对话框触发，那时候人还在这一页上，把录音停下反而像被打断。
            if (event == Lifecycle.Event.ON_STOP) actions.onHandOffTake()
        }
        owner.addObserver(observer)
        onDispose {
            owner.removeObserver(observer)
            actions.onHandOffTake()
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text(
            text = stringResource(R.string.audio_row_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when {
            take?.phase == TakePhase.RECORDING -> RecordingStrip(
                take = take,
                onStop = actions.onStopTake,
                onDiscard = actions.onDiscardTake,
            )

            take?.phase == TakePhase.STAGED -> StagedStrip(
                take = take,
                onCommit = { actions.onCommitTake(entryId) },
                onDiscard = actions.onDiscardTake,
            )

            entry.audioPath != null -> CommittedStrip(
                file = state.audioFile,
                durationMs = entry.audioDurationMs,
                actions = actions,
                onReRecord = { confirmReplace = true },
                onDelete = { confirmDetach = true },
            )

            else -> EmptyStrip(
                permission = permission,
                onRecord = { askPermission = true },
                onOpenSettings = { openAppSettings(context) },
            )
        }
    }

    if (confirmDetach) {
        AlertDialog(
            onDismissRequest = { confirmDetach = false },
            title = { Text(stringResource(R.string.audio_delete_confirm_title)) },
            text = { Text(stringResource(R.string.audio_delete_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDetach = false
                        actions.onDetachAudio(entryId)
                    },
                ) {
                    Text(
                        text = stringResource(R.string.selection_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDetach = false }) {
                    Text(stringResource(R.string.selection_cancel))
                }
            },
        )
    }

    /**
     * 「重录会先删掉原来那段」的确认。
     *
     * 这一次确认不是礼貌：文件名按条目 id 定死，新的那一段与旧的落在同一个位置上，所以开录那一步
     * `MediaRecorder` 是**截断重写**，旧的必然先没掉。它是整个功能里唯一一处由「录」这个动作本身
     * 造成的不可逆，所以要一次明确的同意；确认之后什么都不再问。
     *
     * 确认之后仍然要先过权限那一关（见上面那条 `LaunchedEffect`）：`HomeViewModel.onStartTake` 在
     * 真的录起来之前会把 `audioPath` 摘掉，所以「用户同意了」不能等于「可以动手删」——
     * 只有麦克风确实到手了，那一步才走。顺序错了的长相是：用户点了同意、权限被拒、
     * 旧的那段没了、新的没录上。
     */
    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text(stringResource(R.string.audio_replace_confirm_title)) },
            text = { Text(stringResource(R.string.audio_replace_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReplace = false
                        askPermission = true
                    },
                ) {
                    Text(stringResource(R.string.audio_re_record))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReplace = false }) {
                    Text(stringResource(R.string.selection_cancel))
                }
            },
        )
    }
}

/** 没录过：一句隐私说明 + 录音键。权限没到手时这颗键换成「去授权」或「去设置里打开」。 */
@Composable
private fun EmptyStrip(
    permission: MicPermission,
    onRecord: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text(
            text = stringResource(R.string.audio_permission_rationale),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (permission == MicPermission.DENIED_FOREVER) {
            Text(
                text = stringResource(R.string.audio_permission_permanently_denied),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        when (VoiceMemo.actionFor(TakePhase.IDLE, permission, hasAudio = false)) {
            TakeAction.OPEN_SETTINGS -> TonalRecord(text = R.string.audio_open_settings, onClick = onOpenSettings)
            else -> TonalRecord(text = R.string.audio_record, onClick = onRecord)
        }
    }
}

/** 正在录：读数 + 停下 / 丢弃。 */
@Composable
private fun RecordingStrip(
    take: PendingTake,
    onStop: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 秒表在界面这一侧走：它每百来毫秒变一次，而那种频率不该绕到 ViewModel 再回来——
    // VM 只知道「什么时候开始的」。
    var elapsedMs by remember(take.startedAtElapsed) { mutableLongStateOf(0L) }
    LaunchedEffect(take.startedAtElapsed) {
        while (true) {
            elapsedMs = VoiceMemo.elapsedMs(android.os.SystemClock.elapsedRealtime(), take.startedAtElapsed)
            delay(TIMER_TICK_MS)
        }
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RecordingDot()
            Text(
                text = stringResource(R.string.audio_recording_label, VoiceMemo.formatDuration(elapsedMs)),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            PrimaryButton(text = stringResource(R.string.audio_stop), onClick = onStop, modifier = Modifier.weight(1f))
            OutlinedAction(text = stringResource(R.string.audio_discard), onClick = onDiscard, modifier = Modifier.weight(1f))
        }
    }
}

/** 录完了但还没被收下：这是这个功能唯一的中动态。 */
@Composable
private fun StagedStrip(
    take: PendingTake,
    onCommit: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 时长还不知道 = 文件还在写尾巴。这时候「收下」是灰的：按下去会写进一个还不知道长度的引用。
    val duration = take.stagedMs
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text(
            text = duration?.let { stringResource(R.string.audio_staged_label, VoiceMemo.formatDuration(it)) }
                ?: stringResource(R.string.audio_take_sealing),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            PrimaryButton(
                text = stringResource(R.string.audio_commit),
                onClick = onCommit,
                enabled = duration != null,
                modifier = Modifier.weight(1f),
            )
            OutlinedAction(text = stringResource(R.string.audio_discard), onClick = onDiscard, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * 已经收下：播放条 + 重录 / 删除。
 *
 * [file] 为 null 是真实存在的一种状态：`diary.json` 单独上云而声音不上（§8.7），换机之后
 * 完全可能读到 `audioPath` 而没有那个文件。这时候说清楚「不在这台设备上」，而不是画一条
 * 按下去没有反应的播放条——那会被读成控件坏了。
 */
@Composable
private fun CommittedStrip(
    file: File?,
    durationMs: Long,
    actions: VoiceMemoActions,
    onReRecord: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        if (file == null) {
            Text(
                text = stringResource(R.string.audio_file_missing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            PlaybackRow(
                file = file,
                fallbackDurationMs = durationMs,
                onProblem = actions.onPlaybackProblem,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            OutlinedAction(
                text = stringResource(R.string.audio_re_record),
                onClick = onReRecord,
                modifier = Modifier.weight(1f),
            )
            OutlinedAction(
                text = stringResource(R.string.audio_delete),
                onClick = onDelete,
                modifier = Modifier.weight(1f),
            )
        }
        // 重录的确认由上层那一节挂着的对话框负责（见 VoiceMemoSection）：这里只负责把「要重录」
        // 这一下说出去。放在这里画一个对话框，它就没有主人——而覆盖旧录音这件事必须有主人同意。
    }
}

/** 那颗还在录的圆点。 */
@Composable
private fun RecordingDot(modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    // 「还在录」这件事由旁边那串一直在走的 mm:ss 说全；呼吸只是它的一种说法，不携带额外信息，
    // 所以系统开了「移除动画」时整条循环不建立——框架把时长压成 0 之后它仍然每帧重画一条
    // 不动的线，那是要避免的，不是要变慢的。
    val alpha: Float = if (reduceMotion) {
        DOT_ALPHA_HIGH
    } else {
        val transition = rememberInfiniteTransition(label = "takeDot")
        val animated by transition.animateFloat(
            initialValue = DOT_ALPHA_LOW,
            targetValue = DOT_ALPHA_HIGH,
            animationSpec = infiniteRepeatable(
                animation = tween(Motion.BREATHING_PERIOD_MS / 2, easing = Motion.enterEase),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "takeDotAlpha",
        )
        animated
    }
    Box(
        modifier = modifier
            .size(12.dp)
            .background(MaterialTheme.colorScheme.error.copy(alpha = alpha), CircleShape),
    )
}

/**
 * 播放条：一个能单手按的圆形播放键 + 一条可以拖也可以点的进度 + 一直看得见的时长。
 *
 * 进度用 [Slider] 而不是自己画一条能点的线：M3 的滑块自带「点哪跳哪」与拖拽，
 * 还把「现在放到哪儿 / 一共多长」报给读屏——这三件事自己实现会得到一个更难看也更难对的东西。
 * 位置与进度**不随动效降级**：那条进度就是信息本身（放在哪儿了），把它摘掉界面就少了一条读数。
 *
 * 时长读数取「播放器准备好的那份」，取不到就用日记里存的：后者让用户在按下播放之前
 * 就知道这段有多长，而「要不要听」是按这个数决定的。
 */
@Composable
private fun PlaybackRow(
    file: File,
    fallbackDurationMs: Long,
    onProblem: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var preparedDurationMs by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }

    val player = remember(file) {
        VoicePlayer(
            file = file,
            onEnded = {
                playing = false
                positionMs = 0L
            },
            onFailed = {
                playing = false
                onProblem()
            },
            onPrepared = { prepared -> preparedDurationMs = prepared },
        )
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    val totalMs = if (preparedDurationMs > 0L) preparedDurationMs else fallbackDurationMs
    val fraction = if (scrubbing) {
        scrubFraction
    } else {
        if (totalMs > 0L) (positionMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f) else 0f
    }

    // MediaPlayer 没有位置回调，只有轮询这一条路。300ms：够让进度读起来是连续的，
    // 又不至于每秒十次重组把这一页的其他部分一起拖着算。
    LaunchedEffect(playing) {
        while (playing) {
            positionMs = player.positionMs()
            delay(POSITION_TICK_MS)
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(PLAY_BUTTON_SIZE)
                    .pressable(
                        onClick = {
                            playing = player.toggle()
                            if (playing) positionMs = player.positionMs()
                        },
                        role = Role.Button,
                        pressedScale = Scale.Small,
                    ),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = if (playing) WordLensIcons.Pause else WordLensIcons.Play,
                        // 时长已经在右边那串读数里，这里再念一遍是重复；读屏用户要的是「按下会怎样」。
                        contentDescription = if (playing) {
                            stringResource(R.string.audio_pause_desc)
                        } else {
                            stringResource(R.string.audio_play_desc, VoiceMemo.formatDuration(totalMs))
                        },
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(PLAY_ICON_SIZE),
                    )
                }
            }

            val seekDesc = stringResource(R.string.audio_seek_desc)
            Slider(
                value = fraction,
                onValueChange = { scrubbing = true; scrubFraction = it },
                onValueChangeFinished = {
                    val target = (scrubFraction * totalMs.toFloat()).toLong()
                    player.seekTo(target)
                    // 记下**刚请求的**那个位置，不回读播放器：MediaPlayer 的 seek 是异步的，
                    // 松手那一刻读回来的经常还是旧值，进度条会在手指离开时弹回原位——
                    // 而那正是用户最想确认「我拖到了这里」的一下。
                    positionMs = target
                    scrubbing = false
                },
                colors = SliderDefaults.colors(
                    // 滑块要够大：这条进度是这一节唯一要拖着用的东西，默认尺寸是给音量条准备的。
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = seekDesc },
            )

            Text(
                text = VoiceMemo.formatDuration(if (positionMs > 0L) positionMs else totalMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // 等宽读数：mm:ss 的位数不变，但数字宽度变了会让整行在播放时抖一下。
                modifier = Modifier.heightIn(min = PLAY_BUTTON_SIZE),
            )
        }
    }
}

/** 「录一段」与「去设置」共用这一颗键：同一形状，只是文案与去处不同。 */
@Composable
private fun TonalRecord(text: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PrimaryButton(text = stringResource(text), onClick = onClick, modifier = modifier)
}

/**
 * 权限被永久拒绝之后唯一的出路：应用详情页。
 *
 * 与取景页那一条同一套做法——继续 `launch` 只会拿到同一个静默的 false，用户则在原地
 * 第三次点同一个按钮。
 */
private fun openAppSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ),
        )
    }
}

private val PackageManagerGranted = android.content.pm.PackageManager.PERMISSION_GRANTED

/** 秒表节拍。100ms 是「数字在走」和「重组不吵」的交点：再快读不出差别，再慢会看着卡住。 */
private const val TIMER_TICK_MS = 200L

/** 进度轮询节拍，见 [PlaybackRow]。 */
private const val POSITION_TICK_MS = 300L

/** 播放键的直径。48dp 是能单指点到的最小目标，而这一个是这一节唯一必须够得着的控件。 */
private val PLAY_BUTTON_SIZE = 48.dp
private val PLAY_ICON_SIZE = 22.dp

/** 呼吸圆点的两端。顶点是它本来就会到达的读数，降级不引入新的对比度。 */
private const val DOT_ALPHA_HIGH = 1f
private const val DOT_ALPHA_LOW = 0.35f
