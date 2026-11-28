package app.zhinengtika.nativeapp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class AppNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeShowsQueryAndAccountNavigationOpensLoginForm() {
        compose.onNodeWithText("查询题卡").assertIsDisplayed()
        compose.onNodeWithText("题卡编号").assertIsDisplayed()
        compose.onNodeWithText("查询").assertIsNotEnabled()

        compose.onNodeWithText("账号").performClick()
        compose.onNodeWithText("登录学生账号").assertIsDisplayed()
        compose.onNodeWithText("密码").assertIsDisplayed()
    }

    @Test
    fun offlineDirectoryAndSubjectNavigationAreVisibleAboveTheList() {
        compose.onNodeWithContentDescription("离线资料库").performClick()
        compose.onNodeWithText("整库 ZIP").assertIsDisplayed()
        compose.onNodeWithText("目录（", substring = true).assertIsDisplayed()
        compose.onNodeWithText("题卡（", substring = true).assertIsDisplayed()
        compose.onNodeWithText("语文").assertIsDisplayed()
        compose.onNodeWithText("数学").assertIsDisplayed()
        compose.onNodeWithText("other", substring = true).assertDoesNotExist()
    }

    @Test
    fun offlineSyntheticCardNeverOffersSubmission() {
        compose.onNodeWithContentDescription("离线资料库").performClick()
        compose.onNodeWithText("导入题卡").assertIsDisplayed()
        compose.onNodeWithText("载入合成演示示例（非真实题目）").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("合成演示题卡（非真实题目）").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("合成演示题卡（非真实题目）").performScrollTo().performClick()
        compose.onNodeWithText("离线只读 · 不能提交").assertIsDisplayed()
        compose.onNodeWithText("作业").assertDoesNotExist()
        compose.onNodeWithText("全部").assertIsDisplayed()
    }
}
