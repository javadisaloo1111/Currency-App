package ir.talayar.app.ui.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ir.talayar.app.domain.model.AppUpdate
import ir.talayar.app.domain.model.UpdateError
import ir.talayar.app.domain.model.UpdateErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Update dialog UX contract:
 *  - plain Persian copy («نسخه جدید آمده است» / «به‌روزرسانی» / «بعداً»)
 *  - no technical jargon (APK, GitHub, Release, API…) and no raw exception text anywhere
 *  - forced updates hide «بعداً»
 *  - a failed *download* retries the download, a failed *check* retries the check
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
        override fun checkNow() { calls += "checkNow" }
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
        assertNoJargon()
    }

    @Test
    fun `later button dismisses available update`() {
        val actions = render(UpdateViewModel.State.Available(update))
        composeRule.onNodeWithText("بعداً").performClick()
        assertEquals(listOf("dismiss"), actions.calls)
    }

    @Test
    fun `forced updates hide the later button`() {
        val forced = update.copy(forced = true)
        render(UpdateViewModel.State.Available(forced))
        composeRule.onNodeWithText("بعداً").assertDoesNotExist()
    }

    @Test
    fun `checking state shows a spinner dialog that can be cancelled`() {
        val actions = render(UpdateViewModel.State.Checking)

        composeRule.onNodeWithText("در حال بررسی بروزرسانی…").assertIsDisplayed()
        composeRule.onNodeWithText("لطفاً چند لحظه صبر کنید.").assertIsDisplayed()
        composeRule.onNodeWithText("انصراف").performClick()
        assertEquals(listOf("dismiss"), actions.calls)
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
    fun `a failed download shows the reason and retries the download`() {
        val actions = render(
            UpdateViewModel.State.Error(
                UpdateError(UpdateErrorKind.INCOMPLETE_DOWNLOAD, "expected 8645881 bytes, got 1048576"),
                update,
            ),
        )

        composeRule.onNodeWithText("دانلود ناموفق بود").assertIsDisplayed()
        composeRule.onNodeWithText(UpdateErrorKind.INCOMPLETE_DOWNLOAD.userMessage).assertIsDisplayed()
        composeRule.onNodeWithText("تلاش مجدد").performClick()
        assertEquals(listOf("retryDownload"), actions.calls)
    }

    @Test
    fun `a failed check shows the reason and retries the check`() {
        val actions = render(
            UpdateViewModel.State.Error(UpdateError(UpdateErrorKind.RATE_LIMITED, "HTTP 403")),
        )

        composeRule.onNodeWithText("بروزرسانی ناموفق بود").assertIsDisplayed()
        composeRule.onNodeWithText(UpdateErrorKind.RATE_LIMITED.userMessage).assertIsDisplayed()
        composeRule.onNodeWithText("تلاش مجدد").performClick()
        assertEquals(listOf("checkNow"), actions.calls)
    }

    @Test
    fun `an unreachable update server is not reported as being offline`() {
        render(UpdateViewModel.State.Error(UpdateError(UpdateErrorKind.CONNECTION_FAILED, "reset by peer")))

        composeRule.onNodeWithText("ارتباط با سرور بروزرسانی برقرار نشد. لطفاً دوباره تلاش کنید.")
            .assertIsDisplayed()
        composeRule.onNodeWithText(UpdateErrorKind.NO_INTERNET.userMessage).assertDoesNotExist()
    }

    @Test
    fun `an offline device is told to turn on its internet`() {
        render(UpdateViewModel.State.Error(UpdateError(UpdateErrorKind.NO_INTERNET, "no network")))

        composeRule.onNodeWithText(UpdateErrorKind.NO_INTERNET.userMessage).assertIsDisplayed()
    }

    @Test
    fun `every failure message is persian, distinct and free of jargon`() {
        // ComposeTestRule allows setContent only once per test; drive kinds via mutable state.
        var state by mutableStateOf<UpdateViewModel.State>(
            UpdateViewModel.State.Error(
                UpdateError(UpdateErrorKind.entries.first(), "java.net.SocketTimeoutException: timeout"),
            ),
        )
        composeRule.setContent {
            UpdateDialog(state = state, actions = RecordingActions())
        }
        for (kind in UpdateErrorKind.entries) {
            state = UpdateViewModel.State.Error(
                UpdateError(kind, "java.net.SocketTimeoutException: timeout"),
            )
            composeRule.waitForIdle()
            composeRule.onNodeWithText(kind.userMessage).assertIsDisplayed()
            assertNoJargon()
            // A raw exception must never reach the screen.
            composeRule.onNodeWithText("SocketTimeoutException", substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun `an error dialog can be postponed`() {
        val actions = render(UpdateViewModel.State.Error(UpdateError(UpdateErrorKind.TIMEOUT, "timed out")))

        composeRule.onNodeWithText("بعداً").performClick()
        assertEquals(listOf("dismiss"), actions.calls)
    }

    @Test
    fun `permission-needed state guides to settings`() {
        val actions = render(UpdateViewModel.State.NeedsPermission(update))

        composeRule.onNodeWithText("اجازهٔ نصب لازم است").assertIsDisplayed()
        composeRule.onNodeWithText("رفتن به تنظیمات").performClick()
        assertEquals(listOf("openPermissionSettings"), actions.calls)
    }

    @Test
    fun `permission-needed state offers install again after granting`() {
        val actions = render(UpdateViewModel.State.NeedsPermission(update))

        composeRule.onNodeWithText("نصب").performClick()
        assertEquals(listOf("retryInstall"), actions.calls)
    }

    @Test
    fun `hidden state renders nothing`() {
        render(UpdateViewModel.State.Hidden)
        composeRule.onNodeWithText("نسخه جدید آمده است").assertDoesNotExist()
    }

    @Test
    fun `ready state renders nothing until install is triggered`() {
        render(UpdateViewModel.State.Ready)
        composeRule.onNodeWithText("نسخه جدید آمده است").assertDoesNotExist()
    }

    private fun assertNoJargon() {
        for (jargon in listOf("APK", "GitHub", "Release", "API", "Version Code", "apk", "Exception")) {
            composeRule.onAllNodesWithText(jargon, substring = true).fetchSemanticsNodes().let {
                assertTrue("jargon '$jargon' must not appear in the update dialog", it.isEmpty())
            }
        }
    }
}
