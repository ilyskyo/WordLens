// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云端回复的解析。
 *
 * ## 为什么这一层值得这么多条断言
 *
 * 它是整条识别链上唯一读**不受控输入**的地方：回复的形状由远端模型决定，而它会在你没改任何
 * 代码的情况下变——加一句开场白、把 JSON 裹进代码栏、开始先输出一个 thinking 块。那些变化在
 * 真机上表现为「云端这次没认出东西」，而没有网络与密钥就复现不了；在这里它们各是一行。
 *
 * 第一条断言钉的是一只具体的虫子：标签上限原来按**拍平后的条数**掐（12），而一个词会贡献
 * headword + 三语释义 + 音标最多 6 条，于是模型认出的 8 个物体只有前 2 个能走到匹配那一步。
 * 云端这条路的卖点就是认得多，却在自己这一层被截掉八成——界面上只会显示「它看到了 cup、mug」。
 */
class CloudReplyTest {

    /** 把一段文本装进 Messages API 的信封里。转义交给 [JsonPrimitive]，不手写反斜杠。 */
    private fun envelope(text: String): String =
        """{"content":[{"type":"text","text":${JsonPrimitive(text)}}]}"""

    private fun entry(headword: String, confidence: String? = "0.9") = buildString {
        append("{\"headword\":\"").append(headword).append("\"")
        append(",\"ipa\":\"/x/\",\"zh\":\"杯子\",\"ja\":\"やつ\",\"ko\":\"컵\"")
        if (confidence != null) append(",\"confidence\":").append(confidence)
        append('}')
    }

    private fun wordsOf(vararg entries: String) = """{"words":[${entries.joinToString(",")}]}"""

    private fun texts(labels: List<RawLabel>) = labels.map { it.text }

    @Test
    fun `all eight named objects survive`() {
        val entries = (1..8).map { entry("thing$it") }
        val labels = CloudReply.labels(envelope(wordsOf(*entries.toTypedArray())))

        assertEquals(8, texts(labels).distinct().size)
        assertEquals("thing1", labels.first().text)
        assertEquals("thing8", labels.last().text)
        // 释义与音标不进标签列表：`LexiconIndex` 的两张索引 key 全是英语词头，
        // 「杯子」永远查不出任何东西，留着只会挤掉一个真正能用的位置。
        assertTrue(labels.none { it.text == "杯子" || it.text == "/x/" })
    }

    /** 超过上限时按**词**掐，不是按条数。 */
    @Test
    fun `the cap counts words not labels`() {
        val entries = (1..20).map { entry("w$it", "0.8") }
        val labels = CloudReply.labels(envelope(wordsOf(*entries.toTypedArray())))

        assertEquals(CloudReply.MAX_WORDS, labels.size)
        assertEquals("w12", labels.last().text)
    }

    @Test
    fun `a thinking block ahead of the text does not hide the reply`() {
        val raw = """{"content":[{"type":"thinking","thinking":"let me look"},{"type":"text",""" +
            """"text":${JsonPrimitive(wordsOf(entry("cup", "0.7")))}},{"type":"text","text":"mug"}]}"""
        assertEquals(listOf("cup"), texts(CloudReply.labels(raw)))
    }

    @Test
    fun `a fenced json body still parses`() {
        val fenced = "```json\n" + wordsOf(entry("kettle", "0.6")) + "\n```"
        val labels = CloudReply.labels(envelope(fenced))
        assertEquals(listOf("kettle"), texts(labels))
        assertEquals(0.6f, labels.first().score, 0.0001f)
    }

    /** 模型开始讲人话的时候，一行一个词地读，而不是整段失败。 */
    @Test
    fun `prose degrades to one word per line`() {
        val labels = CloudReply.labels(
            envelope("- cup\n* mug\n• a very long line that is clearly not a single word at all and must be dropped\n\ncup"),
        )
        // 重复的 cup 只留第一次；带项目符号的行被剥成裸词。
        assertEquals(listOf("cup", "mug"), texts(labels))
    }

    /** 开场白也是一行，而这一行的产物会显示在「它看到了什么」里——那句不是词。 */
    @Test
    fun `a preamble line is not mistaken for a word`() {
        val labels = CloudReply.labels(envelope("Sure! Here is what I see in this photo:\ncup\nmug"))
        assertEquals(listOf("cup", "mug"), texts(labels))
    }

    /** 整段回复根本不是 JSON 时也要有产出，而不是抛。 */
    @Test
    fun `a body that is not json at all still yields lines instead of throwing`() {
        val labels = CloudReply.labels("I think the objects are a cup and a mug\ncup\nmug")
        assertEquals(listOf("cup", "mug"), texts(labels))
    }

    @Test
    fun `confidence is clamped into the range the scheduler expects`() {
        val labels = CloudReply.labels(envelope(wordsOf(entry("a", "3.5"), entry("b", "-1"))))
        assertEquals(1f, labels[0].score, 0.0001f)
        assertEquals(0f, labels[1].score, 0.0001f)
    }

    @Test
    fun `the first mention of a repeated headword wins`() {
        val labels = CloudReply.labels(envelope(wordsOf(entry("cup", "0.9"), entry("Cup", "0.2"))))
        assertEquals(1, labels.size)
        assertEquals(0.9f, labels.first().score, 0.0001f)
    }

    @Test
    fun `blank and malformed entries are skipped rather than poisoning the list`() {
        val inner = """{"words":[{"headword":"  "},null,42,{"headword":"spoon"}]}"""
        assertEquals(listOf("spoon"), texts(CloudReply.labels(envelope(inner))))
    }

    @Test
    fun `an empty words array is no labels, not a crash`() {
        assertEquals(emptyList<String>(), texts(CloudReply.labels(envelope("""{"words":[]}"""))))
    }
}
