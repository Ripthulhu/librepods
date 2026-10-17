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

package me.kavishdevar.librepods.presentation.viewmodel

import android.widget.Toast
import me.kavishdevar.librepods.R

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.billing.BillingManager
import me.kavishdevar.librepods.bluetooth.DeviceSettingsRefreshResult
import me.kavishdevar.librepods.audio.ExperimentalAudioController
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.bluetooth.att.ATTHandle
import me.kavishdevar.librepods.core.data.StemAction
import me.kavishdevar.librepods.core.data.heartrate.HeartRateSample
import me.kavishdevar.librepods.core.data.recording.Recording
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.data.xposed.XposedRemotePrefProvider
import me.kavishdevar.librepods.database.app.AppSettingsEntity
import me.kavishdevar.librepods.database.app.AppStateEntity
import me.kavishdevar.librepods.repository.AppDataRepository
import me.kavishdevar.librepods.repository.HeartRateRepository
import me.kavishdevar.librepods.repository.RecordingRepository
import kotlin.time.Duration
import kotlin.time.Instant

data class AppleUiState(
    val state: AppleState = AppleState(),
    val settings: AppleSettings = AppleSettings(),
    val metadata: AppleMetadata = AppleMetadata(),

    val appState: AppStateEntity = AppStateEntity(),
    val appSettings: AppSettingsEntity = AppSettingsEntity(),

    val isPremium: Boolean = false,
    val vendorIdHook: Boolean = false,
    val recordings: List<Recording> = emptyList(),

    val heartRateSamples: List<HeartRateSample> = emptyList(),

    val heartRateRange: ClosedRange<Long>? = null,
)

