// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.remember

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ilyskyo.wordlens.R
import com.ilyskyo.wordlens.data.model.ReviewItem
import com.ilyskyo.wordlens.data.model.ReviewSource
import com.ilyskyo.wordlens.data.model.StudyMaterial
import com.ilyskyo.wordlens.srs.Fsrs
import com.ilyskyo.wordlens.ui.components.EmptyState
import com.ilyskyo.wordlens.ui.components.PillOption
import com.ilyskyo.wordlens.ui.components.PillSwitch
import com.ilyskyo.wordlens.ui.components.SpeakButton
import com.ilyskyo.wordlens.ui.theme.Haptic
import com.ilyskyo.wordlens.ui.theme.IpaTextStyle
import com.ilyskyo.wordlens.ui.theme.Motion
import com.ilyskyo.wordlens.ui.theme.RatingTone
import com.ilyskyo.wordlens.ui.theme.Scale
import com.ilyskyo.wordlens.ui.theme.Space
import com.ilyskyo.wordlens.ui.theme.WordLensTheme
import com.ilyskyo.wordlens.ui.theme.pressable
import com.ilyskyo.wordlens.ui.theme.rememberHaptic
import com.ilyskyo.wordlens.ui.theme.rememberReduceMotion
import com.ilyskyo.wordlens.ui.theme.softShadow
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 「记住」页状态。 */
data class RememberUiState(
    val material: StudyMaterial = StudyMaterial.WORDS_AND_EVENTS,
    val done: Int = 0,
    val total: Int = 0,
    val current: RememberCard? = null,
    /**
     * 因为「两面同字」而根本没进队列的卡数。
     *
     * 必须显示出来：把卡悄悄滤掉的另一种写法是队列空了而用户不知道为什么，那会被读成
     * 「我今天没有要复习的」，而实际上是有几张卡他这辈子都复习不了。
     */
    val skippedSameFace: Int = 0,
    /** 这一张这轮翻过没有：评级的闸门，也是答题时长的起点。 */
    val revealed: Boolean = false,
    /** 此刻看着哪一面。可以翻回去，所以它跟 [revealed] 不是一回事。 */
    val showingAnswer: Boolean = false,
    val finished: Boolean = false,
    val streakDays: Int = 0,
    val speakEnabled: Boolean = true,
    /** 四个评级各自会产生的间隔天数，直接印在按钮上。 */
    val intervals: Map<Fsrs.Rating, Int> = emptyMap(),
    /** 用户手动「标记已掌握」而移出队列的卡。它们不再被调度，但可以取消。 */
    val archived: List<ReviewItem> = emptyList(),
    /** 当前卡之后还有几张，用来画卡堆层数。不含内容，理由见 [RememberScreen] 的文件注释。 */
    val upcoming: Int = 0,
    /** 本轮已用时（秒）。0 表示还没开始。 */
    val sessionSeconds: Int = 0,
    /** 本轮「第一次就想起来」的比例。 */
    val firstTryAccuracy: Float = 1f,
)

/**
 * 一张待复习的卡片。
 *
 * 词汇与事件是同一个类型：共用 FSRS 队列、共用翻面与滑动，差别只在卡上摆什么。
 * 拆成两个 Composable 会让翻面动画、按钮布局、无障碍语义各写一遍，然后各自漂移。
 */
data class RememberCard(
    val item: ReviewItem,
    /** 正面主标题：单词或事件的一句话。 */
    val prompt: String,
    val ipa: String? = null,
    val gloss: String? = null,
    val example: String? = null,
    val photo: android.graphics.Bitmap? = null,
    val source: ReviewSource = ReviewSource.USER,
    /** 事件所属日期，事件卡上显示。 */
    val dayLabel: String? = null,
)

/** 滑动是否已经把某一侧「按住」了。只有三档，所以跨界时只会重组一次。 */
private enum class Arm { None, Again, Good }

/**
 * 「记住」页。
 *
 * ## 一个页面里同时有翻面、滑动、四个按钮、滚数字，秩序必须写死
 *
 * 1. **正面不受理滑动**（拖拽手势根本不注册）。还没想起来就被判分，比慢一步更伤。
 * 2. 滑动跟手 1:1，松手才交给弹簧；越过阈值那一刻只震一次。
 * 3. 滑动与按钮是同一个动作的两种表达（左=忘了，右=好），所以滑过界时对应按钮会亮起来，
 *    让人知道这两条路通到同一个地方。
 * 4. 动画期间不重组。翻面与滑动的每一帧都只写 `graphicsLayer`，
 *    进度状态只在**跨越阈值**那一刻才被提升到组合层——每帧重组一张满是文字的卡片，
 *    是这一页最容易做也最容易掉的坑。
 *
 * ## 为什么卡堆不露出下一张的内容
 *
 * 后面几张只画**空白纸背**。提前看到下一个词会毁掉自由回忆，而自由回忆正是复习的全部价值。
 *
 * ## 为什么 EASY 不是「归档」
 *
 * FSRS 里 EASY 是**间隔会变得很长**，不是「学完了」。做成「标记并归档」，这张卡就永远不再被
 * 调度，用户损失的是三个月后那次复习——那恰恰是他已经投入成本想保住的东西。
 * 「别再给我看它」是长按菜单里那个独立的显式动作，且始终可撤销。
 *
 * ## 「减少动画」的界线——将来动这一页之前先读这段
 *
 * 系统开了「移除动画」之后，这一页只降**装饰性**的动效：卡面提示的呼吸
 * （[BreathingHint]）与结算页的依次入场（[Staggered]）。以下三样看着也像是可以顺手一起降，
 * 但降了就不是减动效而是减功能，**不要**把它们加进降级名单：
 *
 * - 翻卡（[FlipCard]）：正面与背面是同一张卡的两种**内容状态**，不翻等于把释义直接摊开，
 *   复习的第一环（自由回忆）就没了。
 * - 跟手滑动评级（`draggable` + [Motion.settle]）：那是用户自己正在做的动作，位移就是
 *   操作本身的反馈；改成瞬移等于让手势失效。
 * - 进度条增长（[RememberProgress]）与滚动数字（[RollingNumber]）：它们是读数变化的
 *   **唯一**提示。去掉之后「我刚才那一下评级到底有没有被记上」在这页就没有答案了。
 *
 * 判断标准只有一条：**把这个动效拿掉，屏幕上的信息还完整吗？** 完整才降，不完整就不许动。
 * 这条界线之所以写在这里，是因为「减动效」最容易做错的方向就是顺着列表往下减，
 * 一路减到把功能也减掉，而那时界面看起来更干净、更没人会去回滚。
 *
 * ## AI 来源标记
 *
 * 复习是把内容反复巩固的过程。模型编错的一条事件会被 FSRS 忠实地刻进长期记忆，而用户永远
 * 不会知道。所以 AI 生成的卡片在正面就带来源提示。
 */
