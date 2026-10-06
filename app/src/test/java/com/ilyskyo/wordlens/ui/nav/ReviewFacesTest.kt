// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.nav

import com.ilyskyo.wordlens.data.model.StudyDirection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「两面同字」这张卡到底能不能复习。
 *
 * 这一类卡是修好语言方向之后**留下来**的东西：卡上的词头当时就是母语，改设置不会回头改它，
 * 所以只有队列这一层能挡住。而挡住它的那道闸门和渲染它的那段代码必须同源，否则被跳过的
 * 与看得见的那批会悄悄错开——这个文件就是钉住这件事的。
 */
class ReviewFacesTest {

    @Test
    fun `recognize puts the foreign word in front and the native meaning behind`() {
        assertEquals("cup" to "杯子", facesOf("cup", "杯子", StudyDirection.RECOGNIZE))
    }

    @Test
    fun `recall puts them the other way round`() {
        assertEquals("杯子" to "cup", facesOf("cup", "杯子", StudyDirection.RECALL))
    }

    @Test
    fun `a card whose headword is the native word has nothing on the back`() {
        // 这就是那批化石：母语 zh、目标语也曾经是 zh，于是词头和释义都是「伞」。
        assertEquals(true, facesCollapse("伞", "伞", StudyDirection.RECOGNIZE))
        assertEquals(true, facesCollapse("伞", "伞", StudyDirection.RECALL))
    }

    @Test
    fun `a missing gloss still leaves a card worth reviewing when recognizing`() {
        // 背面为 null 时界面走例句和照片，这仍然是一次合法的复习。
        assertEquals(false, facesCollapse("cup", null, StudyDirection.RECOGNIZE))
    }

    @Test
    fun `a missing gloss collapses when recalling, because the front falls back to the headword`() {
        // 回忆方向下缺释义会退回词头当正面，于是正面与背面是同一个词——这张卡什么都没说。
        assertEquals(true, facesCollapse("cup", null, StudyDirection.RECALL))
    }

    @Test
    fun `case and stray spaces do not make two different words`() {
        assertEquals(true, facesCollapse("Cup", "cup", StudyDirection.RECOGNIZE))
        assertEquals(true, facesCollapse("杯子 ", "杯子", StudyDirection.RECALL))
    }
}
