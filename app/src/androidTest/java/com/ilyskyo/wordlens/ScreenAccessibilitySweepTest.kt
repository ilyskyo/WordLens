// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 每一个能点的东西都必须**自己报出名字**。
 *
 * ## 它是怎么被证明「能红」的（第一次的结论是错的，错在目标不在匹配器）
 *
 * 第一次 falsify 挑的是 `LookbackScreen:604` 日历键的 `contentDescription`，抹空之后三条仍全绿，
 * 于是我判定匹配器没在看东西。**那个判定不对**：那颗键的 click 动作所在的合并节点
 * 还带着别的文字，抹掉 desc 之后它仍然「有名字」——测试答的是「这控件报不报名字」，
 * 而我要抹的那一颗本来就还有别的路报名字。**目标选错了，不是尺子坏了。**
 *
 * 换一个纯图标、名字只有 desc 一条来源的控件：`HomeTabBar` 的 `FloatingAction`
 * （`.clearAndSetSemantics { contentDescription = … }`，里面只有 Icon，没有 Text）。
 * 抹掉 `tab_capture` 那一条之后：回看页与记住页各红一次，各自精确指出**一颗** mute 节点
 * （节点#86 / 节点#130，`被吞掉的子节点文字=[]`——正是「除了 desc 之外没有别的名字来源」的形状）。
 * 已还原。
 *
 * ## 一条顺带纠正过来的事
 *
 * `uiautomator dump` 里那 7 颗「text 与 content-desc 全空」的可点节点**不能当作 TalkBack 听到的内容**：
 * 它把合并节点的子 Text 也照样列出来，而父节点那格是空的——量的是 dump 的表示法，不是语义本身。
 * 用它下结论会把一条好好的尺子判成瞎的。要量平台那一棵，得走 `AccessibilityNodeInfo` 的正路。
 *
 * ## 为什么盯合并树
 *
 * TalkBack 读的是合并后的语义树：`Modifier.clickable` 所在节点会把子树里的文字与
 * contentDescription 收进来，然后只念那一句。所以「未合并树里有个 Text」不算数——
 * 屏幕前那个人听到的，就是合并后这个节点上有的东西。
 *
 * ## 为什么这件事只能量出来、不能靠看
 *
 * 图标按钮在截图上完全正常：有形状、有配色、点得动，`lintVitalRelease` 也过。
 * 只有把语义树翻出来才看得见它没名字。而这类遗漏的形状永远是「后来加的那一颗」。
 */
@RunWith(AndroidJUnit4::class)
class ScreenAccessibilitySweepTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun everyClickableControlOnTheLookbackPageAnnouncesItself() {
        assertNoMuteClickables("回看页", 4, compose.onAllNodes(hasClickAction()))
    }

    @Test
    fun everyClickableControlOnTheRememberPageAnnouncesItself() {
        openTab(R.string.home_tab_remember)
        assertNoMuteClickables("记住页", 6, compose.onAllNodes(hasClickAction()))
    }

    @Test
    fun everyClickableControlOnTheSearchPageAnnouncesItself() {
        openPage(R.string.home_search)
        assertNoMuteClickables("搜索页", 2, compose.onAllNodes(hasClickAction()))
    }

    /**
     * 页签与工具条上的胶囊都做过 `clearAndSetSemantics`，能点的是合并后的那一个节点，
     * 所以按 contentDescription 找（按文字找会命中未合并的 Text，注入点击时报
     * 「只有一个未合并节点匹配」）。点之前先等到空闲：动画还在跑时注入，
     * 会落在一颗正在位移的胶囊上，然后报一个和无障碍无关的错。
     */
    private fun openPage(labelRes: Int) {
        compose.waitForIdle()
        compose.onNodeWithContentDescription(compose.activity.getString(labelRes)).performClick()
        compose.waitForIdle()
    }

    private fun openTab(labelRes: Int) = openPage(labelRes)

    private fun assertNoMuteClickables(screen: String, atLeast: Int, nodes: SemanticsNodeInteractionCollection) {
        compose.waitForIdle()
        val all = nodes.fetchSemanticsNodes()
        // 这一条是给自己看的：**新的绿不等于测到了东西**。匹配器一颗都没抓到时，
        // 下面那个「不合格集合为空」会安静地通过。
        //
        // 下限不是猜的，是 2026-10-06 在 wl(AVD) 上量出来的：回看 4 颗、记住 7 颗、搜索 2 颗。
        // 当时随手写的「>= 5」会让回看与搜索都红——那一跑不是发现 bug，是发现我在瞎填数字。
        // 掉到这个下限以下意味着两件事之一：这一页少了控件，或者匹配器/语义写法变了。
        assertTrue(
            "$screen 只扫到 ${all.size} 个可点节点（下限 $atLeast）——先确认这一页还在、" +
                "匹配器还在抓东西，再谈无障碍",
            all.size >= atLeast,
        )
        val mute = all.mapNotNull { node ->
            val config = node.config
            val described = config.getOrNull(SemanticsProperties.ContentDescription)
                ?.any { it.isNotBlank() } == true
            val labelled = config.getOrNull(SemanticsProperties.Text)
                ?.any { it.text.isNotBlank() } == true
            if (described || labelled) {
                null
            } else {
                // 报**未合并子树里本来有什么**：如果这里非空，说明这一格其实有字，
                // 只是被 `clearAndSetSemantics` 之类的写法吞掉了——那是比「忘了写标签」
                // 更值得知道的一种错。
                val swallowed = node.children.mapNotNull {
                    it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
                }
                "  - 节点#${node.id} 角色=${config.getOrNull(SemanticsProperties.Role)} " +
                    "被吞掉的子节点文字=$swallowed"
            }
        }
        assertTrue(
            "$screen 上有 ${mute.size} 个能点但自己不报名字的控件（读屏只会念「按钮」或沉默）：\n" +
                mute.joinToString("\n"),
            mute.isEmpty(),
        )
    }
}
