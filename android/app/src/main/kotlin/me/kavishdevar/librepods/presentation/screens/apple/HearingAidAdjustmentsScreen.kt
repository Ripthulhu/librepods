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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.bluetooth.att.ATTHandle
import me.kavishdevar.librepods.core.bluetooth.att.types.HearingAidSettings
import me.kavishdevar.librepods.core.bluetooth.att.types.parseHearingAidSettingsResponse
import me.kavishdevar.librepods.core.bluetooth.att.types.sendHearingAidSettings
import me.kavishdevar.librepods.core.bluetooth.att.types.hearingAidSettingsWritable
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.StyledSlider
import me.kavishdevar.librepods.presentation.components.primitives.StyledToggle
import me.kavishdevar.librepods.presentation.icons.LocalIcons
import me.kavishdevar.librepods.presentation.viewmodel.AppleViewModel
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "HearingAidAdjustments"

@Composable
fun HearingAidAdjustmentsScreen(
    viewModel: AppleViewModel,
    navigateBack: (() -> Unit)?
) {
    val verticalScrollState = rememberScrollState()
    val uiState by viewModel.uiState.collectAsState()

    val state = uiState.state

    val data = state.hearingAidData
    val writable = hearingAidSettingsWritable(data)
    val settings = remember(data) { mutableStateOf(parseHearingAidSettingsResponse(data)) }
    fun updateSettings(edit: (HearingAidSettings) -> HearingAidSettings) {
        if (!writable) return
        val current = settings.value ?: return
        val updated = edit(current)
        if (updated == current) return
        settings.value = updated
        sendHearingAidSettings(data, updated, viewModel::writeATTCharacteristic)
    }
    DisposableEffect(viewModel) {
        viewModel.observeATTCharacteristic(ATTHandle.HEARING_AID)
        onDispose { viewModel.stopObservingATTCharacteristic() }
    }
    StyledScaffold(
        title = stringResource(R.string.adjustments),
        navigateBack = navigateBack
    ) { topPadding, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .verticalScroll(verticalScrollState)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(topPadding))
            if (!writable) Text(stringResource(R.string.hearing_settings_read_only), style = MaterialTheme.typography.bodySmall)

            StyledSlider(
                enabled = writable,
                label = stringResource(R.string.amplification),
                valueRange = -1f..1f,
                value = (settings.value?.netAmplification ?: 0f).coerceIn(-1f, 1f),
                onValueChange = {
                    updateSettings { settings -> settings.withAmplification(amplification = it) }
                },
                startImageVector = LocalIcons.current.SpeakerMin,
                endImageVector = LocalIcons.current.SpeakerMax,
                independent = true,
            )

            StyledToggle(
                enabled = writable,
                label = stringResource(R.string.swipe_to_control_amplification),
                checked = state.controlStates[ControlCommandIdentifier.HPS_GAIN_SWIPE]?.getOrNull(0) == 0x01.toByte(),
                onCheckedChange = { viewModel.setControlCommand(ControlCommandIdentifier.HPS_GAIN_SWIPE, it) },
                description = stringResource(R.string.swipe_amplification_description)
            )

            StyledSlider(
                enabled = writable,
                label = stringResource(R.string.balance),
                valueRange = -1f..1f,
                value = (settings.value?.balance ?: 0f).coerceIn(-1f, 1f),
                onValueChange = {
                    updateSettings { settings -> settings.withAmplification(balance = it) }
                },
                snapPoints = listOf(-1f, 0f, 1f),
                startLabel = stringResource(R.string.left),
                endLabel = stringResource(R.string.right),
                independent = true,
            )

            StyledSlider(
                enabled = writable,
                label = stringResource(R.string.tone),
                valueRange = -1f..1f,
                value = (settings.value?.leftTone ?: 0f).coerceIn(-1f, 1f),
                onValueChange = {
                    updateSettings { settings -> settings.copy(leftTone = it, rightTone = it) }
                },
                startLabel = stringResource(R.string.darker),
                endLabel = stringResource(R.string.brighter),
                independent = true,
            )

            StyledSlider(
                enabled = writable,
                label = stringResource(R.string.ambient_noise_reduction),
                valueRange = 0f..1f,
                value = (settings.value?.leftAmbientNoiseReduction ?: 0f).coerceIn(0f, 1f),
                onValueChange = {
                    updateSettings { settings -> settings.copy(leftAmbientNoiseReduction = it, rightAmbientNoiseReduction = it) }
                },
                startLabel = stringResource(R.string.less),
                endLabel = stringResource(R.string.more),
                independent = true,
            )

            StyledToggle(
                enabled = writable,
                label = stringResource(R.string.conversation_boost),
                checked = settings.value?.leftConversationBoost ?: false,
                onCheckedChange = { updateSettings { settings -> settings.copy(leftConversationBoost = it, rightConversationBoost = it) } },
                description = stringResource(R.string.conversation_boost_description)
            )

            Spacer(modifier = Modifier.height(bottomPadding))
        }
    }
}
