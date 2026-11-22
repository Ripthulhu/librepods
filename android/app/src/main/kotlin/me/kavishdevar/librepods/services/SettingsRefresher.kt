package me.kavishdevar.librepods.services

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.kavishdevar.librepods.audio.ExperimentalAudioController
import me.kavishdevar.librepods.bluetooth.DeviceSettingsRefreshResult
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.devices.AppleDevice
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "LibrePodsService"

/**
 * Reserves one control-session refresh per pair and closes it through a watchdog when the
 * pair, call or audio state stops allowing it. Other service work reads [anyActive] to stay out of its way.
 */
internal class SettingsRefresher(
    private val scope: CoroutineScope,
    private val destroying: () -> Boolean,
    private val audioController: (MacAddress) -> ExperimentalAudioController?,
    private val deviceIsCurrent: (AppleDevice) -> Boolean,
    private val callActive: () -> Boolean,
    /** Microphone ownership or a blocked BLE capture. */
    private val captureBusy: () -> Boolean,
    private val companionBusy: () -> Boolean,
) {
    private class SettingsRefresh(val device: AppleDevice, val controller: ExperimentalAudioController) {
        val failure = AtomicReference<DeviceSettingsRefreshResult?>()
    }
    private val settingsRefreshes = ConcurrentHashMap<MacAddress, SettingsRefresh>()

    fun anyActive(): Boolean = settingsRefreshes.isNotEmpty()

    fun isRefreshing(address: MacAddress): Boolean = settingsRefreshes.containsKey(address)

    /** Null means that this exact device can currently refresh its control session. */
    fun readiness(device: AppleDevice): DeviceSettingsRefreshResult? {
        if (destroying()) return DeviceSettingsRefreshResult.UNAVAILABLE
        if (settingsRefreshes.containsKey(device.macAddress)) return DeviceSettingsRefreshResult.ALREADY_RUNNING
        if (companionBusy()) return DeviceSettingsRefreshResult.ALREADY_RUNNING
        if (!deviceIsCurrent(device) || device.aacp.captureAudioSocket() == null)
            return DeviceSettingsRefreshResult.NOT_CONNECTED
        if (callActive()) return DeviceSettingsRefreshResult.CALL_ACTIVE
        val controller = audioController(device.macAddress) ?: return DeviceSettingsRefreshResult.UNAVAILABLE
        if (captureBusy() || device.state.value.recordingState.isRecording ||
            device.state.value.microphoneState.isActive || !controller.canRefreshSettings())
            return DeviceSettingsRefreshResult.AUDIO_BUSY
        return null
    }

    private fun refreshFailure(operation: SettingsRefresh): DeviceSettingsRefreshResult? {
        if (destroying()) return DeviceSettingsRefreshResult.UNAVAILABLE
        val device = operation.device
        if (settingsRefreshes[device.macAddress] !== operation || !deviceIsCurrent(device) ||
            audioController(device.macAddress) !== operation.controller) return DeviceSettingsRefreshResult.NOT_CONNECTED
        if (callActive()) return DeviceSettingsRefreshResult.CALL_ACTIVE
        if (captureBusy() || device.state.value.recordingState.isRecording ||
            device.state.value.microphoneState.isActive) return DeviceSettingsRefreshResult.AUDIO_BUSY
        return null
    }

    suspend fun refresh(device: AppleDevice): DeviceSettingsRefreshResult {
        var reserved: SettingsRefresh? = null
        var watchdog: Job? = null
        var request: me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager.ControlSessionRefresh? = null
        try {
            val unavailable = withContext(Dispatchers.Main.immediate) {
                readiness(device)?.let { return@withContext it }
                val controller = audioController(device.macAddress) ?: return@withContext DeviceSettingsRefreshResult.UNAVAILABLE
                if (!controller.tryBeginSettingsRefresh()) return@withContext DeviceSettingsRefreshResult.AUDIO_BUSY
                val operation = SettingsRefresh(device, controller)
                if (settingsRefreshes.putIfAbsent(device.macAddress, operation) != null) {
                    controller.endSettingsRefresh()
                    return@withContext DeviceSettingsRefreshResult.ALREADY_RUNNING
                }
                reserved = operation
                null
            }
            unavailable?.let { return it }
            val operation = checkNotNull(reserved)
            refreshFailure(operation)?.let { return it }
            val refresh = device.aacp.prepareControlSessionRefresh() ?: return DeviceSettingsRefreshResult.NOT_CONNECTED
            request = refresh
            val caller = currentCoroutineContext()[Job]
            val deadline = SystemClock.elapsedRealtime() + 10_000
            // This independent job closes a blocked socket.connect(), including caller cancellation.
            watchdog = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    val failure = refreshFailure(operation) ?: when {
                        caller?.isActive == false || SystemClock.elapsedRealtime() >= deadline -> DeviceSettingsRefreshResult.FAILED
                        else -> null
                    }
                    if (failure != null) {
                        operation.failure.compareAndSet(null, failure)
                        device.aacp.cancelControlSessionRefresh(refresh)
                        break
                    }
                    delay(100)
                }
            }
            val reopened = withContext(Dispatchers.IO) {
                device.aacp.refreshControlSession(refresh) { operation.failure.get() == null && refreshFailure(operation) == null }
            }
            watchdog.cancelAndJoin()
            return operation.failure.get() ?: refreshFailure(operation) ?:
                if (reopened) DeviceSettingsRefreshResult.RECONNECTED else DeviceSettingsRefreshResult.FAILED
        } catch (cancelled: CancellationException) {
            request?.let { device.aacp.cancelControlSessionRefresh(it) }
            throw cancelled
        } catch (error: Exception) {
            request?.let { device.aacp.cancelControlSessionRefresh(it) }
            Log.w(TAG, "Settings refresh failed: ${error.javaClass.simpleName}")
            return reserved?.failure?.get() ?: DeviceSettingsRefreshResult.FAILED
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                watchdog?.cancelAndJoin()
                reserved?.let { operation ->
                    settingsRefreshes.remove(device.macAddress, operation)
                    operation.controller.endSettingsRefresh()
                }
            }
        }
    }
}
