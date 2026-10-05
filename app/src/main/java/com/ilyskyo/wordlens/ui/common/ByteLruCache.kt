// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.common

/**
 * 按**字节**上限的 LRU 缓存。
 *
 * ## 为什么不按条数
 *
 * 这里缓存的是解码好的位图，而一张 768×576 的 ARGB_8888 是 1.7 MB、一张 1440×1080 的是
 * 6 MB——按「最多 64 条」限量的话，最坏情况是三百多 MB，进程直接被系统杀掉；而按条数缩到
 * 安全值，正常照片又装不下几页时间轴，滚动时反复重新解码同样掉帧。只有按字节算，上限才是
 * 一个能照着堆预算填写的数字。
 *
 * ## 淘汰时只丢引用，绝不 recycle()
 *
 * 这个缓存里放的是 Compose **正在绘制**的 Bitmap：组合线程可能刚把它取出来、正交给
 * `Image` / `DrawBitmap` 用。此时 recycle 会让另一根线程在 native 层踩到已释放的像素，
 * 表现为随机的 SIGSEGV 或「Canvas: trying to use a recycled bitmap」，比 OOM 更难查。
 * ARGB_8888 的像素在 native 堆上、由 GC 连着 `Bitmap` 的 finalizer 管理，所以丢掉引用
 * 之后自然回收，这一层不需要（也不允许）任何显式释放。调用方若要主动释放，必须自己确认
 * 没有任何线程还持有它——那不在这个类的职责里，全类找不到一个 recycle 正是这个约定的
 * 可检查形式。
 *
 * ## 为什么是线程安全的
 *
 * 时间轴在 Default 线程解码、主线程读取，`sizeBytes` 这本账交错一次就是一笔永不归还的
 * 泄漏（账目虚高 → 疯狂淘汰 → 每次滚动都重解）。所有公开方法都在实例锁上串行。
 *
 * 纯 JVM：不 import 任何 Android 类型，因此淘汰顺序、账目、边界都能在单测里逐条验证。
 */
class ByteLruCache<K : Any, V>(
    maxBytes: Long,
    private val sizeOf: (V) -> Int,
) {

    /**
     * 字节上限。负数夹到 0：预算是调用方算出来的（条目数 × 单张估算），
     * 一次溢出就会变成负数，而负上限会让「还能不能再塞」的判据反向，缓存表现得像坏了。
     *
     * 上限为 0 时并非「什么都不缓存」：0 字节的条目仍然会存——调用方用它来记住
     * 「这个文件解不出来」的 null 占位，那不占内存，却能挡住每帧重解同一个坏文件。
     * 代价是这类条目按定义不受上限约束，所以有界性完全靠 [sizeOf] 如实计费：
     * 真实占用报 0，这个缓存就会退化成无界的 Map。
     */
    val maxBytes: Long = maxBytes.coerceAtLeast(0L)

    /** 一条缓存项和它当初计入账目的字节数：记在项上，淘汰时才知道该扣多少。 */
    private class Item<T>(val value: T, val bytes: Int)

    // accessOrder = true：每次访问把该项挪到链尾，链头因此始终是「最久没被用过」的那个。
    private val entries = LinkedHashMap<K, Item<V>>(16, 0.75f, true)

    /** 当前计入账目的总字节。 */
    var sizeBytes: Long = 0L
        private set

    /** 被淘汰的条目数，供测试与诊断：滚动时它持续增长而 `sizeBytes` 不降，说明预算给小了。 */
    var evictionCount: Int = 0
        private set

    /** 命中会把该项提到最近使用的位置（靠 LinkedHashMap 的 accessOrder）。 */
    fun get(key: K): V? = synchronized(this) { entries[key]?.value }

    /**
     * 放入一条，按需淘汰最久未用的。
     *
     * @return 是否真的存下了。`false` 表示这一条**自己就超过** [maxBytes]：那种条目存进去会
     *   立刻把自己淘汰掉，等于白花一次记账，还会让 [evictionCount] 虚高得像「缓存正常」。
     *   走这条路时已有条目一个都不动，调用方按「没有缓存」处理即可（位图照旧返回，
     *   只是下次还得重解）。
     */
    fun put(key: K, value: V): Boolean {
        // 负数按 0 记：sizeOf 是调用方给的估算，一旦有负值入账，`sizeBytes > maxBytes`
        // 就永远不成立，上限形同虚设。
        val bytes = sizeOf(value).coerceAtLeast(0).toLong()
        if (bytes > maxBytes) {
            // 同 key 的旧值必须作废：它对应的已经不是这个 value 了，留着就是脏数据。
            remove(key)
            return false
        }
        synchronized(this) {
            // 先扣旧值再入账：同一 key 反复换更大的位图时，不扣就会把账越推越高，
            // 最终淘汰掉一批本不该被淘汰的条目。
            remove(key)
            // sizeOf 返回 Int，所以 bytes 必然在 Int 范围内，这里的 toInt 不会截断。
            entries[key] = Item(value, bytes.toInt())
            sizeBytes += bytes
            // 循环里每轮都确实删掉一项，而能进循环就说明 sizeBytes > maxBytes >= 0，
            // 链上至少有一条（就是刚放进去的这条）可删，因此不存在无限淘汰。
            while (sizeBytes > maxBytes) {
                val eldest = entries.entries.firstOrNull() ?: break
                entries.remove(eldest.key)
                sizeBytes -= eldest.value.bytes
                evictionCount++
            }
        }
        return true
    }

    /** @return 被移除的值；不在缓存里返回 null。移除同样只丢引用。 */
    fun remove(key: K): V? = synchronized(this) {
        val removed = entries.remove(key)
        if (removed != null) sizeBytes -= removed.bytes
        removed?.value
    }

    /** 清空并把账目归零。淘汰计数保留：它是用过的历史，不是当前占用。 */
    fun clear() {
        synchronized(this) {
            entries.clear()
            sizeBytes = 0L
        }
    }
}
