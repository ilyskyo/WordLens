// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision.camera

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 导入照片的时间判定。
 *
 * 这几条断言钉的是「哪一天的记录」这件事：每一条都只影响一个数字，界面上没有任何异常，
 * 表现是某天的记录静默地归错了日期——那种问题在真机上最难查（要重新造一张带坏 EXIF 的
 * 照片、传进相册、再挑两次），在这里只是一行。
 */
class PhotoTimingTest {

    /** 固定时区，免得测试结果跟着跑测试的那台机器的系统时区变。 */
    private val tokyo: ZoneId = ZoneOffset.ofHours(9)
    private val shanghai: ZoneId = ZoneOffset.ofHours(8)

    /** 2026:10:05 14:23:07 在 UTC+9 那一刻的毫秒数（=2026-10-05 05:23:07 UTC）。 */
    private val tokyoMoment = 1_791_177_787_000L

    @Test
    fun `EXIF wins over the file's modified time`() {
        // mtime 是下载或备份恢复时随手刷上的，比相机写下的一手记录晚了好几天。
        val taken = PhotoTiming.takenAtOf(
            exifDateTimeOriginal = "2026:10:05 14:23:07",
            lastModifiedMs = tokyoMoment + 400_000L,
            nowMs = tokyoMoment + 9_000_000L,
            zone = tokyo,
        )
        assertEquals(tokyoMoment, taken)
    }

    /** 那串墙上时间**没有时区**：它按哪个时区解释，直接决定这条记录落在哪一天。 */
    @Test
    fun `the same wall time in two zones differs by exactly one hour`() {
        val east = PhotoTiming.takenAtOf("2026:10:05 14:23:07", 0L, 0L, tokyo)
        val west = PhotoTiming.takenAtOf("2026:10:05 14:23:07", 0L, 0L, shanghai)
        assertEquals(tokyoMoment, east)
        // 东九区比东八区早一小时：同一串墙上时间，在东八区的假设下发生得更晚。
        assertEquals(3_600_000L, west - east)
    }

    /**
     * 时区假设能把一张夜里的照片挪到**另一天**去。
     *
     * 这条是「为什么 zone 要暴露成参数、为什么默认系统时区是一个必须写进注释的取舍」的
     * 证据：相机在东八区写下 23:30，手机此刻显示在东九区，那一条记录就会长到 10 月 6 日头上。
     */
    @Test
    fun `a late-night shot crosses the day boundary with the zone assumption`() {
        val late = PhotoTiming.takenAtOf("2026:10:05 23:30:00", 0L, 0L, shanghai)
        assertEquals("2026-10-05", dayOf(late, shanghai))
        assertEquals("2026-10-06", dayOf(late, tokyo))
    }

    @Test
    fun `missing or blank EXIF falls through to modified time`() {
        assertEquals(1_700_000_000_000L, PhotoTiming.takenAtOf(null, 1_700_000_000_000L, 9L, tokyo))
        assertEquals(1_700_000_000_000L, PhotoTiming.takenAtOf("   ", 1_700_000_000_000L, 9L, tokyo))
    }

    /**
     * 厂商写坏这一行的几种真实形态：全零、用横杠的日期、少了秒、位数不足。
     * 每一种都必须安静地落到下一档，而不是抛——一次导入因为日期格式读不出来就整个失败，
     * 是最不该有的结果。
     */
    @Test
    fun `malformed EXIF falls through instead of throwing`() {
        val broken = listOf(
            "0000:00:00 00:00:00",
            "2026-10-05 14:23:07",
            "2026:10:05",
            "2026:10:05 14:23",
            "2026:1:5 14:23:07",
            "   2026:10:05 14:23:07 GMT+09:00",
            "not a date at all",
        )
        broken.forEach { raw ->
            assertEquals("EXIF「$raw」该落到修改时间", 42L, PhotoTiming.takenAtOf(raw, 42L, 999L, tokyo))
        }
    }

    @Test
    fun `without EXIF and without modified time it is now`() {
        assertEquals(999L, PhotoTiming.takenAtOf(null, 0L, 999L, tokyo))
        // 修改时间是非正数同样算「不知道」：Provider 在没有值时给 0 或 -1，那是哨兵不是时刻。
        assertEquals(999L, PhotoTiming.takenAtOf(null, -1L, 999L, tokyo))
    }

    @Test
    fun `a photo from years back stays on its own day, not on today`() {
        val now = 1_893_456_000_000L // 2030-01-01
        val taken = PhotoTiming.takenAtOf("2019:03:02 08:00:00", now, now, tokyo)
        assertEquals("2019-03-02", dayOf(taken, tokyo))
    }

    @Test
    fun `seconds and milliseconds are told apart by magnitude`() {
        // MediaStore 的 DATE_MODIFIED 是秒，DocumentsContract 的 COLUMN_LAST_MODIFIED 是毫秒。
        // 同一个时刻，两种单位，读出来的必须是同一个瞬间。
        assertEquals(tokyoMoment, PhotoTiming.toMillis(tokyoMoment / 1000L))
        assertEquals(tokyoMoment, PhotoTiming.toMillis(tokyoMoment))
        assertEquals(0L, PhotoTiming.toMillis(0L))
        assertEquals(0L, PhotoTiming.toMillis(-1L))
    }

    /** 分界取整：刚好越过那一档就不再多乘一千倍——判据是数量级，不是列名。 */
    @Test
    fun `the unit boundary does not double-count`() {
        assertEquals(99_999_999_999_000L, PhotoTiming.toMillis(99_999_999_999L))
        assertEquals(100_000_000_000L, PhotoTiming.toMillis(100_000_000_000L))
    }

    private fun dayOf(epochMs: Long, zone: ZoneId): String =
        java.time.Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toString()
}
