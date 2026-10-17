package me.kavishdevar.librepods.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.AppOpsManager
import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.ParcelUuid
import android.os.ext.SdkExtensions
import android.provider.Settings
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.audio.ExperimentalAudioController
import me.kavishdevar.librepods.audio.MicrophonePhase
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.bluetooth.aacp.types.MagicKeyType
import me.kavishdevar.librepods.core.bluetooth.verifyRPA
import me.kavishdevar.librepods.core.bluetooth.ble.NearbyAirPods
import me.kavishdevar.librepods.core.bluetooth.ble.ProximityReport
import me.kavishdevar.librepods.core.bluetooth.ble.RecentProximityReports
import me.kavishdevar.librepods.bluetooth.NearbyPairingController
import me.kavishdevar.librepods.bluetooth.DeviceSettingsRefreshResult
import me.kavishdevar.librepods.bluetooth.canLoadAppleDevice
import me.kavishdevar.librepods.bluetooth.nearbyScanSettings
import me.kavishdevar.librepods.core.data.heartrate.HeartRateSample
import me.kavishdevar.librepods.core.data.recording.Recording
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.effectiveAllowOff
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatteryStatus
import me.kavishdevar.librepods.core.devices.ComponentStatus
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.Device
import me.kavishdevar.librepods.core.devices.DeviceComponentState
import me.kavishdevar.librepods.core.utils.redactMac
import me.kavishdevar.librepods.database.app.AppSettingsEntity
import me.kavishdevar.librepods.presentation.activities.MainActivity
import me.kavishdevar.librepods.presentation.overlays.IslandType
import me.kavishdevar.librepods.presentation.overlays.IslandListeningMode
import me.kavishdevar.librepods.presentation.overlays.IslandWindow
import me.kavishdevar.librepods.presentation.widgets.BatteryWidget
import me.kavishdevar.librepods.utils.MediaController
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaInstant

private const val TAG = "LibrePodsService"
private const val NEARBY_SCAN_TAG = "NearbyScan"
private const val STOP_MICROPHONE = "me.kavishdevar.librepods.STOP_MICROPHONE"

@SuppressLint("MissingPermission")
class LibrePodsService: Service() {
    inner class LocalBinder : Binder() {
        fun getService(): LibrePodsService = this@LibrePodsService
    }

    private val binder = LocalBinder()

    private val _devices = MutableStateFlow<Map<MacAddress, Device<*, *, *>>>(emptyMap())
    val devices = _devices.asStateFlow()

    private val deviceJobs = ConcurrentHashMap<MacAddress, List<Job>>()
    private val deviceCreationLocks = ConcurrentHashMap<MacAddress, Mutex>()
    @Volatile private var destroying = false
    private val microphoneOwners = ConcurrentHashMap.newKeySet<MacAddress>()
    private val audioDevices = ConcurrentHashMap<MacAddress, AppleDevice>()
    private val audioJobs = ConcurrentHashMap<MacAddress, Job>()
    private val sensorSuspensions = ConcurrentHashMap<MacAddress, Channel<Boolean>>()
    private class SettingsRefresh(val device: AppleDevice, val controller: ExperimentalAudioController) {
        val failure = AtomicReference<DeviceSettingsRefreshResult?>()
    }
    private val settingsRefreshes = ConcurrentHashMap<MacAddress, SettingsRefresh>()
    private var microphonePermissionMonitor: Job? = null
    private val audioControllers get() = (application as LibrePodsApplication).experimentalAudioControllers

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val bleHandler = Handler(Looper.getMainLooper())
    private val nearbyVisits = NearbyAirPods.Visits()
    private val recentProximityReports = RecentProximityReports()
    private var pairingModel: Int? = null
    private var nearbyPairing: NearbyPairingController? = null
    private var bleGeneration = 0L
    private var bleRequested = false
    private var bleReady = false
    @Volatile private var bleCaptureBlocked = false
    private var bleScanner: BluetoothLeScanner? = null
    private var bleScanCallback: ScanCallback? = null
    private val pairedLids = mutableMapOf<MacAddress, Boolean>()
    private val pairedObservations = mutableMapOf<MacAddress, Long>()
    private val bleReconnectAt = mutableMapOf<MacAddress, Long>()

    private val appleRepository by lazy {
        (application as LibrePodsApplication).appleRepository
    }

    private val appDataRepository by lazy {
        (application as LibrePodsApplication).appDataRepository
    }

    private val widgetConfigRepository by lazy {
        (application as LibrePodsApplication).widgetConfigRepository
    }

    private val heartRateRepository by lazy {
        (application as LibrePodsApplication).heartRateRepository
    }

    @Suppress("unused")
    private val recordingRepository by lazy {
        (application as LibrePodsApplication).recordingRepository
    }

    private val healthConnectClient by lazy {
        (application as LibrePodsApplication).healthConnectClient
    }

    private val islandWindow: IslandWindow by lazy {
        (application as LibrePodsApplication).islandWindow
    }

    @Suppress("unused")
    private fun observeCameraState() {
        serviceScope.launch(Dispatchers.IO) {
            LibrePodsAccessibilityService.isCameraOpen.collect { isCamera ->
                if (isCamera) {
                    Log.d(TAG, "Camera is open")
                } else {
                    Log.d(TAG, "Camera is closed")
                }
            }
        }
    }

    private var isCallRinging = false
    @Volatile private var telephonyCallState = TelephonyManager.CALL_STATE_IDLE

