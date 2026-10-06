// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ilyskyo.wordlens.ui.theme.Space
import com.ilyskyo.wordlens.ui.theme.WordLensTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 表单弹层的**布局**断言。
 *
 * ## 为什么这一条要跑在设备上，而不是 JVM
 *
 * 「最后一排胶囊被动作行切掉一半」是这一整轮里最难缠的一类缺陷的形状：它编得过、
 * 298 项 JVM 单测全绿、`lintVitalRelease` 也过，只有**真实渲染**才看得见。
 * 而人眼验过一次不会留下任何东西——下次谁改弹层的高度，它还会再坏一遍。
 *
 * `assertIsDisplayed` 读的是节点在窗口内的实际可见矩形：被裁掉、被顶出屏幕、
 * 高度算成 0，都会红。所以这条测试是那个 bug 的**永久**守卫，而不是一次性截图。
 *
 * ## 但 CI 现在跑不到它
 *
 * `.github/workflows` 里只有 `testDebugUnitTest` + `assembleRelease` + 发布冒烟，
 * **没有模拟器 job**，所以这条要在本地 `./gradlew :app:connectedDebugAndroidTest` 才会执行。
 * 把它接进 CI 需要加一个带 AVD 的 job（慢、且模拟器 job 本身会偶发不稳定），
 * 那是一个取舍，不是免费的——先记在这里，别以为推上去就有人替我看着。
 *
 * ## 为什么是六个胶囊而不是两个
 *
 * 被切掉的从来不是第一颗。这个 bug 只在「内容超出一格高度」时出现，所以测试必须
 * 复现那个超出：两个输入框加六颗心情，正是「编辑这一条」在真机上的内容量。
 */
@RunWith(AndroidJUnit4::class)
class FormSheetLayoutTest {

    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun everyMoodChipStaysVisibleBeneathThePinnedTopBar() {
        compose.setContent {
            WordLensTheme {
                WordLensFormSheet(
                    title = "Edit this entry",
                    cancelText = "Cancel",
                    onCancel = {},
                    confirmText = "Save",
                    onConfirm = {},
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(Space.md),
                    ) {
                        InsetField(value = "", onValueChange = {}, label = "Title")
                        InsetField(
                            value = "",
                            onValueChange = {},
                            label = "What happened that day",
                            singleLine = false,
                            minLines = 2,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                            Text(text = "How it felt")
                            MoodRow()
                        }
                    }
                }
            }
        }

        // 第一颗和最后一颗都断言：只查最后一颗的话，「一整排根本没渲染」也能通过，
        // 而那正是另一种坏法。
        compose.onNodeWithText("Quiet").assertIsDisplayed()
        compose.onNodeWithText("Overcast").assertIsDisplayed()
        // 两条出路钉在顶栏，不跟着内容滚：内容再长，出路也得一直在眼前。
        compose.onNodeWithText("Save").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }
}

/** 六颗心情。用真实的那六颗，而不是凑数的字符串。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoodRow() {
    FlowRow(
        modifier = Modifier.padding(bottom = Space.sm),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        listOf("Quiet", "Warm", "Crowded", "Messy", "Bright", "Overcast").forEach { label ->
            OptionChip(label = label, selected = false, onClick = {})
        }
    }
}
