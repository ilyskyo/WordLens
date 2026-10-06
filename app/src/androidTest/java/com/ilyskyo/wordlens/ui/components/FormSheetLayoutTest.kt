// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.components

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ilyskyo.wordlens.R
import com.ilyskyo.wordlens.ui.theme.Space
import com.ilyskyo.wordlens.ui.theme.WordLensTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

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

    /**
     * 同一张弹层在四种语言下都必须放得下。
     *
     * 上面那条写死了英文标签，于是它只守得住一种排版——而这一族缺陷恰恰是
     * 「换一种语言才出现」：韩文与日文的 mood 标签普遍比中文长，一行放几颗、要不要折行、
     * 折了之后最后一排会不会被钉住的顶栏切掉，全都跟着文字宽度变。
     * 用户报回来的那几张截图（遮挡、被切一半）没有一张是在英文下拍的。
     *
     * **一门语言一个 @Test，而不是一个循环**：`compose.setContent` 在同一个测试里
     * 只能调一次，第二次直接 `IllegalStateException: has already set content`
     * （第一版就是写成循环，红在这条上，跟布局一点关系没有）。
     *
     * 语言不是靠改系统设置切的（那会把模拟器留在别人手里不认的状态），而是
     * `createConfigurationContext` 造一个只给这一次组合用的 Context，再用 `LocalContext`
     * 提供进去——`stringResource` 走的就是这份 resources，所以量到的是那门语言
     * 真实的字符串宽度，不是我把英文抄长一点。
     */
    @Test
    fun theMoodChipsStayVisibleInEnglish() = assertSheetFitsLayout("en")

    @Test
    fun theMoodChipsStayVisibleInChinese() = assertSheetFitsLayout("zh")

    @Test
    fun theMoodChipsStayVisibleInJapanese() = assertSheetFitsLayout("ja")

    @Test
    fun theMoodChipsStayVisibleInKorean() = assertSheetFitsLayout("ko")

    private fun assertSheetFitsLayout(tag: String) {
        val localized = compose.activity.createConfigurationContext(
            Configuration(compose.activity.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag(tag)))
            },
        )
        val res = localized.resources
        val labels = listOf(
            R.string.mood_quiet, R.string.mood_warm, R.string.mood_crowded,
            R.string.mood_messy, R.string.mood_bright, R.string.mood_grey,
        ).map { res.getString(it) }
        val save = res.getString(R.string.detail_edit_save)
        // 项目里各处的「取消」是跟着场景写的，没有通用那一条；这里要的是**真实宽度**，
        // 所以取框架自带的——它四种语言都有翻译。
        val cancel = localized.getString(android.R.string.cancel)
        // 六个标题各用各的串：同一句出现两次会让 onNodeWithText 报「找到 2 个节点」，
        // 那是测试自己的毛病，看起来却像布局坏了（第一版就红在这里）。
        val title = res.getString(R.string.detail_edit)
        val fieldLabel = res.getString(R.string.detail_edit_title)
        val noteLabel = res.getString(R.string.detail_event_hint)
        val moodHeader = res.getString(R.string.detail_event_save)

        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized) {
                WordLensTheme {
                    WordLensFormSheet(
                        title = title,
                        cancelText = cancel,
                        onCancel = {},
                        confirmText = save,
                        onConfirm = {},
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(Space.md),
                        ) {
                            InsetField(value = "", onValueChange = {}, label = fieldLabel)
                            InsetField(
                                value = "",
                                onValueChange = {},
                                label = noteLabel,
                                singleLine = false,
                                minLines = 2,
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                                Text(text = moodHeader)
                                MoodRow(labels)
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        try {
            compose.onNodeWithText(labels.first()).assertIsDisplayed()
            compose.onNodeWithText(labels.last()).assertIsDisplayed()
            compose.onNodeWithText(save).assertIsDisplayed()
            compose.onNodeWithText(cancel).assertIsDisplayed()
        } catch (e: AssertionError) {
            // 不带语言与原词的话，红了只能再猜是哪一门——四条测试长一样是最难查的那种测试。
            throw AssertionError("[$tag] 首颗=${labels.first()} 末颗=${labels.last()}：${e.message}", e)
        }
    }
}

/** 六颗心情。默认用真实的那六颗英文，跨语言那条把本地化后的六个传进来。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoodRow(labels: List<String> = DEFAULT_MOOD_LABELS) {
    FlowRow(
        modifier = Modifier.padding(bottom = Space.sm),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        labels.forEach { label ->
            OptionChip(label = label, selected = false, onClick = {})
        }
    }
}

private val DEFAULT_MOOD_LABELS = listOf("Quiet", "Warm", "Crowded", "Messy", "Bright", "Overcast")

