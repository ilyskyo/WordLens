// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.lookback

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File

/**
 * 一段录音的播放器：只管 MediaPlayer 那台机器，读数归界面。
 *
 * ## 为什么不做成 Compose 可观察的
 *
 * 界面要的三个读数各有各的节拍——在不在放跟着按钮走，位置每三百毫秒轮询一次，时长只在准备完成
 * 那一刻来一次。把它们做成三个 `mutableStateOf` 挂在这个类上，「谁在什么时候重画」就从界面的
 * 决定变成了这个类的秘密，而那个轮询循环本来也只有调用方知道该不该开着（没在放的时候不该转）。
 * 所以这一层只留三个回调：结束、失败、准备好。与 [com.ilyskyo.wordlens.ui.capture.CaptureCamera]
 * 同一个道理——框架媒体的持有对象长在它唯一的使用者旁边，不假装自己是领域层的东西。
 *
 * ## 实例是第一次按播放时才建的
 *
 * 详情页是高频路径，而一次进入并不一定会按下播放。冷启动一台 MediaPlayer 要占解码器资源，
 * 每条记录都预建一个，等于用看不见的成本换一次不确定会不会发生的即时性。
 *
 * @param file 那一段录音。不存在或读不出来时不会提前报错——错误发生在真正要放的那一刻，
 *   由 [onFailed] 说出去。这里不做「先 exists 一下」的预检：那只是把同一次失败换个时间说。
 */
class VoicePlayer(
    private val file: File,
    private val onEnded: () -> Unit = {},
    private val onFailed: () -> Unit = {},
    private val onPrepared: (Long) -> Unit = {},
) {

    private var player: MediaPlayer? = null

    /** 准备还没好就按了播放：等 prepared 回调里补上那一下，而不是让这点击掉进地板。 */
    private var wantsPlay = false

    /**
     * 播放或暂停，返回按下之后**是不是在放**。
     *
     * 返回布尔而不是让界面去读播放器状态：`isPlaying` 在 `start()` 之后不一定立刻为真（准备是
     * 异步的），让界面按返回值决定那颗图标，就不会出现「按下去图标没换、声音却已经响了」。
     * 还没准备好时同样返回 true——这一按的意思就是放，prepared 回调会把那一下补上，真放不起来
     * 才走 [onFailed] 把它收回。
     */
    fun toggle(): Boolean {
        val existing = player
        if (existing != null && existing.playing()) {
            runCatching { existing.pause() }
            wantsPlay = false
            return false
        }
        val p = existing ?: create() ?: run {
            wantsPlay = false
            onFailed()
            return false
        }
        if (!p.prepared()) {
            wantsPlay = true
            return true
        }
        // 放过一遍之后位置停在末尾：再按播放要的是「再来一遍」，不是「继续放一个空尾」。
        if (p.positionOrZero() >= p.durationOrZero()) p.seekToOrZero(0L)
        return if (runCatching { p.start() }.isSuccess) {
            wantsPlay = false
            true
        } else {
            wantsPlay = false
            onFailed()
            false
        }
    }

    /**
     * 当前播放位置。没建播放器、或还没准备好时是 0——那时候界面上的读数取日记里存的那个总长，
     * 而不是在播放条左边画一个凭空的 00:00。
     */
    fun positionMs(): Long = player?.positionOrZero() ?: 0L

    /**
     * 跳到某个位置。**不**在这里回报新位置：`seekTo` 是异步的，紧接着读 `getCurrentPosition`
     * 有可能还是旧值，界面按它画会让进度条在松手那一下弹回去。要那个读数请由调用方自己记下
     * 它刚请求的值（见 VoiceMemoRow 的 onValueChangeFinished）。
     */
    fun seekTo(ms: Long) {
        val p = player ?: return
        if (!p.prepared()) return
        p.seekToOrZero(ms.coerceIn(0L, p.durationOrZero()))
    }

    /**
     * 交还资源。幂等：`DisposableEffect` 的 onDispose 与「换了一段录音」都会走到这里，
     * 而漏一次就是一次解码器占用——低配机上那会表现成「别的 App 放不出声音」。
     */
    fun release() {
        val p = player
        player = null
        wantsPlay = false
        if (p != null) runCatching { p.release() }
    }

    private fun create(): MediaPlayer? {
        val p = MediaPlayer()
        // 逐个 p. 写，不用 p.apply { }：apply 把 MediaPlayer 变成隐式接收者，而它自己就带着
        // isPlaying 与 duration 两个成员——回调里一句 `isPlaying = false` 会安静地指到它身上，
        // 报出来是「val 不能重新赋值」，而那句话本来要改的是界面的读数。
        p.setAudioAttributes(
            // 用户在媒体音量下调它（不是闹钟、不是通知音），而内容是说话——这两条分别决定音量路由
            // 和某些机型对语音的音效处理。用旧的 setAudioStreamType 会被系统当成音乐。
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        p.setOnPreparedListener { prepared ->
            onPrepared(prepared.durationOrZero())
            if (wantsPlay) {
                wantsPlay = false
                if (!runCatching { prepared.start() }.isSuccess) onFailed()
            }
        }
        p.setOnCompletionListener {
            wantsPlay = false
            onEnded()
        }
        p.setOnErrorListener { _, _, _ ->
            // 返回 true = 这个错误已经被处理了。不处理的话框架会再往 logcat 里丢一遍，
            // 而界面上还是那副「按了没反应」的样子。
            wantsPlay = false
            onFailed()
            true
        }
        return try {
            p.setDataSource(file.path)
            p.prepareAsync()
            player = p
            p
        } catch (t: RuntimeException) {
            // 文件不在、格式读不出来都落在这一条上（IllegalArgumentException）。
            runCatching { p.release() }
            null
        }
    }
}

// ── MediaPlayer 这四处读取都会抛，全部包起来：它是一台会被人在下面掐掉的外部机器 ──

private fun MediaPlayer.playing(): Boolean = runCatching { isPlaying }.getOrDefault(false)

/** `isPrepared` 不是公开 API，只能自己问时长——而它在没准备好时返回 0 或直接抛。 */
private fun MediaPlayer.prepared(): Boolean = durationOrZero() > 0L

private fun MediaPlayer.durationOrZero(): Long = runCatching { duration.toLong() }.getOrDefault(0L)

private fun MediaPlayer.positionOrZero(): Long = runCatching { currentPosition.toLong() }.getOrDefault(0L)

private fun MediaPlayer.seekToOrZero(ms: Long) {
    runCatching { seekTo(ms.toInt()) }
}
