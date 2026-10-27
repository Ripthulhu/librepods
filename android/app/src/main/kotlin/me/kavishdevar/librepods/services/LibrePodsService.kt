package me.kavishdevar.librepods.services

import me.kavishdevar.librepods.core.data.recording.Recording
import me.kavishdevar.librepods.core.utils.redactMac
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
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
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
import android.media.AudioDeviceInfo
import android.media.AudioDeviceCallback
import android.media.AudioAttributes
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.audio.ExperimentalAudioController
import me.kavishdevar.librepods.audio.LivePcmConsumer
import me.kavishdevar.librepods.audio.MicrophonePhase
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.bluetooth.aacp.types.AppleEvent
import me.kavishdevar.librepods.core.bluetooth.aacp.types.MagicKeyType
import me.kavishdevar.librepods.core.bluetooth.verifyRPA
import me.kavishdevar.librepods.core.bluetooth.ble.NearbyAirPods
import me.kavishdevar.librepods.core.bluetooth.ble.ProximityReport
import me.kavishdevar.librepods.core.bluetooth.ble.CaseBatteryReport
import me.kavishdevar.librepods.core.bluetooth.ble.RecentProximityReports
import me.kavishdevar.librepods.bluetooth.NearbyPairingController
import me.kavishdevar.librepods.bluetooth.DeviceSettingsRefreshResult
import me.kavishdevar.librepods.bluetooth.hasNearbyDevicesPermissions
import me.kavishdevar.librepods.bluetooth.canLoadAppleDevice
import me.kavishdevar.librepods.bluetooth.nearbyScanSettings
import me.kavishdevar.librepods.shizuku.A2dpRecoveryGuard
import me.kavishdevar.librepods.shizuku.BackgroundAudioAccess
import me.kavishdevar.librepods.shizuku.BluetoothVolumeSync
import me.kavishdevar.librepods.core.data.apple.BuddyState
import me.kavishdevar.librepods.core.data.StemAction
import me.kavishdevar.librepods.utils.AssistantLauncher
import me.kavishdevar.librepods.core.data.heartrate.HeartRateSample
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.effectiveAllowOff
import me.kavishdevar.librepods.core.devices.effectiveEarDetection
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatteryStatus
import me.kavishdevar.librepods.core.devices.hasSameDisplayValues
import me.kavishdevar.librepods.core.devices.ComponentStatus
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.Device
import me.kavishdevar.librepods.core.devices.DeviceComponentState
import me.kavishdevar.librepods.core.devices.DeviceComponent
import me.kavishdevar.librepods.database.app.AppSettingsEntity
import me.kavishdevar.librepods.presentation.activities.MainActivity
import me.kavishdevar.librepods.presentation.overlays.IslandType
import me.kavishdevar.librepods.presentation.overlays.IslandListeningMode
import me.kavishdevar.librepods.presentation.overlays.IslandWindow
import me.kavishdevar.librepods.presentation.widgets.BatteryWidget
import me.kavishdevar.librepods.utils.MediaController
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaInstant
import rikka.shizuku.Shizuku
import me.kavishdevar.librepods.companion.CompanionHandoffController
import me.kavishdevar.librepods.companion.CompanionHoldStore
import me.kavishdevar.librepods.companion.CompanionStatus
import me.kavishdevar.librepods.companion.CompanionPreferences
import me.kavishdevar.librepods.companion.CompanionPreferenceAdapter
import me.kavishdevar.librepods.companion.CompanionCallMonitor
import me.kavishdevar.librepods.shizuku.NativeCallConnection
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import me.kavishdevar.librepods.utils.BluetoothIdentitySettings
import me.kavishdevar.librepods.data.xposed.XposedRemotePrefProvider

private const val TAG = "LibrePodsService"
private const val NEARBY_SCAN_TAG = "NearbyScan"
private const val NEARBY_TAKEOVER_TAG = "NearbyTakeover"
private const val STEM_ACTIONS_TAG = "StemActions"
private const val STOP_MICROPHONE = "me.kavishdevar.librepods.STOP_MICROPHONE"
private const val STOP_COMMUNICATION = "me.kavishdevar.librepods.STOP_COMMUNICATION"
private const val A2DP_ACTIVE_DEVICE_CHANGED = "android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED"
private const val HEADSET_ACTIVE_DEVICE_CHANGED = "android.bluetooth.headset.profile.action.ACTIVE_DEVICE_CHANGED"

/** Notification listening-mode buttons paired with the ANC_MODE value the service expects (1 off, 2 ANC, 3 transparency, 4 adaptive). */
private val NOTIFICATION_LISTENING_MODE_BUTTONS = listOf(
    R.id.notification_off_button to 1,
    R.id.notification_transparency_button to 3,
    R.id.notification_adaptive_button to 4,
    R.id.notification_anc_button to 2,
)

@SuppressLint("MissingPermission")
class LibrePodsService: Service() {
    internal val dongleControls by lazy { me.kavishdevar.librepods.dongle.DongleController(this) }
    inner class LocalBinder : Binder() {
        fun getService(): LibrePodsService = this@LibrePodsService
    }

    private val binder = LocalBinder()

    private val _devices = MutableStateFlow<Map<MacAddress, Device<*, *, *>>>(emptyMap())
    val devices = _devices.asStateFlow()

