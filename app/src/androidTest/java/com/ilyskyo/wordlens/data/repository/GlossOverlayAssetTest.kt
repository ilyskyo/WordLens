// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 注音层要**真的走进索引**，不只是躺在 assets 里。
 *
 * JVM 那一侧的 `GlossOverlayTest` 量的是文件：id 解析得到、书写系统对、两种语言同一批概念。
 * 它管不到 `LexiconRepository.reload()` 里那段接线——项目没有 Robolectric，
 * 而 `AssetManager` 只在设备上是真的。那一段接错的方式很安静：文件名匹配写歪、
 * 前缀常量与真实文件名不一致，结果是词典照常加载、条数照常、**只是日韩那两格永远是空的**。
 * 用户那边就是「背面没有翻译」，而所有构建全绿。
 *
 * 这一条把那条通路钉住：从真实资产读一遍，然后问一个日语母语的人实际会问的问题。
 */
@RunWith(AndroidJUnit4::class)
class GlossOverlayAssetTest {

    @Test
    fun aJapaneseNativeSpeakerGetsAMotherTongueGlossFromTheShippedAssets() {
        runBlocking {
            val repo = LexiconRepository(
                ApplicationProvider.getApplicationContext(),
                CoroutineScope(SupervisorJob() + Dispatchers.IO),
            )
            val loaded = repo.reload()
            assertTrue("内置词典没读出来（$loaded 条）", loaded > 10_000)

            val cup = repo.byId("en.cup")
            assertNotNull("注音层把 en.cup 弄丢了", cup)
            assertEquals("カップ", cup?.glosses?.get("ja"))
            assertEquals("カップ", cup?.words?.get("ja"))
            assertEquals("잔", cup?.glosses?.get("ko"))

            // 覆盖量而不是某一条：补丁若整体没生效，上面三条已经会红；这一条盯的是
            // 「只生效了一部分」——例如语言 tag 写错了一格，那时 cup 仍然对而索引大面积是空的。
            val withJa = repo.all().count { !it.glosses["ja"].isNullOrBlank() }
            val withKo = repo.all().count { !it.glosses["ko"].isNullOrBlank() }
            assertTrue("索引里带日语释义的只有 $withJa 条", withJa >= 150)
            assertEquals("两种语言生效的范围必须一样：ja=$withJa ko=$withKo", withJa, withKo)
        }
    }
}