@Composable
fun RememberScreen(
    topInset: PaddingValues,
    bottomInset: PaddingValues,
    state: RememberUiState = RememberUiState(),
    onMaterialChange: (StudyMaterial) -> Unit = {},
    onCardTap: () -> Unit = {},
    onGrade: (Fsrs.Rating) -> Unit = {},
    onSpeak: () -> Unit = {},
    onMarkMastered: () -> Unit = {},
    onUnmark: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val materials = StudyMaterial.entries
    val selectedIndex = materials.indexOf(state.material).coerceAtLeast(0)
    var armed by remember(state.current?.item?.id) { mutableStateOf(Arm.None) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Space.screen)
            // 悬浮页签盖不住第一行：素材筛选与进度条都在避让范围之内。
            .padding(top = topInset.calculateTopPadding()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PillSwitch(
            options = materials.map { PillOption(key = it.name, label = stringResource(it.titleRes)) },
            selectedIndex = selectedIndex,
            onSelect = { index -> onMaterialChange(materials[index]) },
            modifier = Modifier.fillMaxWidth(),
            // 传给胶囊的是**下限**而不是死值：系统字号放大时 PillSwitch 会自己长高到容得下
            // labelLarge 的那一行，这里写 44 只规定「至少 44」。
            height = PILL_HEIGHT,
        )
        RememberProgress(done = state.done, total = state.total)

        if (state.skippedSameFace > 0) {
            Text(
                text = pluralStringResource(
                    R.plurals.review_skipped_same_face,
                    state.skippedSameFace,
                    state.skippedSameFace,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 归档是唯一会永久改变队列的动作，所以它必须始终可撤销——入口常驻在进度条下面，
        // 而不是只在空状态里出现（否则刚归档完、队列还有下一张时就没有反悔的地方）。
        if (state.archived.isNotEmpty()) {
            var archivedOpen by remember { mutableStateOf(false) }
            TextButton(onClick = { archivedOpen = true }) {
                Text(
                    text = stringResource(R.string.review_archived_count, state.archived.size),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (archivedOpen) {
                ArchivedDialog(
                    items = state.archived,
                    onUnmark = onUnmark,
                    onDismiss = { archivedOpen = false },
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            when {
                state.finished -> RememberFinished(
                    streakDays = state.streakDays,
                    sessionSeconds = state.sessionSeconds,
                    firstTryAccuracy = state.firstTryAccuracy,
                    modifier = Modifier.fillMaxWidth(),
                )

                state.current == null -> EmptyState(
                    emoji = "\uD83C\uDF31",
                    title = stringResource(R.string.review_empty_title),
                    body = stringResource(R.string.review_empty_body),
                )

                else -> ReviewCardArea(
                    state = state,
                    armed = armed,
                    onArmChange = { armed = it },
                    onCardTap = onCardTap,
                    onSpeak = onSpeak,
                    onGrade = onGrade,
                    onMarkMastered = onMarkMastered,
                )
            }
        }

        if (state.current != null && !state.finished) {
            GradeRow(
                revealed = state.revealed,
                intervals = state.intervals,
                armed = armed,
                onGrade = onGrade,
                modifier = Modifier.padding(bottom = bottomInset.calculateBottomPadding() + Space.md),
            )
        } else {
            Box(Modifier.height(bottomInset.calculateBottomPadding() + Space.md))
        }
    }
}

/**
 * 卡片区：卡堆 + 跟手滑动 + 3D 翻面。
 *
 * 滑动量存在 [Animatable] 而不是普通 Float：松手之后要能从**手指的速度**接着走完，
 * 而只有 Animatable 能把 initialVelocity 交给弹簧。位移全程只写 `graphicsLayer`。
 */
@Composable
private fun ReviewCardArea(
    state: RememberUiState,
    armed: Arm,
    onArmChange: (Arm) -> Unit,
    onCardTap: () -> Unit,
    onSpeak: () -> Unit,
    onGrade: (Fsrs.Rating) -> Unit,
    onMarkMastered: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val card = state.current ?: return
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    var widthPx by remember { mutableStateOf(0f) }
    var menuOpen by remember(card.item.id) { mutableStateOf(false) }
    // 这张卡已经飞出去了。松手到下一张卡到位之间隔着一次磁盘写（fsync），
    // 而 `state.current` 是在那次写之后才换的——原来的写法是立刻把 offsetX 归零，
    // 于是那半秒里屏幕正中显示的恰恰是用户刚刚处置掉的那一张。
    // 归零本身要留：progress 随之回 0，两侧光晕也一起灭掉；要压掉的只有这张卡的画面。
    var dismissed by remember(card.item.id) { mutableStateOf(false) }

    // -1..1：负数偏向「忘了」，正数偏向「好」。只在需要连续读数的地方用，
    // 跨界这件事本身用 Arm 枚举上报，避免每帧重组。
    val progress: State<Float> = remember {
        derivedStateOf {
            if (widthPx <= 0f) 0f else (offsetX.value / (widthPx * SWIPE_THRESHOLD)).coerceIn(-1f, 1f)
        }
    }

    // `rememberDraggableState` 只在**第一次组合**收下那个 lambda，之后不再换：
    // 所以手势里绝不能直接读 `armed` 这个参数——那样比较的永远是首帧的 Arm.None，
    // 于是「拖过阈值又拖回来」这条路径因为 next == 旧的 armed 而被跳过，
    // 按钮会一直亮着，而卡已经回到中间了。rememberUpdatedState 就是给这种回调用的。
    val latestArmed = rememberUpdatedState(armed)

    val dragState = rememberDraggableState { delta ->
        scope.launch {
            offsetX.snapTo(offsetX.value + delta)
            val next = when {
                progress.value <= -1f -> Arm.Again
                progress.value >= 1f -> Arm.Good
                else -> Arm.None
            }
            if (next != latestArmed.value) onArmChange(next)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { widthPx = it.width.toFloat() },
            contentAlignment = Alignment.Center,
        ) {
            // 卡堆：后面只画空白纸背。
            BackSheet(layers = state.upcoming.coerceAtMost(MAX_BACK_CARDS))

            SwipeGlow(side = SwipeSide.Again, progress = progress)
            SwipeGlow(side = SwipeSide.Good, progress = progress)

            FlipCard(
                card = card,
                // 转的是「此刻看着哪一面」，不是「翻过没有」——后者一旦为真就再也不变，
                // 接在它上面的话这张卡只能往背面翻一次（真机上就是这么点不动的）。
                showingAnswer = state.showingAnswer,
                // 正面那行提示要按「这一张翻过没有」换措辞，而不是按此刻看着哪一面。
                revealed = state.revealed,
                onClick = onCardTap,
                onLongClick = { menuOpen = true },
                offsetX = offsetX,
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(FLIP_CARD_ASPECT, matchHeightConstraintsFirst = true)
                    .then(
                        // 已经飞走的那一张，在下一张到位之前不给它任何回到中间的机会。
                        if (dismissed) Modifier.graphicsLayer { alpha = 0f } else Modifier
                    )
                    .then(
                        // 正面连手势都不注册：比「注册了但忽略」更诚实，也不会顺手一滑就吞掉事件。
                        if (state.revealed) {
                            Modifier.draggable(
                                state = dragState,
                                orientation = Orientation.Horizontal,
                                onDragStopped = { velocity ->
                                    scope.launch {
                                        val beyond = abs(offsetX.value) >= widthPx * SWIPE_THRESHOLD
                                        val rating = if (offsetX.value < 0f) Fsrs.Rating.AGAIN else Fsrs.Rating.GOOD
                                        if (!beyond) {
                                            // 没过阈值：从当前速度接管送回原位。snapTo(0) 会得到
                                            // 一次「被弹回去」，而不是「自己滑回去」。
                                            onArmChange(Arm.None)
                                            offsetX.animateTo(0f, initialVelocity = velocity, animationSpec = Motion.settle)
                                        } else {
                                            val flyTo = if (rating == Fsrs.Rating.AGAIN) -widthPx * FLY_OUT else widthPx * FLY_OUT
                                            offsetX.animateTo(flyTo, initialVelocity = velocity, animationSpec = Motion.smooth)
                                            // 飞出去的那一段动画已经播完了，从这里起这张卡不该再出现。
                                            dismissed = true
                                            onArmChange(Arm.None)
                                            offsetX.snapTo(0f)
                                            onGrade(rating)
                                        }
                                    }
                                },
                            )
                        } else {
                            Modifier
                        },
                    ),
            )

            // 归档只放这里，不进评级按钮行：「别再给我看它」和「我记得很牢」必须在界面上分开，
            // 否则用户会把还没记住的词当成学完了。
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                // 默认的 `MenuDefaults.containerColor` 是 `surfaceContainerHigh`：在这套暖色板上
                // 被 tonal elevation 推成一块发紫的灰，再配上 M3 菜单那 4dp 圆角，装机看到的是一
                // 「渲染坏了的砖」，而不是「一个菜单」。改成页面自己的纸面 + 24dp 超椭圆，
                // 与 Notice / 对话框同一套形状语言。
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                shape = MaterialTheme.shapes.large,
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.review_mark_mastered)) },
                    onClick = {
                        menuOpen = false
                        onMarkMastered()
                    },
                )
            }
        }

        if (card.item is ReviewItem.Word) {
            // 只有词汇才有得读；事件是一句话，发音没有意义。
            SpeakButton(
                onClick = onSpeak,
                contentDescription = stringResource(R.string.capture_speak),
                enabled = state.speakEnabled && card.ipa != null,
            )
        }
    }
}

/**
 * 翻面卡片。
 *
 * 旋转用 `tween(FLIP_MS, enterEase)` 而不是弹簧：弹簧在 180° 附近过冲，视觉上就是
 * 「背面的字镜像了一下再回来」，读起来像渲染出错。位移一律走弹簧这条规则的**唯一例外**是旋转，
 * 理由写在 Motion 里。
 *
 * 中途反向（翻到一半再点一次）会从当前角度继续——这是 `animateFloatAsState` 换 target
 * 的固有行为，不需要额外实现。
 *
 * 角度、纸张弧度、两面可见度全部在 `graphicsLayer` 块里读 State：翻面期间**零重组**。
 */
@Composable
private fun FlipCard(
    card: RememberCard,
    showingAnswer: Boolean,
    revealed: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    offsetX: Animatable<Float, AnimationVector1D>,
    progress: State<Float>,
    modifier: Modifier = Modifier,
) {
    val haptic = rememberHaptic()
    val rotationState = animateFloatAsState(
        targetValue = if (showingAnswer) 180f else 0f,
        animationSpec = tween(durationMillis = FLIP_MS, easing = Motion.enterEase),
        label = "cardFlip",
    )
    val frontDesc = stringResource(R.string.review_card_front_desc, card.prompt)
    val backDesc = stringResource(R.string.review_card_back_desc, card.gloss ?: card.prompt)

    // 翻过 90° 那一刻震一下 Pop。观察真实角度而不是「延时到半程触发」：
    // 半程反向时不该再震，被打断的那一次也不该补一记。
    LaunchedEffect(card.item.id) {
        var wasBack = false
        snapshotFlow { rotationState.value > 90f }.collect { back ->
            if (back && !wasBack) haptic(Haptic.Pop)
            wasBack = back
        }
    }

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .pressable(
                onClick = onClick,
                onLongClick = onLongClick,
                pressedScale = Scale.Large,
                // 卡片自己不震 Tick：它的按下反馈由翻面与滑动承担。
                // 轻点一下也震会稀释掉翻面那一声 Pop 的意义。
                haptic = null,
            )
            .graphicsLayer {
                val rotation = rotationState.value
                rotationY = rotation
                // 纸张弧度：转到 90° 最鼓，两端回到 1。
                val paper = 1f + PAPER_LIFT * sin(Math.toRadians(rotation.toDouble()).toFloat())
                scaleX = paper
                scaleY = paper
                translationX = offsetX.value
                // 每 24dp 位移转 1°：340dp 宽的卡滑到边缘约 14°，
                // 读起来像纸被甩起来，而不是木板在转。
                rotationZ = offsetX.value / 24f
                // 飞出时同时淡掉：淡出比「突然消失」更接近物体离开视野。
                alpha = 1f - abs(progress.value) * FLY_FADE
                // 相机距离越大透视越弱。14×density 是「看得出是张纸在转」与
                // 「卡片变形到认不出」之间唯一可用的那一段。
                cameraDistance = 14f * density
            }
            .semantics { contentDescription = if (showingAnswer) backDesc else frontDesc },
    ) {
        // 两面同时存在于树里，用 alpha 承接 90°→140° 的渐显。
        // 用 AnimatedContent 换内容会得到它自己的进/出时序，与旋转角不同步，
        // 背面文字会在卡片还侧着的时候就先出现。
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Space.cardPadding)
                    .graphicsLayer { alpha = if (rotationState.value <= 90f) 1f else 0f },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CardFront(card = card, progress = progress, revealed = revealed)
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Space.cardPadding)
                    .graphicsLayer {
                        rotationY = 180f
                        alpha = backAlpha(rotationState.value)
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CardBack(card = card)
            }
        }
    }
}

