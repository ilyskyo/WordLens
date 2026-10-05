// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.core.voice

/**
 * 语音日记附件的纯逻辑：文件名、时长读数、那一段的状态机、以及「现在能不能录」。
 *
 * ## 为什么这些判断要离开 Android
 *
 * 这件事难的部分不是 MediaRecorder 的调用顺序，而是**它随时可能被打断**：用户按下返回、
 * 页面离开组合、来电、别的 App 抢走麦克风、进程被系统杀掉。每一种打断都要同时回答三个问题——
 * 磁盘上那份还在不在、麦克风还拿着没有、用户知不知道发生了什么——而这三问的答案是一台状态机，
 * 不是几个 if。抽成不碰框架的函数才能逐格断言：真机上复现一次「录到一半来电」要真拨一个电话
 * 进来，在这里只是一行断言。
 *
 * ## 「那一段」（take）是什么
 *
 * 录完了、但还没被用户**收下**的那份声音。它是这个功能唯一的状态词，存在的理由只有一条：
 * 用户录到一半被中断时，替他保住已经录下的那几秒，而不是悄悄丢掉，也不是替他决定留下。
 *
 * ## 一条命名约定撑起整个删除级联
 *
 * 文件从第一秒就写在**最终位置**上（[fileName]），所以「收下」只是让日记开始引用它，不是搬文件；
 * 「丢弃」和「删除条目」删的是同一个路径。于是级联永远只有一个分支——与贴纸的
 * `st-<entryId>.png` 同一条约定。也正因为只有一个路径，一段没被收下的声音不可能被谁引用错，
 * 只可能被扫掉（见 `DiaryRepository.sweepUnreferencedAudio`）。
 *
 * 代价写在 [TakeAction.REPLACE] 一侧：覆盖之前旧的必须先没掉，所以那一次确认对话框是必需的，
 * 不是礼貌。
 *
 * ## 为什么不转文字
 *
 * 自己说过一遍的词和读过一遍的词是两种记忆，而这本日记是由场景组成的。转成文字就把它变回
 * 文本，而文本是所有别的词汇应用已经在做的东西。所以这里没有语音识别、没有云端、也没有后台
 * 服务：录、存、放、删，四件事到此为止。
 */
object VoiceMemo {

    /**
     * 一段声音的时长上限：90 秒。
     *
     * 「一句当时的声音」通常十秒内说完，这个数字不是给正常用量准备的，而是给**忘了按停**
     * 准备的：没有上限，那一段会一直写到磁盘或写满一次通话。90 秒单声道 64kbps 约 0.7MB，
     * 是「说走了神也保住前情」与「不至于吃掉存储」的交点。到点由录音机自己收手
     * （[TakeEvent.LIMIT]），所以它仍然是一段完整可读的文件，而不是被外部掐断的半截。
     */
    const val MAX_TAKE_MS: Long = 90_000L

    /**
     * 短于这个数不算录到东西。
     *
     * 按下录音又立刻松手会得到几百毫秒的呼吸。让它被收下，界面上就多出一条 00:00 的播放条——
     * 用户按播放听到一点杂音，得出的结论是「这个功能坏了」。
     */
    const val MIN_TAKE_MS: Long = 500L

    /**
     * 那段声音的文件名，相对 `filesDir/audio`。
     *
     * 按条目 id 定死，与贴纸同一族：删条目时不需要先读 `audioPath` 就知道该删哪个文件，
     * 而那一段收下了没有也只由 `audioPath` 说，不由文件名说。
     */
    fun fileName(entryId: String): String = "a-$entryId.m4a"

    /** 这一段有没有资格被收下。太短的在收尾那一步就当垃圾处理掉。 */
    fun isKeepable(durationMs: Long): Boolean = durationMs >= MIN_TAKE_MS

    /**
     * `mm:ss`。
     *
     * 不用格式化器：`SimpleDateFormat` 吃时区与 locale（东亚 locale 会把读数写成「1分05秒」
     * 这种塞不进播放条的形状），而时长是数字不是文案，四种语言下必须长得一样。
     * 手工补零也因此更便宜——这一条在录音时每一百多毫秒就被重算一次。
     *
     * 秒数**向下取整**而不是四舍五入：显示 00:01 的那一段真的不止一秒，反过来把 1.6 秒报成
     * 00:02 会让用户去等一段不存在的声音。分钟不折成小时——上限 90 秒的读数永远是两位数，
     * 加一个换算只是多一条会写错的分支。
     */
    fun formatDuration(durationMs: Long): String {
        val total = (durationMs / 1_000L).coerceAtLeast(0L)
        return twoDigits(total / 60L) + ":" + twoDigits(total % 60L)
    }

    private fun twoDigits(value: Long): String = if (value < 10L) "0$value" else "$value"

