/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.presentation.screens.apple

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.att.ATTHandle
import me.kavishdevar.librepods.core.bluetooth.att.types.HearingAidSettings
import me.kavishdevar.librepods.core.bluetooth.att.types.parseHearingAidSettingsResponse
import me.kavishdevar.librepods.core.bluetooth.att.types.sendHearingAidSettings
import me.kavishdevar.librepods.core.bluetooth.att.types.hearingAidSettingsWritable
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.presentation.viewmodel.AppleUiState
import me.kavishdevar.librepods.presentation.viewmodel.AppleViewModel
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "UpdateHearingTestScreen"

@Composable
fun UpdateHearingTestRoute(
    viewModel: AppleViewModel,
    navigateBack: (() -> Unit)?
) {
    val uiState by viewModel.uiState.collectAsState()

    UpdateHearingTestScreen(
        uiState = uiState,
        navigateBack = navigateBack,
        setATTCharacteristicValue = viewModel::writeATTCharacteristic
    )
}

@Composable
fun UpdateHearingTestScreen(
    uiState: AppleUiState,
    navigateBack: (() -> Unit)?,
    setATTCharacteristicValue: (ATTHandle, ByteArray) -> Unit
) {
    val state = uiState.state

    val verticalScrollState = rememberScrollState()

    StyledScaffold(
        title = stringResource(R.string.update_hearing_test),
        navigateBack = navigateBack
    ) { topPadding, bottomPadding ->
        Column(
            modifier = Modifier
                .verticalScroll(verticalScrollState)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(topPadding))

            Text(
                text = stringResource(R.string.hearing_test_value_instruction),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
            )
            val data = state.hearingAidData
            val writable = hearingAidSettingsWritable(data)
            val settings = remember(data) { mutableStateOf(parseHearingAidSettingsResponse(data)) }
            if (!writable) Text(stringResource(R.string.hearing_settings_read_only), style = MaterialTheme.typography.bodySmall)
            fun updateBand(index: Int, text: String, left: Boolean) {
                if (!writable) return
                val value = text.toFloatOrNull()?.takeIf { it.isFinite() } ?: return
                val current = settings.value ?: return
                val updated = if (left) current.copy(leftEQ = current.leftEQ.copyOf().also { it[index] = value })
                    else current.copy(rightEQ = current.rightEQ.copyOf().also { it[index] = value })
                if (updated == current) return
                settings.value = updated
                sendHearingAidSettings(data, updated, setATTCharacteristicValue)
            }
            val frequencies =
                listOf("250Hz", "500Hz", "1kHz", "2kHz", "3kHz", "4kHz", "6kHz", "8kHz")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Spacer(modifier = Modifier.width(60.dp))
                Text(
                    text = stringResource(R.string.left),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMediumEmphasized
                )
                Text(
                    text = stringResource(R.string.right),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMediumEmphasized
                )
            }

            frequencies.forEachIndexed { index, freq ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = freq,
                        modifier = Modifier
                            .width(60.dp)
                            .align(Alignment.CenterVertically),
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.labelSmall
                    )
                    OutlinedTextField(
                        value = settings.value?.leftEQ?.get(index)?.toString().orEmpty(),
                        readOnly = !writable,
                        onValueChange = { updateBand(index, it, left = true) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        textStyle = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = settings.value?.rightEQ?.get(index)?.toString().orEmpty(),
                        readOnly = !writable,
                        onValueChange = { updateBand(index, it, left = false) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        textStyle = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(bottomPadding))
        }
    }
}

@Preview(name = "Apple")
@Composable
fun UpdateHearingTestScreenPreviewApple() {
    LibrePodsTheme(
        designSystem = DesignSystem.Apple
    ) {
        Box (
            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            UpdateHearingTestScreen(
                uiState = AppleUiState(),
                navigateBack = null,
                setATTCharacteristicValue = { _, _ -> }
            )
        }
    }
}

@Preview(name = "Material")
@Composable
fun UpdateHearingTestScreenPreviewMaterial() {
    LibrePodsTheme(
        designSystem = DesignSystem.Material
    ) {
        Box (
            modifier = Modifier
                .wrapContentHeight()
                .background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            UpdateHearingTestScreen(
                uiState = AppleUiState(),
                navigateBack = null,
                setATTCharacteristicValue = { _, _ -> }
            )
        }
    }
}