/**
 * 背面可见度：90° 之前完全不可见（那时它正对着屏幕背面），90°→140° 线性补上。
 *
 * 不做渐显会得到「背面文字闪现」；铺满 90°→180° 则会得到「背面是半透明的」，
 * 前后两层字叠在一起。终点取 140°：字在卡片几乎转正之前就已基本可读，
 * 最后一程只负责减速而不是负责显现。
 */
private fun backAlpha(rotation: Float): Float =
    ((rotation - 90f) / (BACK_FULL_ANGLE - 90f)).coerceIn(0f, 1f)

/** 后面的空白纸背。最多两张：三张以上读起来像一叠纸，而不是「还有两张」。 */
@Composable
private fun BackSheet(layers: Int, modifier: Modifier = Modifier) {
    if (layers <= 0) return
    val shape = MaterialTheme.shapes.extraLarge
    val accents = WordLensTheme.accents
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(FLIP_CARD_ASPECT, matchHeightConstraintsFirst = true),
        contentAlignment = Alignment.Center,
    ) {
        // 从最远的一张画起，近的盖住远的。
        for (depth in layers downTo 1) {
            val d = depth.toFloat()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(FLIP_CARD_ASPECT, matchHeightConstraintsFirst = true)
                    .graphicsLayer {
                        scaleX = 1f - d * BACK_SCALE_STEP
                        scaleY = 1f - d * BACK_SCALE_STEP
                        translationY = d * BACK_DROP_DP * density
                        alpha = BACK_ALPHA_START - (d - 1f) * BACK_ALPHA_STEP
                    }
                    .softShadow(shape, tint = accents.shadowTint, intensity = 0.6f)
                    .background(MaterialTheme.colorScheme.surface, shape),
            )
        }
    }
}

