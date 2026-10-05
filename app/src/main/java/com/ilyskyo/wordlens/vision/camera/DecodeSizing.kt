// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision.camera

/**
 * 「一张源图该按几分之一解码、解完再缩到多大」的两步算术，抽成纯函数。
 *
 * ## 为什么要单独抽出来
 *
 * `BitmapFactory.Options.inSampleSize` 内部只会往下取到 2 的幂：源图长边 2560、目标 1280 时
 * 它能给到 2（1280），但源图长边 2016、目标 1024 时它只能给 1（2016）——
 * 于是解码结果最长可以顶到目标的将近 2 倍，按面积算就是将近 4 倍的内存。时间轴一次要解
 * 几十张照片，这个误差直接决定会不会在滚动时 OOM。
 *
 * 正确做法是两步：先用 `inSampleSize` 粗降到「不小于目标的最小整数倍」，再用
 * [scaledSize] 精确缩放到长边正好等于目标。第二步需要第一步的输出尺寸作为输入，两者必须
 * 一起算才对，所以放在同一个文件里，并保证只吃 Int、不碰任何 Android 类型——
 * 真机上验证一次要几秒且看不到中间值，而这里每个数字都能在手算的对拍表上钉住。
 */
object DecodeSizing {

    /**
     * 第一步：`inSampleSize`，返回不小于 1 的 2 的幂。
     *
     * 语义是「最大的那个 2 的幂 s，使得 `max(srcWidth, srcHeight) / s >= maxPx` 仍成立」，
     * 即降采样后长边**不短于**目标。这条下界是它的正确性关键：一旦短于目标，第二步就变成
     * 放大，放大出来的软图会毁掉词框的贴合度，还白付一次缩放开销。
     *
     * 因此只要源图长边不小于 maxPx，它就保证 `max(srcWidth, srcHeight) / s` 落在
     * `[maxPx, 2 * maxPx)`：最多比目标大一倍，平均浪费一档内存——这就是为什么必须配合
     * [scaledSize] 用。源图本来就更小时 s 是 1，原图尺寸就是目的地。
     *
     * 退化输入（尺寸非正、`maxPx <= 0`）返回 1 而不是抛：调用方在解码失败路径上，
     * 一个来自参数校验的异常会把真正的原因（文件坏了 / 内存不够）盖掉。
     */
    fun inSampleSizeFor(srcWidth: Int, srcHeight: Int, maxPx: Int): Int {
        if (srcWidth <= 0 || srcHeight <= 0 || maxPx <= 0) return 1
        val longest = maxOf(srcWidth, srcHeight)
        var sample = 1
        // 用 Long 比较：条件是「再降一档之后长边仍不短于目标」，写成 Int 的 `maxPx * 2`
        // 在 4K 以上源图配大 maxPx 时会溢出成负数，那样循环立刻退出、等于没降采样。
        while (longest.toLong() / sample >= maxPx.toLong() * 2) {
            // 4 是安全余量：保证下一句 `sample *= 2` 不会翻成负数（负数会让循环永不退出）。
            if (sample > Int.MAX_VALUE / 4) break
            sample *= 2
        }
        return sample
    }

    /**
     * 第二步：解码之后要**精确缩到**的尺寸，长边恰为 [maxPx]（源图本来就更小则原样返回，
     * 绝不放大）。保持宽高比，两个分量都至少 1。
     *
     * 为什么长边要「恰好」而不是「不超过」：时间轴与详情页的布局是按这个尺寸算内存预算的，
     * 差一档就是几 MB 的差别。
     *
     * 退化输入返回 `1 to 1`：与 [inSampleSizeFor] 同一个理由，宁可给一张难看的图，
     * 也不在解码路径上抛。
     */
    fun scaledSize(srcWidth: Int, srcHeight: Int, maxPx: Int): Pair<Int, Int> {
        if (srcWidth <= 0 || srcHeight <= 0 || maxPx <= 0) return 1 to 1
        val longest = maxOf(srcWidth, srcHeight)
        if (longest <= maxPx) return srcWidth to srcHeight
        // 向下取整而不是四舍五入：长边必须还原成恰好 maxPx（longest * maxPx / longest 在整数
        // 除法下是精确的），多半个像素就是超预算。短边因此最多少 1 像素，肉眼不可见。
        // 乘法先转 Long：4K 源图配大 maxPx 时 srcWidth * maxPx 会溢出 Int。
        // 下界 1 而不是 0：极扁源图（4000×10 缩到长边 500）的短边按比例是 1.25 → 0，
        // 而 0 宽或 0 高的位图会让 createBitmap 抛异常。
        val width = (srcWidth.toLong() * maxPx / longest).toInt().coerceAtLeast(1)
        val height = (srcHeight.toLong() * maxPx / longest).toInt().coerceAtLeast(1)
        return width to height
    }
}
