package ir.talayar.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ir.talayar.app.domain.model.AlertKind
import ir.talayar.app.ui.components.ThresholdField
import ir.talayar.app.ui.theme.Dimens

/**
 * Bottom sheet for creating a price alert:
 *  - عبور از حد بالا / پایین (قیمت هدف)
 *  - تغییر درصدی شدید (٪)
 * The alert engine (Worker + Notification) is already wired; new kinds can be
 * added here without touching the pipeline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertSheet(
    assetName: String,
    unitLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (AlertKind, Double) -> String?,
) {
    var kind by remember { mutableStateOf(AlertKind.ABOVE) }
    var thresholdText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenPadding)
                .padding(bottom = Dimens.SpaceXXL),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
        ) {
            Text("هشدار قیمت — $assetName", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "با فعال بودن اعلان‌ها، هنگام برقراری شرط هشدار به شما اطلاع می‌دهیم.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (option in AlertKind.entries) {
                    FilterChip(
                        selected = kind == option,
                        onClick = { kind = option },
                        label = { Text(option.label) },
                    )
                }
            }

            ThresholdField(
                value = thresholdText,
                onValueChange = {
                    thresholdText = it
                    error = null
                },
                label = if (kind == AlertKind.PERCENT) "درصد تغییر" else "قیمت هدف",
                suffix = if (kind == AlertKind.PERCENT) "٪" else unitLabel,
                errorMessage = error,
            )

            Button(
                onClick = {
                    val value = thresholdText.toDoubleOrNull()
                    if (value == null) {
                        error = "لطفاً یک عدد معتبر وارد کنید."
                    } else {
                        error = onConfirm(kind, value)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = thresholdText.isNotBlank(),
            ) {
                Text("ثبت هشدار")
            }
        }
    }
}
