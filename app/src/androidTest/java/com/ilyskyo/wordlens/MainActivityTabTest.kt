// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 页签要活得过「模态页盖上来再离开」。
 *
 * ## 这条守的是一个真实发生过、且只能靠设备发现的回归
 *
 * 主页场景在搜索/取景/详情盖上来时会**整个离开组合**（`MainActivity` 的 `AnimatedContent`
 * 同一时刻只组合栈顶那一页）。于是「在记住页点搜索、加个词、返回」会把人甩回「回看」——
 * 用户明明是从记住页出去的。修法是页签由宿主持有（`homeTab`），而不是 `WordLensApp` 自己
 * `rememberSaveable`：后者保的是 `onSaveInstanceState` 那条路，保不住「子树被拆掉再重建」。
 *
 * 这件事 JVM 测不到（没有组合生命周期），而手工验一次不会留下任何东西。所以走设备：
 * 这条测试就是那条路径本身。
 *
 * ## 为什么断言的是「Both」而不是页签本身
 *
 * 页签在两个 tab 上都在，光看它在不在证明不了停在**哪一页**。`study_both` 是复习页的素材
 * 筛选器，只有记住页才有——它出现，才等于「还停在记住页」。
 *
 * 两个字符串都从 `activity.getString` 取，不写死英文：设备语言是模拟器/CI 说了算的。
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTabTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun theChosenTabSurvivesAModalPagePushedOnTopOfTheHomeScene() {
        val remember = compose.activity.getString(R.string.home_tab_remember)
        val both = compose.activity.getString(R.string.study_both)
        val search = compose.activity.getString(R.string.home_search)

        // 页签那一段用 `clearAndSetSemantics` 把图标与文字合并成了一个节点，并把 label
        // 写成了 contentDescription——可点的是**那个合并后的节点**，不是里面的 Text
        // （按文字找会命中未合并的 Text，注入点击时会报「只有一个未合并节点匹配」）。
        compose.onNodeWithContentDescription(remember).performClick()
        compose.waitForIdle()
        // 「Both」也在被合并过的胶囊里，所以同样按 contentDescription 找。
        compose.onNodeWithContentDescription(both).assertIsDisplayed()

        // 盖上来一个模态页：主页场景就此离开组合。
        compose.onNodeWithContentDescription(search).performClick()
        compose.waitForIdle()

        // 返回键走的是应用自己的分发（`MainActivity` 的 BackHandler 会弹页面栈），
        // 而不是模拟物理返回——后者在 instrumentation 下更容易被输入法吃掉。
        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()

        // 守卫在这里：修之前这一条会红，因为页签回到了默认的 LOOKBACK，筛选器根本不在树上。
        compose.onNodeWithContentDescription(both).assertIsDisplayed()
    }
}
