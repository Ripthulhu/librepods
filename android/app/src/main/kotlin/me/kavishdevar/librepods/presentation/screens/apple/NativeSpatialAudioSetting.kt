/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.screens.apple

import android.content.Intent
import android.media.AudioManager
import android.media.Spatializer
import android.os.Bundle
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.flow.MutableStateFlow
import me.kavishdevar.librepods.audio.NativeHeadTrackingState
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.presentation.components.primitives.StyledListScope
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItemOrientation
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItemContent
import me.kavishdevar.librepods.presentation.components.primitives.StyledToggleContent
import me.kavishdevar.librepods.presentation.viewmodel.AppleViewModel
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import java.util.concurrent.atomic.AtomicBoolean

internal data class FixedSpatialState(val checked: Boolean?, val writable: Boolean, val reason: String) {
    companion object {
        fun read(value: Bundle): FixedSpatialState {
            @Suppress("DEPRECATION") fun flag(name: String) = value.get(name) as? Boolean
            val known = value.getInt("version", 0) == 1 && flag("known") == true
            val nativeEnabled = flag("enabled")
            val device = flag("device_enabled")
            val tracking = flag("head_tracker_enabled")
            val checked = if (known) fixedChecked(nativeEnabled, device, tracking) else null
            return FixedSpatialState(checked,
                known && checked != null && flag("fixed_supported") == true && flag("route_selected") == true,
                value.getString("reason") ?: "unavailable")
        }

        /** Fixed spatial audio is on when native spatial audio is enabled for the device without head tracking. */
        private fun fixedChecked(nativeEnabled: Boolean?, device: Boolean?, tracking: Boolean?): Boolean? =
            if (nativeEnabled == null || device == null || tracking == null) null else nativeEnabled && device && !tracking
    }
}

@Composable
internal fun supportsNativeSpatialAudio(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching { context.getSystemService(AudioManager::class.java).spatializer.immersiveAudioLevel >
            Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE }.getOrDefault(false)
    }
}

@Composable
internal fun StyledListScope.NativeSpatialAudioSetting(viewModel: AppleViewModel) {
    // State belongs to the rendered row; partial recomposition must not append to the list builder.
    item { index, count -> NativeSpatialAudioRow(viewModel, index, count) }
}

@Composable
private fun NativeSpatialAudioRow(viewModel: AppleViewModel, index: Int, count: Int) {
    val context = LocalContext.current
    val connection by viewModel.connectionState.collectAsState()
    val ui by viewModel.uiState.collectAsState()
    val token = remember(viewModel, connection, ui.state.controlSession) { viewModel.captureSpatialAudioOwner() }
    val live = remember(viewModel, token) { AtomicBoolean(true) }
    DisposableEffect(live) { onDispose { live.set(false) } }
    val access = remember(context, viewModel) { ShizukuAudioClient(context, requireRoot = true) }
    DisposableEffect(access) { onDispose { access.close() } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access.connect() }
    val bridge by access.state.collectAsState()
    val controller = viewModel.headTrackingController
    val headTracking by (controller?.state ?:
        remember { MutableStateFlow(NativeHeadTrackingState()) }).collectAsState()
    val rootReady = headTracking.spatialSupported || bridge.connected && access.hasRootPrivileges
    val connected = connection == ConnectionState.CONNECTED && token != null
    val current = remember(live) { { live.get() && token != null && viewModel.ownsSpatialAudio(token) } }
    val snapshot = if (headTracking.spatialSupported) FixedSpatialState(headTracking.spatialEnabled,
        headTracking.spatialAvailable || headTracking.spatialEnabled == true ||
            headTracking.spatialEnabled == null && headTracking.desiredSpatialEnabled != null,
        headTracking.reason) else if (rootReady && (controller == null || headTracking.reason != "loading"))
        FixedSpatialState(null, false, "unavailable") else null
    SpatialAudioSettingContent(snapshot, connected, rootReady, headTracking.busy || headTracking.spatialBusy,
        index = index, count = count,
        requestRoot = access::requestPermission,
        openSystemSettings = { context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) },
        change = { enabled ->
            if (!headTracking.busy && !headTracking.spatialBusy && snapshot?.writable == true && current() &&
                (headTracking.spatialEnabled != null || !enabled)) controller?.setSpatialEnabled(enabled)
        })
}

@Composable
internal fun SpatialAudioSettingContent(
    snapshot: FixedSpatialState?, connected: Boolean, rootReady: Boolean, busy: Boolean = false,
    requested: Boolean? = null, requestRoot: () -> Unit = {}, openSystemSettings: () -> Unit = {},
    change: (Boolean) -> Unit,
    index: Int = 0, count: Int = 1,
) {
    val title = stringResource(R.string.quick_settings_spatial_audio)
    val needsRoot = connected && !rootReady
    val needsSystemSettings = connected && rootReady && snapshot?.reason == "global_disabled"
    val recovery = connected && rootReady && !busy && snapshot?.writable == true && snapshot.checked == null
    if (recovery) {
        StyledListItemContent(
            content = { Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) },
            supportingContent = { Text(stringResource(R.string.spatial_audio_recovery_off), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)) },
            orientation = StyledListItemOrientation.Vertical, index = index, count = count, onClick = { change(false) })
    } else if (needsRoot || needsSystemSettings) {
        StyledListItemContent(
            content = { Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) },
            supportingContent = { Text(stringResource(if (needsRoot) R.string.spatial_audio_root
                else R.string.spatial_audio_global_disabled), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)) },
            orientation = StyledListItemOrientation.Vertical,
            index = index, count = count,
            onClick = if (needsRoot) requestRoot else openSystemSettings)
    } else {
        val description = when {
            !connected -> R.string.spatial_audio_connect
            snapshot == null -> R.string.spatial_audio_loading
            !snapshot.writable -> R.string.spatial_audio_unavailable
            else -> R.string.spatial_audio_description
        }
        StyledToggleContent(modifier = Modifier, label = title, description = stringResource(description),
            index = index, count = count,
            checked = requested ?: (snapshot?.checked == true), enabled = connected && rootReady &&
                !busy && snapshot?.writable == true, onCheckedChange = change)
    }
}
