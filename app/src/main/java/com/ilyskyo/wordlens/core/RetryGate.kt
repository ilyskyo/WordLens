// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.core

/** 从未失败过的哨兵：任何 nowMs 都大于它，所以第一次总是放行。 */
private const val NEVER = Long.MIN_VALUE

/**
 * 带冷却窗口的重试门：允许试一次，失败后一段时间内不再试。
 *
 * ## 为什么需要它
 *
 * 容器的 `detector by lazy` 把「第一次初始化」的结果永久缓存。Kotlin 的 lazy 对异常同样只
 * 算一次，而这里更糟：`EfficientDetector.create` 内部 catch 了一切、失败时返回 null，
 * 于是 lazy 缓存的是「一个合法的 null」。一次瞬时的加载失败（模型文件被并发写坏、
 * 低端机首帧内存吃紧）就让整个进程生命周期都不会再出现词片，日志里只留下一行。
 * 这个门把「缓存结果」换成「缓存重试时机」：失败只是等一会儿再试，不是永远放弃。
 *
 * ## 为什么必须有 reset
 *
 * 没有它，一次瞬时失败之后门就永远停在冷却状态：调用方拿到成功结果却没人把门打开，
 * 下一次失败要等冷却窗口自然结束才试——更糟的是「成功之后仍然被当成刚失败过」。
 * **成功必须显式把门清零**，那才是「瞬时失败」的语义。
 *
 * ## 时间源
 *
 * [allow] / [recordFailure] 的毫秒都由调用方提供（不要在这里读时钟，否则单测只能靠 sleep）。
 * 首选 `SystemClock.elapsedRealtime()`：它单调，不会被用户改系统时间或 NTP 校时打断。
 * 墙上时钟真的倒退了也不会把门关死，见 [allow]。
 *
 * 本类**不加锁**：只有两个字段的账目，加锁不如让调用方把自己的「问门 + 动作 + 记录」整段
 * 放进同一个 `@Synchronized` 里——那样判断与动作是一个原子决定，也不会出现两个线程
 * 同时越过门各建一个检测器。
 */
class RetryGate(cooldownMs: Long) {

    /**
     * 冷却窗口长度。负数夹到 0：窗口通常来自配置或常量运算，一个负值会让
     * 「现在能试吗」永远为真，等于没有门——重试退化成每帧一次的风暴。
     */
    val cooldownMs: Long = cooldownMs.coerceAtLeast(0L)

    private var openAtMs = NEVER
    private var lastSeenMs = NEVER

    /** 累计失败次数，**不随 [reset] 清零**：它是要报进日志的历史，不是当前状态。 */
    var failures: Int = 0
        private set

    /**
     * 现在能不能试一次。到点即放行：`nowMs` 正好等于冷却终点时返回 true——
     * 窗口的语义是「至少等这么久」，边界含在内更好测也更符合直觉。
     */
    fun allow(nowMs: Long): Boolean {
        // 时钟倒退（用户手动改时间、NTP 校时、休眠后墙上时钟换源）：把冷却终点拉回
        // 「从现在起最多再等一个完整窗口」。不这么做的话终点会停在旧时钟算出的远未来，
        // 一次跳回几年前的校时就能让检测器永远不再重建。
        if (nowMs < lastSeenMs) {
            val reanchored = deadlineAfter(nowMs)
            if (openAtMs > reanchored) openAtMs = reanchored
        }
        lastSeenMs = nowMs
        return nowMs >= openAtMs
    }

    /** 这次尝试失败了：从这一刻起重新计一个冷却窗口。 */
    fun recordFailure(nowMs: Long) {
        failures++
        lastSeenMs = nowMs
        openAtMs = deadlineAfter(nowMs)
    }

    /** 尝试成功了：冷却状态清零，下一次随时可以再试。 */
    fun reset() {
        openAtMs = NEVER
        lastSeenMs = NEVER
    }

    /** 距离下一次可重试还有多少毫秒，0 表示现在就能试。给「还要等多久」那行日志用。 */
    fun retryInMs(nowMs: Long): Long = if (nowMs >= openAtMs) 0L else openAtMs - nowMs

    /**
     * 饱和加：调用方若给极端时间戳，`nowMs + cooldownMs` 会溢出成负数，
     * 而负的终点等于门永远敞开。宁可等到天荒地老，也不要重试风暴。
     */
    private fun deadlineAfter(nowMs: Long): Long =
        if (nowMs > Long.MAX_VALUE - cooldownMs) Long.MAX_VALUE else nowMs + cooldownMs
}
