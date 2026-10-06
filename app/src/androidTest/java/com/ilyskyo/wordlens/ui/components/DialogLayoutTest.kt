// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.ui.components

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ilyskyo.wordlens.R
import com.ilyskyo.wordlens.ui.theme.WordLensTheme
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 提示窗的**不变量**：正文再长，两条出路也得一直在屏幕上。
 *
 * ## 为什么值得单独一条
 *
 * `WordLensDialog` 的正文那一段是可滚的（`heightIn(max = 420.dp)` + `verticalScroll`），
 * 而标题与动作行钉在外面。这个分工只有在「正文长到超过一格」时才被用到，
 * 而那正是它最容易坏的时候——坏法不是崩，是**底部那颗按钮被推出屏幕**，
 * 于是用户只剩「按外面把它关掉」这一条出路， destructive 的那一颗永远按不到。
 *
 * ## 为什么四种语言各测一遍
 *
 * 正文挑的是全项目最长的那几条真串之一（`settings_cloud_note`：密钥怎么存、会不会上传）。
 * 它的长度在各语言里差很多，而布局是跟着宽度折行的：同一句韩文比英文多占两三行是常态。
 * 只测英文等于只测最容易满足的那个 case——用户报回来的遮挡截图没有一张是英文的。
 *
 * ## 断言的是「钉住的东西在」，不是「正文全在」
 *
 * 正文本来就该被滚，所以只要求它**存在**；标题与两颗按钮才是不许不见的。
 * 把「正文完全可见」写成断言会得到一条正确的红。
 */
@RunWith(AndroidJUnit4::class)
class DialogLayoutTest {

    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun theActionsStayOnScreenInEnglish() = assertDialogFits("en")

    @Test
    fun theActionsStayOnScreenInChinese() = assertDialogFits("zh")

    @Test
    fun theActionsStayOnScreenInJapanese() = assertDialogFits("ja")

    @Test
    fun theActionsStayOnScreenInKorean() = assertDialogFits("ko")

    private fun assertDialogFits(tag: String) {
        val localized = compose.activity.createConfigurationContext(
            Configuration(compose.activity.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag(tag)))
            },
        )
        val res = localized.resources
        // 四段文字各用各的串：同一个词出现两次会让 onNodeWithText 报「找到 2 个节点」，
        // 看起来像布局坏了，其实是断言撞了名（同族的坑在 FormSheetLayoutTest 里记着）。
        val title = res.getString(R.string.detail_delete_confirm_title)
        val body = res.getString(R.string.settings_cloud_note)
        val primary = res.getString(R.string.settings_cloud_key_clear)
        val secondary = localized.getString(android.R.string.cancel)

        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized) {
                WordLensTheme {
                    WordLensDialog(
                        title = title,
                        onDismiss = {},
                        primaryText = primary,
                        onPrimary = {},
                        message = body,
                        secondaryText = secondary,
                        onSecondary = {},
                        destructive = true,
                    )
                }
            }
        }
        compose.waitForIdle()

        try {
            compose.onNodeWithText(title).assertIsDisplayed()
            compose.onNodeWithText(primary).assertIsDisplayed()
            compose.onNodeWithText(secondary).assertIsDisplayed()
            // 正文允许被滚走，但它得真的在窗口里——否则这条测试什么都没测到。
            compose.onNodeWithText(body).assertExists()
        } catch (e: AssertionError) {
            throw AssertionError("[$tag] title=$title primary=$primary secondary=$secondary：${e.message}", e)
        }
    }
}