    private val deviceJobs = ConcurrentHashMap<MacAddress, List<Job>>()
    private val deviceCreationLocks = ConcurrentHashMap<MacAddress, Mutex>()
    @Volatile private var destroying = false
    private var startedWithPermissions = false
    private val microphoneOwners = ConcurrentHashMap.newKeySet<MacAddress>()
    private val communicationArmed = ConcurrentHashMap<MacAddress, me.kavishdevar.librepods.audio.CommunicationAudioController>()
    private val audioDevices = ConcurrentHashMap<MacAddress, AppleDevice>()
    private val audioJobs = ConcurrentHashMap<MacAddress, Job>()
    private val sensorSuspensions = ConcurrentHashMap<MacAddress, Channel<Boolean>>()
    private class SettingsRefresh(val device: AppleDevice, val controller: ExperimentalAudioController) {
        val failure = AtomicReference<DeviceSettingsRefreshResult?>()
    }
    private val settingsRefreshes = ConcurrentHashMap<MacAddress, SettingsRefresh>()
    private val nearbyConnections = ConcurrentHashMap<MacAddress, Any>()
    private val companionOperations = ConcurrentHashMap<MacAddress, Any>()
    private val companionReleased = ConcurrentHashMap.newKeySet<MacAddress>()
    private val companionHoldLock = Any()
    @Volatile private var companionHoldReady = false
    private val companionHoldStore by lazy { CompanionHoldStore(this) }
    private var companion: CompanionHandoffController? = null
    val companionHandoff get() = companion
    val companionAudioAvailability get() = companionBridge.state
    fun companionMediaAvailable(): Boolean = companionBridge.state.value.connected
    private val companionBridge by lazy { ShizukuAudioClient(this) }
    private val companionPreferenceAdapter by lazy {
        CompanionPreferenceAdapter(appDataRepository, appleRepository,
            { address -> devices.value[MacAddress(address)] as? AppleDevice }, { companion?.invalidate(); companionBridge.cancelOwnedMedia() },
            { device, value ->
                companionSettingsWriters.getOrPut(device.macAddress) { Mutex() }.withLock {
                    if (device.settings.value != value) false else {
                        appleRepository.saveSettings(device.macAddress, value)
                        device.settings.value == value
                    }
                }
            })
    }
    private val companionPreferences by lazy { CompanionPreferences(this, companionPreferenceAdapter::read, companionPreferenceAdapter::apply,
        companionPreferenceAdapter::supports, { device, key -> me.kavishdevar.librepods.core.devices.LocalPreferenceEdits.token(this, device, key) }) }
    private val companionCalls by lazy { CompanionCallMonitor(this, serviceScope) { state, fresh -> companion?.onCallState(state, fresh) } }
    private var companionCallMonitorJob: Job? = null
    private class WearMedia(val transport: BluetoothSocket) {
        @Volatile var paused = false
        val pauseCompleted = CompletableDeferred<Unit>()
    }
    private val wearMediaEpochs = ConcurrentHashMap<MacAddress, WearMedia>()
    private val companionSettingsWriters = ConcurrentHashMap<MacAddress, Mutex>()
    private data class NearbyMedia(val device: AppleDevice, val state: Int, val deferUntil: Long,
        val localCloseUntil: Long = 0)
    private data class NearbyCase(val device: AppleDevice, val closedLocally: Boolean, val deferUntil: Long = 0)
    private val nearbyMedia = ConcurrentHashMap<MacAddress, NearbyMedia>()
    private val nearbyCases = ConcurrentHashMap<MacAddress, NearbyCase>()
    private var microphonePermissionMonitor: Job? = null
    private val audioControllers get() = (application as LibrePodsApplication).experimentalAudioControllers
    private val communicationControllers get() = (application as LibrePodsApplication).communicationAudioControllers
    private val gameControllers get() = (application as LibrePodsApplication).nativeGameModeControllers
    private val microphoneStarts = ConcurrentHashMap<MacAddress, Job>()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val backgroundAudioAccess by lazy { BackgroundAudioAccess(this) }
    private val bluetoothVolumeSync by lazy { BluetoothVolumeSync(this) }
    private var bluetoothVolumeSyncJob: Job? = null
    private val backgroundAudioBinderReceived = Shizuku.OnBinderReceivedListener { ensureBackgroundAudioAccess() }
    private val backgroundAudioPermissionResult = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) ensureBackgroundAudioAccess()
    }
    private val bleHandler = Handler(Looper.getMainLooper())
    private val nearbyVisits = NearbyAirPods.Visits()
    private val nearbyInvitationProximity = NearbyAirPods.InvitationProximity()
    private val recentProximityReports = RecentProximityReports()
    private data class CaseBatteryObservation(val key: ByteArray, val block: ByteArray, val observedNanos: Long)
    private val caseBatteryObservations = ConcurrentHashMap<MacAddress, CaseBatteryObservation>()
    private var pairingModel: Int? = null
    private var nearbyPairing: NearbyPairingController? = null
    private var bleGeneration = 0L
    private var bleRequested = false
    private var bleReady = false
    @Volatile private var bleCaptureBlocked = false
    private var bleScanner: BluetoothLeScanner? = null
    private var bleScanCallback: ScanCallback? = null
    private var bleScanMode: Int? = null
    private val bleScanStarts = ArrayDeque<Long>()
    private var bleRetry: Runnable? = null
    private val updateScreenScanMode = Runnable { if (bleRequested) startBleScanner(updateMode = true) }
    private val pairedLids = mutableMapOf<MacAddress, Boolean>()
    private val pairedObservations = mutableMapOf<MacAddress, Long>()
    private val connectionHaptics by lazy { ConnectionHaptics(this) }
    private val nativeAudioCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            retireWearMediaForOtherRoute()
            companion?.callRouteChanged()
            checkBluetoothVolumeSync()
            updateConnectionHaptics()
            devices.value.values.filterIsInstance<AppleDevice>().forEach { device ->
                if (hasNativeAudioOutput(device.bluetoothDevice))
                    serviceScope.launch(Dispatchers.IO) { onDeviceConnected(device.bluetoothDevice) }
            }
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            retireWearMediaForOtherRoute()
            companion?.callRouteChanged()
            checkBluetoothVolumeSync()
            updateConnectionHaptics()
        }
    }

    private fun retireWearMediaForOtherRoute() {
        dongleControls.mediaRouteChanged()
        if (wearMediaEpochs.keys.any { !MediaController.isMediaRoute(it.value) }) {
            wearMediaEpochs.clear()
            companionBridge.cancelOwnedMedia()
        }
    }

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

    @Volatile private var isCallRinging = false
    private var headGestureOwner: AppleDevice? = null
    private var headGestureSession: Any? = null
    private var heartRateAlertOwner: AppleDevice? = null

    private fun canControlCall(device: AppleDevice): Boolean =
        !destroying && isCallRinging && devices.value[device.macAddress] === device &&
            device.settings.value.headGesturesEnabled &&
            device.connectionState.value == ConnectionState.CONNECTED &&
            device.state.value.componentState.any { it.status == ComponentStatus.IN_EAR }

    @Synchronized private fun retireHeadGestures(device: AppleDevice? = null) {
        val owner = headGestureOwner ?: return
        if (device != null && owner !== device) return
        headGestureOwner = null
        headGestureSession = null
        owner.stopHeadTracking()
    }

    @Synchronized private fun retireIneligibleHeadGestures(device: AppleDevice) {
        if (headGestureOwner === device && !canControlCall(device)) retireHeadGestures(device)
    }
    @Volatile private var telephonyCallState = TelephonyManager.CALL_STATE_IDLE

    private val telephonyCallback = object: TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            telephonyCallState = state
            dongleControls.callStateChanged(state != TelephonyManager.CALL_STATE_IDLE)
            if (state != TelephonyManager.CALL_STATE_IDLE) { wearMediaEpochs.clear(); companionBridge.cancelOwnedMedia() }
            if (state != TelephonyManager.CALL_STATE_IDLE && companion?.hasCallReservation != true) companion?.invalidate()
            if (state == TelephonyManager.CALL_STATE_RINGING) {
                isCallRinging = true
                synchronized(this@LibrePodsService) {
                    if (headGestureOwner?.let(::canControlCall) == true) return
                    retireHeadGestures()
                    devices.value.values.filterIsInstance<AppleDevice>().firstOrNull(::canControlCall)?.let { device ->
                        headGestureOwner = device
                        val session = Any().also { headGestureSession = it }
                        device.detectHeadGestures { accept ->
                            synchronized(this@LibrePodsService) {
                                if (headGestureOwner !== device || headGestureSession !== session) return@detectHeadGestures
                                if (!canControlCall(device)) {
                                    retireHeadGestures(device)
                                    return@detectHeadGestures
                                }
                                // Retire the callback before calling Telecom; a second queued gesture cannot act.
                                isCallRinging = false
                                retireHeadGestures(device)
                                try {
                                    val telecomManager = getSystemService(TelecomManager::class.java)
                                    @Suppress("DEPRECATION")
                                    if (accept) telecomManager?.acceptRingingCall(VideoProfile.STATE_AUDIO_ONLY)
                                    else telecomManager?.endCall()
                                    Toast.makeText(this@LibrePodsService,
                                        getString(if (accept) R.string.call_accepted else R.string.call_rejected),
                                        Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error handling call gesture", e)
                                }
                            }
                        }
                    }
                }
            } else {
                isCallRinging = false
                retireHeadGestures()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        BluetoothIdentitySettings.initialize(this)
        if (!hasNearbyDevicesPermissions(this)) {
            stopSelf()
            return
        }
        startedWithPermissions = true
        dongleControls.restore()
        restoreCompanionHolds()
        startPrivilegedAccess()
        startBackgroundAudioAccess()
        companion = CompanionHandoffController(this, serviceScope, ::companionStatus, ::companionOperation,
            companionPreferences, ::companionCallAllowed, ::companionCallReturnAllowed, ::companionCallStatus,
            ::companionCallAcquire, ::companionCallReceipt, ::companionPhoneQuiet, ::allCompanionCallsIdle)
        companionCallMonitorJob = serviceScope.launch {
            while (isActive) {
                companionCalls.enable(companion?.linkedDevice()?.let(::companionCallAllowed) == true)
                delay(1000)
            }
        }

        observeAppSettings()

        registerBluetoothReceivers()
        updateConnectionHaptics(initial = true)
        getSystemService(AudioManager::class.java).registerAudioDeviceCallback(nativeAudioCallback, bleHandler)

        nearbyPairing = NearbyPairingController(this, onInteractionChanged = {
            pairingModel = it
            if (it != null) companion?.invalidate()
        },
            onInvitationRejected = nearbyVisits::retry) { paired ->
            islandWindow.suppressPairingOpening(paired.address, SystemClock.elapsedRealtimeNanos())
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val device = createDevice(paired) as? AppleDevice
                    if (device != null) withContext(Dispatchers.Main.immediate) {
                        if (!destroying && devices.value[device.macAddress] === device &&
                            paired.bondState == BluetoothDevice.BOND_BONDED) {
                            nearbyPairing?.updatePairingBattery(paired, device.state.value.battery)
                        }
                    }
                    if (paired.uuids.isNullOrEmpty()) paired.fetchUuidsWithSdp()
                    else onDeviceConnected(paired)
                } catch (_: SecurityException) { stopSelf() }
            }
        }
        serviceScope.launch {
            loadDevices()
            bleReady = true
            startBleScanner()
            devices.value.values.forEach { device ->
                launch(Dispatchers.IO) { onDeviceConnected(device.bluetoothDevice) }
            }
            while (isActive) {
                delay(5.seconds)
                checkBluetoothVolumeSync()
                recentProximityReports.expire(SystemClock.elapsedRealtimeNanos())
                nearbyVisits.expire(SystemClock.elapsedRealtime(), pairingModel).forEach { nearbyPairing?.onNearbyDevice(it) }
                devices.value.values.filterIsInstance<AppleDevice>().forEach { it.expireNearbyBattery(System.currentTimeMillis()) }
            }
        }

        MediaController.initialize(
            audioManager = getSystemService(AudioManager::class.java),
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
        if (!hasNearbyDevicesPermissions(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            STOP_MICROPHONE -> audioControllers.values.forEach { it.stop() }
            STOP_COMMUNICATION -> communicationControllers.values.forEach { it.setEnabled(false) }
            // TODO: make this a sealed class or something, instead of stringly typed. also, make this different for widget so other apps can use it too
            "ACTION_SET_ANC_MODE" -> {
                val ancMode = intent.getIntExtra("ANC_MODE", -1)
                if (ancMode in 1..4) serviceScope.launch {
                    val device = try {
                        when {
                            intent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID) -> {
                                val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                                    AppWidgetManager.INVALID_APPWIDGET_ID)
                                if (widgetId <= AppWidgetManager.INVALID_APPWIDGET_ID) null
                                else widgetConfigRepository.getWidgetConfig(widgetId)?.let { devices.value[it.macAddress] }
                            }
                            intent.hasExtra("MAC_ADDRESS") -> intent.getStringExtra("MAC_ADDRESS")
                                ?.let { runCatching { MacAddress(it) }.getOrNull() }
                                ?.let { devices.value[it] }
                            else -> devices.value.values.filterIsInstance<AppleDevice>()
                                .firstOrNull { it.connectionState.value == ConnectionState.CONNECTED }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        Log.w(TAG, "Could not resolve the requested ANC device", failure)
                        return@launch
                    }
                    if (!destroying && hasNearbyDevicesPermissions(this@LibrePodsService) &&
                        device is AppleDevice && devices.value[device.macAddress] === device &&
                        device.connectionState.value == ConnectionState.CONNECTED) {
                        Log.i(TAG, "Setting ANC mode to $ancMode for device ${device.macAddress.toRedactedString()}")
                        device.setControlCommand(ControlCommandIdentifier.LISTENING_MODE, ancMode)
                    } else {
                        Log.w(TAG, "Requested ANC device is unavailable")
                    }
                }
            }
        }

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        destroying = true
        dongleControls.close()
        companion?.close()
        companionCallMonitorJob?.cancel()
        companionCalls.close()
        companion = null
        companionBridge.close()
        if (Build.VERSION.SDK_INT >= 37) {
            runCatching { Shizuku.removeBinderReceivedListener(backgroundAudioBinderReceived) }
            runCatching { Shizuku.removeRequestPermissionResultListener(backgroundAudioPermissionResult) }
        }
        if (!startedWithPermissions) {
            serviceScope.cancel()
            bleHandler.removeCallbacksAndMessages(null)
            super.onDestroy()
            return
        }
        isCallRinging = false
        retireHeadGestures()
        clearHeartRateAlert()
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
        getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(nativeAudioCallback)
        bleHandler.removeCallbacksAndMessages(null)
        unregisterBluetoothReceivers()
        stopBleScanner()
        recentProximityReports.clear()
        caseBatteryObservations.clear()
        nearbyMedia.clear()
        connectionHaptics.clear()
        nearbyCases.clear()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun startPrivilegedAccess() {
        serviceScope.launch {
            companionBridge.state.collect { state ->
                if (state.connected) {
                    ensureBackgroundAudioAccess()
                    checkBluetoothVolumeSync()
                }
            }
        }
        companionBridge.connect()
    }

    private fun startBackgroundAudioAccess() {
        if (Build.VERSION.SDK_INT < 37) return
        runCatching {
            Shizuku.addBinderReceivedListener(backgroundAudioBinderReceived)
            Shizuku.addRequestPermissionResultListener(backgroundAudioPermissionResult)
        }.onFailure { Log.w(TAG, "Could not observe Shizuku background audio access", it) }
        ensureBackgroundAudioAccess()
    }

    private fun ensureBackgroundAudioAccess() {
        if (Build.VERSION.SDK_INT < 37 || destroying || !startedWithPermissions) return
        serviceScope.launch(Dispatchers.IO) {
            if (!destroying) {
                if (companionBridge.isRoot) companionBridge.ensureOwnBackgroundAudio()
                else backgroundAudioAccess.ensureAllowed()
            }
        }
    }

    /** Active A2DP changes need checking even when Android retains both output endpoints. */
    private fun checkBluetoothVolumeSync() {
        if (destroying || !startedWithPermissions) return
        companionBridge.connect()
        // Retire the previous route's transient state even while its repair is still running.
        selectBluetoothVolumeSyncRoute()
        if (bluetoothVolumeSyncJob?.isActive == true) return
        val operation = serviceScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            repeat(2) { attempt ->
                if (attempt != 0) delay(750)
                try {
                    val route = selectBluetoothVolumeSyncRoute() ?: return@launch
                    val device = devices.value.values.filterIsInstance<AppleDevice>()
                        .singleOrNull { it.macAddress.value.equals(route.address, ignoreCase = true) } ?: return@launch
                    val controller = audioControllers[device.macAddress] ?: return@launch
                    val caller = currentCoroutineContext()[Job]
                    fun current() = caller?.isActive == true && refreshDeviceIsCurrent(device) &&
                        audioControllers[device.macAddress] === controller && !callBlocksSettingsRefresh() &&
                        !settingsRefreshes.containsKey(device.macAddress) && microphoneOwners.isEmpty() &&
                        !bleCaptureBlocked && !device.state.value.recordingState.isRecording &&
                        !device.state.value.microphoneState.isActive
                    if (!current() || !controller.tryBeginSettingsRefresh()) return@launch
                    try {
                        // Share the volume lock so a new conversation cannot race the repair's ramp.
                        MediaController.withIdleMediaVolume {
                            if (companionBridge.isRoot)
                                companionBridge.checkVolumeSync(device.macAddress.value, device, ::current)
                            else bluetoothVolumeSync.check(device.macAddress.value, device, ::current)
                        }
                    } finally {
                        controller.endSettingsRefresh()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w("BluetoothVolumeSync", "Could not check selected AirPods volume sync", error)
                }
            }
        }
        bluetoothVolumeSyncJob = operation
        operation.start()
    }

    private fun selectBluetoothVolumeSyncRoute(): AudioDeviceInfo? {
        val route = runCatching {
            getSystemService(AudioManager::class.java).getAudioDevicesForAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            ).singleOrNull()?.takeIf { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        }.getOrElse {
            Log.w("BluetoothVolumeSync", "Could not resolve selected media route", it)
            null
        }
        bluetoothVolumeSync.selectRoute(route?.address)
        devices.value.values.filterIsInstance<AppleDevice>()
            .filterNot { it.macAddress.value.equals(route?.address, ignoreCase = true) }
            .forEach { companionBridge.forgetVolumeSync(it) }
        return route
    }

    fun isNearbyDeviceAudioConnected(device: AppleDevice): Boolean = runCatching {
        !destroying && devices.value[device.macAddress] === device &&
            device.bluetoothDevice.bondState == BluetoothDevice.BOND_BONDED &&
            getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && it.address.equals(device.macAddress.value, true)
            }
    }.getOrDefault(false)

    private fun companionDevice(address: String): AppleDevice? = runCatching {
        (devices.value[MacAddress(address)] as? AppleDevice)?.takeIf {
            !destroying && companionHoldReady && it.bluetoothDevice.bondState == BluetoothDevice.BOND_BONDED &&
                getSystemService(BluetoothManager::class.java).adapter.isEnabled &&
                BluetoothIdentitySettings.allowsRootlessCompanion()
        }
    }.getOrNull()

    private fun restoreCompanionHolds() = synchronized(companionHoldLock) {
        companionHoldReady = false
        try {
            val held = companionHoldStore.load().map(::MacAddress)
            companionReleased.clear()
            companionReleased.addAll(held)
            companionHoldReady = true
        } catch (_: Exception) {
            Log.w(TAG, "Saved audio choice is unavailable; automatic control connections are blocked")
        }
    }

    private fun setCompanionHold(address: MacAddress, held: Boolean): Boolean = synchronized(companionHoldLock) {
        if (!companionHoldReady) return false
        if (companionReleased.contains(address) == held) return true
        val next = companionReleased.toMutableSet().apply { if (held) add(address) else remove(address) }
        try { companionHoldStore.save(next.map { it.value }.toSet()) }
        catch (_: Exception) { return false }
        if (held) companionReleased.add(address) else companionReleased.remove(address)
        true
    }

    private fun companionBusy(device: AppleDevice): Boolean = callBlocksSettingsRefresh() ||
        microphoneOwners.isNotEmpty() || bleCaptureBlocked || settingsRefreshes.isNotEmpty() ||
        nearbyConnections.isNotEmpty() || device.state.value.recordingState.isRecording ||
        device.state.value.microphoneState.isActive

    private fun companionCallAllowed(address: String): Boolean = companionDevice(address)?.settings?.value?.let {
        it.callTakeoverEnabled && it.callTakeoverConsent &&
            checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    } == true

    private fun companionCallReturnAllowed(address: String) = companionCallAllowed(address) &&
        companionDevice(address)?.settings?.value?.callReturnEnabled == true

    private fun companionCallBusy(device: AppleDevice): Boolean = !companionCallAllowed(device.macAddress.value) ||
        companionCalls.state !in setOf("ringing", "active") ||
        getSystemService(AudioManager::class.java).mode !in setOf(AudioManager.MODE_NORMAL, AudioManager.MODE_RINGTONE, AudioManager.MODE_IN_CALL) ||
        microphoneOwners.isNotEmpty() || bleCaptureBlocked || settingsRefreshes.isNotEmpty() || nearbyConnections.isNotEmpty() ||
        device.state.value.recordingState.isRecording || device.state.value.microphoneState.isActive ||
        runCatching {
            getSystemService(AudioManager::class.java).activeRecordingConfigurations.any {
                it.clientAudioSource !in setOf(android.media.MediaRecorder.AudioSource.VOICE_CALL,
                    android.media.MediaRecorder.AudioSource.VOICE_UPLINK, android.media.MediaRecorder.AudioSource.VOICE_DOWNLINK)
            }
        }.getOrDefault(true)

    private suspend fun companionCallReceipt(address: String, requested: () -> Boolean): Int {
        val device = companionDevice(address) ?: return 0
        if (!requested() || !companionCallAllowed(address)) return 0
        companionBridge.connect()
        if (withTimeoutOrNull(2000) { companionBridge.state.first { it.connected } } == null) return 0
        return companionBridge.nativeCall(address, false) { requested() && companionDevice(address) === device && companionCallAllowed(address) }
    }

    private suspend fun companionCallStatus(address: String, requested: () -> Boolean): CompanionStatus {
        val device = companionDevice(address) ?: return CompanionStatus(false, false, false)
        if (!requested() || companionCallBusy(device) || companionOperations.isNotEmpty()) return CompanionStatus(false, true, false)
        val receipt = companionCallReceipt(address) { requested() && !companionCallBusy(device) }
        return CompanionStatus(receipt and NativeCallConnection.READY == NativeCallConnection.READY,
            companionCallBusy(device), receipt != 0)
    }

    private suspend fun companionCallAcquire(address: String, requested: () -> Boolean): String {
        val device = companionDevice(address) ?: return "unavailable"
        if (!requested() || companionCallBusy(device)) return "busy"
        val operation = Any()
        if (companionOperations.putIfAbsent(device.macAddress, operation) != null) return "busy"
        fun current() = requested() && companionDevice(address) === device &&
            companionOperations[device.macAddress] === operation && !companionCallBusy(device)
        try {
            if (!current() || !setCompanionHold(device.macAddress, false)) return "unavailable"
            companionBridge.connect()
            if (withTimeoutOrNull(2000) { companionBridge.state.first { it.connected } } == null || !current()) return "unavailable"
            val flags = companionBridge.nativeCall(address, true, ::current)
            return if (current() && flags and NativeCallConnection.READY == NativeCallConnection.READY) "hfp_ready" else "failed"
        } finally { companionOperations.remove(device.macAddress, operation) }
    }

    private fun allCompanionCallsIdle(): Boolean = companionCalls.state == "idle" && !callBlocksSettingsRefresh() && runCatching {
            !getSystemService(TelecomManager::class.java).isInCall
        }.getOrDefault(false)

    private suspend fun companionPhoneQuiet(address: String, requested: () -> Boolean): Boolean {
        fun sameEnd() = requested() && companionCalls.state == "idle" &&
            getSystemService(AudioManager::class.java).mode != AudioManager.MODE_IN_COMMUNICATION
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (sameEnd() && SystemClock.elapsedRealtime() < deadline) {
            if (allCompanionCallsIdle() && companionOperations.isEmpty()) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                val receipt = withTimeoutOrNull(remaining.coerceAtLeast(1)) {
                    companionCallReceipt(address) { sameEnd() && allCompanionCallsIdle() }
                } ?: 0
                if (sameEnd() && allCompanionCallsIdle() && companionOperations.isEmpty() &&
                    receipt and NativeCallConnection.SCO_IDLE != 0) return true
            }
            delay(80)
        }
        return false
    }

    /** Consent remains phone-local even when a peer changes the shared desired policy. */
    fun setCompanionCallsEnabled(enabled: Boolean, returnAfterCall: Boolean? = null) {
        val address = companion?.linkedDevice() ?: return
        val device = companionDevice(address) ?: return
        if (enabled && checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        companion?.invalidate()
        device.updateSettings {
            if (returnAfterCall != null) it.copy(callReturnEnabled = returnAfterCall)
            else it.copy(callTakeoverEnabled = enabled, callTakeoverConsent = enabled)
        }
    }

    private suspend fun companionStatus(address: String, current: () -> Boolean): CompanionStatus {
        val device = companionDevice(address) ?: return CompanionStatus(false, false, false)
        val connected = isNearbyDeviceAudioConnected(device)
        if (!current() || companionBusy(device) || companionOperations.isNotEmpty()) return CompanionStatus(connected, true, false)
        val available = try {
            companionBridge.connect()
            withTimeoutOrNull(2_000) { companionBridge.state.first { it.connected } } != null &&
                current() && companionDevice(address) === device && !companionBusy(device) &&
                companionBridge.inspectNativePlayback(address) { current() && companionDevice(address) === device && !companionBusy(device) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        return CompanionStatus(isNearbyDeviceAudioConnected(device), companionBusy(device), available)
    }

    private suspend fun companionOperation(phase: String, address: String, requested: () -> Boolean): String {
        val device = companionDevice(address) ?: return "unavailable"
        if (!requested()) return "superseded"
        if (companionBusy(device)) return "busy"
        wearMediaEpochs.clear()
        companionBridge.cancelOwnedMedia()
        val operation = Any()
        if (companionOperations.putIfAbsent(device.macAddress, operation) != null) return "busy"
        fun current() = requested() && companionDevice(address) === device &&
            companionOperations[device.macAddress] === operation && !callBlocksSettingsRefresh() &&
            microphoneOwners.isEmpty() && !bleCaptureBlocked && settingsRefreshes.isEmpty() &&
            !device.state.value.recordingState.isRecording && !device.state.value.microphoneState.isActive
        try {
            if (!current()) return "superseded"
            if (phase == "acquire") {
                if (!setCompanionHold(device.macAddress, false)) return "unavailable"
                val acquired = connectNearbyDevice(device, ::current, companionRequest = true)
                return if (acquired && current() && isNearbyDeviceAudioConnected(device)) "acquired" else "failed"
            }
            if (phase != "release") return "invalid_state"
            companionBridge.connect()
            if (withTimeoutOrNull(2_000) { companionBridge.state.first { it.connected } } == null || !current()) return "unavailable"
            // Retire only this pair's control work before releasing its two native profiles.
            if (!setCompanionHold(device.macAddress, true)) return "unavailable"
            device.aacp.disconnect()
            Log.i("CompanionHandoff", "phase=release event=control_closed closed=${device.aacp.captureAudioSocket() == null}")
            if (!current() || !companionBridge.releaseNativePlayback(address, ::current)) return "failed"
            fun outputsReleased(): Boolean = runCatching {
                getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).none {
                    it.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO) &&
                        it.address.equals(address, true)
                }
            }.getOrDefault(false)
            val released = withTimeoutOrNull(3_000) {
                while (current() && !outputsReleased()) delay(40)
                current() && outputsReleased() && device.aacp.captureAudioSocket() == null
            } == true
            Log.i("CompanionHandoff", "phase=release event=output_check released=$released")
            return if (released) "released" else "failed"
        } finally {
            companionOperations.remove(device.macAddress, operation)
        }
    }

    private fun updateConnectionHaptics(initial: Boolean = false) {
        if (destroying || !startedWithPermissions) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            bleHandler.post { updateConnectionHaptics(initial) }
            return
        }
        if (!initial && !runCatching {
                getSystemService(BluetoothManager::class.java)?.adapter?.state == BluetoothAdapter.STATE_ON
            }.getOrDefault(false)) return
        val outputs = runCatching {
            getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                .mapNotNull { it.address.takeIf(String::isNotBlank) }.toSet()
        }.getOrNull() ?: return
        val paired = runCatching {
            devices.value.values.filterIsInstance<AppleDevice>().filter {
                it.bluetoothDevice.bondState == BluetoothDevice.BOND_BONDED
            }.map { it.macAddress.value }.toSet()
        }.getOrNull() ?: return
        connectionHaptics.update(outputs, paired)
    }

    private fun hasNativeAudioOutput(device: BluetoothDevice): Boolean = runCatching {
        device.bondState == BluetoothDevice.BOND_BONDED &&
            getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO) &&
                    it.address.equals(device.address, true)
            }
    }.getOrDefault(false)

    /** A presentation hint only: the Nearby label still requires the real audio-output predicate. */
    fun shouldDeferNearbyConnect(device: AppleDevice): Boolean = runCatching {
        if (destroying || devices.value[device.macAddress] !== device ||
            device.bluetoothDevice.bondState != BluetoothDevice.BOND_BONDED) false
        else if (isNearbyDeviceAudioConnected(device)) {
            nearbyCases.computeIfPresent(device.macAddress) { _, case ->
                if (case.device === device) case.copy(deferUntil = 0) else case
            }
            nearbyMedia.computeIfPresent(device.macAddress) { _, media ->
                if (media.device === device) media.copy(deferUntil = 0) else media
            }
            false
        }
        else {
            val now = SystemClock.elapsedRealtime()
            val media = nearbyMedia[device.macAddress]?.takeIf { it.device === device }
            val case = nearbyCases[device.macAddress]?.takeIf { it.device === device }
            (case?.deferUntil ?: 0) > now || (media?.deferUntil ?: 0) > now
        }
    }.getOrDefault(false)

    private fun observeNearbyMedia(device: AppleDevice, state: Int) {
        if (destroying || devices.value[device.macAddress] !== device ||
            device.bluetoothDevice.bondState != BluetoothDevice.BOND_BONDED ||
            state !in BluetoothProfile.STATE_DISCONNECTED..BluetoothProfile.STATE_DISCONNECTING) return
        val now = SystemClock.elapsedRealtime()
        val previous = nearbyMedia[device.macAddress]?.takeIf { it.device === device }
        if (previous?.state == state) return
        val connected = isNearbyDeviceAudioConnected(device)
        val failedReconnect = state == BluetoothProfile.STATE_DISCONNECTED && previous?.state == BluetoothProfile.STATE_CONNECTING
        // A close advertisement can arrive just after Android tears down its local profile.
        val localUntil = if (failedReconnect) 0 else if (state in setOf(BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_DISCONNECTING) &&
            (previous?.state == BluetoothProfile.STATE_CONNECTED || connected)) now + 2_000
            else previous?.localCloseUntil ?: 0
        val deferUntil = when (state) {
            BluetoothProfile.STATE_CONNECTING -> now + 18_000
            BluetoothProfile.STATE_CONNECTED -> if (connected) 0 else now + 1_000
            else -> 0
        }
        nearbyMedia[device.macAddress] = NearbyMedia(device, state, deferUntil, localUntil)
        if (failedReconnect) {
            nearbyCases.computeIfPresent(device.macAddress) { _, case ->
                if (case.device === device) case.copy(closedLocally = false, deferUntil = 0) else case
            }
        }
    }

    /** Called only for the already authenticated, ordered lid reports. */
    private fun observeNearbyCase(device: AppleDevice, open: Boolean): Boolean {
        if (destroying || devices.value[device.macAddress] !== device ||
            device.bluetoothDevice.bondState != BluetoothDevice.BOND_BONDED) return false
        if (pairedLids.put(device.macAddress, open) == open) return false
        val now = SystemClock.elapsedRealtime()
        val previous = nearbyCases[device.macAddress]?.takeIf { it.device === device }
        val media = nearbyMedia[device.macAddress]?.takeIf { it.device === device }
        val local = isNearbyDeviceAudioConnected(device) || media?.state == BluetoothProfile.STATE_CONNECTED ||
            (media?.localCloseUntil ?: 0) > now
        // A close must not expose Connect between rapid lid reports or extend an existing opening's deadline.
        val activeDeadline = previous?.deferUntil?.takeIf { it > now } ?: 0
        val deferUntil = if (activeDeadline > 0) activeDeadline
            else if (open && previous?.closedLocally == true) now + 4_000 else 0
        nearbyCases[device.macAddress] = NearbyCase(device, !open && local, deferUntil)
        if (open && deferUntil > 0 && Log.isLoggable(NEARBY_TAKEOVER_TAG, Log.DEBUG))
            Log.d(NEARBY_TAKEOVER_TAG, "Deferring Connect briefly after a local case close")
        return open
    }

    /** An explicit tap connects the selected media profile; an AACP socket alone is not audio ownership. */
    suspend fun connectCompanionPhone(): Boolean {
        val address = companion?.linkedDevice() ?: return false
        val device = runCatching {
            (devices.value[MacAddress(address)] as? AppleDevice)?.takeIf {
                !destroying && companionHoldReady && it.bluetoothDevice.bondState == BluetoothDevice.BOND_BONDED &&
                    getSystemService(BluetoothManager::class.java).adapter.isEnabled
            }
        }.getOrNull() ?: return false
        return connectNearbyDevice(device)
    }

    /** The same selected-pair action also works when the companion PC is unavailable. */
    suspend fun connectNearbyDevice(device: AppleDevice, stillRequested: () -> Boolean = { true },
        companionRequest: Boolean = false): Boolean {
        var explicitCurrent: () -> Boolean = { true }
        if (!companionRequest) {
            explicitCurrent = withContext(Dispatchers.Main.immediate) {
                companion?.beginLocalIntent() ?: { true }
            }
            if (!setCompanionHold(device.macAddress, false)) return false
        }
        val operation = Any()
        if (nearbyConnections.putIfAbsent(device.macAddress, operation) != null) return false
        val started = SystemClock.elapsedRealtime()
        val pendingControl = AtomicReference<BluetoothSocket?>()
        val controlRetired = AtomicBoolean(false)
        var earlyControl: Job? = null
        var keepControl: () -> Boolean = { false }
        val caller = currentCoroutineContext()[Job]
        val retiredReason = AtomicReference<String?>()
        var outcome = "declined"
        if (Log.isLoggable(NEARBY_TAKEOVER_TAG, Log.DEBUG)) Log.d(NEARBY_TAKEOVER_TAG, "Tap: device=${device.macAddress.toRedactedString()}")
        try {
            val controller = withContext(Dispatchers.Main.immediate) {
                if (destroying || devices.value[device.macAddress] !== device ||
                    device.bluetoothDevice.bondState != BluetoothDevice.BOND_BONDED) null
                else {
                    attachExperimentalAudio(device)
                    audioControllers[device.macAddress]
                }
            } ?: return false
            fun ownerFailure(): String? = runCatching {
                when {
                    !explicitCurrent() -> "newer choice"
                    !stillRequested() -> "request retired"
                    companionOperations.containsKey(device.macAddress) && !companionRequest -> "handoff active"
                    destroying -> "service stopped"
                    devices.value[device.macAddress] !== device -> "device replaced"
                    audioDevices[device.macAddress] !== device || audioControllers[device.macAddress] !== controller -> "audio owner replaced"
                    device.bluetoothDevice.bondState != BluetoothDevice.BOND_BONDED -> "unpaired"
                    callBlocksSettingsRefresh() -> "call or communication mode active"
                    microphoneOwners.isNotEmpty() || bleCaptureBlocked -> "microphone transport active"
                    settingsRefreshes.isNotEmpty() -> "settings refresh active"
                    device.state.value.recordingState.isRecording || device.state.value.microphoneState.isActive -> "microphone active"
                    else -> null
                }
            }.getOrElse { "guard exception: ${it.javaClass.simpleName}" }
            fun current(): Boolean {
                val reason = ownerFailure() ?: when {
                    nearbyConnections[device.macAddress] !== operation -> "request replaced"
                    caller?.isActive == false -> "request cancelled"
                    else -> null
                }
                if (reason != null && retiredReason.compareAndSet(null, reason))
                    if (Log.isLoggable(NEARBY_TAKEOVER_TAG, Log.DEBUG)) Log.d(NEARBY_TAKEOVER_TAG, "Retired: reason=$reason elapsedMs=${SystemClock.elapsedRealtime() - started}")
                return reason == null
            }
            if (!current() || !controller.canRefreshSettings()) return false
            val alreadyConnected = isNearbyDeviceAudioConnected(device)
            if (!alreadyConnected) {
                val aacpUuid = ParcelUuid.fromString(A2dpRecoveryGuard.AACP_UUID)
                fun advertisesAacp() = device.bluetoothDevice.uuids?.contains(aacpUuid) == true
                // Authenticated BLE can find a saved pair before Android has restored its Classic service cache.
                if (!advertisesAacp() && !device.bluetoothDevice.fetchUuidsWithSdp()) return false
                if (!current()) return false
                controller.bridge.connect()
                if (withTimeoutOrNull(2_000) { controller.bridge.state.first { it.connected } } == null || !current()) return false
                if (withTimeoutOrNull(2_000) {
                        while (current() && !advertisesAacp()) delay(40)
                        current() && advertisesAacp()
                    } != true) return false
            }
            if (Log.isLoggable(NEARBY_TAKEOVER_TAG, Log.DEBUG)) Log.d(NEARBY_TAKEOVER_TAG, "Ready: elapsedMs=${SystemClock.elapsedRealtime() - started}")
            if (device.connectionState.value !in setOf(ConnectionState.CONNECTED, ConnectionState.CONNECTING)) {
                val published = CompletableDeferred<Unit>()
                fun controlCurrent() = !controlRetired.get() && caller?.isCancelled != true &&
                    earlyControl?.isActive != false && ownerFailure() == null &&
                    (!alreadyConnected || isNearbyDeviceAudioConnected(device)) &&
                    ((nearbyConnections[device.macAddress] === operation && caller?.isActive != false) ||
                        isNearbyDeviceAudioConnected(device))
                keepControl = ::controlCurrent
                val control = serviceScope.launch(Dispatchers.IO) {
                    var connected = false
                    if (Log.isLoggable(NEARBY_TAKEOVER_TAG, Log.DEBUG)) Log.d(NEARBY_TAKEOVER_TAG, "Control start: elapsedMs=${SystemClock.elapsedRealtime() - started}")
                    try {
                        if (device.connectionState.value !in setOf(ConnectionState.CONNECTED, ConnectionState.CONNECTING)) {
                            connected = device.connect(::controlCurrent) { socket ->
                                pendingControl.set(socket)
                                if (Log.isLoggable(NEARBY_TAKEOVER_TAG, Log.DEBUG)) Log.d(NEARBY_TAKEOVER_TAG, "Control socket: elapsedMs=${SystemClock.elapsedRealtime() - started}")
                                published.complete(Unit)
                                if (!controlCurrent()) device.aacp.cancelPendingConnect(socket)
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.w(TAG, "Early control connection failed", error)
                    } finally {
                        published.complete(Unit)
                        if (Log.isLoggable(NEARBY_TAKEOVER_TAG, Log.DEBUG)) Log.d(NEARBY_TAKEOVER_TAG, "Control complete: connected=$connected elapsedMs=${SystemClock.elapsedRealtime() - started}")
                    }
                }
                earlyControl = control
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        withTimeoutOrNull(18_000) {
                            while (!control.isCompleted && controlCurrent()) delay(40)
                        }
                    } finally {
                        if (!control.isCompleted) {
                            control.cancel()
                            pendingControl.get()?.let { device.aacp.cancelPendingConnect(it) }
                        }
                    }
                }
                // Give the direct control socket a head start without serializing media behind its connection.
                withTimeoutOrNull(200) { published.await() }
                if (!current()) return false
            }
            if (alreadyConnected) {
                // A previous handoff can leave control detached after Android reconnects its own route.
                val attached = withTimeoutOrNull(18_000) {
                    earlyControl?.join()
                    while (current() && isNearbyDeviceAudioConnected(device) &&
                        device.connectionState.value == ConnectionState.CONNECTING) delay(40)
                    current() && isNearbyDeviceAudioConnected(device) &&
                        device.connectionState.value == ConnectionState.CONNECTED && device.aacp.captureAudioSocket() != null
                } == true
                if (!attached) controlRetired.set(true)
                outcome = "already connected"
                return attached
            }
            val connected = controller.bridge.connectNativePlayback(device.macAddress.value, ::current)
            outcome = if (connected) "retired" else "not connected"
            if (!connected || !current()) return false
            // Android publishes the usable audio output slightly after its Bluetooth profile connects.
            outcome = "output unavailable"
            val outputBudget = minOf(10_000L, 24_000L - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(1L)
            if (withTimeoutOrNull(outputBudget) {
                    while (current() && !isNearbyDeviceAudioConnected(device)) delay(40)
                    current() && isNearbyDeviceAudioConnected(device)
                } != true) {
                if (Log.isLoggable(NEARBY_TAKEOVER_TAG, Log.DEBUG)) {
                    val outputs = runCatching {
                        getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                            .joinToString { "type=${it.type}:selected=${it.address.equals(device.macAddress.value, true)}:addressPresent=${it.address.isNotBlank()}" }
                    }.getOrElse { "query failed: ${it.javaClass.simpleName}" }
                    Log.d(NEARBY_TAKEOVER_TAG, "Output unavailable: ownerFailure=${ownerFailure()} retired=${retiredReason.get()} " +
                        "nativeState=${nearbyMedia[device.macAddress]?.state} outputs=[$outputs]")
                }
                return false
            }
            outcome = "connected"
            observeNearbyMedia(device, BluetoothProfile.STATE_CONNECTED)
            serviceScope.launch(Dispatchers.IO) {
                earlyControl?.join()
                if (devices.value[device.macAddress] === device && isNearbyDeviceAudioConnected(device))
                    onDeviceConnected(device.bluetoothDevice)
            }
            return true
        } catch (error: Exception) {
            if (error is CancellationException) { outcome = "cancelled"; throw error }
            outcome = "failed"
            Log.w(TAG, "Nearby media connection failed", error)
            return false
        } finally {
            Log.i("NearbyTakeover", "Complete: result=$outcome elapsedMs=${SystemClock.elapsedRealtime() - started}")
            nearbyConnections.remove(device.macAddress, operation)
            if (!keepControl()) {
                earlyControl?.cancel()
                pendingControl.get()?.let { device.aacp.cancelPendingConnect(it) }
            }
        }
    }

    /** Null means that this exact device can currently refresh its control session. */
    fun deviceSettingsRefreshReadiness(device: AppleDevice): DeviceSettingsRefreshResult? {
        if (destroying) return DeviceSettingsRefreshResult.UNAVAILABLE
        if (settingsRefreshes.containsKey(device.macAddress)) return DeviceSettingsRefreshResult.ALREADY_RUNNING
        if (companionOperations.isNotEmpty()) return DeviceSettingsRefreshResult.ALREADY_RUNNING
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
        val game = if (Build.VERSION.SDK_INT == 37)
            me.kavishdevar.librepods.utils.NativeGameModeController(device, serviceScope) {
                destroying || audioDevices[address] !== device || callBlocksSettingsRefresh() ||
                    microphoneOwners.isNotEmpty() || settingsRefreshes.isNotEmpty() || bleCaptureBlocked ||
                    companionOperations.containsKey(address) || device.settingsOperationPending ||
                    device.state.value.hearingModes.pending != null || device.state.value.accommodationsRequest.pending
            }.also { gameControllers[address] = it }
        else null
        lateinit var communication: me.kavishdevar.librepods.audio.CommunicationAudioController
        communication = me.kavishdevar.librepods.audio.CommunicationAudioController(this, device, controller,
            deviceCurrent = { !destroying && audioDevices[address] === device && audioControllers[address] === controller },
            armForeground = { armed -> armCommunication(address, communication, armed) },
            suspendGame = { game?.suspendForMicrophone() != false },
            startInput = { consumer, current -> startCallMicrophone(device, communication, consumer, current) },
            released = {
                if (audioControllers[address] === controller && controller.microphone.state.value.phase in
                    listOf(MicrophonePhase.IDLE, MicrophonePhase.ERROR)) game?.resumeAfterMicrophone()
            })
        communicationControllers[address] = communication
        val suspensions = Channel<Boolean>(Channel.CONFLATED)
        sensorSuspensions[address] = suspensions
        device.recordingHandler = { recording ->
            if (recording == null) {
                microphoneStarts.remove(address)?.cancel()
                controller.stop()
                if (controller.microphone.state.value.phase in listOf(MicrophonePhase.IDLE, MicrophonePhase.ERROR))
                    game?.resumeAfterMicrophone()
                true
            } else requestMicrophoneStart(device, controller, { controller.startRecording(recording.file) })
        }
        audioJobs[address] = serviceScope.launch {
            if (game != null) launch {
                device.settings.map { it.nativeGameModeEnabled }.distinctUntilChanged()
                    .collect { game.setDesiredOn(it) }
            }
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
                if (state.phase in listOf(MicrophonePhase.IDLE, MicrophonePhase.ERROR) &&
                    !microphoneStarts.containsKey(address) && communicationControllers[address]?.ownsSession != true)
                    game?.resumeAfterMicrophone()
            }
        }
    }

    /** No call-route admission yet. Accepted requests own the consumer even if start is cancelled. */
    internal fun startLiveMicrophone(device: AppleDevice, consumer: LivePcmConsumer): AutoCloseable? {
        return startMicrophoneConsumer(device, consumer)
    }

    private fun startCallMicrophone(device: AppleDevice,
        call: me.kavishdevar.librepods.audio.CommunicationAudioController,
        consumer: me.kavishdevar.librepods.audio.CallPcmConsumer,
        current: () -> Boolean): AutoCloseable? {
        fun permit() = current() && communicationControllers[device.macAddress] === call &&
            call.hasPreparedRoute && call.ownsInput(consumer)
        if (!permit()) return null
        return startMicrophoneConsumer(device, consumer, ::permit)
    }

    private fun startMicrophoneConsumer(device: AppleDevice, consumer: LivePcmConsumer,
        callPermit: (() -> Boolean)? = null): AutoCloseable? {
        val controller = audioControllers[device.macAddress] ?: return null
        val lock = Any()
        var closed = false
        var pending: Job? = null
        val accepted = requestMicrophoneStart(device, controller,
            { synchronized(lock) { !closed && if (callPermit == null) controller.startLive(consumer)
                else controller.startLiveForCall(consumer, callPermit) } },
            { consumer.close() }, { pending = it }, callPermit)
        if (!accepted) return null
        return AutoCloseable {
            synchronized(lock) {
                if (!closed) {
                    closed = true
                    pending?.let {
                        microphoneStarts.remove(device.macAddress, it)
                        it.cancel()
                    }
                    controller.stopLive(consumer)
                }
            }
        }
    }

    private fun requestMicrophoneStart(
        device: AppleDevice,
        controller: ExperimentalAudioController,
        start: () -> Boolean,
        onNotStarted: () -> Unit = {},
        onQueued: (Job) -> Unit = {},
        callPermit: (() -> Boolean)? = null,
    ): Boolean {
        val address = device.macAddress
        val socket = device.aacp.captureAudioSocket() ?: return false
        val game = gameControllers[address]
        fun current() = !destroying && audioDevices[address] === device && audioControllers[address] === controller &&
            gameControllers[address] === game && device.aacp.ownsAudioSocket(socket) &&
            (callPermit == null || callPermit()) &&
            (!callBlocksSettingsRefresh() || callPermit?.invoke() == true) &&
            !bleCaptureBlocked && settingsRefreshes.isEmpty()
        if (!current() || microphoneStarts.containsKey(address)) return false
        if (game == null || game.state.value.let { !it.desiredOn && !it.active && !it.restoring && !it.remoteRestorePending })
            return start()
        val started = java.util.concurrent.atomic.AtomicBoolean()
        val request = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (game.suspendForMicrophone() && current() &&
                    microphoneStarts[address] === coroutineContext[Job]) started.set(start())
            } finally {
                coroutineContext[Job]?.let { microphoneStarts.remove(address, it) }
                if (!started.get() && !microphoneStarts.containsKey(address) &&
                    audioControllers[address] === controller && gameControllers[address] === game) {
                    device.updateRecordingState { it.copy(isRecording = false) }
                    if (controller.microphone.state.value.phase in listOf(MicrophonePhase.IDLE, MicrophonePhase.ERROR))
                        game.resumeAfterMicrophone()
                }
            }
        }
        if (microphoneStarts.putIfAbsent(address, request) != null) {
            request.cancel()
            return false
        }
        // Completion also runs when a queued lazy start is cancelled before its body executes.
        request.invokeOnCompletion {
            microphoneStarts.remove(address, request)
            if (!started.get()) runCatching(onNotStarted)
        }
        onQueued(request)
        request.start()
        return true
    }

    private fun detachExperimentalAudio(address: MacAddress) {
        communicationControllers.remove(address)?.let { call ->
            communicationArmed.remove(address, call)
            call.close()
        }
        microphoneStarts.remove(address)?.cancel()
        gameControllers.remove(address)?.close()
        audioDevices.remove(address)?.recordingHandler = null
        sensorSuspensions.remove(address)?.close()
        audioJobs.remove(address)?.cancel()
        audioControllers.remove(address)?.let { it.onDisconnected(); it.close() }
        setMicrophoneActive(address, false)
    }

    private fun microphonePermissionAllowed(address: MacAddress? = null): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val owners = if (address == null) microphoneOwners.toSet() else microphoneOwners + address
        val callOwnsMute = owners.isNotEmpty() && owners.all { communicationControllers[it]?.let { call ->
            call.hasPreparedRoute || call.ownsMicrophoneCapture
        } == true }
        if (getSystemService(AudioManager::class.java).isMicrophoneMute && !callOwnsMute) return false
        if (owners.any { audioControllers[it]?.bridge?.isMicrophonePrivacyBlocked() != false }) return false
        return runCatching {
            val mode = getSystemService(AppOpsManager::class.java)
                .checkOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, applicationInfo.uid, packageName)
            mode != AppOpsManager.MODE_IGNORED && mode != AppOpsManager.MODE_ERRORED
        }.getOrDefault(false)
    }

    private fun armCommunication(address: MacAddress,
        owner: me.kavishdevar.librepods.audio.CommunicationAudioController, active: Boolean): Boolean {
        if (!active) {
            if (!communicationArmed.remove(address, owner)) return true
            if (!destroying) startForegroundNotification()
            return true
        }
        val controller = audioControllers[address] ?: return false
        if (destroying || communicationControllers[address] !== owner || !controller.bridge.hasRootPrivileges ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            controller.bridge.isMicrophonePrivacyBlocked() ||
            !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return false
        communicationArmed[address] = owner
        return try { startForegroundNotification(); true } catch (_: Exception) {
            communicationArmed.remove(address, owner)
            false
        }
    }

    private fun setMicrophoneActive(address: MacAddress, active: Boolean) {
        if (active) companion?.invalidate()
        if (active && address in microphoneOwners) return
        if (active) {
            check(settingsRefreshes.isEmpty()) { "Wait for the settings refresh to finish." }
            check(!destroying && microphonePermissionAllowed(address)) { "Allow microphone access first." }
            if (address !in microphoneOwners) {
                check(ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) ||
                    (communicationArmed.containsKey(address) && communicationControllers[address]?.hasPreparedRoute == true)) {
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
        if (!hasNearbyDevicesPermissions(this)) { stopSelf(); return }
        try {
            // ACL/SDP and BLE presence do not authorize reclaiming another host's audio.
            if (destroying || !companionHoldReady || companionReleased.contains(MacAddress(bluetoothDevice.address)) ||
                companionOperations.containsKey(MacAddress(bluetoothDevice.address)) || !hasNativeAudioOutput(bluetoothDevice)) return
            val device = createDevice(bluetoothDevice) ?: return
            if (destroying || device.bluetoothDevice.bondState != BluetoothDevice.BOND_BONDED ||
                device.connectionState.value in setOf(ConnectionState.CONNECTED, ConnectionState.CONNECTING)) return
            if (device is AppleDevice) withContext(Dispatchers.Main.immediate) {
                if (!destroying && devices.value[device.macAddress] === device) attachExperimentalAudio(device)
            }
            fun current() = !destroying && companionHoldReady && devices.value[device.macAddress] === device &&
                !companionReleased.contains(device.macAddress) && !companionOperations.containsKey(device.macAddress) &&
                hasNativeAudioOutput(bluetoothDevice)
            if (!current()) return
            if (device is AppleDevice) device.connect(::current) else device.connect()
        } catch (_: SecurityException) { stopSelf() }
    }

    private fun onDeviceDisconnected(mac: MacAddress) {
        (devices.value[mac] as? AppleDevice)?.let {
            retireHeadGestures(it)
            clearHeartRateAlert(it)
        }
        (devices.value[mac] as? AppleDevice)?.let { observeNearbyMedia(it, BluetoothProfile.STATE_DISCONNECTED) }
        detachExperimentalAudio(mac)
        Log.i(TAG, "Device disconnected: ${mac.toRedactedString()}")
        devices.value[mac]?.let { if (it is AppleDevice) it.disconnectControl() else it.disconnect() }
        updateDeviceNotification(device = devices.value[mac] ?: return)
    }

    private suspend fun loadDevices() {
        if (!hasNearbyDevicesPermissions(this)) { stopSelf(); return }
        try {
            val adapter = getSystemService(BluetoothManager::class.java).adapter ?: return
            for (bluetoothDevice in adapter.bondedDevices) createDevice(bluetoothDevice)
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent) {
            if (!hasNearbyDevicesPermissions(this@LibrePodsService)) { stopSelf(); return }
            try {
                when (intent.action) {
                    A2DP_ACTIVE_DEVICE_CHANGED -> {
                        retireWearMediaForOtherRoute()
                        checkBluetoothVolumeSync()
                        return
                    }
                    HEADSET_ACTIVE_DEVICE_CHANGED, android.bluetooth.BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED -> {
                        companion?.callRouteChanged()
                        return
                    }
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF -> {
                        bleHandler.removeCallbacks(updateScreenScanMode)
                        if (bleRequested) {
                            // A quick lock/unlock keeps the working scan instead of spending two registrations.
                            if (intent.action == Intent.ACTION_SCREEN_ON) startBleScanner(updateMode = true)
                            else bleHandler.postDelayed(updateScreenScanMode, 30_000)
                        }
                        return
                    }
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        companion?.bluetoothChanged()
                        if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_ON) {
                            if (bleRequested) serviceScope.launch { loadDevices(); startBleScanner() }
                        } else {
                            devices.value.values.filterIsInstance<AppleDevice>().forEach {
                                bluetoothVolumeSync.forget(it)
                                companionBridge.forgetVolumeSync(it)
                            }
                            stopActiveBleScan()
                            nearbyPairing?.cancelPending()
                            nearbyVisits.clear()
                            nearbyInvitationProximity.clear()
                            recentProximityReports.clear()
                            pairedLids.clear()
                            nearbyMedia.clear()
                            nearbyCases.clear()
                            connectionHaptics.clear()
                            islandWindow.clearPairingOpening()
                            islandWindow.close()
                        }
                        return
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        companion?.bluetoothChanged()
                        val changed = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        if (changed != null && changed.bondState == BluetoothDevice.BOND_NONE) {
                            nearbyPairing?.updatePairingBattery(changed, emptySet())
                            val address = MacAddress(changed.address)
                            bluetoothVolumeSync.forget(address.value)
                            companionBridge.forgetVolumeSync(address.value)
                            onDeviceDisconnected(address)
                            deviceJobs.remove(address)?.forEach { it.cancel() }
                            _devices.update { it - address }
                            pairedObservations.remove(address)
                            caseBatteryObservations.remove(address)
                            recentProximityReports.clear()
                            pairedLids.remove(address)
                            nearbyMedia.remove(address)
                            nearbyCases.remove(address)
                            islandWindow.clearPairingOpening(address.value)
                            islandWindow.closePaired(address.value)
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
                        BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                            if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) in setOf(
                                    BluetoothProfile.STATE_DISCONNECTING, BluetoothProfile.STATE_DISCONNECTED)) {
                                bluetoothVolumeSync.forget(bluetoothDevice.address)
                                companionBridge.forgetVolumeSync(bluetoothDevice.address)
                            }
                            checkBluetoothVolumeSync()
                            (devices.value[MacAddress(bluetoothDevice.address)] as? AppleDevice)?.let {
                                observeNearbyMedia(it, intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1))
                                islandWindow.updatePairedBattery(it)
                            }
                        }
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
            } catch (_: SecurityException) { stopSelf() }
        }
    }

    fun registerBluetoothReceivers() {
        val intentFilter = IntentFilter().apply {
            addAction("android.bluetooth.device.action.ACL_CONNECTED")
            addAction("android.bluetooth.device.action.ACL_DISCONNECTED")
            addAction("android.bluetooth.device.action.UUID")
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(A2DP_ACTIVE_DEVICE_CHANGED)
            addAction(HEADSET_ACTIVE_DEVICE_CHANGED)
            addAction(android.bluetooth.BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
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
        if (CaseBatteryReport.encryptedBlock(manufacturer) != null) {
            handleCaseBattery(manufacturer, observed, System.currentTimeMillis() - (nowNanos - observed) / 1_000_000)
            return
        }
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
                "rssi=${result.rssi}, " +
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
            if (owned != null || nearbyInvitationProximity.observe(event, address, result.rssi, observed / 1_000_000)) {
                nearbyVisits.observe(event, observed / 1_000_000, owned != null, protectedModel = pairingModel)?.let {
                    nearbyPairing?.onNearbyDevice(it, observed)
                }
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
        val opening = report.lidOpen?.let { open ->
            islandWindow.observePairedCaseLid(device.macAddress.value, open, observed)
            observeNearbyCase(device, open)
        } == true
        device.receiveNearbyBattery(report.batteries)
        islandWindow.updatePairedBattery(device, report.model)
        if (opening && device.settings.value.showIslandPopup) {
            islandWindow.showPairedLid(device, report.model, observed,
                onConnect = { connectNearbyDevice(device) },
                isAudioConnected = { isNearbyDeviceAudioConnected(device) },
                deferConnectAction = { shouldDeferNearbyConnect(device) })
            if (Log.isLoggable(NEARBY_SCAN_TAG, Log.DEBUG))
                Log.d(NEARBY_SCAN_TAG, "Lid advertisement to paired overlay: ${(SystemClock.elapsedRealtimeNanos() - observed) / 1_000_000}ms")
        }
    }

    private fun handleCaseBattery(manufacturer: ByteArray, observed: Long, observedAt: Long) {
        if (destroying || bleCaptureBlocked) return
        val block = CaseBatteryReport.encryptedBlock(manufacturer) ?: return
        val matches = devices.value.values.filterIsInstance<AppleDevice>().mapNotNull { device ->
            retireCaseBatteryObservation(device)
            if (device.bluetoothDevice.bondState != BluetoothDevice.BOND_BONDED) return@mapNotNull null
            val key = device.state.value.magicKeys[MagicKeyType.ENC_KEY]?.copyOf() ?: return@mapNotNull null
            val report = CaseBatteryReport.decode(manufacturer, key, device.metadata.value.model, observedAt)
                ?: return@mapNotNull null
            Triple(device, key, report)
        }
        // A case has no IRK-resolved identity. Ambiguous key/layout matches cannot select a pair.
        val (device, key, report) = matches.singleOrNull() ?: return
        synchronized(device.aacp) {
            if (destroying || bleCaptureBlocked || devices.value[device.macAddress] !== device ||
                device.bluetoothDevice.bondState != BluetoothDevice.BOND_BONDED ||
                device.metadata.value.model != AirPodsModel.AIRPODS_PRO_3 ||
                device.state.value.magicKeys[MagicKeyType.ENC_KEY]?.contentEquals(key) != true) return
            val previous = caseBatteryObservations[device.macAddress]
            if (previous?.key?.contentEquals(key) == true &&
                (previous.block.contentEquals(block) || observed <= previous.observedNanos)) return
            caseBatteryObservations[device.macAddress] = CaseBatteryObservation(key, block, observed)
            device.receiveCaseBattery(report.batteries, observedAt)
            islandWindow.updatePairedBattery(device)
        }
    }

    private fun retireCaseBatteryObservation(device: AppleDevice) {
        val previous = caseBatteryObservations[device.macAddress] ?: return
        if (devices.value[device.macAddress] !== device || device.bluetoothDevice.bondState != BluetoothDevice.BOND_BONDED ||
            device.metadata.value.model != AirPodsModel.AIRPODS_PRO_3 ||
            device.state.value.magicKeys[MagicKeyType.ENC_KEY]?.contentEquals(previous.key) != true)
            caseBatteryObservations.remove(device.macAddress, previous)
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

    private fun scheduleBleRetry(delayMs: Long, updateMode: Boolean = false) {
        bleRetry?.let(bleHandler::removeCallbacks)
        bleRetry = Runnable {
            bleRetry = null
            if (bleRequested && !destroying) startBleScanner(updateMode)
        }.also { bleHandler.postDelayed(it, delayMs) }
    }

    private fun startBleScanner(updateMode: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            bleHandler.post { startBleScanner(updateMode) }
            return
        }
        if (destroying) return
        bleRequested = true
        if (bleCaptureBlocked || !bleReady) return
        if (!hasNearbyDevicesPermissions(this)) { stopActiveBleScan(); stopSelf(); return }
        val interactive = getSystemService(PowerManager::class.java).isInteractive
        val settings = nearbyScanSettings(interactive, appDataRepository.settings.value.bleScanMode)
        val mode = settings.scanMode
        if (bleScanCallback != null && (!updateMode || bleScanMode == mode)) {
            bleRetry?.let(bleHandler::removeCallbacks)
            bleRetry = null
            return
        }
        val now = SystemClock.elapsedRealtime()
        while (bleScanStarts.firstOrNull()?.let { now - it >= 30_000 } == true) bleScanStarts.removeFirst()
        if (bleScanStarts.size >= 5) {
            // Keep a healthy scan running until Android's rolling registration allowance recovers.
            scheduleBleRetry(30_000 - (now - bleScanStarts.first()) + 1, updateMode)
            return
        }
        val scanner = try {
            val adapter = getSystemService(BluetoothManager::class.java).adapter ?: return
            if (!adapter.isEnabled) return
            adapter.bluetoothLeScanner ?: return
        } catch (_: SecurityException) { stopActiveBleScan(); stopSelf(); return }
        stopActiveBleScan()
        val generation = ++bleGeneration
        val filter = ScanFilter.Builder().setManufacturerData(0x004c, byteArrayOf()).build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                bleHandler.post {
                    if (!destroying && generation == bleGeneration) try { handleScanResult(result) }
                    catch (_: SecurityException) { stopActiveBleScan(); stopSelf() }
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
                    if (errorCode != ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED) scheduleBleRetry(30_000)
                }
            }
        }
        bleScanner = scanner
        bleScanCallback = callback
        bleScanMode = mode
        bleScanStarts.addLast(now)
        try {
            scanner.startScan(listOf(filter), settings, callback)
            if (Log.isLoggable(NEARBY_SCAN_TAG, Log.DEBUG)) Log.d(NEARBY_SCAN_TAG, "BLE lid monitoring started: mode=$mode, reportDelay=0")
        } catch (_: SecurityException) {
            stopActiveBleScan()
            stopSelf()
            Log.w(TAG, "Nearby devices permission is unavailable")
        } catch (_: Exception) {
            stopActiveBleScan()
            scheduleBleRetry(30_000)
        }
    }

    private fun stopActiveBleScan() {
        bleRetry?.let(bleHandler::removeCallbacks)
        bleRetry = null
        bleHandler.removeCallbacks(updateScreenScanMode)
        bleGeneration++
        val scanner = bleScanner
        val callback = bleScanCallback
        bleScanner = null
        bleScanCallback = null
        bleScanMode = null
        if (scanner != null && callback != null) runCatching { scanner.stopScan(callback) }
    }

    private fun stopBleScanner() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            bleHandler.post { stopBleScanner() }
            return
        }
        bleRequested = false
        stopActiveBleScan()
        nearbyInvitationProximity.clear()
        nearbyVisits.clear().forEach { nearbyPairing?.onNearbyDevice(it) }
    }
    private suspend fun createDevice(bluetoothDevice: BluetoothDevice): Device<*, *, *>? {
        if (!hasNearbyDevicesPermissions(this)) { stopSelf(); return null }
        return try {
            val address = MacAddress(bluetoothDevice.address)
            deviceCreationLocks.computeIfAbsent(address) { Mutex() }.withLock {
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
                device.onExplicitConnect = {
                    companion?.invalidate()
                    if (!setCompanionHold(address, false)) null else {
                        {
                            !destroying && companionHoldReady && devices.value[address] === device &&
                                !companionReleased.contains(address) && !companionOperations.containsKey(address)
                        }
                    }
                }
                device.onExplicitDisconnect = { companion?.invalidate() }
                device.onSettingsSaveFailed = { serviceScope.launch { Toast.makeText(this@LibrePodsService,
                    R.string.companion_setting_save_failed, Toast.LENGTH_LONG).show() } }
                entity?.let {
                    device.loadInitialState(AppleState(capabilities = it.cache.capabilities, magicKeys = it.cache.magicKeys,
                        controlStates = it.cache.controlStates), it.settings, it.metadata)
                }
                if (destroying || bluetoothDevice.bondState != BluetoothDevice.BOND_BONDED) return@withLock null
                nearbyMedia.remove(address)
                nearbyCases.remove(address)
                _devices.update { it + (address to device) }
                runCatching { companionPreferences.restore(address.value) }
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager.createNotificationChannel(NotificationChannel("device_$address",
                    "Device ${device.metadata.value.name}", NotificationManager.IMPORTANCE_LOW))
                val installed = withContext(Dispatchers.Main.immediate) {
                    if (destroying || devices.value[address] !== device || bluetoothDevice.bondState != BluetoothDevice.BOND_BONDED) false
                    else {
                        attachExperimentalAudio(device)
                        deviceJobs[address] = listOf(observeConversationAwareness(device), observeStemActions(device), observeAppleState(device), observeAppleSettings(device), observeAppleMetadata(device),
                            serviceScope.launch {
                                device.connectionState.collect {
                                    retireIneligibleHeadGestures(device)
                                    updateHeartRateAlert(device)
                                    if (!destroying && devices.value[address] === device) {
                                        islandWindow.updatePairedBattery(device)
                                        islandWindow.updateAdaptiveStrength(device)
                                    }
                                }
                            })
                        val keys = device.state.value.magicKeys
                        val irk = keys[MagicKeyType.IRK]?.copyOf()
                        val encryption = keys[MagicKeyType.ENC_KEY]?.copyOf()
                        if (irk != null && encryption != null)
                            replayRecentBattery(device, device.aacp.captureAudioSocket(), irk, encryption)
                        updateConnectionHaptics()
                        true
                    }
                }
                if (!installed) return@withLock null
                device
            }
        } catch (_: SecurityException) { stopSelf(); null }
    }

    fun observeAppSettings(): Job {
        var oldAppSettings: AppSettingsEntity = appDataRepository.settings.value

        return serviceScope.launch(Dispatchers.IO) {
            appDataRepository.settings.collect { settings ->
                if (oldAppSettings.bleReportDelay != settings.bleReportDelay || oldAppSettings.bleScanMode != settings.bleScanMode) {
                    startBleScanner(updateMode = true)
                }
                islandWindow.updateSettings(settings)
                oldAppSettings = settings
                companion?.linkedDevice()?.let { companion?.preferencesChanged(it) }
            }
        }
    }

    fun observeStemActions(device: AppleDevice): Job = serviceScope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        device.events.collect { event ->
            if (event !is AppleEvent.StemPress) return@collect
            fun current() = !destroying && devices.value[device.macAddress] === device &&
                isNearbyDeviceAudioConnected(device) && device.aacp.ownsAudioSocket(event.sourceSocket)
            if (Log.isLoggable(STEM_ACTIONS_TAG, Log.DEBUG))
                Log.d(STEM_ACTIONS_TAG, "hold=${event.pressType}/${event.bud} current=${current()} action=${if (current()) device.hostHoldAction(event) else null}")
            if (!current()) return@collect
            when (device.hostHoldAction(event)) {
                StemAction.DIGITAL_ASSISTANT -> withContext(Dispatchers.Main.immediate) {
                    if (current() && device.hostHoldAction(event) == StemAction.DIGITAL_ASSISTANT &&
                        !AssistantLauncher.launch(this@LibrePodsService))
                        Toast.makeText(this@LibrePodsService, R.string.assistant_launch_failed, Toast.LENGTH_LONG).show()
                }
                StemAction.CYCLE_NOISE_CONTROL_MODES -> device.cycleHoldListeningMode(event)
                else -> Unit
            }
        }
    }

    fun observeConversationAwareness(device: AppleDevice): Job = serviceScope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        var owner: BluetoothSocket? = null
        val updates = Channel<AppleEvent.ConversationAwareness?>(Channel.BUFFERED)
        try {
            // Subscribe before connecting; speech stages cannot pass through the conflated state flow.
            launch(start = CoroutineStart.UNDISPATCHED) {
                device.events.collect { event ->
                    if (event is AppleEvent.ConversationAwareness) updates.send(event)
                }
            }
            launch(start = CoroutineStart.UNDISPATCHED) { device.state.collect { updates.send(null) } }
            launch(start = CoroutineStart.UNDISPATCHED) { device.connectionState.collect { updates.send(null) } }
            launch(start = CoroutineStart.UNDISPATCHED) { devices.collect { updates.send(null) } }
            for (event in updates) {
                val socket = device.aacp.captureAudioSocket()
                val state = device.state.value
                val choice = state.requestedControls[ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG]
                    ?: state.controlStates[ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG]
                val eligible = !destroying && devices.value[device.macAddress] === device &&
                    device.connectionState.value in setOf(ConnectionState.CONNECTED, ConnectionState.CONNECTING) && socket != null &&
                    choice?.firstOrNull() != 2.toByte()
                if (!eligible || owner !== socket) {
                    owner?.let { MediaController.cancelConversation(it) }
                    owner = null
                }
                if (event == null || !eligible || event.sourceSocket !== socket ||
                    !device.aacp.ownsAudioSocket(event.sourceSocket)) continue
                owner = event.sourceSocket
                val settings = device.settings.value
                try {
                    when (event.stage) {
                        1, 2, 3 -> MediaController.startSpeaking(event.stage, event.sourceSocket, device.macAddress.value,
                            settings.conversationAwarenessVolume.toInt(), settings.relativeConversationAwarenessVolumeEnabled,
                            settings.conversationAwarenessPauseMusicEnabled, settings.conversationAwarenessReducedVolume.toInt())
                        4, 6, 7, 8, 9 -> MediaController.stopSpeaking(event.sourceSocket)
                    }
                } catch (error: RuntimeException) {
                    Log.w(TAG, "Could not apply conversation awareness stage", error)
                    MediaController.cancelConversation(event.sourceSocket)
                }
            }
        } finally {
            owner?.let { MediaController.cancelConversation(it) }
        }
    }

    fun observeAppleState(device: AppleDevice): Job = serviceScope.launch(Dispatchers.IO) {
        var previousState = device.state.value
        var pairingBattery: Set<Battery>? = null
        var lastSavedKeys: Map<MagicKeyType, ByteArray>? = null
        val listeningMode = IslandListeningMode()

        try {
        device.state.collect { state ->
            retireCaseBatteryObservation(device)
            // Capture before any repository suspension; an old emission cannot belong to a later socket.
            val modeTransport = device.aacp.captureAudioSocket()
            val deviceSettings = device.settings.value
            retireIneligibleHeadGestures(device)
            if (state.hrmState != previousState.hrmState || state.currentHeartRate != previousState.currentHeartRate)
                updateHeartRateAlert(device)

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

            // Independent of battery/component updates: StateFlow can coalesce them with a mode report.
            saveChangedControlCache(previousState, state) {
                appleRepository.saveCacheFromState(device.macAddress, it)
            }
            val modeId = ControlCommandIdentifier.LISTENING_MODE
            val conversationId = ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG
            val previousModeReport = previousState.controlStates[modeId]
            val modeReport = state.controlStates[modeId]
            val requestedMode = modeReport != null && sequenceOf(previousState, state).any {
                it.requestedControls[modeId]?.contentEquals(modeReport) == true
            }
            val conversationEnabled = (state.requestedControls[conversationId]
                ?: state.controlStates[conversationId])?.singleOrNull() == 1.toByte()
            // Temporary Transparency can precede its speech event; only presentation waits for correlation.
            if (conversationEnabled && !requestedMode && modeReport?.singleOrNull() == 3.toByte() &&
                previousModeReport?.singleOrNull() != 3.toByte()) delay(150)
            val quietReport = device.state.value.quietListeningModeReport
            val quietRequest = quietReport?.value === modeReport && quietReport != null &&
                withTimeoutOrNull(150) { quietReport.written.await() } != false
            val currentModeState = device.state.value
            val conversationAllowed = (currentModeState.requestedControls[conversationId]
                ?: currentModeState.controlStates[conversationId])?.singleOrNull() != 2.toByte()
            listeningMode.observeReport(modeTransport?.takeIf(device.aacp::ownsAudioSocket),
                previousModeReport, modeReport,
                currentReport = currentModeState.controlStates[modeId],
                conversationStage = if (conversationAllowed) currentModeState.conversationAwarenessState else 0,
                conversationSequence = if (conversationAllowed) currentModeState.conversationAwarenessSequence else 0,
                requestedMode = currentModeState.lastListeningModeRequest, quietRequest = quietRequest)?.let { mode ->
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

            if (state.capabilities != previousState.capabilities) {
                if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "capabilities: ${state.capabilities}")

                if (state.capabilities.isNotEmpty()) {
                    appleRepository.saveCacheFromState(device.macAddress, state)
                }
            }

            if (!state.battery.hasSameDisplayValues(previousState.battery)) {
                if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "battery state: ${state.battery}")
                updateWidgets()

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

            if (state.componentState != previousState.componentState) {
                if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "component state: ${state.componentState}")

                val earDetectionEnabled = deviceSettings.hostAutoPause
                if (earDetectionEnabled) {
                    processComponentStateChange(
                        device = device,
                        previousComponentState = previousState.componentState,
                        newComponentState = state.componentState,
                        transport = modeTransport
                    )
                }

                updateDeviceNotification(device = device)
            }

            if (state.controlStates != previousState.controlStates) {
                if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "control states: ${state.controlStates}")

                updateDeviceNotification(device = device)
            }

            if (state.currentHeartRate?.timestamp != previousState.currentHeartRate?.timestamp) {
                state.currentHeartRate?.let { heartRateSample ->
                    processHeartRateSample(
                        device = device,
                        heartRateSample = heartRateSample,
                        interval = state.heartRateInterval
                    )
                }
            }

            previousState = state
        }
        } finally {
            retireHeadGestures(device)
            clearHeartRateAlert(device)
        }
    }

    fun observeAppleSettings(device: AppleDevice): Job = serviceScope.launch(Dispatchers.IO) {
        // StateFlow emits the current value first, including writes made before this collector starts.
        device.settings.collect { settings ->
            retireIneligibleHeadGestures(device)
            updateHeartRateAlert(device)
            withContext(Dispatchers.Main.immediate) {
                if (!destroying && devices.value[device.macAddress] === device && device.settings.value == settings) {
                    islandWindow.updateAdaptiveStrength(device)
                    updateDeviceNotification(device = device)
                }
            }
            companionSettingsWriters.getOrPut(device.macAddress) { Mutex() }.withLock {
                if (device.settings.value == settings) appleRepository.saveSettings(device.macAddress, settings)
            }
            companion?.preferencesChanged(device.macAddress.value)
        }
    }

    fun observeAppleMetadata(device: AppleDevice): Job = serviceScope.launch(Dispatchers.IO) {
        var previousMetadata = device.metadata.value

        device.metadata.collect { metadata ->
            retireCaseBatteryObservation(device)
            withContext(Dispatchers.Main.immediate) {
                if (!destroying && devices.value[device.macAddress] === device && device.metadata.value == metadata) {
                    islandWindow.updatePairedBattery(device)
                    islandWindow.updateAdaptiveStrength(device)
                }
            }
            if (metadata != previousMetadata) {
                if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "metadata: $metadata")

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


                    val leftBattery = state.battery.find { it.component == BatteryComponent.LEFT }?.level ?: 0
                    val rightBattery = state.battery.find { it.component == BatteryComponent.RIGHT }?.level ?: 0
                    val batteryPercentage = leftBattery.coerceAtMost(rightBattery)

                    islandWindow.show(
                        owner = device,
                        onConnect = { connectNearbyDevice(device) },
                        isAudioConnected = { isNearbyDeviceAudioConnected(device) },
                        deferConnectAction = { shouldDeferNearbyConnect(device) },
                        name = metadata.name,
                        batteryLevel = batteryPercentage,
                        type = type,
                        reversed = reversed,
                        otherDeviceName = otherDeviceName,
                        statusText = statusText,

                        openApp = {
                            val intent = Intent(
                                this,
                                MainActivity::class.java,
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(intent)
                        },

                        showOffListeningMode = state.effectiveAllowOff(device.settings.value, metadata.modelNumber) == true,
                        noiseControlModeValue = (state.requestedControls[ControlCommandIdentifier.LISTENING_MODE]
                            ?: state.controlStates[ControlCommandIdentifier.LISTENING_MODE])?.firstOrNull()?.toInt() ?: 3,
                        onNoiseControlModeChanged = { device.setListeningModeFromUi(it) },
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
                if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(
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
                    val state = device.state.value
                    val metadata = device.metadata.value
                    val batteryText = state.battery.joinToString(" ") { "${it.component.name[0]}: ${it.level}%" }
                    val currentMode = (state.requestedControls[ControlCommandIdentifier.LISTENING_MODE]
                        ?: state.controlStates[ControlCommandIdentifier.LISTENING_MODE])?.firstOrNull()?.toInt()
                    val showOff = state.effectiveAllowOff(device.settings.value, metadata.modelNumber) == true

                    // Android caps notification actions at three and left-aligns them, so the listening-mode
                    // buttons live in a custom expanded view: evenly spaced, centered, and with room for Off.
                    val expandedView = RemoteViews(packageName, R.layout.notification_noise_control).apply {
                        setTextViewText(R.id.notification_title, metadata.name)
                        setTextViewText(R.id.notification_battery, batteryText)
                        setViewVisibility(R.id.notification_off_button, if (showOff) View.VISIBLE else View.GONE)
                        NOTIFICATION_LISTENING_MODE_BUTTONS.forEach { (viewId, mode) ->
                            setOnClickPendingIntent(
                                viewId,
                                PendingIntent.getService(
                                    this@LibrePodsService,
                                    notificationId + mode,
                                    Intent(this@LibrePodsService, LibrePodsService::class.java).apply {
                                        action = "ACTION_SET_ANC_MODE"
                                        putExtra("MAC_ADDRESS", device.macAddress.toString())
                                        putExtra("ANC_MODE", mode)
                                    },
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                )
                            )
                            val selected = mode == currentMode
                            setInt(
                                viewId, "setBackgroundResource",
                                if (selected) R.drawable.shape_notification_mode_button_selected
                                else R.drawable.shape_notification_mode_button
                            )
                            setFloat(viewId, "setAlpha", if (selected) 1f else 0.7f)
                        }
                    }

                    val updatedNotificationBuilder =
                        NotificationCompat.Builder(this, "device_${device.macAddress}")
                            .setSmallIcon(R.drawable.ic_airpods)
                            .setContentTitle(metadata.name)
                            .setContentText(batteryText)
                            .setContentIntent(pendingIntent)
                            .setCategory(Notification.CATEGORY_STATUS)
                            .setPriority(NotificationCompat.PRIORITY_LOW)
                            .setOnlyAlertOnce(true)
                            .setOngoing(true)
                            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                            .setCustomBigContentView(expandedView)

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
        val communicationReady = communicationArmed.isNotEmpty()
        val microphoneService = microphoneActive || communicationReady
        val callOwnsInput = communicationControllers.values.any { it.ownsSession }
        if (microphoneService) notificationManager.createNotificationChannel(NotificationChannel(
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

        val notification = NotificationCompat.Builder(this, if (microphoneService) "microphone_session" else "foreground_service_status")
            .setSmallIcon(R.drawable.ic_airpods).setContentTitle(getString(R.string.service_running))
            .setContentText(getString(R.string.foreground_notification_description))
            .setContentIntent(pendingIntentNotifDisable).setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW).setOngoing(true)
            .apply {
                if (microphoneActive) {
                    setContentTitle(getString(R.string.pixel_microphone_title))
                    setContentText(getString(R.string.pixel_microphone_active))
                    val stop = PendingIntent.getService(this@LibrePodsService, 58,
                        Intent(this@LibrePodsService, LibrePodsService::class.java)
                            .setAction(if (callOwnsInput) STOP_COMMUNICATION else STOP_MICROPHONE),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    addAction(0, getString(R.string.pixel_microphone_stop), stop)
                } else if (communicationReady) {
                    setContentTitle(getString(R.string.communication_audio_title))
                    setContentText(getString(R.string.communication_audio_waiting))
                    val stop = PendingIntent.getService(this@LibrePodsService, 59,
                        Intent(this@LibrePodsService, LibrePodsService::class.java).setAction(STOP_COMMUNICATION),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    addAction(0, getString(R.string.pixel_microphone_stop), stop)
                }
            }.build()

        try {
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                (if (microphoneService) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            startForeground(1, notification, types)
        } catch (e: Exception) {
            if (microphoneService) throw e
            Log.w(TAG, "Could not start the connected-device notification", e)
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
            showIsland(
                device = device,
                type = IslandType.CONNECTED
            )
        }

        if (new == EarPresence.NONE && appDataRepository.settings.value.islandSettings.keepCompactVisible) {
            islandWindow.close()
        }

        if (!pod.settings.value.hostAutoPause || callBlocksSettingsRefresh()) return
        val shouldPause = new == EarPresence.NONE || old == EarPresence.FULL && new == EarPresence.PARTIAL
        if (shouldPause) {
            val owner = WearMedia(transport)
            wearMediaEpochs[pod.macAddress] = owner
            serviceScope.launch {
                try {
                    fun current() = !destroying && devices.value[pod.macAddress] === pod && wearMediaEpochs[pod.macAddress] === owner &&
                        pod.aacp.ownsAudioSocket(transport) &&
                        pod.settings.value.hostAutoPause && !callBlocksSettingsRefresh() && !companionReleased.contains(pod.macAddress) &&
                        !companionOperations.containsKey(pod.macAddress) && isNearbyDeviceAudioConnected(pod) &&
                        (owner.paused || earPresenceOf(pod.state.value.componentState) != EarPresence.FULL)
                    companionBridge.connect()
                    if (withTimeoutOrNull(2000) { companionBridge.state.first { it.connected } } == null || !current()) return@launch
                    owner.paused = companionBridge.pauseOwnedMedia(pod.macAddress.value, ::current)
                } finally {
                    owner.pauseCompleted.complete(Unit)
                }
            }
        } else if (new == EarPresence.FULL || old == EarPresence.NONE) {
            val owner = wearMediaEpochs[pod.macAddress] ?: return
            serviceScope.launch {
                owner.pauseCompleted.await()
                if (!owner.paused || owner.transport !== transport || wearMediaEpochs[pod.macAddress] !== owner ||
                    !pod.aacp.ownsAudioSocket(transport) ||
                    pod.state.value.componentState != newComponentState) return@launch
                companionBridge.finishOwnedMedia(resume = true)
                wearMediaEpochs.remove(pod.macAddress, owner)
            }
        }
    }

    @Synchronized private fun clearHeartRateAlert(device: AppleDevice? = null) {
        val owner = heartRateAlertOwner ?: return
        if (device != null && owner !== device) return
        heartRateAlertOwner = null
        getSystemService(NotificationManager::class.java).cancel(999)
    }

    @Synchronized private fun updateHeartRateAlert(device: AppleDevice) {
        val settings = device.settings.value
        val state = device.state.value
        val sample = state.currentHeartRate
        if (destroying || devices.value[device.macAddress] !== device ||
            device.connectionState.value != ConnectionState.CONNECTED ||
            !settings.hrmAlertEnabled || state.hrmState != BuddyState.ACTIVE ||
            sample == null || sample.bpm <= settings.hrmAlertThreshold) {
            clearHeartRateAlert(device)
            return
        }
        val notification = NotificationCompat.Builder(this, "hrm_alert")
            .setSmallIcon(R.drawable.ic_pulse_alert)
            .setContentTitle(getString(R.string.high_heart_rate))
            .setContentText(getString(R.string.high_heart_rate_notification_text, sample.bpm))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(999, notification)
        heartRateAlertOwner = device
    }

    private fun processHeartRateSample(device: AppleDevice, heartRateSample: HeartRateSample, interval: Duration) {
        if (destroying || devices.value[device.macAddress] !== device ||
            device.connectionState.value != ConnectionState.CONNECTED) return
        serviceScope.launch(Dispatchers.IO) {
            heartRateRepository.insert(heartRateSample)
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
                }
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
     */
    }
}

// TODO: move out of this file
private enum class EarPresence { NONE, PARTIAL, FULL }

private fun earPresenceOf(components: Set<DeviceComponentState>): EarPresence {
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

/** Save each confirmed control update even when the same emission also changes battery or wear state. */
internal suspend fun saveChangedControlCache(previous: AppleState, current: AppleState,
                                            save: suspend (AppleState) -> Unit) {
    if (current.controlStates.isNotEmpty() && current.controlStates != previous.controlStates) save(current)
}
