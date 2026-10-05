// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.core.voice

import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.io.IOException

/**
 * 一台录音机：一次只录一段，写到 [target] 那个位置上。
 *
 * ## 它只管麦克风，不管数据
 *
 * 这个对象存在的唯一理由是把「拿着麦克风」这件事收在一处，好让**任何**一条离开路径都把麦克风
 * 还回去。文件的去留、状态往哪走、该对用户说哪一句，全部由
 * [com.ilyskyo.wordlens.ui.nav.HomeViewModel] 按 [VoiceMemo.transition] 的裁决执行——录音机自己
 * 去删文件的话，就有了两个都以为自己在管那个文件的人，而这两个人的删除时机并不一致。
 *
 * ## 失败有三种长相，缺一种就会变成静默的丢录音
 *
 * - `start()` 直接抛（`IllegalStateException`，或各家 ROM 的 `RuntimeException: start failed`）；
 * - 录的过程中 `OnErrorListener` 报回来（媒体服务死了、被别的应用抢走）；
 * - `OnInfoListener` 报回来：只有我们设的那个时长上限是好消息（文件已经写完整了），
 *   其余的信息码在这条路上都是厂商把坏消息塞进了 `MEDIA_RECORDER_INFO_UNKNOWN` 里。
 *
 * 三条里的后两条都收敛成 [TakeEvent] 交回上层（带着一台录音机的身份，让调用方能认出这是
 * 「现在还拿着的那一台」报的，而不是上一台迟到的消息）；第一条不用报——[start] 直接把结果
 * 返回给调用方，那更准，也不会让同一个失败被说两次。
 *
 * @param onEvent 只会收到 [TakeEvent.LIMIT] / [TakeEvent.IN_USE] / [TakeEvent.FAILED] 三种。
 *   监听器挂在创建这台录音机的那个 Looper 上，而调用方在主线程上建它，所以消息到的是主线程，
 *   界面那一侧不必再切一次线程。
 */
