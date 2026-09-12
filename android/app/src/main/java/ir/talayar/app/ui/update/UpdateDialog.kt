package ir.talayar.app.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.talayar.app.core.Formatters

/**
 * Global update flow host: renders the update dialog on top of the app
 * whenever a new version is available.
 */
@Composable
fun UpdateHost(viewModel: UpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    UpdateDialog(state = state, actions = viewModel)
}

/**
 * Persian RTL update dialog (Material 3, matches the app theme).
 *
 * Copy stays user-facing: no "APK"/"GitHub"/"Release" jargon, just
 * «نسخه جدید آمده است» / «به‌روزرسانی» / «بعداً». Failures show the *reason*
 * (`UpdateError.userMessage`) — never a raw exception, a stack trace or a
 * misleading «no internet» when the network is up but the update server is not
 * reachable.
 */
@Composable
fun UpdateDialog(state: UpdateViewModel.State, actions: UpdateActions) {
    when (state) {
        UpdateViewModel.State.Hidden -> Unit

        UpdateViewModel.State.Checking -> {
            AlertDialog(
                onDismissRequest = { actions.dismiss() },
                title = { Text("در حال بررسی بروزرسانی…") },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            text = "لطفاً چند لحظه صبر کنید.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = actions::dismiss) { Text("انصراف") }
                },
            )
        }

        is UpdateViewModel.State.Available -> {
            val forced = state.update.forced
            AlertDialog(
                onDismissRequest = { if (!forced) actions.dismiss() },
                title = { Text("نسخه جدید آمده است") },
                text = {
                    Text(
                        "نسخهٔ ${Formatters.toPersianDigits(state.update.latestVersion)} از طلایار منتشر شده است. " +
                            "با به‌روزرسانی، همیشه جدیدترین قیمت‌ها و امکانات را داشته باشید.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = actions::startDownload) { Text("به‌روزرسانی") }
                },
                dismissButton = {
                    if (!forced) {
                        TextButton(onClick = actions::dismiss) { Text("بعداً") }
                    }
                },
            )
        }

        is UpdateViewModel.State.Downloading -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("در حال دانلود بروزرسانی") },
                text = {
                    Column {
                        LinearProgressIndicator(
                            progress = { state.percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "${Formatters.toPersianDigits(state.percent.toString())}٪",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = actions::cancelDownload) { Text("انصراف") }
                },
            )
        }

        // The verified package is with the system installer now; nothing to render.
        UpdateViewModel.State.Ready -> Unit

        is UpdateViewModel.State.NeedsPermission -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("اجازهٔ نصب لازم است") },
                text = {
                    Text(
                        "برای نصب نسخهٔ جدید، در صفحه‌ای که باز می‌شود اجازهٔ نصب از این برنامه را فعال کنید " +
                            "و سپس دوباره «نصب» را بزنید.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = actions::openPermissionSettings) { Text("رفتن به تنظیمات") }
                },
                dismissButton = {
                    TextButton(onClick = actions::retryInstall) { Text("نصب") }
                },
            )
        }

        is UpdateViewModel.State.Error -> {
            // A failure of the download/install phase keeps the update context and
            // retries the download; a failure of the check phase retries the check.
            val duringDownload = state.update != null
            AlertDialog(
                onDismissRequest = { actions.dismiss() },
                title = { Text(if (duringDownload) "دانلود ناموفق بود" else "بروزرسانی ناموفق بود") },
                text = { Text(state.error.userMessage) },
                confirmButton = {
                    TextButton(
                        onClick = { if (duringDownload) actions.retryDownload() else actions.checkNow() },
                    ) {
                        Text("تلاش مجدد")
                    }
                },
                dismissButton = {
                    TextButton(onClick = actions::dismiss) { Text("بعداً") }
                },
            )
        }
    }
}
