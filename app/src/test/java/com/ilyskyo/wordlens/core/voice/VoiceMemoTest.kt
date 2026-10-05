// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 那一段录音的状态机。
 *
 * ## 为什么这一张表值得逐格断言
 *
 * 录音这件事难的部分不是 `MediaRecorder` 的调用顺序，而是它**随时可能被打断**：来电、别的 App
 * 抢走麦克风、用户按下返回、进程被系统杀掉。每一种打断都要同时回答三个问题——磁盘上那份还在不在、
 * 麦克风还拿着没有、用户知不知道发生了什么——而这三问的答案是一张表，不是几个 if。
 *
 * 真机上复现一次「录到一半来电」要真拨一个电话进来；在这里是一行断言。更要紧的是：这张表里
 * 每一格写错都不崩溃，只是**悄悄丢掉用户说过的一句话**，或者在磁盘上留一段没人认领的人声。
 * 前者没人会报，后者是隐私问题。
 */
class VoiceMemoTest {

    // ── 正常结束：都留下文件，都等用户决定 ──────────────────────────────────

    @Test
    fun `a stopped take is kept on disk and waits for the user`() {
        val t = VoiceMemo.transition(TakePhase.RECORDING, TakeEvent.STOPPED)
        assertEquals(TakePhase.STAGED, t?.phase)
        assertEquals(TakeFile.KEEP, t?.file)
        // 用户自己按的停，不需要额外解释。
        assertNull(t?.notice)
    }

    /** 到上限由录音机自己收手：文件是完整的，所以与手动停止同路，但必须说一句——不是用户按的。 */
    @Test
    fun `the duration limit stops the take on its own and says so`() {
        val t = VoiceMemo.transition(TakePhase.RECORDING, TakeEvent.LIMIT)
        assertEquals(TakePhase.STAGED, t?.phase)
        assertEquals(TakeFile.KEEP, t?.file)
        assertEquals(TakeNotice.LIMIT, t?.notice)
    }

    /**
     * 退到后台与离开这一条都算「正常结束」：麦克风当场还回去，那一段停在原地等用户回来。
     *
     * 与按停唯一的区别是必须说一句，否则那颗秒表是无声无息停下来的，用户读到的是界面坏了。
     */
    @Test
    fun `handing off seals the take without deciding for the user`() {
        val t = VoiceMemo.transition(TakePhase.RECORDING, TakeEvent.HAND_OFF)
        assertEquals(TakePhase.STAGED, t?.phase)
        assertEquals(TakeFile.KEEP, t?.file)
        assertEquals(TakeNotice.HANDED_OFF, t?.notice)
    }

    @Test
    fun `keeping a take leaves the file where it already lies`() {
        val t = VoiceMemo.transition(TakePhase.STAGED, TakeEvent.COMMITTED)
        assertEquals(TakePhase.IDLE, t?.phase)
        assertEquals(TakeFile.KEEP, t?.file)
    }

    @Test
    fun `discarding a take deletes the file`() {
        val staged = VoiceMemo.transition(TakePhase.STAGED, TakeEvent.DISCARDED)
        assertEquals(TakePhase.IDLE, staged?.phase)
        assertEquals(TakeFile.DELETE, staged?.file)

        val recording = VoiceMemo.transition(TakePhase.RECORDING, TakeEvent.DISCARDED)
        assertEquals(TakeFile.DELETE, recording?.file)
    }

    // ── 异常结束：那半截不可信，删掉并且说出来 ──────────────────────────────

    /** 被掐断的 m4a 连 moov 索引都没有，留着它只会得到一条按了没声音的播放条。 */
    @Test
    fun `a take broken while recording is deleted and reported`() {
        for (event in listOf(TakeEvent.IN_USE, TakeEvent.FAILED)) {
            val t = VoiceMemo.transition(TakePhase.RECORDING, event)
            assertEquals("$event", TakePhase.IDLE, t?.phase)
            assertEquals("$event", TakeFile.DELETE, t?.file)
            assertTrue("$event", VoiceMemo.isFailure(event))
        }
        assertEquals(TakeNotice.IN_USE, VoiceMemo.transition(TakePhase.RECORDING, TakeEvent.IN_USE)?.notice)
        assertEquals(TakeNotice.FAILED, VoiceMemo.transition(TakePhase.RECORDING, TakeEvent.FAILED)?.notice)
    }

    /**
     * 异常动不到已经暂存的那一段。
     *
     * 那时候麦克风早还回去了，磁盘上的文件是完整可读的：一次录音机的错误与它无关。
     * 这一格是整个表里最容易被写错的一格——写错的长相是「接了个电话，刚录好的那句没了」。
     */
    @Test
    fun `a failure cannot touch an already staged take`() {
        assertNull(VoiceMemo.transition(TakePhase.STAGED, TakeEvent.IN_USE))
        assertNull(VoiceMemo.transition(TakePhase.STAGED, TakeEvent.FAILED))
    }

    /** 太短的这一段等于没录到内容：直接当垃圾处理，别让它长成一条 00:00 的播放条。 */
    @Test
    fun `a take that is too short is thrown away`() {
        val t = VoiceMemo.transition(TakePhase.RECORDING, TakeEvent.TOO_SHORT)
        assertEquals(TakePhase.IDLE, t?.phase)
        assertEquals(TakeFile.DELETE, t?.file)
        assertEquals(TakeNotice.TOO_SHORT, t?.notice)
    }

    /** 只有等着收下的那段会被别条顶掉：正在录的那段占着麦克风，不可能同时有第二段在录。 */
    @Test
    fun `superseding only replaces a staged take, never a live one`() {
        assertEquals(TakeFile.DELETE, VoiceMemo.transition(TakePhase.STAGED, TakeEvent.SUPERSEDED)?.file)
        assertNull(VoiceMemo.transition(TakePhase.RECORDING, TakeEvent.SUPERSEDED))
    }

