/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.screens.apple

import android.content.Context
import android.media.AudioManager
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.audio.MicrophonePhase
import me.kavishdevar.librepods.audio.NativeHeadTrackingController
import me.kavishdevar.librepods.audio.SpatialCalibrationProgress
import me.kavishdevar.librepods.audio.SpatialHeadTrackingProbe
import me.kavishdevar.librepods.audio.SpatialHeadTrackingProbeRequest
import me.kavishdevar.librepods.audio.SpatialHeadTrackingProbeResult
import me.kavishdevar.librepods.audio.nativeSpatialVersion
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.ComponentStatus
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.DeviceComponent
import java.util.concurrent.atomic.AtomicBoolean

/** A setup screen owns one foreground attempt; navigation and resume never start another. */
@Composable
internal fun SpatialCalibrationRoute(device: AppleDevice, navigateBack: () -> Unit,
    isSelected: () -> Boolean = { true },
    calibrationController: NativeHeadTrackingController? = null,
    runProbe: suspend (Context, SpatialHeadTrackingProbeRequest, AppleDevice, () -> Boolean,
        (SpatialCalibrationProgress) -> Unit) -> SpatialHeadTrackingProbeResult = { context, request, target, current, progress ->
        SpatialHeadTrackingProbe(context).run(request, target, current, onProgress = progress)
    },
) {
    val context = LocalContext.current
    val app = context.applicationContext as? LibrePodsApplication
    val headTracking = calibrationController ?: app?.nativeHeadTrackingControllers?.get(device.macAddress)
    val calibrationOwner = remember(device) { Any() }
    DisposableEffect(headTracking, calibrationOwner) {
        headTracking?.setCalibrationActive(calibrationOwner, true)
        onDispose { headTracking?.setCalibrationActive(calibrationOwner, false) }
    }
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val selected by rememberUpdatedState(isSelected)
    val screenLive = remember(device) { AtomicBoolean(true) }
    val foreground = remember(device) { AtomicBoolean(true) }
    var running by remember(device) { mutableStateOf(false) }
    var progress by remember(device) { mutableStateOf<SpatialCalibrationProgress?>(null) }
    var result by remember(device) { mutableStateOf<SpatialHeadTrackingProbeResult?>(null) }
    var attempt by remember(device) { mutableStateOf<Job?>(null) }
    var activeOwner by remember(device) { mutableStateOf<AtomicBoolean?>(null) }
    fun cancel() { activeOwner?.set(false); attempt?.cancel() }
    DisposableEffect(screenLive) {
        onDispose { screenLive.set(false); cancel() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        foreground.set(false); cancel()
        headTracking?.setCalibrationActive(calibrationOwner, false)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        foreground.set(true)
        headTracking?.setCalibrationActive(calibrationOwner, true)
    }
    DisposableEffect(view, running) {
        val before = view.keepScreenOn
        if (running) view.keepScreenOn = true
        onDispose { view.keepScreenOn = before }
    }
    val back = { cancel(); navigateBack() }
    BackHandler(enabled = running, onBack = back)
    fun startAllowed() = nativeSpatialVersion && screenLive.get() && foreground.get()
    SpatialCalibrationScreen(progress, running, result, onStart = {
        if (!running && startAllowed()) {
            val socket = device.aacp.captureAudioSocket()
            if (socket == null || device.connectionState.value != ConnectionState.CONNECTED) {
                result = SpatialHeadTrackingProbeResult("disconnected", 0, null)
            } else {
                val live = AtomicBoolean(true)
                activeOwner = live
                running = true; progress = null; result = null
                attempt = scope.launch {
                    // Binder audio queries run at 10 Hz, outside the pose and ownership callbacks.
                    val idle = AtomicBoolean(false)
                    val checked = CompletableDeferred<Unit>()
                    val audioGuard = launch(Dispatchers.IO) {
                        val audio = context.getSystemService(AudioManager::class.java)
                        while (isActive) {
                            idle.set(runCatching { audio.mode == AudioManager.MODE_NORMAL &&
                                audio.activeRecordingConfigurations.isEmpty() && app != null &&
                                app.experimentalAudioControllers.values.all { it.microphone.state.value.phase in
                                    listOf(MicrophonePhase.IDLE, MicrophonePhase.ERROR) } &&
                                app.communicationAudioControllers.values.none { it.ownsSession || it.hasPreparedRoute }
                            }.getOrDefault(false))
                            checked.complete(Unit)
                            delay(100)
                        }
                    }
                    fun current(): Boolean {
                        val inCase = listOf(DeviceComponent.LEFT, DeviceComponent.RIGHT).all { side ->
                            device.state.value.componentState.any { it.component == side && it.status == ComponentStatus.IN_CASE }
                        }
                        return live.get() && screenLive.get() && foreground.get() && selected() && idle.get() &&
                            device.connectionState.value == ConnectionState.CONNECTED && device.aacp.ownsAudioSocket(socket) && !inCase
                    }
                    // The cancellation branch rethrows after recording the result; detekt only accepts a bare rethrow.
                    @Suppress("SuspendFunSwallowedCancellation")
                    try {
                        checked.await()
                        val completed = runProbe(context, SpatialHeadTrackingProbeRequest(device.macAddress.value),
                            device, ::current, { next ->
                                scope.launch { if (screenLive.get() && live.get()) progress = next }
                            })
                        if (screenLive.get() && live.get()) result = completed
                    } catch (cancelled: CancellationException) {
                        if (screenLive.get()) result = SpatialHeadTrackingProbeResult("cancelled", 0, null)
                        throw cancelled
                    } finally {
                        live.set(false); audioGuard.cancel()
                        if (screenLive.get() && activeOwner === live) { running = false; progress = null }
                    }
                }
            }
        }
    }, onCancel = ::cancel, navigateBack = back)
}
