// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * 语言资源的纪律。
 *
 * ## 为什么这是一条测试而不是一次人工核对
 *
 * 「四语齐」这件事在这个项目里靠人核对过很多次，而它恰恰是人最容易看漏的那类：少一个 key 时
 * 界面不会崩，只会**掉回默认语言**——于是中文界面里冒出一句英文，看上去像有意这么写的。
 * 已经真的出过两次：一条韩文串里混进了简体汉字，一条日文串里出现了英文语序的「的」。
 * 两次都不是靠眼睛发现的。
 *
 * AAPT2 编译期管不了这些：它只保证每个 locale 各自能编译，不保证四个 locale 说的是同一件事。
 * 所以断言得自己写。
 *
 * ## 断言的是「会出事的那一面」，不是「看起来更整齐」
 *
 * 语序**允许**不同：中日韩里 `%1$s` 出现的位置和英文本来就不一样，比字符串会一律红，
 * 所以这里比的是占位符的**集合**。同理 plurals 的档位数量各国不同（en 有 one/other，
 * 中日韩按 CLDR 只有 other），那是正确的不对称，不作断言。
 */
class StringsDisciplineTest {

    private val res: File by lazy { findResDir() }

    /** 默认包是简体中文，其余三个目录名就是语言。 */
    private val locales = listOf(
        "values" to "zh",
        "values-en" to "en",
        "values-ja" to "ja",
        "values-ko" to "ko",
    )

    @Test
    fun `every locale has exactly the same string keys`() {
        val tables = locales.associate { (dir, tag) -> tag to entriesOf(stringsFile(dir)) }
        val reference = tables.getValue("zh").keys
        assertTrue("默认包一个字符串都没有：资源目录找错了？$res", reference.isNotEmpty())
        tables.forEach { (tag, entries) ->
            // 少一个 key 不会崩，只会静默掉回默认语言；多一个 key 则是永远不会被用到的死串。
            assertEquals("$tag 缺少 key：${reference - entries.keys}", emptySet<String>(), reference - entries.keys)
            assertEquals("$tag 多出 key：${entries.keys - reference}", emptySet<String>(), entries.keys - reference)
        }
    }

    @Test
    fun `no locale ships a value made only of whitespace`() {
        locales.forEach { (dir, tag) ->
            entriesOf(stringsFile(dir)).forEach { (key, value) ->
                assertTrue("$tag/$key 是空白串", value.isNotBlank())
            }
        }
    }

    @Test
    fun `each locale's text belongs to that locale`() {
        locales.forEach { (dir, tag) ->
            entriesOf(stringsFile(dir)).forEach { (key, value) ->
                val han = value.count { it.isHan }
                val kana = value.count { it.isKana }
                val hangul = value.count { it.isHangul }
                when (tag) {
                    // 拉丁语界面里出现任何一个中日韩字符都是事故。
                    "en" -> assertEquals("en/$key 里出现了中日韩文字：$value", 0, han + kana + hangul)
                    "zh" -> assertEquals("zh/$key 里混进了假名或谚文：$value", 0, kana + hangul)
                    // 日文允许汉字，但不该出现谚文——那只会是复制粘贴带进来的。
                    "ja" -> assertEquals("ja/$key 里混进了谚文：$value", 0, hangul)
                    // 韩文这条正是已经踩过的那次：简体汉字混在谚文里肉眼分不出来，
                    // 读起来是一段既不是中文也不是韩文的机器翻译腔。
                    "ko" -> assertEquals("ko/$key 里混进了汉字或假名：$value", 0, han + kana)
                }
            }
        }
    }

    @Test
    fun `format placeholders match across locales`() {
        // 占位符少一个，getString 当场抛；多一个，界面上会出现没人解释的「%2$s」。
        val perKey = locales.associate { (dir, tag) ->
            tag to entriesOf(stringsFile(dir)).mapValues { (_, value) ->
                PLACEHOLDER.findAll(value).map { it.value }.toSet()
            }
        }
        val reference = perKey.getValue("zh")
        perKey.forEach { (tag, table) ->
            table.forEach { (key, placeholders) ->
                assertEquals("$tag/$key 的占位符与默认包不一致", reference.getValue(key), placeholders)
            }
        }
    }

