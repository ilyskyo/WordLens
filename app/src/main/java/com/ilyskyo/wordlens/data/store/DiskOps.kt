// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.data.store

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 磁盘操作的接缝。
 *
 * ## 为什么要抽这一层
 *
 * 「文件读得到、但改名失败」这种组合，在真机上出现过（跨文件系统、国产 ROM 的存储拦截器、
 * 瞬时 IO 抖动），但在单测里造不出来 —— 而恰恰是这几种组合决定了用户的数据是被保住还是被清空。
 * 没有这个接口，那些分支就只能靠上线后碰运气。
 *
 * 所有方法都不抛：把失败值化，强迫调用方处理，而不是让异常沿着协程冒到看不见的地方。
 */
interface DiskOps {

    fun exists(file: File): Boolean

    /** 读全文。失败时抛（由调用方分类：IO 与解析失败的处理方式完全不同）。 */
    fun readText(file: File): String

    /** 原子改名；跨卷或被拦截时返回 false。 */
    fun rename(from: File, to: File): Boolean

    /** 保复制一份（改名不动时才有的退路），失败返回 false。 */
    fun copy(from: File, to: File): Boolean

    /** 写一份并落盘。 */
    fun write(target: File, text: String): WriteResult
}

/** 落盘的结果。失败带上原因，否则日志里只剩「写失败了」这种没用的话。 */
sealed interface WriteResult {
    /** 成功，live 文件已经是新内容。 */
    data object Written : WriteResult

    /** 失败，**live 文件保持原样**（这是这条接口的核心承诺：要么写成功，要么不动旧数据）。 */
    data class Failed(val cause: Throwable) : WriteResult
}

/** 真实文件系统实现。 */
object RealDiskOps : DiskOps {

    override fun exists(file: File): Boolean = file.isFile

    override fun readText(file: File): String = file.readText(Charsets.UTF_8)

    override fun rename(from: File, to: File): Boolean = from.renameTo(to)

    override fun copy(from: File, to: File): Boolean = runCatching {
        from.copyTo(to, overwrite = true)
    }.isSuccess

    /**
     * 先写 `*.tmp` 并 `fd.sync()`，再改名到位。
     *
     * 改名失败时**不截断重写目标文件**——那正是「原子写」三个字最容易被悄悄破坏的地方：
     * 写到一半进程被杀，用户的整个牌组就成了一段残缺 JSON。改成先把旧文件挪成 `.bak`，
     * 再试一次改名；如果挪不动或挪完仍放不回去，就把 `.bak` 还原并报告失败。
     */
    override fun write(target: File, text: String): WriteResult {
        val dir = target.parentFile
        val tmp = File(dir, "${target.name}.tmp")
        val written = runCatching {
            dir?.mkdirs()
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
        }
        if (written.isFailure) {
            tmp.delete()
            return WriteResult.Failed(written.exceptionOrNull() ?: IOException("write to ${tmp.name} failed"))
        }
        if (tmp.renameTo(target)) return WriteResult.Written

        // 改名不动：先把上一份好数据挪开，再试一次；两步都不行就还原。
        val backup = File(dir, "${target.name}.bak")
        val hadLive = target.exists()
        if (hadLive && !target.renameTo(backup)) {
            tmp.delete()
            return WriteResult.Failed(IOException("rename ${tmp.name} -> ${target.name} failed and the live file could not be moved aside"))
        }
        if (tmp.renameTo(target)) return WriteResult.Written

        if (hadLive) backup.renameTo(target)   // 尽力还原，别把用户的数据留在 `.bak` 里没人找得到
        tmp.delete()
        return WriteResult.Failed(IOException("rename ${tmp.name} -> ${target.name} failed after moving the live file aside"))
    }
}
