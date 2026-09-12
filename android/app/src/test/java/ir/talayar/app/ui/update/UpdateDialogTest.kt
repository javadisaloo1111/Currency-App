package ir.talayar.app.ui.update

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ir.talayar.app.domain.model.AppUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Update dialog UX contract:
 *  - plain Persian copy («نسخه جدید آمده است» / «به‌روزرسانی» / «بعداً»)
 *  - no technical jargon (APK, GitHub, Release, API…) anywhere
 *  - forced updates hide «بعداً»; failures offer «تلاش مجدد»
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "fa-rIR-w411dp-h900dp")
class UpdateDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val update = AppUpdate(
        latestVersion = "1.0.2",
        apkUrl = "https://github.com/javadisaloo1111/Currency-App/releases/download/v1.0.2/app-release-v1.0.2.apk",
        apkSize = 8_645_881,
        sha256Url = null,
        forced = false,
    )

    private class RecordingActions : UpdateActions {
        val calls = mutableListOf<String>()
        override fun dismiss() { calls += "dismiss" }
        override fun startDownload() { calls += "startDownload" }
        override fun cancelDownload() { calls += "cancelDownload" }
        override fun retryDownload() { calls += "retryDownload" }
        override fun retryInstall() { calls += "retryInstall" }
        override fun openPermissionSettings() { calls += "openPermissionSettings" }
    }

    private fun render(state: UpdateViewModel.State, actions: RecordingActions = RecordingActions()): RecordingActions {
        composeRule.setContent {
            UpdateDialog(state = state, actions = actions)
        }
        return actions
    }

    @Test
    fun `available state shows the new-version dialog with plain persian copy`() {
        val actions = render(UpdateViewModel.State.Available(update))

        composeRule.onNodeWithText("نسخه جدید آمده است").assertIsDisplayed()
        composeRule.onNodeWithText("به‌روزرسانی").assertIsDisplayed()
        composeRule.onNodeWithText("بعداً").assertIsDisplayed()
        composeRule.onNodeWithText("۱.۰.۲", substring = true).assertIsDisplayed()

        composeRule.onNodeWithText("به‌روزرسانی").performClick()
        assertEquals(listOf("startDownload"), actions.calls)
    }

    @Test
    fun `available state never leaks technical jargon`() {
        render(UpdateViewModel.State.Available(update))
        for (jargon in listOf("APK", "GitHub", "Release", "API", "Version Code", "apk")) {
            composeRule.onAllNodesWithText(jargon, substring = true).fetchSemanticsNodes().let {
                assertTrue("jargon '$jargon' must not appear in the update dialog", it.isEmpty())
            }
        }
    }

    @Test
    fun `later button dismisses and forced updates hide it`() {
        val actions = render(UpdateViewModel.State.Available(update))
        composeRule.onNodeWithText("بعداً").performClick()
        assertEquals(listOf("dismiss"), actions.calls)

        val forced = update.copy(forced = true)
        render(UpdateViewModel.State.Available(forced))
        composeRule.onNodeWithText("بعداً").assertDoesNotExist()
    }

    @Test
    fun `downloading state shows progress and cancel`() {
        val actions = render(UpdateViewModel.State.Downloading(42))

        composeRule.onNodeWithText("در حال دانلود بروزرسانی").assertIsDisplayed()
        composeRule.onNodeWithText("۴۲٪", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("انصراف").performClick()
        assertEquals(listOf("cancelDownload"), actions.calls)
    }

    @Test
    fun `failed download offers retry`() {
        val actions = render(
            UpdateViewModel.State.Failed("دانلود نسخهٔ جدید ناموفق بود. اتصال اینترنت را بررسی کنید و دوباره تلاش کنید."),
        )

        composeRule.onNodeWithText("دانلود ناموفق بود").assertIsDisplayed()
        composeRule.onNodeWithText("تلاش مجدد").performClick()
        assertEquals(listOf("retryDownload"), actions.calls)
    }

    @Test
    fun `permission-needed state guides to settings`() {
        val actions = render(UpdateViewModel.State.NeedsPermission(update))

        composeRule.onNodeWithText("اجازهٔ نصب لازم است").assertIsDisplayed()
        composeRule.onNodeWithText("رفتن به تنظیمات").performClick()
        assertEquals(listOf("openPermissionSettings"), actions.calls)
    }

    @Test
    fun `hidden and ready states render nothing`() {
        render(UpdateViewModel.State.Hidden)
        composeRule.onNodeWithText("نسخه جدید آمده است").assertDoesNotExist()

        render(UpdateViewModel.State.Ready(update))
        composeRule.onNodeWithText("نسخه جدید آمده است").assertDoesNotExist()
    }
}
