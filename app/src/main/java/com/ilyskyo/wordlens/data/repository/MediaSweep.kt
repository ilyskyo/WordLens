// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.data.repository

import com.ilyskyo.wordlens.data.model.Entry
import com.ilyskyo.wordlens.data.model.WordCard

/**
 * 「磁盘上有、两份文档都不引用」的媒体文件该怎么挑出来。
 *
 * ## 为什么会存在没人引用的照片
 *
 * 快门按下去的那一刻 `PhotoEntryPipeline` 就把照片（连贴纸副本）写进了私有目录，而让日记
 * **开始引用**它是用户按「保存」之后的事。中间那一步用户可以不走：左上角的关闭键、系统返回、
 * 或者进程被系统回收。三条路留下来的都是同一件东西——一张用户以为自己没留下的照片。
 * 对一本把「照片永不外传、只存你留下的」当立场的日记来说，磁盘上多一张没人引用的照片不是
 * 几个 MB 的事，是那句话变得不真。
 *
 * ## 为什么判据必须同时看两份文档
 *
 * 名字跨两个文件被引用：条目引用自己的照片与 `st-<entryId>.png` 那份副本，而**词卡**也可能
 * 引用同名的一张——`deleteEntry` 之所以把贴纸名交回调用方而不是直接删，就是为了让活得比日记久的
 * 卡片继续用着它。只看日记那一份，就会把「一条已删记录留下的、但词卡还在用的贴纸」删掉。
 *
 * ## 为什么还要一道时间门槛
 *
 * 冷启动时「分享到见词」与相册导入走的是同一条流水线：**先写文件、后写引用**。清扫若在它中间
 * 插进去，删掉的是用户刚分享、正准备保存的那一张——把一个隐私修复变成一次数据丢失。
 * 用「至少老于一小时」换掉对先后顺序的推理，是唯一同时挡得住进程被杀与这次竞态的做法：
 * 真正没人要的孤儿文件会等到下一次启动之后很久仍然在那里，而正在进行的那一张刚刚被写过。
 */
object MediaSweep {

    /** 一个候选文件：名字与它的最后修改时间。抽出来才能在不碰磁盘的情况下断言这套判据。 */
    data class Candidate(val name: String, val lastModified: Long)

    /**
     * 低于这个年龄的文件一律不当孤儿。
     *
     * 一小时和「快门到保存之间最多能隔多久」无关——那只有几秒——它对齐的是**导入**那一条：
     * 从相册挑一张 12MP 的原图，解码 + 检测 + 抠图在低端机上会走到几十秒，期间文件刚刚写过、
     * 引用还不存在。留一个数量级的余量，而不是刚好卡在最长的那一次。
     */
    const val MIN_AGE_MILLIS: Long = 60L * 60L * 1000L

    /**
     * 两份文档一起说「哪些名字还被引用着」。
     *
     * 只收集非空名字：`EntryObject.stickerPath` 与 `WordCard.stickerPath` 都是可空的，
     * 而一个空串进保留集会成为一个谁都可能撞上的通配——名字要精确，宁可少留也别错留。
     */
    fun referenced(entries: List<Entry>, cards: List<WordCard>): Set<String> = buildSet {
        entries.forEach { entry ->
            add(entry.photoPath)
            entry.objects.forEach { addNonNull(it.stickerPath) }
        }
        cards.forEach { addNonNull(it.stickerPath) }
    }

    /** 该删的那些：不在保留集里，**且**老于 [MIN_AGE_MILLIS]。 */
    fun stale(
        listing: List<Candidate>,
        referenced: Set<String>,
        nowMillis: Long,
    ): List<String> = listing
        .filter { it.name !in referenced && nowMillis - it.lastModified >= MIN_AGE_MILLIS }
        .map { it.name }
        .sorted()

    private fun MutableSet<String>.addNonNull(value: String?) {
        if (!value.isNullOrEmpty()) add(value)
    }
}
