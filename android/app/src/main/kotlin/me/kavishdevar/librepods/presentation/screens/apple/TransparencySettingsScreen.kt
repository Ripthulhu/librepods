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

import android.annotation.SuppressLint
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.att.types.TransparencySettings
import me.kavishdevar.librepods.core.bluetooth.att.types.parseTransparencySettingsResponse
import me.kavishdevar.librepods.core.bluetooth.att.types.sendTransparencySettings
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.components.primitives.StyledSlider
import me.kavishdevar.librepods.presentation.components.primitives.StyledToggle
import me.kavishdevar.librepods.presentation.icons.LocalIcons
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LocalDesignSystem
import me.kavishdevar.librepods.presentation.design.sectionHeader
import me.kavishdevar.librepods.presentation.viewmodel.AppleViewModel

private const val TAG = "TransparencySettings"

@SuppressLint("DefaultLocale")
@Composable
fun TransparencySettingsScreen(
    viewModel: AppleViewModel,
    navigateBack: (() -> Unit)?,
) {
    val isDarkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val textColor = if (isDarkTheme) Color.White else Color.Black
    val verticalScrollState = rememberScrollState()

    val trackColor = if (isDarkTheme) Color(0xFFB3B3B3) else Color(0xFF929491)
    val activeTrackColor = if (isDarkTheme) Color(0xFF007AFF) else Color(0xFF3C6DF5)
    val thumbColor = if (isDarkTheme) Color(0xFFFFFFFF) else Color(0xFFFFFFFF)

    val uiState by viewModel.uiState.collectAsState()

    val state = uiState.state

    StyledScaffold(
        title = stringResource(R.string.customize_transparency_mode),
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
            val backgroundColor = MaterialTheme.colorScheme.surfaceContainer

            val data = state.transparencyData
            val settings = remember(data) { mutableStateOf(parseTransparencySettingsResponse(data)) }
            val writable = settings.value != null
            fun updateSettings(edit: (TransparencySettings) -> TransparencySettings) {
                val current = settings.value ?: return
                val updated = edit(current)
                if (updated == current) return
                settings.value = updated
                sendTransparencySettings(data, updated, viewModel::writeATTCharacteristic)
            }
            if (uiState.vendorIdHook) {
                StyledToggle(
                    enabled = writable,
                    label = stringResource(R.string.transparency_mode),
                    checked = settings.value?.enabled ?: false,
                    description = stringResource(R.string.customize_transparency_mode_description),
                    onCheckedChange = { updateSettings { settings -> settings.copy(enabled = it) } }
                )
                Spacer(modifier = Modifier.height(4.dp))
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
                    independent = true
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
                    description = stringResource(R.string.conversation_boost_description),
                    onCheckedChange = { updateSettings { settings -> settings.copy(leftConversationBoost = it, rightConversationBoost = it) } }
                )

                Text(
                    text = stringResource(R.string.equalizer),
                    style = MaterialTheme.typography.labelSmallEmphasized,
                    color = if (LocalDesignSystem.current == DesignSystem.Material) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.sectionHeader,
                    modifier = Modifier.padding(16.dp, bottom = 4.dp)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(backgroundColor, RoundedCornerShape(28.dp))
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    for (i in 0 until 8) {
                        val eqValue = remember(settings.value?.leftEQ?.get(i)) { mutableFloatStateOf(settings.value?.leftEQ?.get(i) ?: 0f) }
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp)
                        ) {
                            Text(
                                text = String.format("%.2f", eqValue.floatValue),
                                fontSize = 12.sp,
                                color = textColor,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )

                            Slider(
                                enabled = writable,
                                value = eqValue.floatValue,
                                onValueChange = { newVal ->
                                    eqValue.floatValue = newVal
                                    updateSettings { settings -> settings.copy(
                                        leftEQ = settings.leftEQ.copyOf().also { it[i] = newVal },
                                        rightEQ = settings.rightEQ.copyOf().also { it[i] = newVal }) }
                                },
                                valueRange = 0f..100f,
                                modifier = Modifier
                                    .fillMaxWidth(0.9f)
                                    .height(36.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = thumbColor,
                                    activeTrackColor = activeTrackColor,
                                    inactiveTrackColor = trackColor
                                ),
                                thumb = {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .shadow(4.dp, CircleShape)
                                            .background(thumbColor, CircleShape)
                                    )
                                },
                                track = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(12.dp),
                                        contentAlignment = Alignment.CenterStart
                                    )
                                    {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(4.dp)
                                                .background(trackColor, RoundedCornerShape(4.dp))
                                        )
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(eqValue.floatValue / 100f)
                                                .height(4.dp)
                                                .background(
                                                    activeTrackColor,
                                                    RoundedCornerShape(4.dp)
                                                )
                                        )
                                    }
                                }
                            )

                            Text(
                                text = stringResource(R.string.band_label, i + 1),
                                fontSize = 12.sp,
                                color = textColor,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            Spacer(modifier = Modifier.height(bottomPadding))
        }
    }
}