    /**
     * 录音中那颗秒表的读数。
     *
     * 两端都钳。上限：「到时长上限」是录音机异步回调回来的，秒表不能跑出界面刚说过的上限，
     * 跑出 01:30 之后再退回 01:30 读起来像卡住了。下限：[startedAtElapsed] 来自上一台
     * 录音机时会是负数（收尾与新建之间界面还会组合一次），倒着走的秒表读起来像坏了。
     */
    fun elapsedMs(nowElapsed: Long, startedAtElapsed: Long): Long =
        (nowElapsed - startedAtElapsed).coerceIn(0L, MAX_TAKE_MS)

    /**
     * 按下「录一段」之后，界面该做哪一件事。
     *
     * 顺序是有意义的，从上到下就是**不可逆程度从低到高**：
     *
     * 1. 现场已经有那一段（在录、或在等收下）→ [TakeAction.BUSY]。让这一按去开第二台录音机
     *    是最糟的选择：一台 MediaRecorder 只服务一段，结果是一段被另一段截掉的文件。
     * 2. 权限还没到手 → 先问系统。**这条必须排在 REPLACE 之前**：如果先弹「会换掉旧录音」的
     *    确认，用户确认了却因权限被拒而录不成，他就白丢了一段声音。
     * 3. 已经有收下的声音 → [TakeAction.REPLACE]，先确认。
     * 4. 都没有 → [TakeAction.RECORD]。
     *
     * `permission` 里的 DENIED_FOREVER 只在用户**答过**授权框之后才可信：第一次进这一页时
     * `shouldShowRequestPermissionRationale` 也返回 false，把它当成永久拒绝会在第一眼就把按钮
     * 改成「去设置里打开」。判别的位置写在调用点。
     */
    fun actionFor(phase: TakePhase, permission: MicPermission, hasAudio: Boolean): TakeAction = when {
        phase != TakePhase.IDLE -> TakeAction.BUSY
        permission == MicPermission.ASK -> TakeAction.ASK_PERMISSION
        permission == MicPermission.DENIED_FOREVER -> TakeAction.OPEN_SETTINGS
        hasAudio -> TakeAction.REPLACE
        else -> TakeAction.RECORD
    }

    /**
     * 一次事件把那一段推到哪儿，以及磁盘上那份文件怎么走。
     *
     * 返回 null 表示**这件事与当前阶段无关，什么都不做**：已经没有那一段了又收到一次「停止」、
     * 已经暂存了又收到一次「麦克风被占」——都是重复或迟到的事件，不是新的状态。
     *
     * 三条硬规则，都由「不许悄悄丢用户的声音，也不许留下没人引用的声音」推出来：
     *
     * - **正常结束（停止 / 到上限）→ STAGED + 留文件**，等用户在这一条上决定。替他决定收下，
     *   等于把一句被后台打断的半截话当成他留下的内容。
     * - **异常结束 → IDLE + 删文件 + 说一句**。被掐断的 m4a 连 moov 索引都没有，播放只会报错；
     *   留着它比删掉更坏，因为界面上会出现一段按了没声音的录音，而磁盘上多了一段没人认领的人声。
     * - **异常动不到已经暂存的那一段**：那时候麦克风早还回去了，磁盘上的文件是完整可读的，
     *   一次录音机的错误与它无关。
     */
    fun transition(phase: TakePhase, event: TakeEvent): TakeTransition? = when (event) {
        TakeEvent.STARTED -> if (phase == TakePhase.IDLE) {
            TakeTransition(TakePhase.RECORDING, TakeFile.KEEP, null)
        } else {
            null
        }

        TakeEvent.STOPPED, TakeEvent.HAND_OFF -> if (phase == TakePhase.RECORDING) {
            TakeTransition(
                TakePhase.STAGED,
                TakeFile.KEEP,
                if (event == TakeEvent.HAND_OFF) TakeNotice.HANDED_OFF else null,
            )
        } else {
            null
        }

        // 录音机自己收的手。文件是完整的，所以与手动停止同路，但要说一句：不是用户按的。
        TakeEvent.LIMIT -> if (phase == TakePhase.RECORDING) {
            TakeTransition(TakePhase.STAGED, TakeFile.KEEP, TakeNotice.LIMIT)
        } else {
            null
        }

        TakeEvent.TOO_SHORT -> if (phase == TakePhase.RECORDING) {
            TakeTransition(TakePhase.IDLE, TakeFile.DELETE, TakeNotice.TOO_SHORT)
        } else {
            null
        }

        // 文件不动：它本来就长在最终名字上。把 audioPath 写上这件事由调用方做。
        TakeEvent.COMMITTED -> if (phase == TakePhase.STAGED) {
            TakeTransition(TakePhase.IDLE, TakeFile.KEEP, null)
        } else {
            null
        }

        TakeEvent.DISCARDED -> if (phase == TakePhase.IDLE) null else {
            TakeTransition(TakePhase.IDLE, TakeFile.DELETE, TakeNotice.DISCARDED)
        }

        // 只有等着收下的那一段会被顶掉：正在录的那一段占着麦克风，不可能同时有第二段在录。
        TakeEvent.SUPERSEDED -> if (phase == TakePhase.STAGED) {
            TakeTransition(TakePhase.IDLE, TakeFile.DELETE, TakeNotice.SUPERSEDED)
        } else {
            null
        }

        TakeEvent.IN_USE, TakeEvent.FAILED -> if (phase == TakePhase.RECORDING) {
            TakeTransition(
                TakePhase.IDLE,
                TakeFile.DELETE,
                if (event == TakeEvent.IN_USE) TakeNotice.IN_USE else TakeNotice.FAILED,
            )
        } else {
            null
        }
    }