class AppleViewModel(
    private val device: AppleDevice,
    private val appDataRepository: AppDataRepository,
    private val recordingRepository: RecordingRepository,
    private val heartRateRepository: HeartRateRepository,
    private val audioControllerProvider: () -> ExperimentalAudioController? = { null },
) : ViewModel(), DeviceViewModel {
    val audioController: ExperimentalAudioController? get() = audioControllerProvider()
    val billingManager = BillingManager

    val events = device.events
    val connectionState = device.connectionState
    private val _refreshingSettings = MutableStateFlow(false)
    val refreshingSettings = _refreshingSettings.asStateFlow()

    fun refreshDeviceSettings(refresh: suspend (AppleDevice) -> DeviceSettingsRefreshResult) {
        if (!_refreshingSettings.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { refresh(device) }
                val message = when (result) {
                    DeviceSettingsRefreshResult.RECONNECTED -> R.string.refresh_device_settings_requested
                    DeviceSettingsRefreshResult.ALREADY_RUNNING -> R.string.refresh_device_settings_running
                    DeviceSettingsRefreshResult.NOT_CONNECTED -> R.string.refresh_device_settings_disconnected
                    DeviceSettingsRefreshResult.AUDIO_BUSY -> R.string.refresh_device_settings_audio_busy
                    DeviceSettingsRefreshResult.CALL_ACTIVE -> R.string.refresh_device_settings_call_active
                    DeviceSettingsRefreshResult.UNAVAILABLE -> R.string.refresh_device_settings_unavailable
                    DeviceSettingsRefreshResult.FAILED -> R.string.refresh_device_settings_failed
                }
                Toast.makeText(device.context, message, Toast.LENGTH_LONG).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(device.context, R.string.refresh_device_settings_failed, Toast.LENGTH_LONG).show()
            } finally {
                _refreshingSettings.value = false
            }
        }
    }

    private var attObserveJob: Job? = null

    private val _heartRateRange = MutableStateFlow<ClosedRange<Long>?>(null)

    private val heartRateSamples: Flow<List<HeartRateSample>> = _heartRateRange
        .transformLatest { range ->
            if (range == null) {
                emit(emptyList())
            } else {
                val startInstant = Instant.fromEpochMilliseconds(range.start)
                val endInstant = Instant.fromEpochMilliseconds(range.endInclusive)
                val samples = heartRateRepository.get(startInstant, endInstant)
                emit(samples)
            }
        }

    private val deviceDetails = combine(
        device.state,
        device.settings,
        device.metadata
    ) { state, settings, metadata ->
        Triple(state, settings, metadata)
    }

    private val appData = combine(
        appDataRepository.state,
        appDataRepository.settings
    ) { appState, appSettings ->
        Pair(appState, appSettings)
    }

    val uiState = combine(
        deviceDetails,
        appData,
        billingManager.provider.isPremium,
        _heartRateRange,
        heartRateSamples
    ) { (state, settings, metadata), (appState, appSettings), isPremium, heartRateRange, heartRateSamples ->
        AppleUiState(
            state = state.copy(controlStates = state.controlStates + state.requestedControls,
                customEq = state.requestedCustomEq ?: state.customEq),
            settings = settings,
            metadata = metadata,
            isPremium = isPremium,
            vendorIdHook = XposedRemotePrefProvider.create().getBoolean(
                "vendor_id_hook",
                false
            ), // TODO: make this a Flow (even if it means polling every few seconds?)
            heartRateRange = heartRateRange,
            heartRateSamples = heartRateSamples,
            appState = appState,
            appSettings = appSettings
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        AppleUiState(
            state = device.state.value,
            settings = device.settings.value,
            metadata = device.metadata.value,
            isPremium = billingManager.provider.isPremium.value,
            vendorIdHook = XposedRemotePrefProvider.create()
                .getBoolean("vendor_id_hook", false)
        )
    )

    fun disconnect() = device.disconnect()
    fun recordings(): List<Recording> = recordingRepository.recordings()

    fun setControlCommand(identifier: ControlCommandIdentifier, value: ByteArray): Boolean =
        device.setControlCommand(identifier, value)

    fun setControlCommand(identifier: ControlCommandIdentifier, value: Byte): Boolean =
        device.setControlCommand(identifier, value)

    fun setControlCommand(identifier: ControlCommandIdentifier, value: Int): Boolean =
        device.setControlCommand(identifier, value)

    fun setControlCommand(identifier: ControlCommandIdentifier, value: Boolean): Boolean =
        device.setControlCommand(identifier, value)

    fun writeATTCharacteristic(handle: ATTHandle, value: ByteArray) {
        val request = value.copyOf()
        viewModelScope.launch {
            if (!device.writeATTCharacteristic(handle, request)) {
                Toast.makeText(device.context, R.string.att_setting_not_confirmed, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun observeATTCharacteristic(handle: ATTHandle) {
            attObserveJob = device.observeATTCharacteristic(handle)
    }
    fun stopObservingATTCharacteristic() {
        attObserveJob?.cancel()
        attObserveJob = null
    }

    fun startRecording() = device.startRecording(recordingRepository.createRecording())
    fun stopRecording() = device.stopRecording()

    fun toggleListeningMode(modeBit: Int) = device.toggleListeningMode(modeBit)

    fun setLongPressAction(side: String, action: StemAction) = device.setLongPressAction(side, action)

    fun renameDevice(newName: String) = viewModelScope.launch {
        val sent = withContext(Dispatchers.IO) { device.renameDevice(newName) }
        if (!sent) Toast.makeText(device.context, R.string.rename_not_sent, Toast.LENGTH_SHORT).show()
    }


    fun startHeadTracking() = device.startHeadTracking()
    fun stopHeadTracking() = device.stopHeadTracking()

    fun setHeadGesturesEnabled(enabled: Boolean) = device.setHeadGesturesEnabled(enabled)

    fun setCustomEqEnabled(enabled: Boolean) = device.setCustomEqEnabled(enabled)
    fun setCustomEq(low: Int, mid: Int, high: Int) = device.setCustomEq(low, mid, high)

    fun detectHeadGestures(callback: (Boolean) -> Unit) = device.detectHeadGestures(callback)

    fun setHeadGesturesVerticalOffset(offset: Int) = device.setHeadGesturesVerticalOffset(offset)
    fun setHeadGesturesHorizontalOffset(offset: Int) = device.setHeadGesturesHorizontalOffset(offset)
    fun setHeadTrackingInterval(interval: Duration) = device.setHeadTrackingInterval(interval)

    fun startHr() = device.startHr()
    fun stopHr() = device.stopHr()
    fun setHrRange(range: ClosedRange<Long>) {
        _heartRateRange.value = range
    }

    fun updateSettings( transform : (AppleSettings) -> AppleSettings) {
        device.updateSettings(transform)
    }

    fun sendRawPacket(data: ByteArray): Boolean = device.sendRawPacket(data)
}
