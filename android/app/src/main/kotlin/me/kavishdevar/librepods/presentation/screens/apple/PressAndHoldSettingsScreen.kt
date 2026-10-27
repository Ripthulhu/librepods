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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.data.LiveValueState
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.data.StemAction
import me.kavishdevar.librepods.core.devices.*
import me.kavishdevar.librepods.presentation.components.primitives.*
import me.kavishdevar.librepods.presentation.viewmodel.AppleUiState
import me.kavishdevar.librepods.presentation.viewmodel.AppleViewModel

@Composable
fun LongPress(viewModel: AppleViewModel, name: String, navigateBack: (() -> Unit)?) {
    val uiState by viewModel.uiState.collectAsState()
    val connection by viewModel.connectionState.collectAsState()
    val writing by viewModel.writingNoiseModeCycle.collectAsState()
    val cycle by viewModel.liveNoiseCycleEditor().state.collectAsState()
    LongPressSettings(uiState, name, connection == ConnectionState.CONNECTED, writing, navigateBack,
        { side, action -> viewModel.setLongPressAction(side, action) },
        { mask, metadata -> viewModel.setListeningModeCycle(mask, metadata, cycle.revision) }, cycle,
        { viewModel.cancelListeningModeCycle(cycle.revision) })
}

internal fun resolveLongPressSide(route: String, leftLabel: String, rightLabel: String): String? = when (route) {
    "left", leftLabel -> "left"
    "right", rightLabel -> "right"
    else -> null
}

@Composable
internal fun LongPressSettings(uiState: AppleUiState, name: String, connected: Boolean, writing: Boolean,
    navigateBack: (() -> Unit)?, setAction: (String, StemAction) -> Unit, applyCycle: (Int, AppleMetadata) -> Unit,
    liveCycle: LiveValueState<Int>? = null, cancelCycle: () -> Unit = {}) {
    val leftLabel = stringResource(R.string.left)
    val rightLabel = stringResource(R.string.right)
    val side = resolveLongPressSide(name, leftLabel, rightLabel)
    val action = when (side) {
        "left" -> uiState.settings.leftLongPressAction
        "right" -> uiState.settings.rightLongPressAction
        else -> null
    }
    val title = when (side) { "left" -> leftLabel; "right" -> rightLabel; else -> stringResource(R.string.press_and_hold) }
    StyledScaffold(title = title, navigateBack = navigateBack) { top, bottom ->
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
            .padding(top = top + 8.dp, bottom = bottom), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            StyledList(key = side to action) {
                for ((choice, label) in listOf(StemAction.CYCLE_NOISE_CONTROL_MODES to R.string.noise_control,
                    StemAction.DIGITAL_ASSISTANT to R.string.digital_assistant)) {
                    styledListItem(contentText = stringResource(label), selected = action == choice,
                        enabled = connected && side != null, onClick = { side?.let { setAction(it, choice) } })
                }
            }
            if (action == StemAction.CYCLE_NOISE_CONTROL_MODES)
                NoiseModeCycleSettings(uiState.state, uiState.settings, uiState.metadata, connected, writing, liveCycle, cancelCycle) {
                    applyCycle(it, uiState.metadata)
                }
        }
    }
}

@Composable
internal fun NoiseModeCycleSettings(state: AppleState, settings: AppleSettings, metadata: AppleMetadata,
    enabled: Boolean, writing: Boolean, liveCycle: LiveValueState<Int>? = null,
    onCancel: () -> Unit = {}, onApply: (Int) -> Unit) {
    val id = ControlCommandIdentifier.LISTENING_MODE_CONFIGS
    val allowOff = state.effectiveAllowOff(settings, metadata.modelNumber)
    val supported = metadata.supportedNoiseModeCycleMask(allowOff)
    val current = noiseModeCycleValue(state.effectiveControl(id, settings))?.takeIf { validNoiseModeCycle(it, supported) }
    val confirmed = id in state.liveControlReports && state.requestedControls[id] == null &&
        current != null && noiseModeCycleValue(state.controlStates[id]) == current
    var draft by remember(state.controlSession, metadata, supported, liveCycle?.revision) { mutableStateOf<Int?>(null) }
    val chosen = draft ?: liveCycle?.displayed ?: current ?: 0
    LaunchedEffect(confirmed, current, liveCycle?.displayed) {
        if (draft == liveCycle?.displayed || confirmed && draft == current) draft = null
    }
    val modes = listOf(
        Triple(1, R.string.off, R.drawable.ic_noise_cancellation),
        Triple(4, R.string.transparency, R.drawable.ic_transparency),
        Triple(8, R.string.adaptive, R.drawable.ic_adaptive),
        Triple(2, R.string.noise_cancellation, R.drawable.ic_noise_cancellation))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.noise_cycle_shared_description), style = MaterialTheme.typography.bodyMedium)
        StyledList(title = stringResource(R.string.noise_control), key = chosen to supported) {
            // Hide modes this device can't cycle through right now (Off needs the Off Listening Mode toggle, Adaptive needs firmware support).
            modes.filter { (it.first != 8 && it.first != 1) || supported and it.first != 0 }.forEach { (bit, label, icon) ->
                styledListItem(contentText = stringResource(label), selected = chosen and bit != 0,
                    enabled = enabled && supported and bit != 0,
                    onClick = {
                        val next = chosen xor bit
                        draft = next
                        if (validNoiseModeCycle(next, supported)) onApply(next) else onCancel()
                    },
                    orientation = StyledListItemOrientation.Vertical,
                    leadingContent = { Icon(painterResource(icon), contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.height(42.dp).wrapContentWidth()) })
            }
        }
        if (supported and 1 == 0 && metadata.noiseCycleRequiresAllowOff())
            Text(stringResource(R.string.noise_cycle_off_prerequisite), style = MaterialTheme.typography.bodySmall)

    }
}
