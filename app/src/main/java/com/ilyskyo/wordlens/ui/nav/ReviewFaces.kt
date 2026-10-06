// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.nav

import com.ilyskyo.wordlens.data.model.StudyDirection

/**
 * 当前方向下这张词卡的两面：正面永远有字，背面可能没有释义。
 *
 * 抽成函数只为一件事：**队列的闸门**和**卡片的内容**必须用同一个算法。两处各写一遍
 * `when (direction)`，早晚会有一处改了另一处没改，那时的表现是「被跳过的卡」与
 * 「两面同字的卡」不再是同一批——这种错既不会被测出来，也不会在日志里留下痕迹。
 */
internal fun facesOf(headword: String, gloss: String?, direction: StudyDirection): Pair<String, String?> =
    when (direction) {
        StudyDirection.RECOGNIZE -> headword to gloss
        StudyDirection.RECALL -> (gloss ?: headword) to headword
    }

/**
 * 这张卡在当前的方向和语言下，是不是两面同字。
 *
 * 同字的来源不是用户选错了一个词，而是一段**已经被修好的设置**：母语与目标语曾被允许选成
 * 同一门，那时铸下的卡，词头写的就是母语。修好方向不会回头改卡上的字，所以只能在这里挡住。
 *
 * 两种「没有释义」要分开看，它们是不同的东西：
 *
 * - 认词方向（RECOGNIZE）下背面为 null：这只是一张还缺翻译的卡，正面照样能认，背面可以用
 *   例句和照片来答。把它踢出队列等于偷走一次合法的复习，所以**不算塌**。
 * - 回忆方向（RECALL）下背面永远有字（词头），而正面缺释义时会**退化成词头本身**——
 *   于是两面必然同字。这一条**算塌**，因为这张卡在这个方向下确实什么都没说。
 *
 * 比较时两边都 `trim()` 并忽略大小写：`Cup` 与 `cup`、末尾多一个全角空格的「杯子 」与「杯子」
 * 是同一个词，把它们当成两张不同的卡是在跟用户抬杠。
 */
internal fun facesCollapse(headword: String, gloss: String?, direction: StudyDirection): Boolean {
    val (front, back) = facesOf(headword, gloss, direction)
    return back != null && front.trim().equals(back.trim(), ignoreCase = true)
}
