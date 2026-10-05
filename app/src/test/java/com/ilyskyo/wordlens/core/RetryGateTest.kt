// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 冷却重试门。
 *
 * 它要防的是容器里那个 `detector by lazy`：第一次初始化失败之后，lazy 把结果永久缓存，
 * 于是「一次瞬时故障」升级成「整个进程再也没有词片」，而且日志只有一行。时间全靠调用方
 * 传入，所以这里用整数毫秒把每种走法钉死，不需要 sleep。
 */
class RetryGateTest {

    @Test
    fun `the first attempt is always allowed`() {
        val gate = RetryGate(cooldownMs = 5_000)
        assertTrue(gate.allow(0L))
        assertEquals(0, gate.failures)
        // 从没失败过时，任何时间点都放行——门不会「自己关上」。
        assertTrue(gate.allow(123_456L))
        assertEquals(0L, gate.retryInMs(123_456L))
    }

    @Test
    fun `a failure blocks the window and then opens again`() {
        val gate = RetryGate(5_000)
        assertTrue(gate.allow(1_000L))
        gate.recordFailure(1_000L)
        assertFalse(gate.allow(1_000L))
        assertFalse(gate.allow(5_999L))
        // 边界含在内：窗口终点那一刻就算到点。
        assertTrue(gate.allow(6_000L))
        assertEquals(1, gate.failures)
    }

    /** 没有 reset，一次瞬时失败之后成功也永远洗不掉冷却——那是「永久放弃」换了个写法。 */
    @Test
    fun `reset opens the gate immediately`() {
        val gate = RetryGate(60_000)
        gate.recordFailure(1_000L)
        assertFalse(gate.allow(2_000L))
        gate.reset()
        assertTrue(gate.allow(2_000L))
        // reset 清的是冷却状态，不是历史：失败次数留着给日志报「这是第几次重建」。
        assertEquals(1, gate.failures)
    }

    @Test
    fun `repeated failures push the window forward`() {
        val gate = RetryGate(5_000)
        gate.recordFailure(1_000L)
        assertTrue(gate.allow(6_000L))
        gate.recordFailure(6_000L)
        assertFalse(gate.allow(6_000L))
        assertFalse(gate.allow(10_999L))
        assertTrue(gate.allow(11_000L))
        assertEquals(2, gate.failures)
    }

    /**
     * 时钟倒退（用户改系统时间、NTP 校时、休眠后换时钟源）不能把门永久关死。
     * 语义是「倒退之后最多再等一个完整窗口」，而不是「等旧时钟算出来的那个远未来」。
     */
    @Test
    fun `a backwards clock cannot block forever`() {
        val gate = RetryGate(1_000)
        gate.recordFailure(10_000L)
        assertFalse(gate.allow(9_000L))
        // 终点被拉回 9_000 + 1_000：再过 1 秒就放行，不是原来的 11_000 那种旧锚点。
        assertTrue(gate.allow(10_000L))

        val years = RetryGate(1_000)
        years.recordFailure(1_700_000_000_000L)
        assertFalse(years.allow(1_000L))
        assertTrue("校时跳回过去之后最多等一个窗口", years.allow(2_000L))
    }

    @Test
    fun `retryInMs reports the remaining wait`() {
        val gate = RetryGate(5_000)
        assertEquals(0L, gate.retryInMs(0L))
        gate.recordFailure(1_000L)
        assertEquals(5_000L, gate.retryInMs(1_000L))
        assertEquals(3_000L, gate.retryInMs(3_000L))
        assertEquals(0L, gate.retryInMs(6_000L))
        gate.reset()
        assertEquals(0L, gate.retryInMs(0L))
    }

    /** 冷却长度被算成负数时按 0 处理，否则「现在能试吗」恒为真，门形同虚设。 */
    @Test
    fun `a negative cooldown never blocks`() {
        val gate = RetryGate(-1_000)
        assertEquals(0L, gate.cooldownMs)
        gate.recordFailure(500L)
        assertTrue(gate.allow(500L))
        assertTrue(gate.allow(501L))
    }

    /** 零窗口 = 下一次马上就能试：调用方想「每帧重试」时用这个值，而不是绕过门。 */
    @Test
    fun `a zero cooldown blocks nothing`() {
        val gate = RetryGate(0)
        assertTrue(gate.allow(100L))
        gate.recordFailure(100L)
        assertTrue(gate.allow(100L))
        assertEquals(1, gate.failures)
    }

    /** 极端时间戳不能把终点加溢出成负数（那会让门永远敞开、重试变成风暴）。 */
    @Test
    fun `the deadline cannot overflow into the past`() {
        val gate = RetryGate(30_000)
        gate.recordFailure(Long.MAX_VALUE - 10)
        assertFalse(gate.allow(Long.MAX_VALUE - 10))
        assertTrue(gate.allow(Long.MAX_VALUE))
    }
}
