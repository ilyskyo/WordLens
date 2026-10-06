// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.settings

import com.ilyskyo.wordlens.data.model.Lang
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 两个方向永远不能是同一门语言。
 *
 * 这条不变式崩掉的后果不是报错，是一张**没有意义的卡**：正面「伞」、背面释义也是「伞」，
 * 用户认真地翻了一次面，什么也没被回忆起来，而 FSRS 还认真记了一次「想起来了」。
 * 模拟器上真的存出过 zh/zh 这一对——两个选择器互不知晓，各自都能把对方已选的语言再选一次。
 */
class SettingsLanguagePairTest {

    @Test
    fun `picking the other side's language swaps instead of collapsing`() {
        // 现状：母语 zh，目标语 en。把目标语选成 zh —— 意图是「反过来」。
        assertEquals(
            Lang.ENGLISH to Lang.CHINESE,
            SettingsViewModel.pairAfterPicking(Lang.CHINESE, Lang.ENGLISH, choosingTarget = true, chosen = Lang.CHINESE),
        )
        // 对称的一侧：把母语选成当前的目标语，同样互换。
        assertEquals(
            Lang.ENGLISH to Lang.CHINESE,
            SettingsViewModel.pairAfterPicking(Lang.CHINESE, Lang.ENGLISH, choosingTarget = false, chosen = Lang.ENGLISH),
        )
    }

    @Test
    fun `a language nobody holds is simply taken`() {
        assertEquals(
            Lang.CHINESE to Lang.JAPANESE,
            SettingsViewModel.pairAfterPicking(Lang.CHINESE, Lang.ENGLISH, choosingTarget = true, chosen = Lang.JAPANESE),
        )
        assertEquals(
            Lang.KOREAN to Lang.ENGLISH,
            SettingsViewModel.pairAfterPicking(Lang.CHINESE, Lang.ENGLISH, choosingTarget = false, chosen = Lang.KOREAN),
        )
    }

    /** 互换之后必须还能换回来：这条通路不能变成单向门。 */
    @Test
    fun `swapping twice returns to the original pair`() {
        val once = SettingsViewModel.pairAfterPicking(
            Lang.CHINESE, Lang.ENGLISH, choosingTarget = true, chosen = Lang.CHINESE,
        )
        // 换回来：现在母语是 en，把目标语选成 en，就该换回 (zh, en)。
        val twice = SettingsViewModel.pairAfterPicking(
            once.first, once.second, choosingTarget = true, chosen = Lang.ENGLISH,
        )
        assertEquals(Lang.CHINESE to Lang.ENGLISH, twice)
    }

}
