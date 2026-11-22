package me.kavishdevar.librepods.services

import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.ComponentStatus
import me.kavishdevar.librepods.core.devices.Device
import me.kavishdevar.librepods.core.devices.DeviceComponent
import me.kavishdevar.librepods.core.devices.DeviceComponentState
import me.kavishdevar.librepods.core.devices.effectiveEarDetection
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import me.kavishdevar.librepods.utils.MediaController
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "LibrePodsService"

/**
 * Ear-detection auto-pause: pauses phone media through the companion bridge when the buds come out and
 * resumes only the playback this pair paused, on the same control transport.
 */
internal class WearMediaController(
    private val scope: CoroutineScope,
    private val destroying: () -> Boolean,
    private val devices: StateFlow<Map<MacAddress, Device<*, *, *>>>,
    /** Resolved on every use so the service's lazy bridge is not created early. */
    private val bridge: () -> ShizukuAudioClient,
    private val callActive: () -> Boolean,
    /** A companion hold or handoff operation owns this pair's audio. */
    private val companionOwns: (MacAddress) -> Boolean,
    private val isAudioConnected: (AppleDevice) -> Boolean,
    private val keepCompactVisible: () -> Boolean,
    private val showConnectedIsland: (Device<*, *, *>) -> Unit,
    private val closeIsland: () -> Unit,
) {
    private class WearMedia(val transport: BluetoothSocket) {
        @Volatile var paused = false
        val pauseCompleted = CompletableDeferred<Unit>()
    }
    private val wearMediaEpochs = ConcurrentHashMap<MacAddress, WearMedia>()

    /** Forgets every pause this service owns and cancels the bridge's owned media session. */
    fun retireAll() {
        wearMediaEpochs.clear()
        bridge().cancelOwnedMedia()
    }

    fun retireForOtherRoute() {
        if (wearMediaEpochs.keys.any { !MediaController.isMediaRoute(it.value) }) {
            wearMediaEpochs.clear()
            bridge().cancelOwnedMedia()
        }
    }

    fun onComponentStateChange(
        device: Device<*, *, *>,
        previousComponentState: Set<DeviceComponentState>,
        newComponentState: Set<DeviceComponentState>,
        transport: BluetoothSocket?
    ) {
        val pod = device as? AppleDevice ?: return
        if (transport == null || !pod.aacp.ownsAudioSocket(transport) ||
            pod.state.value.componentState != newComponentState) return
        val old = earPresenceOf(previousComponentState)
        val new = earPresenceOf(newComponentState)
        if (old == new || !MediaController.isMediaRoute(device.macAddress.value)) return

        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "earPresence: $old -> $new")

        // new != NONE because old!=new
        if (old == EarPresence.NONE) {
            showConnectedIsland(device)
        }

        if (new == EarPresence.NONE && keepCompactVisible()) {
            closeIsland()
        }

        if (!pod.settings.value.hostAutoPause || !pod.state.value.effectiveEarDetection(pod.settings.value) || callActive()) return
        val shouldPause = new == EarPresence.NONE || old == EarPresence.FULL && new == EarPresence.PARTIAL
        if (shouldPause) {
            val owner = WearMedia(transport)
            wearMediaEpochs[pod.macAddress] = owner
            scope.launch {
                try {
                    fun current() = !destroying() && devices.value[pod.macAddress] === pod && wearMediaEpochs[pod.macAddress] === owner &&
                        pod.aacp.ownsAudioSocket(transport) &&
                        pod.settings.value.hostAutoPause && pod.state.value.effectiveEarDetection(pod.settings.value) &&
                        !callActive() && !companionOwns(pod.macAddress) && isAudioConnected(pod) &&
                        (owner.paused || earPresenceOf(pod.state.value.componentState) != EarPresence.FULL)
                    bridge().connect()
                    if (withTimeoutOrNull(2000) { bridge().state.first { it.connected } } == null || !current()) return@launch
                    owner.paused = bridge().pauseOwnedMedia(pod.macAddress.value, ::current)
                } finally {
                    owner.pauseCompleted.complete(Unit)
                }
            }
        } else if (new == EarPresence.FULL || old == EarPresence.NONE) {
            val owner = wearMediaEpochs[pod.macAddress] ?: return
            scope.launch {
                owner.pauseCompleted.await()
                if (!owner.paused || owner.transport !== transport || wearMediaEpochs[pod.macAddress] !== owner ||
                    !pod.aacp.ownsAudioSocket(transport) ||
                    pod.state.value.componentState != newComponentState) return@launch
                bridge().finishOwnedMedia(resume = true)
                wearMediaEpochs.remove(pod.macAddress, owner)
            }
        }
    }
}

internal enum class EarPresence { NONE, PARTIAL, FULL }

internal fun earPresenceOf(components: Set<DeviceComponentState>): EarPresence {
    if (components.isEmpty()) return EarPresence.NONE
    val inEarCount = components.count { it.status == ComponentStatus.IN_EAR }
    val outOfCaseCount = components.count { it.status != ComponentStatus.IN_CASE }
    val completeReport = components.any { it.component == DeviceComponent.HEADSET } ||
        components.any { it.component == DeviceComponent.LEFT } && components.any { it.component == DeviceComponent.RIGHT }
    return when (inEarCount) {
        0 -> EarPresence.NONE
        // Only an explicit in-case report makes the other bud unnecessary for playback.
        outOfCaseCount -> if (completeReport) EarPresence.FULL else EarPresence.PARTIAL
        else -> EarPresence.PARTIAL
    }
}