class VoiceRecorder(
    private val context: Context,
    private val target: File,
    private val onEvent: (VoiceRecorder, TakeEvent) -> Unit,
) {

    private var recorder: MediaRecorder? = null

    private val audioManager: AudioManager? = context.getSystemService(AudioManager::class.java)

    /** 真正开始写的时刻（`elapsedRealtime`）。没开始成功就是 0，调用方不会去读它。 */
    var startedAtElapsed: Long = 0L
        private set

    /**
     * 开录。
     *
     * 返回的是原因而不是一句文案：调用方拿 [TakeStart] 去选「麦克风被占着」还是「机器出了事」
     * 那两句话——用户可以自己做点什么的失败，和只能重来的失败，不该共用一句道歉。
     */
    fun start(): TakeStart {
        // 通话（含拨号中、VoIP）里麦克风不归我们。这里先问一句而不是等它抛：抢来的那一段
        // 只有对方听得见，或者干脆是一段静音，两种都该在动手之前就挡下来。
        if (micTakenByCall()) return TakeStart.IN_USE
        val rec = newRecorder()
        return try {
            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(AUDIO_BIT_RATE)
                setAudioSamplingRate(AUDIO_SAMPLE_RATE)
                setAudioChannels(1)
                // 到点自己收手：不指望用户记得按停，也不让一个已经看不见的界面拿着麦克风一直写。
                setMaxDuration(VoiceMemo.MAX_TAKE_MS.toInt())
                setOutputFilePath(target)
                setOnInfoListener { _, what, _ -> onInfo(what) }
                setOnErrorListener { _, _, _ ->
                    // 具体是哪个错误码不重要：这一台已经不能继续写了，能做的只有收手。错误码
                    // 各家厂商写得五花八门，按它们分文案只会得到一堆永远对不上的分支。
                    onEvent(this@VoiceRecorder, failureEvent())
                    true
                }
            }
            rec.prepare()
            rec.start()
            recorder = rec
            startedAtElapsed = SystemClock.elapsedRealtime()
            TakeStart.OK
        } catch (io: IOException) {
            // 文件写不出去：目录被外部清掉了，或者存储满了。
            releaseQuietly(rec)
            failureStart()
        } catch (t: RuntimeException) {
            // IllegalStateException（状态不对）与 ROM 那句 start failed 都在这一族里，
            // SecurityException（录的途中权限被撤回）也是。
            releaseQuietly(rec)
            failureStart()
        }
    }

    /**
     * 收尾：停止、把文件写完整、把麦克风还回去，返回读出来的时长（毫秒）。
     *
     * 负数 = 这一段是废的（stop 抛了，或者读完发现里面没有时间），调用方要把它删掉。
     *
     * 时长是**从文件里读**的，不是「按下停止时的秒表」：中途被系统掐掉的那一段，秒表会把它根本
     * 没录进去的时间也算进去，而播放条要按真实内容长度画——差一秒的读数就是「明明写着 12 秒、
     * 放到 11 秒就没声了」。
     *
     * 这个方法会阻塞几十毫秒（要把索引写到文件尾巴上），所以调用方要在 IO 线程上叫它。
     */
    fun finish(): Long {
        val rec = recorder
        recorder = null
        if (rec == null) return -1L
        // stop() 在录音机已经被 HAL 掐死之后会抛 IllegalStateException。这不是「又失败了一次」，
        // 而是同一次失败的另一种长相——所以它落到同一个 -1，由调用方去删那个半截文件。
        val stopped = runCatching { rec.stop() }.isSuccess
        releaseQuietly(rec)
        if (!stopped) return -1L
        return durationMsOf(target)
    }

    /**
     * 丢下这一段走人。
     *
     * 不叫 `stop()`：那会把一段已经决定不要的录音认真写完，白等几十毫秒还多写一次磁盘。
     * 文件由调用方删（[TakeFile.DELETE]），麦克风在这里当场还回去。
     */
    fun abandon() {
        val rec = recorder
        recorder = null
        if (rec != null) releaseQuietly(rec)
    }

    private fun onInfo(what: Int) {
        when (what) {
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED -> onEvent(this, TakeEvent.LIMIT)
            // 到这里的信息码只有两种来源：我们设的那个上限，或者厂商把一次故障塞进了
            // MEDIA_RECORDER_INFO_UNKNOWN。没设文件体积上限，所以 801 那一族不会走到这里；
            // 剩下的都按坏消息处理——把它们当好消息的代价是一段没人知道的静音录音。
            else -> onEvent(this, failureEvent())
        }
    }

    /** 撞墙的原因分两句说：通话里被占是一种、其余失败是另一种，用户能做的动作不一样。 */
    private fun failureEvent(): TakeEvent =
        if (micTakenByCall()) TakeEvent.IN_USE else TakeEvent.FAILED

    /** 开录那一步的同样两句，只是走返回值而不是事件：同一个失败不该被说两次。 */
    private fun failureStart(): TakeStart =
        if (micTakenByCall()) TakeStart.IN_USE else TakeStart.FAILED

    /**
     * 麦克风现在是不是归通话或别的 App。
     *
     * 只读 `mode`：它是系统在「接通 / 拨号 / VoIP」时会写的那个总闸，比去猜错误码可靠，
     * 也不需要任何权限。读不到就按「没在用」处理——这一问只是提前少撞一次墙，真撞上了
     * 还有 start 抛与 onError 那两条路兜着。
     */
    private fun micTakenByCall(): Boolean {
        val mode = runCatching { audioManager?.mode }.getOrNull() ?: AudioManager.MODE_NORMAL
        return mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE
    }

    private fun releaseQuietly(rec: MediaRecorder) {
        // reset 与 release 各包一次：已经死掉的录音机在 reset 时就能再抛一回，而那不该让
        // release 不跑——麦克风挂在上面，漏一次就占到进程结束。
        runCatching { rec.reset() }
        runCatching { rec.release() }
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        // API 31 起官方推荐带 Context 的那个构造（权限归属由框架核对），之下的版本只有无参的。
        // 这不是「兼容用的分支」而是两套都在真实设备上跑着的构造：minSdk 26 的机器还在用。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }

    companion object {
        /**
         * 语音码率 64kbps、单声道。
         *
         * 人声在 48–64kbps 已经听不出压缩痕迹，而 128kbps 只是把九十秒的一段从 0.7MB 变成
         * 1.4MB：这本日记里它的邻居是照片，音频不该比照片更占地方。单声道是刻意的——
         * 「自己说过一遍的那个词」要的是内容不是声场，双声道还会让 VOICE_RECOGNITION 那条链路
         * 上的降噪多做一遍无用功。
         */
        private const val AUDIO_BIT_RATE = 64_000

        /**
         * 采样率 44100。
         *
         * 16k 更省，但 VOICE_RECOGNITION 的音频链在部分机型上会把 16k 再采样一次，出来的是一段
         * 带金属味的噪声；而这段声音的价值恰恰在于**听见自己说过**，电话味正好抹掉了那个特征。
         * 44100 是各家 AAC 编码器都直接吃的率，省下的那点体积不值得换一次「听起来坏了」。
         */
        private const val AUDIO_SAMPLE_RATE = 44_100

        /**
         * 读一个已经写完的音频文件有多长。
         *
         * 用 MediaMetadataRetriever 而不是 MediaPlayer：前者是为「读一个本地文件的元数据」造的，
         * 不建解码器、不占音频输出；后者是为播放准备的，用它读时长等于在收尾那一步又开一个
         * 播放器实例，而那一刻我们正要释放麦克风。
         */
        private fun durationMsOf(file: File): Long = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?: -1L
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrDefault(-1L)
    }
}

/**
 * `setOutputFile` 在几个版本里被反复推荐换成 MediaStore 那套写法，而我们要写的从来不是媒体库：
 * 这是 `filesDir` 下一个不该出现在任何系统相册与媒体索引里的私密文件（说明书 §8.4 的同一套
 * 理由）。留这一个窄接口，把带弃用标注的调用集中到一行上。
 */
@Suppress("DEPRECATION")
private fun MediaRecorder.setOutputFilePath(file: File) {
    setOutputFile(file.absolutePath)
}

/** [VoiceRecorder.start] 的结果。三种都要说话，但说的是三句不同的话。 */
enum class TakeStart {
    /** 麦克风到手，文件开始写。 */
    OK,

    /** 通话里，或别的 App 正占着。用户挂断之后可以马上再试一次。 */
    IN_USE,

    /** 机器出了事：权限被撤回、目录写不进、编码器起不来。 */
    FAILED,
}