/**
 * 一侧的判决提示：一层从被拖那侧漫进来的浅底 + 一枚斜着盖上的判决章。
 *
 * 透明度与章的角度都读 `State`，所以滑动期间不重组。
 *
 * ## 为什么不铺整张卡
 *
 * 上一版是「整张卡染成 14% 的那一档色，左边写一个『忘了』」。装机划了一下，得到的是一整块
 * 粉底加一个孤零零的词——读起来像**卡片自己变了**（换了一张、坏了、被选中了），
 * 而不是「往这一侧松手会判成什么」。大面积平涂把注意力从卡片内容上抢走了，
 * 而那才是这一页真正要看的字。
 *
 * 现在只压一层很轻的底（章本身负责表意），并且把判决词做成一枚**斜盖的章**：
 * 旋转 + 随滑动放大，是贴纸本自己的语法——这一侧要说的不是「背景变了」，
 * 而是「这一张会被判成什么」。章用淡底加深墨字，不用饱和色配白字（见 `Color.kt` 那条硬规则：
 * 饱和色上放白字最多 3.6:1，读不动）。
 */
@Composable
private fun SwipeGlow(side: SwipeSide, progress: State<Float>, modifier: Modifier = Modifier) {
    val ratings = WordLensTheme.accents.ratings
    val tone = when (side) {
        SwipeSide.Again -> ratings.again
        SwipeSide.Good -> ratings.good
    }
    val label = stringResource(if (side == SwipeSide.Again) R.string.review_again else R.string.review_good)
    val shape = MaterialTheme.shapes.extraLarge
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(FLIP_CARD_ASPECT, matchHeightConstraintsFirst = true)
            .graphicsLayer {
                val raw = when (side) {
                    SwipeSide.Again -> -progress.value
                    SwipeSide.Good -> progress.value
                }
                alpha = raw.coerceIn(0f, 1f) * GLOW_MAX_ALPHA
            }
            .background(tone.hue.copy(alpha = 0.08f), shape),
        contentAlignment = if (side == SwipeSide.Again) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        val stampShape = CircleShape
        Box(
            modifier = Modifier
                .padding(horizontal = Space.lg)
                .graphicsLayer {
                    val raw = when (side) {
                        SwipeSide.Again -> -progress.value
                        SwipeSide.Good -> progress.value
                    }
                    // 章是「盖下去」的：越接近阈值越大、越正。起点给到 0.72 而不是 0，
                    // 因为从一颗点长出来会读成弹出提示，而从一枚斜章转正读成判决成形。
                    val p = raw.coerceIn(0f, 1f)
                    scaleX = 0.72f + 0.28f * p
                    scaleY = 0.72f + 0.28f * p
                    rotationZ = (if (side == SwipeSide.Again) -STAMP_TILT else STAMP_TILT) * (1f - p)
                }
                .background(tone.container, stampShape)
                .padding(horizontal = Space.md, vertical = Space.sm),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = tone.ink,
            )
        }
    }
}