    @Test
    fun `apostrophes are escaped or typographic`() {
        // AAPT2 的态度是：裸的 ' 让整条字符串解析失败，而报错位置指向资源文件的第一行，
        // 指不到真正的那一条。`\'` 是合法的，所以这里只拦**没被转义**的撇号；
        // 项目里统一用 ’，这条断言保证下一次半角撇号在测试里就停下，而不是在构建日志里。
        locales.forEach { (dir, tag) ->
            entriesOf(stringsFile(dir)).forEach { (key, value) ->
                val unescaped = Regex("""(?<!\\)'""").findAll(value).count()
                assertEquals("$tag/$key 有未转义的半角撇号：$value", 0, unescaped)
            }
        }
    }

    @Test
    fun `plurals are declared in every locale and always carry other`() {
        val counts = locales.associate { (dir, tag) -> tag to pluralsOf(stringsFile(dir)) }
        val reference = counts.getValue("zh").keys
        counts.forEach { (tag, table) ->
            assertEquals("$tag 的 plurals 与默认包不一致", reference, table.keys)
        }
        // `other` 是唯一一个所有语言都有的档位，缺它等于这条 plurals 在该语言里永远取不到。
        counts.forEach { (tag, table) ->
            table.forEach { (key, quantities) ->
                assertTrue("$tag/plurals/$key 缺少 other 一档", quantities.contains("other"))
            }
        }
    }

    // ── 读取 ─────────────────────────────────────────────────────────────────

    /**
     * 某个 locale 的 strings.xml。
     *
     * `java.io.File` 只有 `(File, String)` 这一段两参数的构造，`stringsFile(dir)`
     * 那种三段写法是 `Path.of` 的形状——写在这里会以「没有可用构造」失败。子路径允许带分隔符，
     * 所以这里合成一段。
     */
    private fun stringsFile(dir: String): File = File(res, "$dir/strings.xml")

    private fun entriesOf(file: File): Map<String, String> =
        childrenOf(load(file), "string").associate { (name, element) ->
            name to element.getTextContent().trim()
        }

    private fun pluralsOf(file: File): Map<String, Set<String>> =
        childrenOf(load(file), "plurals").associate { (name, element) ->
            name to childrenOf(element, "item").map { it.second.getAttribute("quantity") }.toSet()
        }

    private fun load(file: File): Element {
        assertTrue("资源文件不存在：$file", file.isFile)
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
    }

    /**
     * 只取**直接子元素**。
     *
     * `getElementsByTagName` 会一路往下钻：plurals 的子级 item 也会被它捞进一次全局搜索里，
     * 于是两种资源被算成同一堆。层级在这里是有意义的，不能省。
     */
    private fun childrenOf(parent: Element, tag: String): List<Pair<String, Element>> {
        val nodes = parent.childNodes
        val out = ArrayList<Pair<String, Element>>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? Element ?: continue
            if (node.tagName == tag) out += node.getAttribute("name") to node
        }
        return out
    }

    /**
     * 定位 `src/main/res`。
     *
     * Gradle 通常把单元测试的工作目录设为模块目录（这里是 `app/`），但「从哪一层起算」是构建
     * 版本的事，不该靠记住一个相对路径来赌。从当前目录逐级往上找；找不到就失败并说明当时在哪。
     */
    private fun findResDir(): File {
        var cursor: File? = File("").absoluteFile
        while (cursor != null) {
            val candidate = File(cursor, "src/main/res")
            if (File(candidate, "values/strings.xml").isFile) return candidate
            cursor = cursor.parentFile
        }
        throw IllegalStateException(
            "找不到 src/main/res/values/strings.xml；测试的工作目录是 ${File("").absolutePath}",
        )
    }

    private companion object {
        val PLACEHOLDER = Regex("""%\d+\$[sd]""")

        // 用码位而不是字面字符：把 CJK 的边界写成汉字，源码换一次编码或换一个字体就会错位。
        val Char.isHan: Boolean get() = code in 0x4E00..0x9FFF
        val Char.isKana: Boolean get() = code in 0x3040..0x30FF
        val Char.isHangul: Boolean get() = code in 0xAC00..0xD7A3
    }
}