    /** 录音机自己报出来的事件走这一条，把「还能不能用」的判断收在一处。 */
    fun isFailure(event: TakeEvent): Boolean = event == TakeEvent.IN_USE || event == TakeEvent.FAILED
}

/**
 * 现场那一段录音。
 *
 * [startedAtElapsed] 用 `SystemClock.elapsedRealtime()` 而不是墙钟：这一页上那颗秒表最长要跑
 * 九十秒，用户中途改系统时间或 NTP 校时都不能让它跳一下——`core/RetryGate.kt` 为同一件事
 * 论证过一次。
 *
 * [stagedMs] 为 null 表示**文件还在收尾**，时长还不知道。界面上这一段显示「正在收尾」而不是
 * 00:00：把一个还不知道的数字写成零，用户读到的是「我录了一段空的」。
 */
data class PendingTake(
    val entryId: String,
    val phase: TakePhase,
    val startedAtElapsed: Long = 0L,
    val stagedMs: Long? = null,
)

/** 现场那一段录音处在哪个阶段。 */
enum class TakePhase {
    /** 没有那一段。 */
    IDLE,

    /** 麦克风正被占着，文件正在写。 */
    RECORDING,

    /** 录完了、文件完整可读，但用户还没决定收不收下。 */
    STAGED,
}

/** 对现场那一段做的一件事。来自三个方向：用户的按钮、页面生命周期、录音机的回调。 */
enum class TakeEvent {
    /** 录音机真的跑起来了。 */
    STARTED,

    /** 用户按停，或者离开这一条、退到后台——三种「正常结束」里由用户主动发起的那一种。 */
    STOPPED,

    /** 用户离开这一条或退到后台。与 [STOPPED] 同路，只是必须说一句。 */
    HAND_OFF,

    /** 到 [VoiceMemo.MAX_TAKE_MS]，录音机自己停的。 */
    LIMIT,

    /** 停手读出来不足 [VoiceMemo.MIN_TAKE_MS]，等于没录到内容。 */
    TOO_SHORT,

    /** 用户收下：日记开始引用它，文件留在原处。 */
    COMMITTED,

    /** 用户丢弃：删文件。 */
    DISCARDED,

    /** 别条上的一段顶掉了这一条还没收下的那段。 */
    SUPERSEDED,

    /** 麦克风拿不到——通话中，或别的 App 正占着。 */
    IN_USE,

    /** 录音机报错、start 抛、stop 抛、时长读不出来：那半截文件不可用。 */
    FAILED,
}

/** 那一段在磁盘上的那份文件怎么走。 */
enum class TakeFile {
    /** 原样留着（含「被日记开始引用」那一种）。 */
    KEEP,

    /** 立刻删。留着一段没人引用的声音是隐私问题，不是几个 KB 的漏。 */
    DELETE,
}

/**
 * 该对用户说的那一句。
 *
 * 放这里而不是 `R.string` 常量，是因为判定要能在 JVM 上断言，而资源 id 依赖 Android context；
 * 界面负责把这一个枚举翻成四种语言里的那一句。
 */
enum class TakeNotice {
    /** 到时长上限，录音机自己收的手。 */
    LIMIT,

    /** 不是用户按的停：退到后台或离开这一条，那段声音停在原地等他回来决定。 */
    HANDED_OFF,

    TOO_SHORT,
    DISCARDED,
    SUPERSEDED,
    IN_USE,

    /** 录音失败了，而原因不在用户身上。 */
    FAILED,
}

/** [VoiceMemo.transition] 的结果：新阶段 + 文件怎么走 + 要不要报告。 */
data class TakeTransition(
    val phase: TakePhase,
    val file: TakeFile,
    val notice: TakeNotice?,
)

/** 麦克风权限的三档。由界面读系统，判定读这个枚举。 */
enum class MicPermission {
    GRANTED,

    /** 还能再问一次：系统会弹框。 */
    ASK,

    /** 「不再询问」已经勾上：系统不会再弹框，唯一的出路是应用详情页。 */
    DENIED_FOREVER,
}

/** 按下「录一段」之后界面要做的事。见 [VoiceMemo.actionFor] 的排序理由。 */
enum class TakeAction {
    RECORD,

    /** 先确认会换掉旧的那一段，再录。 */
    REPLACE,

    /** 现场已经有一段。 */
    BUSY,

    ASK_PERMISSION,

    /** 永久拒绝：再 launch 只会拿到同一个静默的 false，按钮要做的是把人送到应用详情页。 */
    OPEN_SETTINGS,
}
