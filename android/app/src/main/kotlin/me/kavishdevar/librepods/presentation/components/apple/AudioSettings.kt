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

@file:OptIn(ExperimentalEncodingApi::class)

package me.kavishdevar.librepods.presentation.components.apple

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.StyledListScope
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem
import me.kavishdevar.librepods.presentation.components.primitives.styledToggle
import kotlin.io.encoding.ExperimentalEncodingApi

@Composable
fun AudioSettings(
    adaptiveVolumeCapability: Boolean,
    conversationAwarenessCapability: Boolean,
    loudSoundReductionCapability: Boolean,
    customEqCapability: Boolean,

    adaptiveVolumeChecked: Boolean,
    onAdaptiveVolumeCheckedChange: (Boolean) -> Unit,

    conversationAwarenessChecked: Boolean,
    onConversationAwarenessCheckedChange: (Boolean) -> Unit,

    loudSoundReductionChecked: Boolean,
    onLoudSoundReductionCheckedChange: (Boolean) -> Unit,

    navigateToEqualizer: () -> Unit,

    gameModeVisible: Boolean = false,
    gameModeEnabled: Boolean = false,
    gameModeChecked: Boolean = false,
    gameModeDescription: String? = null,
    onGameModeChanged: (Boolean) -> Unit = {},
    adaptiveVolumeEnabled: Boolean = true,
    conversationAwarenessEnabled: Boolean = true,
    loudSoundReductionEnabled: Boolean = false,
    spatialAudioContent: (@Composable StyledListScope.() -> Unit)? = null,
) {
    val anySettingShown = adaptiveVolumeCapability || conversationAwarenessCapability || loudSoundReductionCapability ||
        gameModeVisible || spatialAudioContent != null
    if (anySettingShown) {
        StyledList(title = stringResource(R.string.audio)) {
            spatialAudioContent?.invoke(this)
            if (gameModeVisible) {
                styledToggle(
                    label = stringResource(R.string.native_game_mode),
                    description = gameModeDescription,
                    checked = gameModeChecked,
                    enabled = gameModeEnabled || gameModeChecked,
                    onCheckedChange = onGameModeChanged,
                )
            }
            if (adaptiveVolumeCapability) {
                styledToggle(
                    label = stringResource(R.string.personalized_volume),
                    description = stringResource(R.string.personalized_volume_description),
                    checked = adaptiveVolumeChecked,
                    enabled = adaptiveVolumeEnabled,
                    onCheckedChange = onAdaptiveVolumeCheckedChange,
                )
            }

            if (conversationAwarenessCapability) {
                styledToggle(
                    label = stringResource(R.string.conversation_awareness),
                    description = stringResource(R.string.conversation_awareness_description),
                    checked = conversationAwarenessChecked,
                    enabled = conversationAwarenessEnabled,
                    onCheckedChange = onConversationAwarenessCheckedChange,
                )
            }

            if (loudSoundReductionCapability) {
                styledToggle(
                    label = stringResource(R.string.loud_sound_reduction),
                    description = stringResource(R.string.loud_sound_reduction_description),
                    checked = loudSoundReductionChecked,
                    enabled = loudSoundReductionEnabled,
                    onCheckedChange = onLoudSoundReductionCheckedChange,
                )
            }

            if (customEqCapability) {
                styledListItem(
                    contentText = stringResource(R.string.equalizer),
                    onClick = navigateToEqualizer,
                )
            }
        }
    }
}
