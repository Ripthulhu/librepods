package me.kavishdevar.librepods.services

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.media.AudioManager
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.kavishdevar.librepods.audio.ExperimentalAudioController
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.Device
import me.kavishdevar.librepods.shizuku.A2dpRecoveryGuard
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "LibrePodsService"
private const val NEARBY_TAKEOVER_TAG = "NearbyTakeover"

/**
 * Runs one explicit Nearby connection per pair: claims the native media profile through the audio bridge,
 * gives the AACP control socket a head start, and keeps or retires that early control with the request.
 */
@SuppressLint("MissingPermission")
internal class NearbyConnector(
    private val scope: CoroutineScope,
    private val destroying: () -> Boolean,
    private val devices: StateFlow<Map<MacAddress, Device<*, *, *>>>,
    private val deferral: NearbyConnectDeferral,
    private val audioManager: () -> AudioManager,
    private val dongleInhibits: () -> Boolean,
    /** Starts a local intent with the companion link and returns whether it is still the newest choice. */
    private val beginLocalIntent: () -> () -> Boolean,
    private val releaseHold: (MacAddress) -> Boolean,
    private val handoffActive: (MacAddress) -> Boolean,
    /** Attaches the experimental audio owner for a current pair; called on the main thread. */
    private val attachAudio: (AppleDevice) -> Unit,
    private val audioOwner: (MacAddress) -> AppleDevice?,
    private val audioController: (MacAddress) -> ExperimentalAudioController?,
    private val callActive: () -> Boolean,
    /** Microphone ownership or a blocked BLE capture. */
    private val captureBusy: () -> Boolean,
    private val settingsRefreshActive: () -> Boolean,
    private val isAudioConnected: (AppleDevice) -> Boolean,
    /** Attaches passive control once the native output is up. */
    private val attachControl: suspend (BluetoothDevice) -> Unit,
) {
    private val connections = ConcurrentHashMap<MacAddress, Any>()

    fun anyActive(): Boolean = connections.isNotEmpty()

    /** The same selected-pair action also works when the companion PC is unavailable. */
    suspend fun connect(device: AppleDevice, stillRequested: () -> Boolean = { true },
        companionRequest: Boolean = false): Boolean {
        if (companionRequest && dongleInhibits()) return false
        var explicitCurrent: () -> Boolean = { true }
        if (!companionRequest) {
            explicitCurrent = withContext(Dispatchers.Main.immediate) {
                beginLocalIntent()
            }
            if (!releaseHold(device.macAddress)) return false
        }
        val operation = Any()
        if (connections.putIfAbsent(device.macAddress, operation) != null) return false
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
                if (destroying() || devices.value[device.macAddress] !== device ||
                    device.bluetoothDevice.bondState != BluetoothDevice.BOND_BONDED) null
                else {
                    attachAudio(device)
                    audioController(device.macAddress)
                }
            } ?: return false
            fun ownerFailure(): String? = runCatching {
                when {
                    companionRequest && dongleInhibits() -> "dongle controls selected"
                    !explicitCurrent() -> "newer choice"
                    !stillRequested() -> "request retired"
                    handoffActive(device.macAddress) && !companionRequest -> "handoff active"
                    destroying() -> "service stopped"
                    devices.value[device.macAddress] !== device -> "device replaced"
                    audioOwner(device.macAddress) !== device || audioController(device.macAddress) !== controller -> "audio owner replaced"
                    device.bluetoothDevice.bondState != BluetoothDevice.BOND_BONDED -> "unpaired"
                    callActive() -> "call or communication mode active"
                    captureBusy() -> "microphone transport active"
                    settingsRefreshActive() -> "settings refresh active"
                    device.state.value.recordingState.isRecording || device.state.value.microphoneState.isActive -> "microphone active"
                    else -> null
                }
            }.getOrElse { "guard exception: ${it.javaClass.simpleName}" }
            fun current(): Boolean {
                val reason = ownerFailure() ?: when {
                    connections[device.macAddress] !== operation -> "request replaced"
                    caller?.isActive == false -> "request cancelled"
                    else -> null
                }
                if (reason != null && retiredReason.compareAndSet(null, reason))
                    if (Log.isLoggable(NEARBY_TAKEOVER_TAG, Log.DEBUG)) Log.d(NEARBY_TAKEOVER_TAG, "Retired: reason=$reason elapsedMs=${SystemClock.elapsedRealtime() - started}")
                return reason == null
            }
            if (!current() || !controller.canRefreshSettings()) return false
            val alreadyConnected = isAudioConnected(device)
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
                    (!alreadyConnected || isAudioConnected(device)) &&
                    ((connections[device.macAddress] === operation && caller?.isActive != false) ||
                        isAudioConnected(device))
                keepControl = ::controlCurrent
                val control = scope.launch(Dispatchers.IO) {
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
                scope.launch(Dispatchers.IO) {
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
                    while (current() && isAudioConnected(device) &&
                        device.connectionState.value == ConnectionState.CONNECTING) delay(40)
                    current() && isAudioConnected(device) &&
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
                    while (current() && !isAudioConnected(device)) delay(40)
                    current() && isAudioConnected(device)
                } != true) {
                if (Log.isLoggable(NEARBY_TAKEOVER_TAG, Log.DEBUG)) {
                    val outputs = runCatching {
                        audioManager().getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                            .joinToString { "type=${it.type}:selected=${it.address.equals(device.macAddress.value, true)}:addressPresent=${it.address.isNotBlank()}" }
                    }.getOrElse { "query failed: ${it.javaClass.simpleName}" }
                    Log.d(NEARBY_TAKEOVER_TAG, "Output unavailable: ownerFailure=${ownerFailure()} retired=${retiredReason.get()} " +
                        "nativeState=${deferral.mediaState(device.macAddress)} outputs=[$outputs]")
                }
                return false
            }
            outcome = "connected"
            deferral.observeMedia(device, BluetoothProfile.STATE_CONNECTED)
            scope.launch(Dispatchers.IO) {
                earlyControl?.join()
                if (devices.value[device.macAddress] === device && isAudioConnected(device))
                    attachControl(device.bluetoothDevice)
            }
            return true
        } catch (error: Exception) {
            if (error is CancellationException) { outcome = "cancelled"; throw error }
            outcome = "failed"
            Log.w(TAG, "Nearby media connection failed", error)
            return false
        } finally {
            Log.i("NearbyTakeover", "Complete: result=$outcome elapsedMs=${SystemClock.elapsedRealtime() - started}")
            connections.remove(device.macAddress, operation)
            if (!keepControl()) {
                earlyControl?.cancel()
                pendingControl.get()?.let { device.aacp.cancelPendingConnect(it) }
            }
        }
    }
}
