// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.data.repository

import com.ilyskyo.wordlens.data.store.WordLensJson
import kotlinx.serialization.json.decodeFromStream
import java.io.InputStream

/**
 * 把用户挑的 `deck.json` 读成一份文档。读不出来就是 null，不抛。
 *
 * 单独立一个文件、与仓库的写路径分开，是因为这里要面对的输入完全不受我们控制：
 * 可能是别的版本写的、可能是被编辑器改过缩进的、也可能压根是一个 JSON 数组。
 * `WordLensJson` 已经 `ignoreUnknownKeys`，所以跨版本向前兼容是有的；
 * 剩下的失败都该收敛成一句人话，而不是栈。
 */
fun decodeDeck(stream: InputStream): DeckDocument? = runCatching {
    stream.use { WordLensJson.instance.decodeFromStream(DeckDocument.serializer(), it) }
}.getOrNull()

/** 字符串入口：给测试与「先读文本再决定」的调用方用。 */
fun decodeDeckText(text: String): DeckDocument? = runCatching {
    WordLensJson.instance.decodeFromString(DeckDocument.serializer(), text)
}.getOrNull()
