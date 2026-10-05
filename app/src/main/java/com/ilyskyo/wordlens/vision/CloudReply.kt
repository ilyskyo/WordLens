// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 把云端模型的回复折成 [RawLabel] 列表。纯函数，没有 android 依赖，因此能在 JVM 上测。
 *
 * ## 为什么要从 CloudVisionEngine 里搬出来
 *
 * 这一层是整条识别链上唯一处理**不受信任输入**的地方：回复的形状由远端的模型决定，而它会
 * 在你没改任何代码的情况下变（加一句开场白、把 JSON 裹进代码栏、开始输出 thinking 块）。
 * 留在 engine 里就意味着它只能靠真机联网才能验；搬出来之后这些情况是几行断言。
 *
 * ## 只发英语词头，不发释义与音标
 *
 * 原先每条词除了 headword 还塞进 zh/ja/ko 释义和 IPA，说这样「用户拍到别种文字时也能命中」。
 * 那句是假的：`LexiconIndex` 的两张索引 `byHeadword` / `byAlias` 的 key 全是**英语**词头
 * （alias 来自 ECDICT 的英文别名，不是译文），一个「杯子」标签永远查不出任何东西。
 * 而它们真正的危害在数量上：一个词的标签最多 6 条，`MAX_LABELS` 又是按**拍平后的标签条数**
 * 掐的，于是模型认出的 8 个东西里只有前 2 个能走到匹配那一步——云端这条路的卖点就是认得多，
 * 却在这里被自己截掉了八成。
 *
 * 现在按**词**掐，上限 12 而提示词只要 8 条，正常回复一条都不该被丢。
 * 释义与音标本来也不是这条路该产出的东西：它们由本地词典补（`gloss`），而取景器里
 * 端设备上摆的「它看到了什么」那行，设备端引擎给的一直是纯英文标签——两条引擎给同一行
 * 内容，形态也该一致。
 */
object CloudReply {

    private val JSON = Json { ignoreUnknownKeys = true }

    /** 一次最多采纳这么多**词**（不是标签条数）。见类注释。 */
    const val MAX_WORDS = 12

    /** 非结构化回复里，超过这个长度的行不当成词。 */
    private const val MAX_LOOSE_LEN = 40

    /** 超过这么多个词的行不当成词——那多半是开场白。见 [parseLoose]。 */
    private const val MAX_LOOSE_TOKENS = 4

    /** 模型没给 confidence 时的默认值：低于设备端，但足以进入候选。 */
    private const val DEFAULT_CONFIDENCE = 0.8f

    /** 完全没结构可解析时，给自由文本一个更低的分。 */
    private const val LOOSE_CONFIDENCE = 0.55f

    /**
     * @param raw HTTP 响应体原文。
     * @return 折出来的标签；空列表表示这条回复里读不出任何词。
     */
    fun labels(raw: String): List<RawLabel> {
        val reply = extractReplyText(raw) ?: return parseLoose(raw)
        return parseStructured(reply) ?: parseLoose(reply)
    }

    /** Messages API 的信封里取助手文本。 */
    private fun extractReplyText(raw: String): String? = runCatching {
        val content = JSON.parseToJsonElement(raw).jsonObject["content"] as? JsonArray ?: return null
        content.firstNotNullOfOrNull { block ->
            (block as? JsonObject)?.primitiveText("text")
        }
    }.getOrNull()

    private fun parseStructured(text: String): List<RawLabel>? {
        val words = runCatching {
            JSON.parseToJsonElement(text.stripFences()).jsonObject["words"] as? JsonArray
        }.getOrNull() ?: return null

        val out = ArrayList<RawLabel>(words.size)
        val seen = HashSet<String>()
        for (element in words) {
            if (out.size >= MAX_WORDS) break
            val entry = element as? JsonObject ?: continue
            val headword = (entry.primitiveText("headword") ?: entry.primitiveText("word"))
                ?.trim()
                .orEmpty()
            if (headword.isBlank()) continue
            // 同一个词被列两次时保留第一次（分更高的那个通常在前）。
            if (!seen.add(headword.lowercase())) continue
            val confidence = entry.primitiveText("confidence")?.toFloatOrNull() ?: DEFAULT_CONFIDENCE
            out += RawLabel(headword, confidence.coerceIn(0f, 1f))
        }
        // 空数组也返回空列表而不是 null：**读到 `words` 字段就说明这是一条结构化回复**，
        // 只是模型这次什么都没认出。退回按行解析会把 `{"words":[]}` 本身当成一个「词」，
        // 而这一行的产物是取景页上那句「它看到了什么」——那行里出现花括号是能被看见的错。
        return out
    }

    /**
     * 模型给出的不是我们的 JSON：按一行一个词读。
     *
     * 丢掉超过 [MAX_LOOSE_TOKENS] 个词的行：一句开场白（"Sure! Here is what I see:"）也是一行，
     * 而这一行的产物不是候选词而是**展示出来的「它看到了什么」**（取景页的无匹配面板），
     * 所以让整句话挤进那一行是真会被人看见的错，不只是匹配噪音。提示词要求的复合词到 3 个词，
     * 4 已经留了余量。
     */
    private fun parseLoose(text: String): List<RawLabel> = text.lineSequence()
        .map {
            it.trim()
                .removePrefix("-")
                .removePrefix("*")
                .removePrefix("•")
                .trim()
        }
        .filter { it.isNotEmpty() && it.length <= MAX_LOOSE_LEN }
        .filter { it.count { c -> c == ' ' } < MAX_LOOSE_TOKENS }
        .distinctBy { it.lowercase() }
        .take(MAX_WORDS)
        .map { RawLabel(it, LOOSE_CONFIDENCE) }
        .toList()

    private fun JsonObject.primitiveText(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    /** 模型爱把 JSON 裹进代码栏，尽管提示词里说了不要。 */
    private fun String.stripFences(): String = trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
}
