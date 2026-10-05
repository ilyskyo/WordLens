// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.core.reminder

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「下一个提醒时刻」的边界。
 *
 * 这些边界在真机上要等到明天才能看出来，而在 JVM 上是几行断言。最重要的一条是
 * **永远不返回 0 或负数**：用户刚把开关拨上去就收到一条通知，读起来像是被这个 App 抢先了；
 * 而一个负的 initialDelay 交给 WorkManager 是未定义行为。
 */
class ReminderPlanTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val dayMillis = 24L * 60 * 60 * 1000

    @Test
    fun `waits for today when the time has not come yet`() {
        val now = at("2026-10-05T10:00", shanghai)
        assertEquals(10L * 60 * 60 * 1000, ReminderPlan.delayUntilNextOccurrence(now, 20 * 60, shanghai))
    }

    @Test
    fun `rolls to tomorrow when today has already passed the time`() {
        val now = at("2026-10-05T22:00", shanghai)
        assertEquals(22L * 60 * 60 * 1000, ReminderPlan.delayUntilNextOccurrence(now, 20 * 60, shanghai))
    }

    @Test
    fun `exactly at the chosen minute waits a whole day instead of firing now`() {
        val now = at("2026-10-05T20:00", shanghai)
        assertEquals(dayMillis, ReminderPlan.delayUntilNextOccurrence(now, 20 * 60, shanghai))
    }

    @Test
    fun `midnight boundary never produces zero`() {
        assertEquals(60_000L, ReminderPlan.delayUntilNextOccurrence(at("2026-10-05T23:58", shanghai), 23 * 60 + 59, shanghai))
        assertEquals(dayMillis, ReminderPlan.delayUntilNextOccurrence(at("2026-10-05T00:00", shanghai), 0, shanghai))
        // 20:00 之后最近的 00:00 在明天零点，也就是还要等 4 个小时——
        // 「跨过午夜」不等于「再等一天」，这条写错过一次，所以留在这里。
        assertEquals(
            4L * 60 * 60 * 1000,
            ReminderPlan.delayUntilNextOccurrence(at("2026-10-05T20:00", shanghai), 0, shanghai),
        )
    }

    @Test
    fun `out of range minutes clamp instead of scheduling an impossible time`() {
        val now = at("2026-10-05T10:00", shanghai)
        // 1440 是「24:00」，不存在；夹到 23:59。
        assertEquals(13L * 60 * 60 * 1000 + 59 * 60_000L, ReminderPlan.delayUntilNextOccurrence(now, 1440, shanghai))
        // 负数夹到 00:00 → 下一天的零点。
        assertEquals(14L * 60 * 60 * 1000, ReminderPlan.delayUntilNextOccurrence(now, -5, shanghai))
    }

    @Test
    fun `spring forward day still waits a positive amount`() {
        // 2026-03-08 的美国东部：02:00 直接跳到 03:00，本地钟面上没有 02:30 这个时刻。
        val newYork = ZoneId.of("America/New_York")
        val now = at("2026-03-08T01:00", newYork)
        val delay = ReminderPlan.delayUntilNextOccurrence(now, 2 * 60 + 30, newYork)
        assertTrue("必须为正，实际 $delay", delay > 0)
        assertTrue("不该超过一天零一小时，实际 $delay", delay <= dayMillis + 60 * 60_000L)
        // 落点仍然是「现在之后」，这是调度唯一真正需要的性质。
        val landed = LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(now + delay),
            newYork,
        )
        assertTrue("落点 $landed 必须晚于 01:00", landed.isAfter(now.toLocalDateTime(newYork)))
    }

    @Test
    fun `minute of day renders with a padded minute`() {
        assertEquals("20:00", ReminderPlan.formatMinuteOfDay(20 * 60))
        assertEquals("00:00", ReminderPlan.formatMinuteOfDay(0))
        assertEquals("23:59", ReminderPlan.formatMinuteOfDay(1439))
        // 补零很重要：否则 20:05 会显示成「20:5」。
        assertEquals("20:05", ReminderPlan.formatMinuteOfDay(20 * 60 + 5))
        assertEquals("23:59", ReminderPlan.formatMinuteOfDay(9999))
    }

    private fun at(isoLocal: String, zone: ZoneId): Long =
        LocalDateTime.parse(isoLocal).atZone(zone).toInstant().toEpochMilli()

    private fun Long.toLocalDateTime(zone: ZoneId): LocalDateTime =
        LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(this), zone)
}