    private val telephonyCallback = object: TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            telephonyCallState = state
            if (state == TelephonyManager.CALL_STATE_RINGING) {
                isCallRinging = true
                _devices.value.values.firstOrNull { device ->
                    device is AppleDevice &&
                    device.connectionState.value == ConnectionState.CONNECTED &&
                    device.state.value.componentState.any { it.status == ComponentStatus.IN_EAR }
                }?.let { device ->
                    (device as AppleDevice).detectHeadGestures { accept ->
                        if (!isCallRinging) return@detectHeadGestures

                        try {
                            val telecomManager = getSystemService(TelecomManager::class.java)

                            @Suppress("DEPRECATION")
                            if (accept) {
                                telecomManager?.acceptRingingCall(
                                    VideoProfile.STATE_AUDIO_ONLY
                                )
                                Toast.makeText(
                                    this@LibrePodsService,
                                    getString(R.string.call_accepted),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(
                                    this@LibrePodsService,
                                    getString(R.string.call_rejected),
                                    Toast.LENGTH_SHORT
                                ).show()
                                telecomManager?.endCall()
                            }

                            isCallRinging = false
                            device.stopHeadTracking()

                        } catch (e: Exception) {
                            Log.e(TAG, "Error accepting call", e)
                        }
                    }
                }
            } else {
                if (isCallRinging) {
                    _devices.value.values.firstOrNull { device ->
                        device is AppleDevice &&
                        device.connectionState.value == ConnectionState.CONNECTED &&
                        device.state.value.componentState.any { it.status == ComponentStatus.IN_EAR }
                    }?.let { device ->
                        (device as AppleDevice).stopHeadTracking()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        observeAppSettings()

        registerBluetoothReceivers()

        nearbyPairing = NearbyPairingController(this, onInteractionChanged = { pairingModel = it }) { paired ->
            islandWindow.suppressPairingOpening(paired.address, SystemClock.elapsedRealtimeNanos())
            serviceScope.launch(Dispatchers.IO) {
                val device = createDevice(paired) as? AppleDevice
                if (device != null) withContext(Dispatchers.Main.immediate) {
                    if (!destroying && devices.value[device.macAddress] === device &&
                        paired.bondState == BluetoothDevice.BOND_BONDED) {
                        nearbyPairing?.updatePairingBattery(paired, device.state.value.battery)
                    }
                }
                if (paired.uuids.isNullOrEmpty()) paired.fetchUuidsWithSdp()
                else onDeviceConnected(paired)
            }
        }
        serviceScope.launch {
            loadDevices()
            bleReady = true
            startBleScanner()
            devices.value.values.forEach { device ->
                if (runCatching { BluetoothDevice::class.java.getMethod("isConnected").invoke(device.bluetoothDevice) as Boolean }
                        .getOrDefault(false)) launch(Dispatchers.IO) { onDeviceConnected(device.bluetoothDevice) }
            }
            while (isActive) {
                delay(5.seconds)
                recentProximityReports.expire(SystemClock.elapsedRealtimeNanos())
                nearbyVisits.expire(SystemClock.elapsedRealtime(), pairingModel).forEach { nearbyPairing?.onNearbyDevice(it) }
                devices.value.values.filterIsInstance<AppleDevice>().forEach { it.expireNearbyBattery(System.currentTimeMillis()) }
            }
        }

        MediaController.initialize(
            audioManager = getSystemService(AudioManager::class.java),
            sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE),
            localMac = null // TODO: smart routing. MAC_ADDRESS message gives host mac?
        )

        if (
            checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
        ) {
            val telephonyManager = getSystemService(TelephonyManager::class.java)

            telephonyManager.registerTelephonyCallback(
                mainExecutor,
                telephonyCallback
            )
        }

        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP_MICROPHONE -> audioControllers.values.forEach { it.stop() }
            // TODO: make this a sealed class or something, instead of stringly typed. also, make this different for widget so other apps can use it too
            "ACTION_SET_ANC_MODE" -> {
                val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                val macAddress = intent.getStringExtra("MAC_ADDRESS")?.let { MacAddress(it) }

                val ancMode = intent.getIntExtra("ANC_MODE", -1)
                val device =
                    widgetConfigRepository.widgetConfigs.value.find { it.appWidgetId == appWidgetId }
                        ?.let { config ->
                            devices.value[config.macAddress]
                        } ?: devices.value[macAddress]
                    ?: devices.value.values.firstOrNull { it.connectionState.value == ConnectionState.CONNECTED }

                if (device != null && ancMode != -1) {
                    Log.i(
                        TAG,
                        "Setting ANC mode to $ancMode for device ${device.macAddress.toRedactedString()}"
                    )
                    when (device) {
                        is AppleDevice -> device.setControlCommand(
                            ControlCommandIdentifier.LISTENING_MODE,
                            ancMode
                        )
                    }
                } else {
                    Log.w(TAG, "No connected Apple device found or invalid ANC mode: $ancMode")
                }
            }
        }

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        destroying = true
        setExperimentalAudioBlocked(true)
        microphonePermissionMonitor?.cancel()
        audioDevices.keys.toList().forEach(::detachExperimentalAudio)
        deviceJobs.values.flatten().forEach { it.cancel() }
        devices.value.values.forEach { it.disconnect() }
        nearbyPairing?.close()
        islandWindow.close()
        islandWindow.clearPairingOpening()
        runCatching { getSystemService(TelephonyManager::class.java).unregisterTelephonyCallback(telephonyCallback) }
        serviceScope.cancel()
        bleHandler.removeCallbacksAndMessages(null)
        unregisterBluetoothReceivers()
        stopBleScanner()
        recentProximityReports.clear()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** Null means that this exact device can currently refresh its control session. */
    fun deviceSettingsRefreshReadiness(device: AppleDevice): DeviceSettingsRefreshResult? {
        if (destroying) return DeviceSettingsRefreshResult.UNAVAILABLE
        if (settingsRefreshes.containsKey(device.macAddress)) return DeviceSettingsRefreshResult.ALREADY_RUNNING
        if (!refreshDeviceIsCurrent(device) || device.aacp.captureAudioSocket() == null)
            return DeviceSettingsRefreshResult.NOT_CONNECTED
        if (callBlocksSettingsRefresh()) return DeviceSettingsRefreshResult.CALL_ACTIVE
        val controller = audioControllers[device.macAddress] ?: return DeviceSettingsRefreshResult.UNAVAILABLE
        if (microphoneOwners.isNotEmpty() || bleCaptureBlocked || device.state.value.recordingState.isRecording ||
            device.state.value.microphoneState.isActive || !controller.canRefreshSettings())
            return DeviceSettingsRefreshResult.AUDIO_BUSY
        return null
    }

    private fun refreshDeviceIsCurrent(device: AppleDevice): Boolean = runCatching {
        !destroying && devices.value[device.macAddress] === device && audioDevices[device.macAddress] === device &&
            device.bluetoothDevice.bondState == BluetoothDevice.BOND_BONDED &&
            device.connectionState.value == ConnectionState.CONNECTED
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun callBlocksSettingsRefresh(): Boolean {
        if (telephonyCallState != TelephonyManager.CALL_STATE_IDLE) return true
        if (runCatching { getSystemService(AudioManager::class.java)?.mode }.getOrNull() != AudioManager.MODE_NORMAL) return true
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            return runCatching { getSystemService(TelephonyManager::class.java)?.callState != TelephonyManager.CALL_STATE_IDLE }
                .getOrDefault(true)
        }
        return false
    }

    private fun refreshFailure(operation: SettingsRefresh): DeviceSettingsRefreshResult? {
        if (destroying) return DeviceSettingsRefreshResult.UNAVAILABLE
        val device = operation.device
        if (settingsRefreshes[device.macAddress] !== operation || !refreshDeviceIsCurrent(device) ||
            audioControllers[device.macAddress] !== operation.controller) return DeviceSettingsRefreshResult.NOT_CONNECTED
        if (callBlocksSettingsRefresh()) return DeviceSettingsRefreshResult.CALL_ACTIVE
        if (microphoneOwners.isNotEmpty() || bleCaptureBlocked || device.state.value.recordingState.isRecording ||
            device.state.value.microphoneState.isActive) return DeviceSettingsRefreshResult.AUDIO_BUSY
        return null
    }

    suspend fun refreshDeviceSettings(device: AppleDevice): DeviceSettingsRefreshResult {
        var reserved: SettingsRefresh? = null
        var watchdog: Job? = null
        var request: me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager.ControlSessionRefresh? = null
        try {
            val unavailable = withContext(Dispatchers.Main.immediate) {
                deviceSettingsRefreshReadiness(device)?.let { return@withContext it }
                val controller = audioControllers[device.macAddress] ?: return@withContext DeviceSettingsRefreshResult.UNAVAILABLE
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
            watchdog = serviceScope.launch(Dispatchers.IO) {
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

    private fun attachExperimentalAudio(device: AppleDevice) {
        val address = device.macAddress
        if (audioDevices[address] === device) return
        detachExperimentalAudio(address)
        val controller = ExperimentalAudioController(this, device) { active ->
            setMicrophoneActive(address, active)
        }
        audioDevices[address] = device
        audioControllers[address] = controller
        val suspensions = Channel<Boolean>(Channel.CONFLATED)
        sensorSuspensions[address] = suspensions
        device.recordingHandler = { recording ->
            if (recording == null) { controller.stop(); true }
            else controller.startRecording(recording.file)
        }
        audioJobs[address] = serviceScope.launch {
            launch(Dispatchers.IO) {
                for (suspended in suspensions) device.setSensorsSuspended(suspended)
            }
            controller.microphone.state.collect { state ->
                device.updateMicrophoneState {
                    it.copy(isActive = state.phase == MicrophonePhase.CAPTURING,
                        durationMs = state.pcmDurationMs, packetsReceived = state.receivedPackets,
                        level = state.level)
                }
                if (state.phase in listOf(MicrophonePhase.IDLE, MicrophonePhase.RECOVERING, MicrophonePhase.ERROR)) {
                    device.updateRecordingState { it.copy(isRecording = false) }
                }
            }
        }
    }

    private fun detachExperimentalAudio(address: MacAddress) {
        audioDevices.remove(address)?.recordingHandler = null
        sensorSuspensions.remove(address)?.close()
        audioJobs.remove(address)?.cancel()
        audioControllers.remove(address)?.let { it.onDisconnected(); it.close() }
        setMicrophoneActive(address, false)
    }

    private fun microphonePermissionAllowed(address: MacAddress? = null): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        if (getSystemService(AudioManager::class.java).isMicrophoneMute) return false
        val owners = if (address == null) microphoneOwners.toSet() else microphoneOwners + address
        if (owners.any { audioControllers[it]?.bridge?.isMicrophonePrivacyBlocked() != false }) return false
        return runCatching {
            val mode = getSystemService(AppOpsManager::class.java)
                .checkOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, applicationInfo.uid, packageName)
            mode != AppOpsManager.MODE_IGNORED && mode != AppOpsManager.MODE_ERRORED
        }.getOrDefault(false)
    }

    private fun setMicrophoneActive(address: MacAddress, active: Boolean) {
        if (active && address in microphoneOwners) return
        if (active) {
            check(settingsRefreshes.isEmpty()) { "Wait for the settings refresh to finish." }
            check(!destroying && microphonePermissionAllowed(address)) { "Allow microphone access first." }
            if (address !in microphoneOwners) {
                check(ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    "Open LibrePods before starting the microphone."
                }
                microphoneOwners += address
                try { startForegroundNotification() } catch (error: Exception) {
                    microphoneOwners -= address
                    throw error
                }
            }
        } else if (!microphoneOwners.remove(address)) return
        sensorSuspensions[address]?.trySend(active)
        val anyActive = microphoneOwners.isNotEmpty()
        MediaController.setMicrophoneCaptureActive(anyActive)
        setExperimentalAudioBlocked(anyActive || destroying)
        if (!destroying) startForegroundNotification()
        if (!anyActive) {
            microphonePermissionMonitor?.cancel()
            microphonePermissionMonitor = null
        } else if (microphonePermissionMonitor?.isActive != true) {
            microphonePermissionMonitor = serviceScope.launch {
                while (isActive && !destroying) {
                    if (!microphonePermissionAllowed()) {
                        audioControllers.values.forEach { it.onPermissionRevoked() }
                        break
                    }
                    delay(250)
                }
            }
        }
    }

    private suspend fun onDeviceConnected(bluetoothDevice: BluetoothDevice) {
        if (destroying || bluetoothDevice.bondState != BluetoothDevice.BOND_BONDED) return
        val device = createDevice(bluetoothDevice) ?: return
        if (destroying || device.bluetoothDevice.bondState != BluetoothDevice.BOND_BONDED ||
            device.connectionState.value == ConnectionState.CONNECTED) return
        if (device is AppleDevice) withContext(Dispatchers.Main.immediate) {
            if (!destroying && devices.value[device.macAddress] === device) attachExperimentalAudio(device)
        }
        if (destroying || devices.value[device.macAddress] !== device) return
        device.connect()
    }

    private fun onDeviceDisconnected(mac: MacAddress) {
        detachExperimentalAudio(mac)
        Log.i(TAG, "Device disconnected: ${mac.toRedactedString()}")
        devices.value[mac]?.disconnect()
        updateDeviceNotification(device = devices.value[mac] ?: return)
    }

    private suspend fun loadDevices() {
        val adapter = getSystemService(BluetoothManager::class.java).adapter ?: return
        for (bluetoothDevice in adapter.bondedDevices) createDevice(bluetoothDevice)
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF -> {
                    if (bleRequested) startBleScanner()
                    return
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_ON) {
                        if (bleRequested) serviceScope.launch { loadDevices(); startBleScanner() }
                    } else {
                        stopActiveBleScan()
                        nearbyPairing?.cancelPending()
                        nearbyVisits.clear()
                        recentProximityReports.clear()
                        pairedLids.clear()
                        islandWindow.clearPairingOpening()
                    }
                    return
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val changed = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    if (changed != null && changed.bondState == BluetoothDevice.BOND_NONE) {
                        nearbyPairing?.updatePairingBattery(changed, emptySet())
                        val address = MacAddress(changed.address)
                        onDeviceDisconnected(address)
                        deviceJobs.remove(address)?.forEach { it.cancel() }
                        _devices.update { it - address }
                        pairedObservations.remove(address)
                        recentProximityReports.clear()
                        pairedLids.remove(address)
                        islandWindow.clearPairingOpening(address.value)
                    }
                }
            }
            val bluetoothDevice = intent.getParcelableExtra(
                "android.bluetooth.device.extra.DEVICE",
                BluetoothDevice::class.java
            )
            val action = intent.action

            if (bluetoothDevice != null) {
                when (action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        if (bluetoothDevice.uuids == null) {
                            bluetoothDevice.fetchUuidsWithSdp()
                        } else {
                            serviceScope.launch(Dispatchers.IO) { onDeviceConnected(bluetoothDevice) }
                        }
                    }

                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> onDeviceDisconnected(
                        MacAddress(
                            bluetoothDevice.address
                        )
                    )

                    BluetoothDevice.ACTION_UUID -> serviceScope.launch(Dispatchers.IO) { onDeviceConnected(bluetoothDevice) }
                }
            }
        }
    }

