/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.screens.apple

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.audio.SpatialCalibrationFailure
import me.kavishdevar.librepods.audio.SpatialCalibrationProgress
import me.kavishdevar.librepods.audio.SpatialCalibrationStage
import me.kavishdevar.librepods.audio.SpatialHeadTrackingProbeResult
import me.kavishdevar.librepods.presentation.components.primitives.MaterialButtonStyle
import me.kavishdevar.librepods.presentation.components.primitives.StyledButton
import me.kavishdevar.librepods.presentation.components.primitives.StyledScaffold
import me.kavishdevar.librepods.presentation.icons.LocalIcons

/** Presentation only: opening or restoring this screen never starts a sensor or a root operation. */
@Composable
internal fun SpatialCalibrationScreen(
    progress: SpatialCalibrationProgress?,
    running: Boolean,
    result: SpatialHeadTrackingProbeResult?,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    navigateBack: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val scrollState = rememberScrollState()
    var lastCue by rememberSaveable { mutableStateOf<String?>(null) }
    val stage = progress?.stage.takeIf { running }
    LaunchedEffect(running, stage, result) {
        scrollState.scrollTo(0)
        when {
            !running || stage == SpatialCalibrationStage.PREPARING || stage == SpatialCalibrationStage.NEUTRAL -> lastCue = null
            stage == SpatialCalibrationStage.LOOK_UP && lastCue != stage.name -> {
                lastCue = stage.name
                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
            }
            stage == SpatialCalibrationStage.RETURN && lastCue != stage.name -> {
                lastCue = stage.name
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }

    val completed = !running && spatialCalibrationCompleted(result)
    val paused = !running && result?.reason == "cancelled"
    val cleanupUnverified = !running && result?.cleanup?.let {
        !it.getBoolean("restored") || !it.getBoolean("sink_closed") || !it.getBoolean("cleanup_complete", true)
    } == true
    val needsSpatialAudio = !running && result?.reason == "spatial_disabled"
    val saveFailed = !running && result?.reason == "profile_save_failed"
    val leaveBeforeRetry = cleanupUnverified || needsSpatialAudio
    val title = when {
        running -> when (stage) {
            SpatialCalibrationStage.NEUTRAL -> R.string.spatial_calibration_neutral
            SpatialCalibrationStage.LOOK_UP -> R.string.spatial_calibration_look_up
            SpatialCalibrationStage.RETURN -> R.string.spatial_calibration_return
            SpatialCalibrationStage.READY -> R.string.spatial_calibration_preview
            else -> R.string.spatial_calibration_preparing
        }
        result == null -> R.string.spatial_calibration_intro_title
        cleanupUnverified -> R.string.spatial_calibration_stopping_failed
        needsSpatialAudio -> R.string.spatial_calibration_fixed_off
        saveFailed -> R.string.spatial_calibration_save_failed
        paused -> R.string.spatial_calibration_paused
        completed -> R.string.spatial_calibration_complete
        result.calibrated -> R.string.spatial_calibration_preview_failed
        else -> R.string.spatial_calibration_retry_title
    }
    val message = when {
        running -> when (stage) {
            SpatialCalibrationStage.NEUTRAL -> R.string.spatial_calibration_neutral_body
            SpatialCalibrationStage.LOOK_UP -> R.string.spatial_calibration_look_up_body
            SpatialCalibrationStage.RETURN -> R.string.spatial_calibration_return_body
            SpatialCalibrationStage.READY -> R.string.spatial_calibration_preview_body
            else -> R.string.spatial_calibration_preparing_body
        }
        result == null -> R.string.spatial_calibration_intro_body
        cleanupUnverified -> R.string.spatial_calibration_stopping_failed_body
        needsSpatialAudio -> R.string.spatial_calibration_fixed_off_body
        saveFailed -> R.string.spatial_calibration_save_failed_body
        paused -> R.string.spatial_calibration_paused_body
        completed -> R.string.spatial_calibration_complete_body
        result.calibrated -> R.string.spatial_calibration_preview_failed_body
        else -> calibrationRetryMessage(result.calibrationFailure)
    }

    StyledScaffold(title = stringResource(R.string.spatial_calibration_title), navigateBack = navigateBack) { top, bottom ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(top = top, bottom = bottom)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
            val availableHeight = maxHeight
            Column(Modifier.fillMaxWidth().verticalScroll(scrollState).testTag("spatial_calibration_scroll"),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(min = availableHeight).padding(24.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.fillMaxWidth().padding(top = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Icon(when {
                            completed -> LocalIcons.current.Check
                            stage == SpatialCalibrationStage.LOOK_UP -> Icons.Outlined.KeyboardArrowUp
                            stage == SpatialCalibrationStage.RETURN -> Icons.Outlined.KeyboardArrowDown
                            else -> LocalIcons.current.AirPodsPro3
                        }, contentDescription = null, modifier = Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(title), style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().testTag("spatial_calibration_instruction").semantics {
                                heading(); liveRegion = LiveRegionMode.Polite
                            })
                        Text(stringResource(message), style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth())
                        val remaining = progress?.secondsRemaining?.takeIf { running && stage != SpatialCalibrationStage.READY && it > 0 }
                        if (remaining != null) {
                            Text(pluralStringResource(R.plurals.spatial_calibration_seconds, remaining, remaining),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                                modifier = Modifier.testTag("spatial_calibration_countdown"))
                        }
                        if (!running && result == null) Text(stringResource(R.string.spatial_calibration_haptic_hint),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                    Column(Modifier.fillMaxWidth().padding(top = 40.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        StyledButton(Modifier.fillMaxWidth().testTag("spatial_calibration_primary"),
                            onClick = when { running -> onCancel; completed || leaveBeforeRetry -> navigateBack; else -> onStart },
                            materialButtonStyle = MaterialButtonStyle.Filled) {
                            Text(stringResource(when {
                                running -> R.string.cancel
                                leaveBeforeRetry -> R.string.spatial_calibration_go_back
                                completed -> R.string.spatial_calibration_done
                                paused -> R.string.spatial_calibration_start_again
                                result != null -> R.string.spatial_calibration_retry
                                else -> R.string.spatial_calibration_start
                            }), textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 14.dp))
                        }
                        if (!running && !completed && !leaveBeforeRetry) StyledButton(Modifier.fillMaxWidth(), onClick = navigateBack,
                            materialButtonStyle = MaterialButtonStyle.Normal) {
                            Text(stringResource(R.string.spatial_calibration_not_now), textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 12.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

internal fun spatialCalibrationCompleted(result: SpatialHeadTrackingProbeResult?) = result?.let {
    it.calibrated && it.calibrationFailure == null && it.reason in setOf("deadline", "duration_complete", "finished") &&
        it.cleanup?.getBoolean("restored") == true && it.cleanup.getBoolean("sink_closed") &&
        it.cleanup.getBoolean("cleanup_complete", true)
} == true

@StringRes
private fun calibrationRetryMessage(failure: SpatialCalibrationFailure?): Int = when (failure) {
    SpatialCalibrationFailure.NEUTRAL_UNSTEADY, SpatialCalibrationFailure.NEUTRAL_GRAVITY_INVALID -> R.string.spatial_calibration_retry_still
    SpatialCalibrationFailure.RETURN_UNSTEADY, SpatialCalibrationFailure.RETURN_NOT_NEUTRAL,
    SpatialCalibrationFailure.RETURN_GRAVITY_INVALID -> R.string.spatial_calibration_retry_return
    SpatialCalibrationFailure.UP_TOO_SMALL -> R.string.spatial_calibration_retry_lift
    SpatialCalibrationFailure.UP_AXIS_NOT_HORIZONTAL, SpatialCalibrationFailure.UP_AXIS_INCONSISTENT,
    SpatialCalibrationFailure.INVALID_BASIS -> R.string.spatial_calibration_retry_straight
    else -> R.string.spatial_calibration_retry_connection
}
