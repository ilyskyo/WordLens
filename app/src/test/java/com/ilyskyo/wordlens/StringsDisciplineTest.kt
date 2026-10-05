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

    /**
     * 带参数取的串必须真的声明那些占位符。
     *
     * ## 为什么四个 locale 一致的断言还不够
     *
     * `format placeholders match across locales` 比的是**横向**一致性，所以四个 locale 一样缺
     * 占位符时它通过——而这正是已经发生过的那次：`stringResource(R.string.audio_recording_label,
     * formatDuration(elapsedMs))`，而那条串写的是「正在录」。Android 对多余的格式参数不报错，
     * 只是**不用它**，于是秒表每两百毫秒算一次、每次都算对、屏幕上一直显示同一句静态文案；
     * lint 也不报（它不核对 Compose 的 `stringResource` 调用点）。
     *
     * 这一类错全部落在「逻辑对而界面没显示」那一侧：JVM 里状态机测不到，肉眼要读两种文件
     * 对得上才看得见。所以把调用点数出来跟资源比。
     */
    @Test
    fun `a string taken with arguments declares those placeholders`() {
        val entries = entriesOf(stringsFile("values"))
        val offenders = mutableListOf<String>()

        sourceFiles().forEach { file ->
            val text = file.readText()
            CALL.findAll(text).forEach { match ->
                val key = match.groupValues[1]
                val arguments = countArgumentsAt(match.range.last + 1, text)
                val placeholders = ARG_TOKEN.findAll(entries[key].orEmpty()).map { it.value }.toSet().size
                if (arguments > 0 && placeholders == 0) {
                    offenders += "${file.name} 用 $arguments 个参数取 $key，而这条串一个占位符都没有"
                } else if (arguments != placeholders) {
                    offenders += "${file.name} 取 $key：传了 $arguments 个参数，串里有 $placeholders 个占位符"
                }
            }
        }
        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }

    /**
     * 从 `R.string.<key>` 之后那位开始数**顶层**参数个数，直到这一次调用的右括号。
     *
     * 不能简单数逗号：`getString(R.string.x, dayKeyLabel(dayKey, ctx))` 是一个参数不是两个，
     * 所以要跟着括号深度走；也不能不管引号——`"80%)"` 里那个括号会把深度提前收掉，
     * 于是后半句的逗号全被算成参数。
     */
    private fun countArgumentsAt(from: Int, text: String): Int {
        var index = from
        var depth = 0
        var arguments = 0
        var inString = false
        var escaped = false
        while (index < text.length) {
            val c = text[index]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '(' -> depth++
                c == ')' -> if (depth == 0) return arguments else depth--
                // 顶层逗号不一定是分隔符：这一族代码统一写**行尾逗号**（`foo(a, b,)^` 那种多行调用），
                // 它后面紧跟的就是右括号。把它数成参数会让每一条带行尾逗号的取串都误报，
                // 而一条会随机误报的纪律测试，最后会被下一次红灯的人整行注释掉。
                c == ',' && depth == 0 -> if (closesCallAfter(index, text)) return arguments else arguments++
            }
            index++
        }
        return 0
    }

    /** 这个顶层逗号之后紧跟的是不是本次调用的右括号（跨换行与缩进）。 */
    private fun closesCallAfter(commaIndex: Int, text: String): Boolean {
        var i = commaIndex + 1
        while (i < text.length && (text[i].isWhitespace() || text[i] == '/')) {
            // 注释不会出现在参数与右括号之间吗？会——多行调用的最后一行常有说明。跳过整行。
            if (text[i] == '/') {
                while (i < text.length && text[i] != '\n') i++
            } else {
                i++
            }
        }
        return i < text.length && text[i] == ')'
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

    /** 主源码树。用已经定位好的 `res` 往上走一层，不再猜测试的工作目录在哪。 */
    private fun sourceFiles(): List<File> {
        val root = File(res.parentFile, "java")
        assertTrue("找不到主源码树：$root", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

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

        /**
         * 比 `PLACEHOLDER` 宽：单个参数的串常常直接写 `%d` / `%s`（Android 允许，只有多参数才必须
         * 带位置）。这一条断言问的是「传了参数而串里没有任何槽位」，所以要把两种写法都算上。
         */
        val ARG_TOKEN = Regex("""%(?:\d+\$)?[a-zA-Z]""")

        /**
         * 只认「资源 id 是第一个参数」那两种取法。
         *
         * 不匹配裸的 `R.string.foo`：`when` 分支里 `-> R.string.settings_palette_warm` 那种
         * 引用后面永远不会跟参数，把它也算进来只会制造误报。
         */
        val CALL = Regex("""(?:stringResource|getString)\s*\(\s*(?:id\s*=\s*)?R\.string\.([a-z0-9_]+)""")

        // 用码位而不是字面字符：把 CJK 的边界写成汉字，源码换一次编码或换一个字体就会错位。
        val Char.isHan: Boolean get() = code in 0x4E00..0x9FFF
        val Char.isKana: Boolean get() = code in 0x3040..0x30FF
        val Char.isHangul: Boolean get() = code in 0xAC00..0xD7A3
    }
}
