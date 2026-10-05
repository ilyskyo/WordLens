// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision.camera

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 「这张照片属于哪一天」的判定，纯函数。
 *
 * ## 为什么导入的照片不能按「现在」算
 *
 * 日记记的是**那一刻**。相册里那张上个月的照片如果按导入时刻写成今天，时间轴就在撒谎：
 * 用户会看到「今天」多出一张根本没发生过的照片，而那条属于上个月记录则永远回不去了。
 * 排序、日历筛选、`dayKey` 全都吃 `takenAt` 这一个数，所以它必须在入口处就取对。
 *
 * ## 优先级：EXIF 拍摄时刻 → 相册里那一项的修改时刻 → 现在
 *
 * EXIF 是相机亲手写下的一手记录，而文件修改时间常常是**二手**的：下载、转发、备份恢复
 * 都会把 mtime 刷成「拿到这个文件的那一刻」，用它写日记会把一张三年前的照片记成今天。
 * 所以能做一手判断就不用二手。这里刻意不再加「看起来不合理就用 mtime 覆盖」这种二次
 * 猜测——判断「不合理」靠的是我们自己的日历假设，而跨时区拍摄、相机时钟没设对这类情况
 * 下，被覆盖的那个反而是真相。
 *
 * ## 为什么这段必须能 JVM 测
 *
 * 它是典型的边界逻辑：标签缺失、格式写坏（厂商五花八门）、字符串里**没有时区**。
 * 每一条都只影响一个数字，界面上没有任何异常——表现是某天的记录静默地归错了日期。
 * 真机上验证一次要重新拍一张、传进相册、再挑两次，而在纯函数上这些只是一行断言。
 */
object PhotoTiming {

    /**
     * EXIF `DateTimeOriginal` 的写法：`2026:10:05 14:23:07`。
     *
     * 两个要点：**没有时区**（按相机当地墙上时间写的，所以要用系统当前时区解释——
     * 这是唯一可用的假设，见 [takenAtOf] 的说明），也**没有亚秒**（亚秒在
     * `SubSecTimeOriginal` 里另存，对「哪一天」没有意义，不取）。
     * 位数是固定的：`MM`/`dd`/`HH` 各两位，写成 `2026:1:5 4:23:07` 的那种解析失败并落到下一档。
     */
    private val exifFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    /**
     * @param exifDateTimeOriginal EXIF 里那串墙上时间；null 或空表示没有。
     * @param lastModifiedMs 相册里那一项的修改时刻（毫秒）；0 表示拿不到。
     * @param nowMs 本次导入的时刻，最后的兜底。
     * @param zone 用哪个时区解释那串没有时区的墙上时间。默认系统时区：
     *   一张在国外拍的照片被换算成本地时刻，误差最多一天，而**日期**归属在绝大多数情况下
     *   仍然正确；比它更糟的选择是硬套 UTC，那会让东亚的照片全体早 8 小时、夜里拍的记到前一天。
     * @return 毫秒 epoch。
     */
    fun takenAtOf(
        exifDateTimeOriginal: String?,
        lastModifiedMs: Long,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        fromExif(exifDateTimeOriginal, zone)?.let { return it }
        if (lastModifiedMs > 0L) return lastModifiedMs
        return nowMs
    }

    /**
     * 解析那串墙上时间。任何不合格式都返回 null 让调用方往下一档走，而不是抛：
     * 相机厂商把这一行写坏的概率不低（全零、少一段、日期用了横杠），
     * 而一次导入因为日期格式读不出来就整张失败是最不该有的结果。
     *
     * 也不做「范围合理性」校验：EXIF 写着 1980 年，多半是那台相机的时钟没设过，
     * 但那仍是它当时**唯一写下**的时刻；把它悄悄挪到今天等于伪造现场。
     */
    private fun fromExif(raw: String?, zone: ZoneId): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching { LocalDateTime.parse(text, exifFormat).atZone(zone).toInstant().toEpochMilli() }
            .getOrNull()
    }

    /**
     * 把内容 Provider 给的时刻统一成毫秒。
     *
     * 需要它是因为两列的单位不一样：MediaStore 的 `DATE_MODIFIED` 是**秒**，
     * DocumentsContract 的 `COLUMN_LAST_MODIFIED` 是**毫秒**，而 Photo Picker 在
     * 不同 Android 版本、不同相册应用之间两边都会遇到（给错单位的第三方 Provider 也存在）。
     *
     * 靠数量级区分而不是靠调用点记住哪一列是什么：秒计数在 21 世纪是 1.7e9 量级，
     * 要到公元 5138 年才越过 [SECONDS_BELOW]；毫秒计数从 2001 年起就在 1e12 量级。
     * 两头都有上万年余量。
     *
     * 非正数一律返回 0（「不知道」）：Provider 在没有值时给 0 或 -1，那是哨兵不是时刻。
     */
    fun toMillis(raw: Long): Long {
        if (raw <= 0L) return 0L
        return if (raw < SECONDS_BELOW) raw * 1000L else raw
    }

    /** 秒与毫秒的分界，见 [toMillis]。 */
    private const val SECONDS_BELOW = 100_000_000_000L
}
