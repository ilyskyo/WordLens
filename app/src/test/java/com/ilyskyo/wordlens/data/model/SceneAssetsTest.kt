// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 场景与氛围词资产文件的回归锁。
 *
 * 这两个 JSON 是手工编辑的，最容易坏在「改着改着少了个引号」而不是逻辑上。测试直接读
 * src/main/assets 下的源文件——APK 里打包的就是它们，这里读不通，运行时一定读不通。
 */
class SceneAssetsTest {

    private val assets = File("src/main/assets/scenes")

    private fun decodeScenes(): SceneTaxonomy = Json.decodeFromString(
        SceneTaxonomy.serializer(),
        File(assets, "scenes.json").readText(Charsets.UTF_8),
    )

    @Test
    fun `scenes json decodes and every scene has words and aliases`() {
        val taxonomy = decodeScenes()
        assertTrue("场景数太少", taxonomy.size >= 10)
        assertEquals("场景 id 有重复", taxonomy.size, taxonomy.scenes.distinctBy { it.id }.size)
        for (scene in taxonomy.scenes) {
            assertTrue("${scene.id} 没有词", scene.words.isNotEmpty())
            assertTrue("${scene.id} 没有别名", scene.labelAliases.isNotEmpty())
            assertTrue("${scene.id} 的别名必须小写", scene.labelAliases.all { it == it.lowercase() })
        }
    }

    @Test
    fun `scene words exist in the shipped lexicon`() {
        val lexiconFile = File("src/main/assets/lexicon/en.json")
        assertTrue("词典资产缺失", lexiconFile.exists())
        val lexicon = Json.decodeFromString(LexiconFile.serializer(), lexiconFile.readText(Charsets.UTF_8))
        // 场景词按「头词或复数别名」命中，与 LexiconIndex 的解析路径一致。
        val known = lexicon.entries.flatMap { entry ->
            listOfNotNull(entry.words["en"]) + entry.labelAliases
        }.map { it.lowercase() }.toSet()

        val missing = decodeScenes().scenes.flatMap { scene ->
            scene.words.filter { it.lowercase() !in known }.map { "${scene.id}:$it" }
        }
        assertTrue("场景词不在词典里: $missing", missing.isEmpty())
    }

    @Test
    fun `ambience json decodes with cues in range`() {
        val ambience = Json.decodeFromString(
            AmbienceFile.serializer(),
            File(assets, "ambience.json").readText(Charsets.UTF_8),
        )
        assertTrue("氛围词太少", ambience.ambience.size >= 10)
        for (word in ambience.ambience) {
            assertTrue("${word.id} 缺英文", word.word.containsKey("en"))
            val cue = word.cue ?: continue
            cue.minBrightness?.let { assertTrue("${word.id} 亮度越界", it in 0f..1f) }
            cue.maxBrightness?.let { assertTrue("${word.id} 亮度越界", it in 0f..1f) }
            cue.minWarmth?.let { assertTrue("${word.id} 色温越界", it in -1f..1f) }
            cue.maxWarmth?.let { assertTrue("${word.id} 色温越界", it in -1f..1f) }
        }
    }
}
