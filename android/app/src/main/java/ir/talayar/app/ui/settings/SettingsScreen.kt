package ir.talayar.app.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.talayar.app.BuildConfig
import ir.talayar.app.data.settings.RefreshInterval
import ir.talayar.app.data.settings.SettingsStore
import ir.talayar.app.data.settings.ThemeMode
import ir.talayar.app.ui.theme.Dimens

@Composable
fun SettingsRoute(viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.uiState.collectAsStateWithLifecycle()
    val serverTest by viewModel.serverTestState.collectAsStateWithLifecycle()
    val updateCheck by viewModel.updateCheckState.collectAsStateWithLifecycle()

    SettingsScreen(
        settings = settings,
        serverTest = serverTest,
        updateCheck = updateCheck,
        onThemeChange = viewModel::setThemeMode,
        onIntervalChange = viewModel::setRefreshInterval,
        onNotificationsChange = viewModel::setNotificationsEnabled,
        onCheckUpdates = viewModel::checkUpdates,
        onSaveServerUrl = viewModel::saveServerUrl,
        onResetServerUrl = viewModel::resetServerUrl,
        onTestServer = viewModel::testServer,
    )
}

@Composable
fun SettingsScreen(
    settings: ir.talayar.app.domain.model.AppSettings,
    serverTest: SettingsViewModel.ServerTestState,
    onThemeChange: (ThemeMode) -> Unit,
    onIntervalChange: (RefreshInterval) -> Unit,
    onNotificationsChange: (Boolean) -> Unit,
    updateCheck: SettingsViewModel.UpdateCheckState = SettingsViewModel.UpdateCheckState.Idle,
    onCheckUpdates: () -> Unit = {},
    onSaveServerUrl: (String?) -> Unit,
    onResetServerUrl: () -> Unit,
    onTestServer: (String) -> Unit,
) {
    var showServerDialog by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showLegal by remember { mutableStateOf(false) }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> onNotificationsChange(granted) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Dimens.SpaceXXL),
    ) {
        Text(
            text = "تنظیمات",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(
                start = Dimens.ScreenPadding,
                end = Dimens.ScreenPadding,
                top = Dimens.SpaceL,
                bottom = Dimens.SpaceM,
            ),
        )

        SettingsGroup(title = "ظاهر") {
            ChipRow(
                options = ThemeMode.entries.map { it.name to it.label },
                selected = settings.themeMode.name,
                onSelect = { id -> ThemeMode.fromId(id)?.let(onThemeChange) },
            )
        }

        SettingsGroup(title = "بروزرسانی خودکار قیمت‌ها") {
            ChipRow(
                options = RefreshInterval.entries.map { it.name to it.label },
                selected = settings.refreshInterval.name,
                onSelect = { id -> RefreshInterval.fromId(id).let(onIntervalChange) },
            )
        }

        SettingsGroup(title = "اعلان‌ها") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.ScreenPadding),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("هشدار قیمت", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "نمایش نوتیفیکیشن هنگام عبور قیمت از حد تعیین‌شده",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.notificationsEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            onNotificationsChange(enabled)
                        }
                    },
                )
            }
        }

        SettingsGroup(title = "سرور قیمت") {
            val label = settings.serverUrl ?: "پیش‌فرض (GitHub Pages)"
            Text(
                text = "آدرس فعلی: $label",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = Dimens.ScreenPadding),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceS),
            ) {
                Button(onClick = { showServerDialog = true }, modifier = Modifier.weight(1f)) {
                    Text("تغییر آدرس سرور")
                }
                OutlinedButton(onClick = onResetServerUrl, modifier = Modifier.weight(1f)) {
                    Text("بازگشت به پیش‌فرض")
                }
            }
            Text(
                text = "برای به‌روزرسانی لحظه‌ای (هر ۱۵ ثانیه) می‌توانید سرور اختصاصی خود را متصل کنید. راهنما در README پروژه.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.ScreenPadding),
            )
        }

        SettingsGroup(title = "درباره برنامه") {
            SettingRowButton(
                text = when (updateCheck) {
                    SettingsViewModel.UpdateCheckState.Checking -> "در حال بررسی بروزرسانی…"
                    SettingsViewModel.UpdateCheckState.Latest -> "بروزرسانی: آخرین نسخه را دارید"
                    is SettingsViewModel.UpdateCheckState.Available ->
                        "بروزرسانی: نسخه جدید (${ir.talayar.app.core.Formatters.toPersianDigits(updateCheck.version)}) موجود است"
                    is SettingsViewModel.UpdateCheckState.Failed -> "بررسی بروزرسانی ناموفق بود — تلاش مجدد"
                    SettingsViewModel.UpdateCheckState.Idle -> "بررسی بروزرسانی"
                },
            ) { onCheckUpdates() }
            SettingRowButton(text = "طلایار — نسخه ${ir.talayar.app.core.Formatters.toPersianDigits(BuildConfig.APP_VERSION_NAME)}") { showAbout = true }
            SettingRowButton(text = "قوانین و حریم خصوصی") { showLegal = true }
            Text(
                text = "ساخته‌شده با Kotlin، Jetpack Compose و Material 3. فونت وزیرمتن (OFL). داده‌های قیمت از درگاه چندمنبعی طلایار دریافت می‌شود.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = Dimens.ScreenPadding,
                    end = Dimens.ScreenPadding,
                    top = Dimens.SpaceS,
                ),
            )
        }
    }

    if (showServerDialog) {
        ServerUrlDialog(
            current = settings.serverUrl ?: "",
            testState = serverTest,
            onDismiss = { showServerDialog = false },
            onSave = {
                onSaveServerUrl(it)
                showServerDialog = false
            },
            onTest = onTestServer,
        )
    }
    if (showAbout) {
        InfoDialog(title = "درباره طلایار", onDismiss = { showAbout = false }) {
            Text(
                "طلایار یک اپلیکیشن فارسی و راست‌به‌چپ برای نمایش قیمت لحظه‌ای طلا، سکه و ارز است.\n\n" +
                    "معماری: Jetpack Compose + Material 3 + MVVM + Clean Architecture\n" +
                    "داده‌ها: درگاه قیمت چندمنبعی با Failover خودکار\n" +
                    "ذخیره‌سازی آفلاین: Room\n\n" +
                    "سازنده: جواد عیسی‌لو\n" +
                    "تلگرام: @javadisaloo",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    if (showLegal) {
        InfoDialog(title = "قوانین و حریم خصوصی", onDismiss = { showLegal = false }) {
            Text(
                "حریم خصوصی:\nطلایار هیچ اطلاعات شخصی شما را جمع‌آوری، ذخیره یا منتقل نمی‌کند. تنظیمات و علاقه‌مندی‌ها فقط روی همین دستگاه ذخیره می‌شوند.\n\n" +
                    "سلب مسئولیت:\nقیمت‌های نمایش‌داده‌شده صرفاً اطلاع‌رسانی هستند و هیچ پیشنهاد خرید یا فروش محسوب نمی‌شوند. مسئولیت استفاده از این اطلاعات با کاربر است.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(
                start = Dimens.ScreenPadding,
                end = Dimens.ScreenPadding,
                top = Dimens.SpaceL,
                bottom = Dimens.SpaceS,
            ),
        )
        content()
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceM),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun ChipRow(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = Dimens.ScreenPadding),
    ) {
        options.forEach { (id, label) ->
            FilterChip(
                selected = id == selected,
                onClick = { onSelect(id) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun SettingRowButton(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.SpaceS),
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ServerUrlDialog(
    current: String,
    testState: SettingsViewModel.ServerTestState,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onTest: (String) -> Unit,
) {
    var value by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("آدرس سرور قیمت") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("https://your-server.example/") },
                    singleLine = true,
                )
                Text(
                    text = "آدرس باید با https شروع شود. خالی بگذارید تا از سرور پیش‌فرض استفاده شود.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (testState) {
                    is SettingsViewModel.ServerTestState.Testing -> Text("در حال تست اتصال…", style = MaterialTheme.typography.bodySmall)
                    is SettingsViewModel.ServerTestState.Ok -> Text(
                        "✓ اتصال موفق — وضعیت سرور: ${testState.status}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    is SettingsViewModel.ServerTestState.Failed -> Text(
                        "✗ ${testState.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> {}
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                    OutlinedButton(onClick = { onTest(value) }, enabled = value.isNotBlank()) {
                        Text("تست اتصال")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(value) }) { Text("ذخیره") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("انصراف") }
        },
    )
}

@Composable
private fun InfoDialog(title: String, onDismiss: () -> Unit, body: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = body,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("بستن") }
        },
    )
}