    fun registerBluetoothReceivers() {
        val intentFilter = IntentFilter().apply {
            addAction("android.bluetooth.device.action.ACL_CONNECTED")
            addAction("android.bluetooth.device.action.ACL_DISCONNECTED")
            addAction("android.bluetooth.device.action.UUID")
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }

        registerReceiver(bluetoothReceiver, intentFilter, RECEIVER_EXPORTED)
    }

    fun unregisterBluetoothReceivers() {
        unregisterReceiver(bluetoothReceiver)
    }

    private fun handleScanResult(result: ScanResult) {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val observed = result.timestampNanos
        if (observed < 0 || observed > nowNanos || nowNanos - observed >= 15_000_000_000L) return
        val manufacturer = result.scanRecord?.getManufacturerSpecificData(0x004c) ?: return
        val proximity = NearbyAirPods.proximityRecord(manufacturer) ?: return
        val address = result.device.address
        val observedAt = System.currentTimeMillis() - (nowNanos - observed) / 1_000_000
        recentProximityReports.observe(address, manufacturer, observed, observedAt, nowNanos)
        val owned = devices.value.values.filterIsInstance<AppleDevice>().firstOrNull { device ->
            device.bluetoothDevice.bondState == BluetoothDevice.BOND_BONDED &&
                device.state.value.magicKeys[MagicKeyType.IRK]?.let { verifyRPA(address, it) } == true
        }
        val lidEvent = NearbyAirPods.lidEvent(manufacturer)
        if (Log.isLoggable(NEARBY_SCAN_TAG, Log.DEBUG)) {
            Log.d(NEARBY_SCAN_TAG, "Proximity: ageMs=${(nowNanos - observed) / 1_000_000}, " +
                "subtype=${proximity[2].toInt() and 255}, flags=${proximity[5].toInt() and 255}, " +
                "lid=${lidEvent?.lidOpen}, owned=${owned != null}, " +
                "rpaShaped=${address.substringBefore(':').toIntOrNull(16)?.and(0xc0) == 0x40}, " +
                "nativeBonded=${result.device.bondState == BluetoothDevice.BOND_BONDED}, " +
                "storedAddress=${devices.value.keys.any { it.value.equals(address, ignoreCase = true) }}, " +
                "knownDevices=${devices.value.size}, " +
                "keyedDevices=${devices.value.values.filterIsInstance<AppleDevice>().count { it.state.value.magicKeys.containsKey(MagicKeyType.IRK) }}, " +
                "encryptedBatteryKey=${owned?.state?.value?.magicKeys?.containsKey(MagicKeyType.ENC_KEY) == true}")
        }
        lidEvent?.let { event ->
            nearbyVisits.observe(event, observed / 1_000_000, owned != null, protectedModel = pairingModel)?.let {
                nearbyPairing?.onNearbyDevice(it, observed)
            }
        }
        val device = owned ?: return
        val previousObservation = pairedObservations[device.macAddress]
        if (previousObservation != null && observed <= previousObservation) return
        if (previousObservation != null && observed - previousObservation >= 30_000_000_000L) pairedLids.remove(device.macAddress)
        val keys = device.state.value.magicKeys
        val irk = keys[MagicKeyType.IRK] ?: return
        val encryption = keys[MagicKeyType.ENC_KEY] ?: return
        val report = ProximityReport.decode(address, manufacturer, irk, encryption, observedAt)
        if (Log.isLoggable(NEARBY_SCAN_TAG, Log.DEBUG)) Log.d(NEARBY_SCAN_TAG, "Owned proximity: decoded=${report != null}")
        if (report == null) return
        if (device.state.value.magicKeys[MagicKeyType.IRK]?.contentEquals(irk) != true ||
            device.state.value.magicKeys[MagicKeyType.ENC_KEY]?.contentEquals(encryption) != true ||
            device.bluetoothDevice.bondState != BluetoothDevice.BOND_BONDED) return
        pairedObservations[device.macAddress] = observed
        device.receiveNearbyBattery(report.batteries)
        islandWindow.updatePairedBattery(device, report.model)
        report.lidOpen?.let { open ->
            islandWindow.observePairedCaseLid(device.macAddress.value, open, observed)
            val previous = pairedLids.put(device.macAddress, open)
            if (open && previous != true && device.settings.value.showIslandPopup) {
                islandWindow.showPairedLid(device, report.model, observed)
                Log.d(TAG, "Lid advertisement to paired overlay: ${(SystemClock.elapsedRealtimeNanos() - observed) / 1_000_000}ms")
            }
        }
        if (report.mayReconnect && report.connection == 0 && device.settings.value.takeoverWhenDisconnected &&
            device.connectionState.value in setOf(ConnectionState.DISCONNECTED, ConnectionState.AVAILABLE) && !bleCaptureBlocked &&
            SystemClock.elapsedRealtime() - (bleReconnectAt[device.macAddress] ?: -60_000L) >= 60_000L) {
            bleReconnectAt[device.macAddress] = SystemClock.elapsedRealtime()
            serviceScope.launch(Dispatchers.IO) {
                if (!destroying && !bleCaptureBlocked && devices.value[device.macAddress] === device &&
                    device.bluetoothDevice.bondState == BluetoothDevice.BOND_BONDED &&
                    device.settings.value.takeoverWhenDisconnected &&
                    device.connectionState.value in setOf(ConnectionState.DISCONNECTED, ConnectionState.AVAILABLE) &&
                    device.state.value.magicKeys[MagicKeyType.IRK]?.contentEquals(irk) == true &&
                    device.state.value.magicKeys[MagicKeyType.ENC_KEY]?.contentEquals(encryption) == true) {
                    device.connect()
                }
            }
        }
    }