    /** 迟到或重复的事件什么都不做——它们不是新的状态。 */
    @Test
    fun `events that do not fit the phase do nothing`() {
        assertNull(VoiceMemo.transition(TakePhase.RECORDING, TakeEvent.STARTED))
        assertNull(VoiceMemo.transition(TakePhase.STAGED, TakeEvent.STARTED))
        assertNull(VoiceMemo.transition(TakePhase.IDLE, TakeEvent.STOPPED))
        assertNull(VoiceMemo.transition(TakePhase.IDLE, TakeEvent.LIMIT))
        assertNull(VoiceMemo.transition(TakePhase.IDLE, TakeEvent.COMMITTED))
        assertNull(VoiceMemo.transition(TakePhase.IDLE, TakeEvent.DISCARDED))
        assertNull(VoiceMemo.transition(TakePhase.IDLE, TakeEvent.HAND_OFF))
        assertNull(VoiceMemo.transition(TakePhase.STAGED, TakeEvent.STOPPED))
    }

    // ── 按下「录一段」之后要做哪件事 ────────────────────────────────────────

    /**
     * 顺序按**不可逆程度从低到高**：现场已有一段 > 先问权限 > 会顶掉旧的要先确认 > 直接录。
     *
     * 权限必须排在 REPLACE 之前：先弹「会换掉旧录音」的确认、用户确认了却因权限被拒而录不成，
     * 他就白丢了一段声音。
     */
    @Test
    fun `the busy take wins over everything else`() {
        for (phase in listOf(TakePhase.RECORDING, TakePhase.STAGED)) {
            assertEquals(
                phase.toString(),
                TakeAction.BUSY,
                VoiceMemo.actionFor(phase, MicPermission.ASK, hasAudio = true),
            )
        }
    }

    @Test
    fun `permission is asked before the replace confirmation`() {
        assertEquals(
            TakeAction.ASK_PERMISSION,
            VoiceMemo.actionFor(TakePhase.IDLE, MicPermission.ASK, hasAudio = true),
        )
        // 永久拒绝之后再 launch 只会拿到同一个静默的 false，所以要送去应用详情页。
        assertEquals(
            TakeAction.OPEN_SETTINGS,
            VoiceMemo.actionFor(TakePhase.IDLE, MicPermission.DENIED_FOREVER, hasAudio = true),
        )
    }

    @Test
    fun `recording over an existing take needs a confirmation first`() {
        assertEquals(
            TakeAction.REPLACE,
            VoiceMemo.actionFor(TakePhase.IDLE, MicPermission.GRANTED, hasAudio = true),
        )
        assertEquals(
            TakeAction.RECORD,
            VoiceMemo.actionFor(TakePhase.IDLE, MicPermission.GRANTED, hasAudio = false),
        )
    }

    // ── 读数与命名 ──────────────────────────────────────────────────────────

    /**
     * `mm:ss`，四语下长得一样，秒数**向下**取整。
     *
     * 把 1.6 秒报成 00:02 会让用户去等一段不存在的声音；反过来显示 00:01 的那一段真的不止一秒。
     */
    @Test
    fun `durations read as mm ss and never round up`() {
        assertEquals("00:00", VoiceMemo.formatDuration(0L))
        assertEquals("00:00", VoiceMemo.formatDuration(999L))
        assertEquals("00:01", VoiceMemo.formatDuration(1_000L))
        assertEquals("00:01", VoiceMemo.formatDuration(1_600L))
        assertEquals("00:12", VoiceMemo.formatDuration(12_400L))
        assertEquals("01:30", VoiceMemo.formatDuration(90_000L))
        // 负数只可能来自还没开始的秒表，读成 -00:01 就是界面坏了的样子。
        assertEquals("00:00", VoiceMemo.formatDuration(-5_000L))
    }

    @Test
    fun `the stopwatch clamps at both ends`() {
        assertEquals(5_000L, VoiceMemo.elapsedMs(nowElapsed = 15_000L, startedAtElapsed = 10_000L))
        // 上限：「到时长上限」是录音机异步回调回来的，秒表不能跑出界面刚说过的 01:30。
        assertEquals(VoiceMemo.MAX_TAKE_MS, VoiceMemo.elapsedMs(nowElapsed = 999_000L, startedAtElapsed = 10_000L))
        // 下限：startedAtElapsed 来自上一台录音机时会是负数，倒着走的秒表读起来像坏了。
        assertEquals(0L, VoiceMemo.elapsedMs(nowElapsed = 10_000L, startedAtElapsed = 30_000L))
    }

    @Test
    fun `a breath is not a take`() {
        assertFalse(VoiceMemo.isKeepable(VoiceMemo.MIN_TAKE_MS - 1L))
        assertTrue(VoiceMemo.isKeepable(VoiceMemo.MIN_TAKE_MS))
        assertTrue(VoiceMemo.isKeepable(VoiceMemo.MAX_TAKE_MS))
    }

    /**
     * 文件名按条目 id 定死，且从一开始就是最终位置上的名字。
     *
     * 整条删除级联只有一个分支，靠的就是这一点：删条目时不需要先读 `audioPath` 就知道该删哪个
     * 文件，而「收下了没有」只由 `audioPath` 说，不由文件名说。
     */
    @Test
    fun `the file name is derived from the entry id`() {
        assertEquals("a-e1.m4a", VoiceMemo.fileName("e1"))
        assertEquals(VoiceMemo.fileName("e1"), VoiceMemo.fileName("e1"))
        assertFalse(VoiceMemo.fileName("e1") == VoiceMemo.fileName("e2"))
    }
}