private enum class SwipeSide { Again, Good }

/** 判决章的初始倾角（度）。滑到阈值时归正，所以它是「盖歪了 → 盖实了」这一段。 */
private const val STAMP_TILT = 12f

@Composable
private fun RememberProgress(done: Int, total: Int, modifier: Modifier = Modifier) {
    val fraction = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    // 自己画进度条：4dp + 全圆端点读起来是「一条线在长」，
    // 而 LinearProgressIndicator 的 8dp 槽加两端指示器在这里像一根音量条。
    val animated by animateFloatAsState(targetValue = fraction, animationSpec = Motion.smooth, label = "progress")
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PROGRESS_HEIGHT)
                .clip(CircleShape)
                .background(WordLensTheme.accents.confidenceTrack),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animated)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.review_progress_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RollingNumber(value = done)
            Text(
                text = "/$total",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            )
        }
    }
}

/**
 * 数字变化时上下滚出去/进来。
 *
 * 直接换文本得到的是「数字被替换」，滚动得到的才是「数字在推进」。
 * 这一页上它是唯一的进度反馈，值得这 200ms。
 */
@Composable
private fun RollingNumber(value: Int, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = value,
        transitionSpec = {
            val goingUp = targetState > initialState
            val direction = if (goingUp) 1 else -1
            (fadeIn(Motion.enterFade()) + slideInVertically(Motion.smoothOffset()) { it * direction })
                .togetherWith(fadeOut(Motion.exitFade()) + slideOutVertically(Motion.smoothOffset()) { -it * direction })
        },
        label = "rollingNumber",
        modifier = modifier,
    ) { shown ->
        Text(
            text = shown.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * 四档评级按钮。
 *
 * 顺序固定「忘了 · 困难 · 好 · 简单」，与颜色无关——这是 SRS 的通用惯例，换色系只换颜色。
 * 每档上印着 FSRS 实时算出的间隔天数：「这张卡下次什么时候回来」是调度器唯一能替用户
 * 决定的事，而用户在按下之前就该看到它。
 */
@Composable
private fun GradeRow(
    revealed: Boolean,
    intervals: Map<Fsrs.Rating, Int>,
    armed: Arm,
    onGrade: (Fsrs.Rating) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Fsrs.Rating.entries.forEach { rating ->
            RatingButton(
                rating = rating,
                days = intervals[rating],
                enabled = revealed,
                armed = (rating == Fsrs.Rating.AGAIN && armed == Arm.Again) ||
                    (rating == Fsrs.Rating.GOOD && armed == Arm.Good),
                onClick = { onGrade(rating) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RatingButton(
    rating: Fsrs.Rating,
    days: Int?,
    enabled: Boolean,
    armed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tone = rating.tone()
    val container = if (enabled) tone.container else MaterialTheme.colorScheme.surfaceVariant
    val content = if (enabled) tone.ink else MaterialTheme.colorScheme.onSurfaceVariant
    val labelRes = when (rating) {
        Fsrs.Rating.AGAIN -> R.string.review_again
        Fsrs.Rating.HARD -> R.string.review_hard
        Fsrs.Rating.GOOD -> R.string.review_good
        Fsrs.Rating.EASY -> R.string.review_easy
    }
    // 滑动越界时放大 1.15：这一步比颜色更早地发生在指尖下，读起来是「它接住了我的滑动」。
    val scale by animateFloatAsState(
        targetValue = if (armed) ARM_SCALE else 1f,
        animationSpec = Motion.bouncy,
        label = "ratingArmedScale",
    )
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = content,
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .softShadow(MaterialTheme.shapes.medium, tint = tone.hue, intensity = if (armed) 1f else 0.55f)
            .pressable(onClick = onClick, enabled = enabled, pressedScale = Scale.Small)
            .padding(vertical = BUTTON_V_PADDING),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
            IntervalText(days = days, color = content)
        }
    }
}

/** 天数变化时淡入淡出：换卡后四档间隔通常一起变，硬跳会读成「界面刷新了」。 */
@Composable
private fun IntervalText(days: Int?, color: Color) {
    AnimatedContent(
        targetState = days,
        transitionSpec = { fadeIn(Motion.enterFade()) togetherWith fadeOut(Motion.exitFade()) },
        label = "intervalText",
    ) { value ->
        Text(
            // null 时也要占一行高度，否则按钮会在换卡瞬间抖一下。
            text = value?.let { intervalLabel(it) } ?: " ",
            style = MaterialTheme.typography.labelMedium,
            color = color.copy(alpha = 0.82f),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** 把天数压成一行短标签，并按语言取正确的量词与复数。 */
@Composable
internal fun intervalLabel(days: Int): String {
    val quantity = intervalQuantity(days)
    val n = quantity.value
    return when (quantity.unit) {
        IntervalUnit.Now -> stringResource(R.string.review_interval_now)
        IntervalUnit.Days -> pluralStringResource(R.plurals.review_interval_days, n, n)
        IntervalUnit.Weeks -> pluralStringResource(R.plurals.review_interval_weeks, n, n)
        IntervalUnit.Months -> pluralStringResource(R.plurals.review_interval_months, n, n)
        IntervalUnit.Year -> stringResource(R.string.review_interval_year)
        IntervalUnit.YearsPlus -> pluralStringResource(R.plurals.review_interval_years, n, n)
    }
}

/**
 * 已归档清单：长按「标记已掌握」之后唯一能把卡捞回来的地方。
 *
 * 只做两件事——列出来、取消标记。不在这里做复习或编辑，那是另一个页面该负的责任。
 */
@Composable
private fun ArchivedDialog(
    items: List<ReviewItem>,
    onUnmark: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.review_archived_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = ARCHIVED_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                items.forEach { item ->
                    val label = when (item) {
                        is ReviewItem.Word -> item.card.headword
                        is ReviewItem.Event -> item.card.text
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onUnmark(item.id) }) {
                            Text(
                                text = stringResource(R.string.review_unmaster),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.review_archived_close))
            }
        },
    )
}

@Composable
private fun CardFront(card: RememberCard, progress: State<Float>, revealed: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.sm, Alignment.CenterVertically),
    ) {
        // 事件卡带照片，因为「那天」本身就是记忆线索；纯文字卡不带，免得空一大块。
        if (card.photo != null) {
            Image(
                bitmap = card.photo.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(PHOTO_BOX)
                    .clip(MaterialTheme.shapes.medium),
            )
        }
        card.dayLabel?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = card.prompt,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        card.ipa?.let {
            Text(
                text = it,
                style = IpaTextStyle.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SourceBadge(source = card.source, modifier = Modifier.padding(top = Space.xs))
        BreathingHint(progress = progress, revealed = revealed)
    }
}

/**
 * 「轻点看释义 · 长按可归档」的呼吸。
 *
 * 周期 2.4s：慢到不抢注意力，又快到「还在想」的那几秒里能被看见一次。
 * 滑动越界时把它压到 0——那一刻该说的是松手，不是轻点。
 *
 * 降级前后：
 *
 * - 默认（开关关）：不透明度在 0.40↔0.80 之间以 2.4s 一轮往返，逐帧变化。
 * - 减少动画：不透明度钉死在 0.80，`rememberInfiniteTransition` 整条不建立——
 *   框架把时长压成 0 也还是会每帧重画一条不动的线，所以这里要的是不创建，不是变慢。
 *
 * 呼吸本身不带任何信息：会不会亮、什么时候亮，都不改变「轻点看释义」这句话的内容，
 * 所以它属于可以降的那一类。
 */
@Composable
private fun BreathingHint(progress: State<Float>, revealed: Boolean, modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    // 两条分支都返回 State<Float>，alpha 只在下面的 graphicsLayer 里被读：
    // 降级与否都不许引入逐帧重组，那是这一页写死的规矩（见文件注释第 4 条）。
    val breath: State<Float> = if (reduceMotion) {
        // 固定值取呼吸的**顶点**而不是往返中点：0.80 是这行字本来就会到达的读数，
        // 降级不引入新的对比度；取中间值会把「减动效」读成「把提示调暗」。
        remember { mutableStateOf(HINT_ALPHA_HIGH) }
    } else {
        val transition = rememberInfiniteTransition(label = "hintBreath")
        transition.animateFloat(
            initialValue = HINT_ALPHA_LOW,
            targetValue = HINT_ALPHA_HIGH,
            animationSpec = infiniteRepeatable(
                animation = tween(Motion.BREATHING_PERIOD_MS / 2, easing = Motion.enterEase),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "hintAlpha",
        )
    }
    Text(
        // 这张卡已经翻过一次，「轻点看释义」就在教一件他刚刚做过的事；换成说清「会在两面之间
        // 来回」。留着旧句不只是啰嗦——它会让人在已经看到答案之后，以为再点一下是「评级」。
        text = stringResource(if (revealed) R.string.review_flip_hint_returned else R.string.review_flip_hint),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.graphicsLayer {
            // 滑动越界时压掉这一条两种模式都保留：它说的是「现在该松手而不是轻点」，是信息。
            alpha = breath.value * (1f - abs(progress.value).coerceIn(0f, 1f))
        },
    )
}

@Composable
private fun CardBack(card: RememberCard, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.sm, Alignment.CenterVertically),
    ) {
        card.gloss?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
        card.example?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        SourceBadge(source = card.source)
    }
}

/** AI 生成的卡片带一个明确标记。用户有权知道这段文字是谁写的。 */
@Composable
private fun SourceBadge(source: ReviewSource, modifier: Modifier = Modifier) {
    if (source != ReviewSource.AI) return
    Text(
        text = stringResource(R.string.review_verify_source),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.tertiary,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f))
            .padding(horizontal = Space.sm, vertical = Space.xs),
    )
}

/** 一张卡的四档配色。 */
@Composable
private fun Fsrs.Rating.tone(): RatingTone {
    val ratings = WordLensTheme.accents.ratings
    return when (this) {
        Fsrs.Rating.AGAIN -> ratings.again
        Fsrs.Rating.HARD -> ratings.hard
        Fsrs.Rating.GOOD -> ratings.good
        Fsrs.Rating.EASY -> ratings.easy
    }
}

/**
 * 完成一组的收尾。
 *
 * 依次延迟 80ms 入场：三个数字同时蹦出来读起来像弹窗，一行一行落下来才像「在结算」。
 * 触觉用 Success（闷响 + 轻音）——这一页里只有「完成」值得一个多余的一步动作，
 * 因为完成本身就是奖励，物理上值得被听见。
 */
@Composable
private fun RememberFinished(
    streakDays: Int,
    sessionSeconds: Int,
    firstTryAccuracy: Float,
    modifier: Modifier = Modifier,
) {
    val haptic = rememberHaptic()
    LaunchedEffect(Unit) { haptic(Haptic.Success) }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Staggered(index = 0) {
            Text(text = "\uD83C\uDF31", fontSize = 56.sp)
        }
        Staggered(index = 1) {
            Text(
                text = stringResource(R.string.review_done_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Staggered(index = 2) {
            Text(
                text = pluralStringResource(R.plurals.review_done_body, streakDays, streakDays),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        // 只有真的做过题才有用时与正确率：空轮（一进来就清空）不该显示「用时 0 秒 100%」。
        if (sessionSeconds > 0) {
            Staggered(index = 3) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Space.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Stat(value = formatDuration(sessionSeconds), label = stringResource(R.string.review_done_time))
                    Stat(
                        value = "${(firstTryAccuracy * 100).roundToInt()}%",
                        label = stringResource(R.string.review_done_accuracy),
                    )
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 依次入场的第 index 项，间隔 [STAGGER_MS]。
 *
 * 降级前后（完成结算的那四行）：
 *
 * - 默认（开关关）：第 index 行等 `index * 80ms`，再从下方三分之一行高滑入并淡入——
 *   一行一行落下来才像「在结算」。
 * - 减少动画：不排队、不滑入，首次组合就直接显示，四行同时到位。
 *
 * 这里连 `delay` 一起去掉，而不是只把进场换成瞬时：那 80ms 的间距是协程排出来的**出场次序**，
 * 不是动画时长，框架的动效缩放管不到它。留着它，界面照样是一行一行往外蹦，只是每行不再滑。
 * 结算页的四行都是静态读数，先落哪一行后落哪一行不携带任何信息，所以整条次序可以一起摘掉。
 */
@Composable
private fun Staggered(index: Int, content: @Composable () -> Unit) {
    val reduceMotion = rememberReduceMotion()
    // 降级时初值就是已显示：AnimatedContent 不为初始状态跑转场，所以四行在首次组合时
    // 直接到位，既不排队也不滑入。写成「早退 + 直接 content()」也能得到同样的画面，
    // 但那条路径会让下面的 remember/LaunchedEffect 变成条件调用，开关一拨就得多搬一次槽位。
    var shown by remember { mutableStateOf(reduceMotion) }
    LaunchedEffect(reduceMotion) {
        if (!reduceMotion) {
            delay(index * STAGGER_MS.toLong())
            shown = true
        }
    }
    AnimatedContent(
        targetState = shown,
        transitionSpec = {
            when {
                reduceMotion -> EnterTransition.None togetherWith ExitTransition.None
                targetState -> {
                    (slideInVertically(Motion.smoothOffset()) { it / 3 } + fadeIn(Motion.smooth))
                        .togetherWith(ExitTransition.None)
                }
                else -> EnterTransition.None togetherWith fadeOut(Motion.exitFade())
            }
        },
        label = "staggered",
    ) { visible ->
        if (visible) content()
    }
}

/** 用时读数：一分钟内只给秒，超过才给「几分几秒」——两个数字并排时宽度会跳。 */
internal fun formatDuration(seconds: Int): String {
    val minutes = seconds / 60
    val rest = seconds % 60
    return if (minutes <= 0) "${rest}s" else "${minutes}m ${rest}s"
}

private const val FLIP_MS = 480
private const val BACK_FULL_ANGLE = 140f
private const val PAPER_LIFT = 0.03f

/** 320×420 的比例，取自设计规范。 */
/**
 * 卡片的长宽比。
 *
 * 用它的所有地方都必须 `matchHeightConstraintsFirst = true`：卡片住在 `weight(1f)` 的盒子里，
 * 而那个盒子的高度由剩余空间决定。只按宽度算高度的话，在矮一点的屏幕上卡片会比盒子**高**，
 * 于是从中间往两头溢出——真机截图上「Today 0 /0」那行读数被卡片的顶边盖掉一半就是这么来的，
 * 而它是这一轮唯一的进度反馈。
 */
private const val FLIP_CARD_ASPECT = 320f / 420f
/** 越过屏宽 30% 就判定成功：再小会让「顺手一推」误判，再大要滑出屏幕才生效。 */
private const val SWIPE_THRESHOLD = 0.30f

/** 飞出目的地取 1.8 倍屏宽：足够保证整张卡离开可视区，即使它正停在屏幕最右。 */
private const val FLY_OUT = 1.8f

/** 飞出时最多淡掉 35%：完全淡掉会让「松手 → 卡片消失」中间多出一段空白。 */
private const val FLY_FADE = 0.35f

private const val MAX_BACK_CARDS = 2
private const val BACK_SCALE_STEP = 0.06f
private const val BACK_DROP_DP = 12f
private const val BACK_ALPHA_START = 0.5f
private const val BACK_ALPHA_STEP = 0.25f
private const val GLOW_MAX_ALPHA = 0.9f
private const val ARM_SCALE = 1.15f
private const val STAGGER_MS = 80
private const val HINT_ALPHA_LOW = 0.4f
private const val HINT_ALPHA_HIGH = 0.8f

/**
 * 材质筛选胶囊的**下限**高度。
 *
 * 48dp 不是照抄 iOS 的 44pt：44pt 是 UIKit 的坐标，而 Android 的无障碍底线是 48dp 的可点区域，
 * 这一排段是复习页唯一的内容切换入口，够得着比矮一点更值钱。
 * 传给 PillSwitch 的是下限而不是死值：系统字号放大时它会自己长高到容得下 labelLarge 那一行。
 */
private val PILL_HEIGHT = 48.dp
private val PROGRESS_HEIGHT = 4.dp

/**
 * 评级按钮的上下内边距。
 *
 * 这一档**不随系统字号调整**，因为按钮是 wrap-content 的：文字一行多高，按钮就多高。
 * fontScale 1.0 时按钮是 10 + 20 + 4 + 16 + 10 = 60dp，1.3 时自己长成 24 + 36 * 1.3 ≈ 71dp，
 * 长出来的是按钮而不是截断——需要跟着它让路的是卡片区（它是 weight(1f) 的那一块），
 * 而不是文字。真正会被大字号挤掉的是横向：四档均分一行，标签过长时截断的风险在宽度那一边。
 */
private val BUTTON_V_PADDING = 10.dp
private val PHOTO_BOX = 96.dp
private val ARCHIVED_MAX_HEIGHT = 320.dp

@Preview(showBackground = true, backgroundColor = 0xFFFFFDFB, widthDp = 380, heightDp = 800)
@Composable
private fun RememberWordPreview() {
    WordLensTheme {
        RememberScreen(
            topInset = PaddingValues(0.dp),
            bottomInset = PaddingValues(0.dp),
            state = RememberUiState(
                done = 3,
                total = 10,
                upcoming = 2,
                current = RememberCard(
                    item = ReviewItem.Word(
                        com.ilyskyo.wordlens.data.model.WordCard(
                            id = "w1",
                            headword = "cup",
                            language = "en",
                        ),
                    ),
                    prompt = "cup",
                    ipa = "/k\u028Ap/",
                    gloss = "\u676F\u5B50",
                ),
                intervals = Fsrs.previewIntervals(Fsrs.State()),
            ),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFFFDFB, widthDp = 380, heightDp = 800)
@Composable
private fun RememberRevealedPreview() {
    WordLensTheme {
        RememberScreen(
            topInset = PaddingValues(0.dp),
            bottomInset = PaddingValues(0.dp),
            state = RememberUiState(
                done = 6,
                total = 10,
                revealed = true,
                showingAnswer = true,
                upcoming = 1,
                current = RememberCard(
                    item = ReviewItem.Word(
                        com.ilyskyo.wordlens.data.model.WordCard(
                            id = "w2",
                            headword = "supplement",
                            language = "en",
                        ),
                    ),
                    prompt = "supplement",
                    ipa = "/\u02C8s\u028Cpl\u026Am\u0259nt/",
                    gloss = "\u8865\u5145\u5242",
                    example = "Take the tablets with water.",
                    source = ReviewSource.AI,
                ),
                intervals = Fsrs.previewIntervals(Fsrs.State()),
            ),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFFFDFB, widthDp = 380, heightDp = 800)
@Composable
private fun RememberFinishedPreview() {
    WordLensTheme {
        RememberScreen(
            topInset = PaddingValues(0.dp),
            bottomInset = PaddingValues(0.dp),
            state = RememberUiState(
                done = 10,
                total = 10,
                finished = true,
                streakDays = 6,
                sessionSeconds = 214,
                firstTryAccuracy = 0.8f,
            ),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFFFDFB, widthDp = 380, heightDp = 700)
@Composable
private fun RememberEmptyPreview() {
    WordLensTheme {
        RememberScreen(
            topInset = PaddingValues(0.dp),
            bottomInset = PaddingValues(0.dp),
            state = RememberUiState(),
        )
    }
}
