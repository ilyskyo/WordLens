// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.data.repository

import com.ilyskyo.wordlens.data.store.WordLensJson
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 把用户挑的 `deck.json` 读成一份文档。读不出来就是 null，不抛。
 *
 * 单独立一个文件、与仓库的写路径分开，是因为这里要面对的输入完全不受我们控制：
 * 可能是别的版本写的、可能是被编辑器改过缩进的、也可能压根是一个 JSON 数组。
 * `WordLensJson` 已经 `ignoreUnknownKeys`，所以跨版本向前兼容是有的；
 * 剩下的失败都该收敛成一句人话，而不是栈。
 *
 * ## 为什么先按字节封顶再解析
 *
 * `decodeFromStream` 名字里有「流」，但它救不了这件事：kotlinx 会把整份文档读成内存里的
 * 对象图，流的「流」只省了那个字符串，不省结构。用户从文件选择器里挑一个 200 MB 的东西
 * （谁没试过把别的什么 json 挑进来），进程就会被系统在解析途中杀掉——没有提示、没有日志，
 * 界面上是「点了导入，App 没了」。那是这一层能造成的最难看的一种失败。
 *
 * 上限按**能装的下最多的真实牌组**定，不按「多大算恶意」定：一张卡的 JSON 大约 300–600 字节，
 * 8 MB 已经是 1.5 万到 2.7 万张卡，而牌组到几千张就已经没人翻得动了。宁可拒绝一个不存在的
 * 超大文件，也不给一次 OOM 留门。
 */
fun decodeDeck(stream: InputStream, maxBytes: Int = MAX_IMPORT_BYTES): DeckDocument? = runCatching {
    val bytes = stream.use { readCapped(it, maxBytes) } ?: return null
    WordLensJson.instance.decodeFromString(DeckDocument.serializer(), bytes.toString(Charsets.UTF_8))
}.getOrNull()

/** 字符串入口：给测试与「先读文本再决定」的调用方用。 */
fun decodeDeckText(text: String): DeckDocument? = runCatching {
    WordLensJson.instance.decodeFromString(DeckDocument.serializer(), text)
}.getOrNull()

/**
 * 读到超过 [limit] 就返回 null，不再往下读。
 *
 * 手写而不是 `InputStream.readNBytes`：后者要 API 32，而这份代码最低跑在 26 上。
 * 逐块读也顺带解决了「长度未知」——content URI 后面的东西经常不给长度。
 */
private fun readCapped(input: InputStream, limit: Int): ByteArray? {
    val out = ByteArrayOutputStream(CAPACITY_HINT.coerceAtMost(limit))
    val chunk = ByteArray(CHUNK_BYTES)
    var total = 0
    while (true) {
        val read = input.read(chunk)
        if (read < 0) return out.toByteArray()
        total += read
        if (total > limit) return null
        out.write(chunk, 0, read)
    }
}

private const val MAX_IMPORT_BYTES = 8 * 1024 * 1024
private const val CHUNK_BYTES = 8 * 1024
private const val CAPACITY_HINT = 64 * 1024