    private fun replayRecentBattery(device: AppleDevice, socket: android.bluetooth.BluetoothSocket?,
                                    irk: ByteArray, encryption: ByteArray) {
        synchronized(device.aacp) {
            if (destroying || bleCaptureBlocked || devices.value[device.macAddress] !== device ||
                device.bluetoothDevice.bondState != BluetoothDevice.BOND_BONDED ||
                device.aacp.captureAudioSocket() !== socket ||
                device.state.value.magicKeys[MagicKeyType.IRK]?.contentEquals(irk) != true ||
                device.state.value.magicKeys[MagicKeyType.ENC_KEY]?.contentEquals(encryption) != true) return
            val observation = recentProximityReports.matching(irk, encryption, SystemClock.elapsedRealtimeNanos()).lastOrNull() ?: return
            if (observation.observedNanos <= (pairedObservations[device.macAddress] ?: -1L)) return
            device.receiveNearbyBattery(observation.report.batteries)
            pairedObservations[device.macAddress] = observation.observedNanos
            islandWindow.updatePairedBattery(device, observation.report.model)
        }
    }

    fun setExperimentalAudioBlocked(active: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            bleHandler.post { setExperimentalAudioBlocked(active) }
            return
        }
        if (destroying || bleCaptureBlocked == active) return
        bleCaptureBlocked = active
        nearbyPairing?.setBlocked(active)
        if (active) stopActiveBleScan() else if (bleRequested) startBleScanner()
    }

    private fun startBleScanner() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            bleHandler.post { startBleScanner() }
            return
        }
        if (destroying) return
        bleRequested = true
        if (bleCaptureBlocked || !bleReady) return
        stopActiveBleScan()
        val adapter = getSystemService(BluetoothManager::class.java).adapter ?: return
        if (!adapter.isEnabled) return
        val scanner = adapter.bluetoothLeScanner ?: return
        val generation = ++bleGeneration
        val interactive = getSystemService(PowerManager::class.java).isInteractive
        val settings = nearbyScanSettings(interactive, appDataRepository.settings.value.bleScanMode)
        val mode = settings.scanMode
        val filter = ScanFilter.Builder().setManufacturerData(0x004c, byteArrayOf()).build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                bleHandler.post {
                    if (!destroying && generation == bleGeneration) try { handleScanResult(result) }
                    catch (_: SecurityException) { stopActiveBleScan() }
                }
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.sortedBy { it.timestampNanos }.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }
            override fun onScanFailed(errorCode: Int) {
                bleHandler.post {
                    if (generation != bleGeneration || destroying) return@post
                    stopActiveBleScan()
                    Log.w(TAG, "BLE scan failed: $errorCode")
                    if (errorCode != ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED) bleHandler.postDelayed({
                        if (bleRequested && !destroying) startBleScanner()
                    }, 30_000)
                }
            }
        }
        bleScanner = scanner
        bleScanCallback = callback
        try {
            scanner.startScan(listOf(filter), settings, callback)
            Log.d(TAG, "BLE lid monitoring started: mode=$mode, reportDelay=0")
        } catch (_: SecurityException) {
            stopActiveBleScan()
            Log.w(TAG, "Nearby devices permission is unavailable")
        } catch (_: Exception) {
            stopActiveBleScan()
            bleHandler.postDelayed({ if (bleRequested && !destroying) startBleScanner() }, 30_000)
        }
    }

    private fun stopActiveBleScan() {
        bleGeneration++
        val scanner = bleScanner
        val callback = bleScanCallback
        bleScanner = null
        bleScanCallback = null
        if (scanner != null && callback != null) runCatching { scanner.stopScan(callback) }
    }

    private fun stopBleScanner() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            bleHandler.post { stopBleScanner() }
            return
        }
        bleRequested = false
        stopActiveBleScan()
        nearbyVisits.clear().forEach { nearbyPairing?.onNearbyDevice(it) }
    }
    private suspend fun createDevice(bluetoothDevice: BluetoothDevice): Device<*, *, *>? {
        val address = MacAddress(bluetoothDevice.address)
        return deviceCreationLocks.computeIfAbsent(address) { Mutex() }.withLock {
            devices.value[address]?.let { return@withLock it }
            if (destroying || bluetoothDevice.bondState != BluetoothDevice.BOND_BONDED) return@withLock null
            // Saved identity and keys remain useful before Classic reconnect/SDP fills Android's UUID cache.
            val entity = appleRepository.load(address)
            if (destroying || !canLoadAppleDevice(
                    bonded = bluetoothDevice.bondState == BluetoothDevice.BOND_BONDED,
                    savedAppleDevice = entity != null,
                    advertisesAacp = bluetoothDevice.uuids?.contains(
                        ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")) == true)) return@withLock null
            val device = AppleDevice(this, getSystemService(BluetoothManager::class.java).adapter,
                bluetoothDevice, ConnectionState.DISCONNECTED)
            entity?.let {
                device.loadInitialState(AppleState(capabilities = it.cache.capabilities, magicKeys = it.cache.magicKeys,
                    controlStates = it.cache.controlStates), it.settings, it.metadata)
            }
            if (destroying || bluetoothDevice.bondState != BluetoothDevice.BOND_BONDED) return@withLock null
            _devices.update { it + (address to device) }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(NotificationChannel("device_$address",
                "Device ${device.metadata.value.name}", NotificationManager.IMPORTANCE_LOW))
            val installed = withContext(Dispatchers.Main.immediate) {
                if (destroying || devices.value[address] !== device || bluetoothDevice.bondState != BluetoothDevice.BOND_BONDED) false
                else {
                    attachExperimentalAudio(device)
                    deviceJobs[address] = listOf(observeAppleState(device), observeAppleSettings(device), observeAppleMetadata(device))
                    val keys = device.state.value.magicKeys
                    val irk = keys[MagicKeyType.IRK]?.copyOf()
                    val encryption = keys[MagicKeyType.ENC_KEY]?.copyOf()
                    if (irk != null && encryption != null)
                        replayRecentBattery(device, device.aacp.captureAudioSocket(), irk, encryption)
                    true
                }
            }
            if (!installed) return@withLock null
            device
        }
    }

    fun observeAppSettings(): Job {
        var oldAppSettings: AppSettingsEntity = appDataRepository.settings.value

        return serviceScope.launch(Dispatchers.IO) {
            appDataRepository.settings.collect { settings ->
                if (oldAppSettings.bleReportDelay != settings.bleReportDelay || oldAppSettings.bleScanMode != settings.bleScanMode) {
                    Log.d(TAG, "BLE settings changed: $settings, restarting scanner")
                    stopBleScanner()
                    startBleScanner()
                }
                islandWindow.updateSettings(settings)
                oldAppSettings = settings
            }
        }
    }

    fun observeAppleState(device: AppleDevice): Job = serviceScope.launch(Dispatchers.IO) {
        var previousState = device.state.value
        var pairingBattery: Set<Battery>? = null
        var lastSavedKeys: Map<MagicKeyType, ByteArray>? = null
        val listeningMode = IslandListeningMode()
        var conversationOwner: Any? = null

        try {
        device.state.collect { state ->
            // Capture before any repository suspension; an old emission cannot belong to a later socket.
            val modeTransport = device.aacp.captureAudioSocket()
            val deviceSettings = device.settings.value

            if (state.battery != pairingBattery) {
                pairingBattery = state.battery
                withContext(Dispatchers.Main.immediate) {
                    if (!destroying && devices.value[device.macAddress] === device &&
                        device.state.value.battery == state.battery) {
                        nearbyPairing?.updatePairingBattery(device.bluetoothDevice, state.battery)
                        islandWindow.updatePairedBattery(device)
                    }
                }
            }
            if (state.controlStates != previousState.controlStates || state.requestedControls != previousState.requestedControls) {
                withContext(Dispatchers.Main.immediate) {
                    if (!destroying && devices.value[device.macAddress] === device) {
                        islandWindow.updateAdaptiveStrength(device)
                    }
                }
            }

            if (state.magicKeys.isNotEmpty() && state.magicKeys != lastSavedKeys) {
                val irk = state.magicKeys[MagicKeyType.IRK]?.copyOf()
                val encryption = state.magicKeys[MagicKeyType.ENC_KEY]?.copyOf()
                if (irk != null && encryption != null) withContext(Dispatchers.Main.immediate) {
                    replayRecentBattery(device, modeTransport, irk, encryption)
                }
                appleRepository.saveCacheFromState(device.macAddress, state)
                lastSavedKeys = state.magicKeys
            }

            val conversationSocket = device.aacp.captureAudioSocket()
            if (conversationOwner !== conversationSocket) {
                conversationOwner?.let { MediaController.cancelConversation(it) }
                conversationOwner = conversationSocket
            }
            if (conversationSocket == null || state.controlStates[ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG]
                    ?.firstOrNull() == 2.toByte()) {
                conversationOwner?.let { MediaController.cancelConversation(it) }
            } else if (state.conversationAwarenessState != previousState.conversationAwarenessState) {
                when (state.conversationAwarenessState) {
                    1, 2, 3 -> MediaController.startSpeaking(state.conversationAwarenessState,
                        conversationSocket, device.macAddress.value,
                        deviceSettings.conversationAwarenessVolume.toInt(),
                        deviceSettings.relativeConversationAwarenessVolumeEnabled,
                        deviceSettings.conversationAwarenessPauseMusicEnabled,
                        deviceSettings.conversationAwarenessReducedVolume.toInt())
                    4, 6, 7, 8, 9 -> MediaController.stopSpeaking(conversationSocket)
                }
            }


            // Independent of battery/component updates: StateFlow can coalesce them with a mode report.
            saveChangedControlCache(previousState, state) {
                appleRepository.saveCacheFromState(device.macAddress, it)
            }
            listeningMode.observeReport(modeTransport?.takeIf(device.aacp::ownsAudioSocket),
                previousState.controlStates[ControlCommandIdentifier.LISTENING_MODE],
                state.controlStates[ControlCommandIdentifier.LISTENING_MODE],
                currentReport = device.state.value.controlStates[ControlCommandIdentifier.LISTENING_MODE])?.let { mode ->
                val owner = modeTransport ?: return@let
                withContext(Dispatchers.Main.immediate) {
                    if (!destroying && devices.value[device.macAddress] === device &&
                        device.aacp.ownsAudioSocket(owner) &&
                        device.state.value.controlStates[ControlCommandIdentifier.LISTENING_MODE]
                            ?.singleOrNull()?.toInt()?.and(255) == mode) {
                        showIsland(device, statusText = getString(when (mode) {
                            1 -> R.string.off
                            2 -> R.string.noise_cancellation
                            3 -> R.string.transparency
                            else -> R.string.adaptive
                        }))
                    }
                }
            }

            if (state.aacpPackets != previousState.aacpPackets) {
                if (!appDataRepository.state.value.hasConnectedToAACP) {
                    appDataRepository.updateState { it.copy(hasConnectedToAACP = true) }
                }
                if (appDataRepository.state.value.firstSuccessfulConnectionTime == null) {
                    appDataRepository.updateState { it.copy(firstSuccessfulConnectionTime = System.currentTimeMillis()) }
                }
            }

            when {
                state.capabilities != previousState.capabilities -> {
                    Log.i(
                        TAG,
                        "capabilities changed for device ${device.macAddress.toRedactedString()}"
                    )
                    Log.d(TAG, "capabilities: ${state.capabilities}")

                    if (state.capabilities.isNotEmpty()) {
                        appleRepository.saveCacheFromState(device.macAddress, state)
                    }
                }

                state.battery != previousState.battery -> {
                    Log.i(
                        TAG,
                        "battery state changed for device ${device.macAddress.toRedactedString()}"
                    )
                    Log.d(TAG, "battery state: ${state.battery}")

                    Log.d(TAG, "updating widgets")
                    updateWidgets()

                    Log.d(TAG, "updating notification")
                    updateDeviceNotification(device = device)

                    // TODO:Shizuku
                    /* Log.d(TAG, "updating bluetooth metadata")
                    shizuku.setMetadata(
                        device.macAddress.value,
                        BluetoothMetadata.METADATA_UNTETHERED_CASE_BATTERY,
                        state.battery.find { it.component == BatteryComponent.CASE }?.level.toString()
                            .toByteArray()
                    )
                    shizuku.setMetadata(
                        device.macAddress.value,
                        BluetoothMetadata.METADATA_UNTETHERED_CASE_CHARGING,
                        (if (state.battery.find { it.component == BatteryComponent.CASE }?.status == BatteryStatus.CHARGING
                            || state.battery.find { it.component == BatteryComponent.CASE }?.status == BatteryStatus.OPTIMIZED_CHARGING
                        ) "1" else "0").toByteArray()
                    )
                    shizuku.setMetadata(
                        device.macAddress.value,
                        BluetoothMetadata.METADATA_UNTETHERED_LEFT_BATTERY,
                        state.battery.find { it.component == BatteryComponent.LEFT }?.level.toString()
                            .toByteArray()
                    )
                    shizuku.setMetadata(
                        device.macAddress.value,
                        BluetoothMetadata.METADATA_UNTETHERED_LEFT_CHARGING,
                        (if (state.battery.find { it.component == BatteryComponent.LEFT }?.status == BatteryStatus.CHARGING
                            || state.battery.find { it.component == BatteryComponent.LEFT }?.status == BatteryStatus.OPTIMIZED_CHARGING
                        ) "1" else "0").toByteArray()
                    )
                    shizuku.setMetadata(
                        device.macAddress.value,
                        BluetoothMetadata.METADATA_UNTETHERED_RIGHT_BATTERY,
                        state.battery.find { it.component == BatteryComponent.RIGHT }?.level.toString()
                            .toByteArray()
                    )
                    shizuku.setMetadata(
                        device.macAddress.value,
                        BluetoothMetadata.METADATA_UNTETHERED_RIGHT_CHARGING,
                        (if (state.battery.find { it.component == BatteryComponent.RIGHT }?.status == BatteryStatus.CHARGING
                            || state.battery.find { it.component == BatteryComponent.RIGHT }?.status == BatteryStatus.OPTIMIZED_CHARGING
                        ) "1" else "0").toByteArray()
                    ) */
                }

                state.componentState != previousState.componentState -> {
                    Log.i(
                        TAG,
                        "component state changed for device ${device.macAddress.toRedactedString()}"
                    )
                    Log.d(TAG, "component state: ${state.componentState}")

                    val earDetectionCtrlCmdValue =
                        state.controlStates[ControlCommandIdentifier.EAR_DETECTION_CONFIG]
                            ?: byteArrayOf(0x01.toByte())
                    Log.d(
                        TAG,
                        "ear detection control command value: ${earDetectionCtrlCmdValue.toHexString()}"
                    )
                    val earDetectionEnabled = earDetectionCtrlCmdValue[0] == 0x01.toByte() || deviceSettings.earDetectionEnabled // temporary, cache broken (?)
                    Log.d(TAG, "ear detection enabled: $earDetectionEnabled")
                    if (earDetectionEnabled) {
                        processComponentStateChange(
                            device = device,
                            previousComponentState = previousState.componentState,
                            newComponentState = state.componentState,
                            disconnectWhenNotWearing = deviceSettings.disconnectWhenNotWearing
                        )
                    }

                    Log.d(TAG, "updating notification")
                    updateDeviceNotification(device = device)
                }

                state.controlStates != previousState.controlStates -> {
                    Log.i(
                        TAG,
                        "control states changed for device ${device.macAddress.toRedactedString()}"
                    )
                    Log.d(TAG, "control states: ${state.controlStates}")

                    Log.d(TAG, "updating notification")
                    updateDeviceNotification(device = device)
                }

                state.currentHeartRate?.timestamp != previousState.currentHeartRate?.timestamp -> {
                    state.currentHeartRate?.let { heartRateSample ->
                        Log.i(
                            TAG,
                            "current heart rate changed from device ${device.macAddress.toRedactedString()}"
                        )
                        Log.d(
                            TAG,
                            "current heart rate: ${heartRateSample.bpm} bpm, timestamp: ${heartRateSample.timestamp.toJavaInstant()}. processing."
                        )

                        processHeartRateSample(
                            heartRateSample = heartRateSample,
                            interval = state.heartRateInterval,
                            alertThreshold = deviceSettings.hrmAlertThreshold
                        )
                    }
                }

            }
            previousState = state
        }
        } finally {
            conversationOwner?.let { MediaController.cancelConversation(it) }
        }
    }

    fun observeAppleSettings(device: AppleDevice): Job = serviceScope.launch(Dispatchers.IO) {
        // StateFlow emits the current value first, including writes made before this collector starts.
        device.settings.collect { settings ->
            appleRepository.saveSettings(device.macAddress, settings)
        }
    }

    fun observeAppleMetadata(device: AppleDevice): Job = serviceScope.launch(Dispatchers.IO) {
        var previousMetadata = device.metadata.value

        device.metadata.collect { metadata ->
            if (metadata != previousMetadata) {
                Log.i(TAG, "metadata changed for device ${device.macAddress.toRedactedString()}")
                Log.d(TAG, "metadata: $metadata")

                appleRepository.saveMetadata(device.macAddress, metadata)

                setAppleBluetoothMetadata(device)

                val notificationManager = getSystemService(NotificationManager::class.java)
                val channel =
                    notificationManager.getNotificationChannel("device_${device.macAddress}")
                channel?.name = "Device ${metadata.name}"
                notificationManager.createNotificationChannel(channel)
            }
            previousMetadata = metadata
        }
    }

    fun showIsland(
        device: Device<*, *, *>,
        type: IslandType = IslandType.CONNECTED,
        reversed: Boolean = false,
        otherDeviceName: String? = null,
        statusText: String? = null
    ) {
        Log.d(TAG, "Showing island window")

        val state = device.state.value
        val settings = device.settings.value

        when (state) {
            is AppleState -> {
                val device = device as AppleDevice
                val state = device.state.value
                val settings = settings as AppleSettings
                val metadata = device.metadata.value

                if (state.componentState.isEmpty()) {
                    Log.w(TAG, "No component state available, can't show island")
                    return
                }

                if (settings.showIslandPopup) {
                    if (!Settings.canDrawOverlays(this)) {
                        Log.w(TAG, "No permission for SYSTEM_ALERT_WINDOW")
                        return
                    }

                    Log.i(TAG, "Showing island for device ${device.macAddress.toRedactedString()}")

                    val leftBattery = state.battery.find { it.component == BatteryComponent.LEFT }?.level ?: 0
                    val rightBattery = state.battery.find { it.component == BatteryComponent.RIGHT }?.level ?: 0
                    val batteryPercentage = leftBattery.coerceAtMost(rightBattery)

                    islandWindow.show(
                        owner = device,
                        name = metadata.name,
                        batteryLevel = batteryPercentage,
                        type = type,
                        reversed = reversed,
                        otherDeviceName = otherDeviceName,
                        statusText = statusText,

                        openApp = {
                            Log.d(TAG, "Opening app")
                            val intent = Intent(
                                this,
                                MainActivity::class.java,
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(intent)
                        },

                        showOffListeningMode = state.effectiveAllowOff(device.settings.value, metadata.modelNumber) == true,
                        noiseControlModeValue = state.controlStates[ControlCommandIdentifier.LISTENING_MODE]?.getOrNull(0)?.toInt() ?: 3,
                        onNoiseControlModeChanged = {
                            device.setControlCommand(
                                ControlCommandIdentifier.LISTENING_MODE, it
                            )
                        },
                        adaptiveStrength = state.controlStates[ControlCommandIdentifier.AUTO_ANC_STRENGTH]?.getOrNull(0)?.toInt() ?: 50,
                        requestedAdaptiveStrength = state.requestedControls[ControlCommandIdentifier.AUTO_ANC_STRENGTH]?.firstOrNull()?.toInt(),
                        onAdaptiveStrengthChanged = {
                            device.setControlCommand(
                                ControlCommandIdentifier.AUTO_ANC_STRENGTH, it
                            )
                        }
                    )
                }
            }

            else -> {
                Log.d(
                    TAG,
                    "Unsupported device state: ${device.state.value.javaClass.simpleName}, not showing island"
                )
                return
            }
        }
    }

    fun updateDeviceNotification(
        device: Device<*, *, *>
    ) {
        val notificationManager = getSystemService(NotificationManager::class.java)

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationId = device.macAddress.toNotificationId()

        if (device.connectionState.value == ConnectionState.CONNECTED || device.connectionState.value == ConnectionState.AVAILABLE) {
            when (device) {
                is AppleDevice -> {
                    Log.d(
                        TAG,
                        "Updating notification for Apple device ${device.macAddress.toRedactedString()}"
                    )

                    val updatedNotificationBuilder =
                        NotificationCompat.Builder(this, "device_${device.macAddress}")
                            .setSmallIcon(R.drawable.ic_airpods)
                            .setContentTitle(device.metadata.value.name)
                            .setContentText(device.state.value.battery.joinToString(" ") { "${it.component.name[0]}: ${it.level}%" })
                            .setContentIntent(pendingIntent)
                            .setCategory(Notification.CATEGORY_STATUS)
                            .setPriority(NotificationCompat.PRIORITY_LOW)
                            .setOngoing(true)
                            .addAction(
                                R.drawable.ic_transparency, // icon never shows up?? (tested on AOSP ROMs)
                                "Transparency",
                                PendingIntent.getService(
                                    this,
                                    notificationId + 3,
                                    Intent(this, LibrePodsService::class.java).apply {
                                        action = "ACTION_SET_ANC_MODE"
                                        putExtra("MAC_ADDRESS", device.macAddress.toString())
                                        putExtra("ANC_MODE", 3)
                                    },
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                ),
                            )
                            .addAction(
                                R.drawable.ic_adaptive,
                                "Adaptive",
                                PendingIntent.getService(
                                    this,
                                    notificationId + 4,
                                    Intent(this, LibrePodsService::class.java).apply {
                                        action = "ACTION_SET_ANC_MODE"
                                        putExtra("MAC_ADDRESS", device.macAddress.toString())
                                        putExtra("ANC_MODE", 4)
                                    },
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                ),
                            )
                            .addAction(
                                R.drawable.ic_noise_cancellation,
                                "Noise Cancellation",
                                PendingIntent.getService(
                                    this,
                                    notificationId + 2,
                                    Intent(this, LibrePodsService::class.java).apply {
                                        action = "ACTION_SET_ANC_MODE"
                                        putExtra("MAC_ADDRESS", device.macAddress.toString())
                                        putExtra("ANC_MODE", 2)
                                    },
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                ),
                            )

                    val updatedNotification = updatedNotificationBuilder.build()

                    notificationManager.notify(notificationId, updatedNotification)
                }
            }

        } else {
            notificationManager.cancel(notificationId)
        }
    }

    fun startForegroundNotification() {
        val disconnectedNotificationChannel = NotificationChannel(
            "foreground_service_status",
            getString(R.string.foreground_service_status),
            NotificationManager.IMPORTANCE_NONE
        )

        val hrmAlertChannel = NotificationChannel(
            "hrm_alert",
            getString(R.string.heart_rate_alert),
            NotificationManager.IMPORTANCE_HIGH
        )

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(disconnectedNotificationChannel)
        notificationManager.createNotificationChannel(hrmAlertChannel)
        val microphoneActive = microphoneOwners.isNotEmpty()
        if (microphoneActive) notificationManager.createNotificationChannel(NotificationChannel(
            "microphone_session", getString(R.string.pixel_microphone_title), NotificationManager.IMPORTANCE_LOW))

        val notificationSettingsIntent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, "foreground_service_status")
        }

        val pendingIntentNotifDisable = PendingIntent.getActivity(
            this,
            0,
            notificationSettingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, if (microphoneActive) "microphone_session" else "foreground_service_status")
            .setSmallIcon(R.drawable.ic_airpods).setContentTitle(getString(R.string.service_running))
            .setContentText(getString(R.string.foreground_notification_description))
            .setContentIntent(pendingIntentNotifDisable).setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW).setOngoing(true)
            .apply {
                if (microphoneActive) {
                    setContentTitle(getString(R.string.pixel_microphone_title))
                    setContentText(getString(R.string.pixel_microphone_active))
                    val stop = PendingIntent.getService(this@LibrePodsService, 58,
                        Intent(this@LibrePodsService, LibrePodsService::class.java).setAction(STOP_MICROPHONE),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    addAction(0, getString(R.string.pixel_microphone_stop), stop)
                }
            }.build()

        try {
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                (if (microphoneActive) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            startForeground(1, notification, types)
        } catch (e: Exception) {
            if (microphoneActive) throw e
            e.printStackTrace()
        }
    }

    fun updateWidgets() {
        // TODO: Room
        val widgetMobileBatteryEnabled = getSharedPreferences(
            "settings",
            MODE_PRIVATE
        ).getBoolean("show_phone_battery_in_widget", false)

        val appWidgetManager = AppWidgetManager.getInstance(this)
        val componentName = ComponentName(this, BatteryWidget::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(componentName)

        val remoteViews = RemoteViews(packageName, R.layout.battery_widget).also { it ->
            val openActivityIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            it.setOnClickPendingIntent(R.id.battery_widget, openActivityIntent)

            // TODO: device-specific widgets
            val device =
                devices.value.values.firstOrNull { it is AppleDevice && it.connectionState.value == ConnectionState.CONNECTED } as? AppleDevice

            val leftBattery =
                device?.state?.value?.battery?.find { it.component == BatteryComponent.LEFT }
            val rightBattery =
                device?.state?.value?.battery?.find { it.component == BatteryComponent.RIGHT }
            val caseBattery =
                device?.state?.value?.battery?.find { it.component == BatteryComponent.CASE }

            it.setTextViewText(R.id.left_battery_widget, leftBattery?.let {
                "${it.level}%"
            } ?: "")
            it.setProgressBar(
                R.id.left_battery_progress, 100, leftBattery?.level ?: 0, false
            )
            it.setViewVisibility(
                R.id.left_charging_icon,
                if (leftBattery?.status == BatteryStatus.CHARGING || leftBattery?.status == BatteryStatus.OPTIMIZED_CHARGING) View.VISIBLE else View.GONE
            )

            it.setTextViewText(R.id.right_battery_widget, rightBattery?.let {
                "${it.level}%"
            } ?: "")
            it.setProgressBar(
                R.id.right_battery_progress, 100, rightBattery?.level ?: 0, false
            )
            it.setViewVisibility(
                R.id.right_charging_icon,
                if (rightBattery?.status == BatteryStatus.CHARGING || rightBattery?.status == BatteryStatus.OPTIMIZED_CHARGING) View.VISIBLE else View.GONE
            )

            it.setTextViewText(R.id.case_battery_widget, caseBattery?.let {
                "${it.level}%"
            } ?: "")
            it.setProgressBar(
                R.id.case_battery_progress, 100, caseBattery?.level ?: 0, false
            )
            it.setViewVisibility(
                R.id.case_charging_icon,
                if (caseBattery?.status == BatteryStatus.CHARGING || caseBattery?.status == BatteryStatus.OPTIMIZED_CHARGING) View.VISIBLE else View.GONE
            )

            it.setViewVisibility(
                R.id.phone_battery_widget_container,
                if (widgetMobileBatteryEnabled) View.VISIBLE else View.GONE
            )
            if (widgetMobileBatteryEnabled) {
                val batteryManager = getSystemService(BatteryManager::class.java)
                val batteryLevel =
                    batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val charging =
                    batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) == BatteryManager.BATTERY_STATUS_CHARGING
                it.setTextViewText(
                    R.id.phone_battery_widget, "$batteryLevel%"
                )
                it.setViewVisibility(
                    R.id.phone_charging_icon, if (charging) View.VISIBLE else View.GONE
                )
                it.setProgressBar(
                    R.id.phone_battery_progress, 100, batteryLevel, false
                )
            }
        }
        appWidgetManager.updateAppWidget(widgetIds, remoteViews)
    }

    private fun processComponentStateChange(
        device: Device<*, *, *>,
        previousComponentState: Set<DeviceComponentState>,
        newComponentState: Set<DeviceComponentState>,
        disconnectWhenNotWearing: Boolean
    ) {
        val old = earPresenceOf(previousComponentState)
        val new = earPresenceOf(newComponentState)
        if (old == new) return

        Log.d(TAG, "earPresence: $old -> $new")

        // new != NONE because old!=new
        if (old == EarPresence.NONE) {
            showIsland(
                device = device,
                type = IslandType.CONNECTED
            )
            Log.i(TAG, "User put in at least one component, showing island.")
        }

        if (new == EarPresence.NONE && appDataRepository.settings.value.islandSettings.keepCompactVisible) {
            islandWindow.close()
        }

        var justEnabledA2dp = false

        when {
            old == EarPresence.NONE -> {
                Log.d(
                    TAG,
                    "User put in at least one component, enabling audio for device ${device.macAddress.toRedactedString()}"
                )
                device.enableAudio()
                device.connectA2dp()
                justEnabledA2dp = true

                device.waitForA2dpConnection(this) {
                    MediaController.sendPlay()
                    MediaController.iPausedTheMedia = false
                }

                if (MediaController.getMusicActive()) {
                    MediaController.userPlayedTheMedia = true
                }
                if (new == EarPresence.PARTIAL) {
                    MediaController.sendPlay()
                    MediaController.iPausedTheMedia = false
                }
            }

            new == EarPresence.NONE -> {
                MediaController.sendPause(force = true)
                if (disconnectWhenNotWearing) {
                    Log.d(
                        TAG,
                        "Disconnecting audio for device ${device.macAddress.toRedactedString()} because user took out all components and disconnectWhenNotWearing is true"
                    )
                    device.disableAudio()
                    device.disconnectAudio()
                }
            }
        }

        when {
            new == EarPresence.FULL -> {
                Log.d("AirPodsParser", "User put in all components.")
                MediaController.userPlayedTheMedia = false
                if (!justEnabledA2dp) {
                    MediaController.sendPlay()
                    MediaController.iPausedTheMedia = false
                }
            }

            old == EarPresence.FULL -> {
                Log.d("AirPodsParser", "User took one out.")
                MediaController.userPlayedTheMedia = false
                if (new == EarPresence.PARTIAL) {
                    MediaController.sendPause()
                }
            }
        }
    }

    private fun processHeartRateSample(heartRateSample: HeartRateSample, interval: Duration, alertThreshold: Int) {
        serviceScope.launch(Dispatchers.IO) {
            Log.d(TAG, "inserting to local db")
            heartRateRepository.insert(heartRateSample)
        }

        CoroutineScope(Dispatchers.Default).launch {
            val notificationManager = getSystemService(NotificationManager::class.java)

            if (heartRateSample.bpm > alertThreshold) {
                val notification = NotificationCompat.Builder(this@LibrePodsService, "hrm_alert")
                    .setSmallIcon(R.drawable.ic_pulse_alert)
                    .setContentTitle(getString(R.string.high_heart_rate))
                    .setContentText(
                        getString(
                            R.string.high_heart_rate_notification_text,
                            heartRateSample.bpm
                        )
                    )
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setOngoing(true)
                    .build()

                notificationManager.notify(999, notification)
            } else {
                if (notificationManager.activeNotifications.any { it.id == 999 }) {
                    notificationManager.cancel(999)
                }
            }
        }

        serviceScope.launch(Dispatchers.IO) {
            if (SdkExtensions.getExtensionVersion(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) >= 7) {
                if (checkSelfPermission(HealthPermission.getWritePermission(HeartRateRecord::class)) != PackageManager.PERMISSION_GRANTED) return@launch
                val healthConnectHeartRateSample = HeartRateRecord.Sample(
                    time = heartRateSample.timestamp.toJavaInstant(),
                    beatsPerMinute = heartRateSample.bpm.toLong()
                )

                val zoneOffset =
                    ZoneOffset.systemDefault().rules.getOffset(heartRateSample.timestamp.toJavaInstant())

                val heartRateRecord = HeartRateRecord(
                    startTime = (heartRateSample.timestamp - interval).toJavaInstant(),
                    endTime = heartRateSample.timestamp.toJavaInstant(),
                    startZoneOffset = zoneOffset,
                    endZoneOffset = zoneOffset,
                    samples = listOf(healthConnectHeartRateSample),
                    metadata = androidx.health.connect.client.records.metadata.Metadata.autoRecorded(
                        device = androidx.health.connect.client.records.metadata.Device(type = androidx.health.connect.client.records.metadata.Device.TYPE_HEARABLE)
                    )
                )

                if (healthConnectClient != null) {
                    serviceScope.launch(Dispatchers.IO) {
                        try {
                            healthConnectClient!!.insertRecords(listOf(heartRateRecord))
                        } catch (e: Exception) {
                            Log.e(TAG, "Error inserting heart rate record", e)
                        }
                    }
                } else {
                    Log.w(TAG, "Health Connect client not available")
                }
            } else {
                Log.d(TAG, "U SDK Extension <7")
            }
        }
    }

    // TODO: Shizuku
    private fun setAppleBluetoothMetadata(
        @Suppress("unused") device: AppleDevice
    ) {
        /* val macAddress = device.macAddress.value
        val metadata = device.metadata.value
        val spec = AirPodsSpecs.getSpec(metadata.model)

        shizuku.setMetadata(
            macAddress,
            BluetoothMetadata.METADATA_MAIN_ICON,
            resToUri(spec.primaryImageRes).toString().toByteArray()
        )
        shizuku.setMetadata(
            macAddress, BluetoothMetadata.METADATA_MODEL_NAME, metadata.modelNumber.toByteArray()
        )
        shizuku.setMetadata(
            macAddress,
            BluetoothMetadata.METADATA_DEVICE_TYPE,
            BluetoothMetadata.DEVICE_TYPE_UNTETHERED_HEADSET.toByteArray()
        )
        spec.caseImageRes?.let {
            shizuku.setMetadata(
                macAddress,
                BluetoothMetadata.METADATA_UNTETHERED_CASE_ICON,
                resToUri(it).toString().toByteArray()
            )
        }
//        shizuku.setMetadata(
//            macAddress,
//            BluetoothMetadata.METADATA_UNTETHERED_RIGHT_ICON,
//            resToUri(spec.components.find { it.type == DeviceComponent.RIGHT }.imageRes).toString().toByteArray()
//        )
//        shizuku.setMetadata(
//            macAddress,
//            BluetoothMetadata.METADATA_UNTETHERED_LEFT_ICON,
//            resToUri(spec.components.find { it.type == DeviceComponent.LEFT }.imageRes).toString().toByteArray()
//        )
        shizuku.setMetadata(
            macAddress,
            BluetoothMetadata.METADATA_MANUFACTURER_NAME,
            metadata.manufacturer.toByteArray()
        )
        shizuku.setMetadata(
            macAddress, BluetoothMetadata.METADATA_COMPANION_APP, "me.kavishdevar.librepods".toByteArray()
        )
        shizuku.setMetadata(
            macAddress,
            BluetoothMetadata.METADATA_UNTETHERED_CASE_LOW_BATTERY_THRESHOLD,
            "20".toByteArray()
        )
        shizuku.setMetadata(
            macAddress,
            BluetoothMetadata.METADATA_UNTETHERED_LEFT_LOW_BATTERY_THRESHOLD,
            "20".toByteArray()
        )
        shizuku.setMetadata(
            macAddress,
            BluetoothMetadata.METADATA_UNTETHERED_RIGHT_LOW_BATTERY_THRESHOLD,
            "20".toByteArray()
        )
        Log.d(TAG, "Metadata set for apple device ${device.macAddress.toRedactedString()}")
     */
    }
}

// TODO: move out of this file
private enum class EarPresence { NONE, PARTIAL, FULL }

private fun earPresenceOf(components: Set<DeviceComponentState>): EarPresence {
    if (components.isEmpty()) return EarPresence.NONE
    val inEarCount = components.count { it.status == ComponentStatus.IN_EAR }
    return when (inEarCount) {
        0 -> EarPresence.NONE
        components.size -> EarPresence.FULL
        else -> EarPresence.PARTIAL
    }
}

/** Save each confirmed control update even when the same emission also changes battery or wear state. */
internal suspend fun saveChangedControlCache(previous: AppleState, current: AppleState,
                                            save: suspend (AppleState) -> Unit) {
    if (current.controlStates.isNotEmpty() && current.controlStates != previous.controlStates) save(current)
}
