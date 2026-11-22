package me.kavishdevar.librepods.services

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import android.telecom.TelecomManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.kavishdevar.librepods.companion.CompanionCallMonitor
import me.kavishdevar.librepods.companion.CompanionMediaSample
import me.kavishdevar.librepods.companion.CompanionStatus
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.Device
import me.kavishdevar.librepods.shizuku.CompanionMediaInspection
import me.kavishdevar.librepods.shizuku.MediaInspectionReason
import me.kavishdevar.librepods.shizuku.MediaInspectionStage
import me.kavishdevar.librepods.shizuku.NativeCallConnection
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import me.kavishdevar.librepods.utils.BluetoothIdentitySettings
import me.kavishdevar.librepods.utils.NativeGameModeController
import java.io.PrintWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The phone side of the companion PC handoff: which pair may be handed over, whether the phone is busy,
 * media and call inspection through the privileged bridge, and the acquire/release operations themselves.
 * [CompanionHandoffController] calls these; an operation in progress blocks other control work through
 * [operationActive] and [anyOperation].
 */
@SuppressLint("MissingPermission")
internal class CompanionHandoffHost(
    private val context: Context,
    private val destroying: () -> Boolean,
    private val devices: StateFlow<Map<MacAddress, Device<*, *, *>>>,
    private val holds: CompanionHolds,
    /** Resolved on every use so the service's lazy bridge and call monitor are not created early. */
    private val bridge: () -> ShizukuAudioClient,
    private val calls: () -> CompanionCallMonitor,
    private val callActive: () -> Boolean,
    private val microphoneActive: () -> Boolean,
    private val microphoneStarting: (MacAddress) -> Boolean,
    /** Microphone capture currently blocks BLE work. */
    private val captureBlocked: () -> Boolean,
    private val settingsRefreshActive: () -> Boolean,
    private val nearbyConnecting: () -> Boolean,
    private val gameController: (MacAddress) -> NativeGameModeController?,
    private val dongleInhibits: () -> Boolean,
    private val retireWearMedia: () -> Unit,
    private val isAudioConnected: (AppleDevice) -> Boolean,
    /** A Nearby connection requested by the companion, retired once [stillRequested] turns false. */
    private val connectNearby: suspend (AppleDevice, () -> Boolean) -> Boolean,
) {
    private val operations = ConcurrentHashMap<MacAddress, Any>()
    private data class MediaDiagnostic(
        val at: Long = 0, val stage: MediaInspectionStage = MediaInspectionStage.ADMISSION,
        val reason: MediaInspectionReason = MediaInspectionReason.NOT_READ,
        val devicePresent: Boolean = false, val holdReady: Boolean = false, val permission: Boolean = false,
        val bonded: Boolean = false, val adapter: Boolean = false, val bridgeConnected: Boolean = false,
        val root: Boolean = false, val outputObserved: Boolean = false, val outputConnected: Boolean = false, val known: Boolean = false,
        val playing: Boolean = false, val available: Boolean = false)
    private val companionMediaDiagnostic = AtomicReference(MediaDiagnostic())

    fun operationActive(address: MacAddress): Boolean = operations.containsKey(address)

    fun anyOperation(): Boolean = operations.isNotEmpty()

    fun dumpMedia(writer: PrintWriter) {
        val value = companionMediaDiagnostic.get()
        writer.println("companion_media stage=${value.stage} reason=${value.reason} " +
            "age_ms=${if (value.at == 0L) -1 else (SystemClock.elapsedRealtime() - value.at).coerceAtLeast(0)} " +
            "pending=${value.reason == MediaInspectionReason.PENDING} device_present=${value.devicePresent} " +
            "hold_ready=${value.holdReady} permission=${value.permission} bonded=${value.bonded} adapter=${value.adapter} " +
            "bridge_connected=${value.bridgeConnected} root=${value.root} output_observed=${value.outputObserved} " +
            "actual_output_connected=${value.outputConnected} " +
            "known=${value.known} playing=${value.playing} available=${value.available}")
    }

    fun companionDevice(address: String): AppleDevice? = runCatching {
        (devices.value[MacAddress(address)] as? AppleDevice)?.takeIf {
            !destroying() && holds.ready && it.bluetoothDevice.bondState == BluetoothDevice.BOND_BONDED &&
                context.getSystemService(BluetoothManager::class.java).adapter.isEnabled &&
                (BluetoothIdentitySettings.allowsRootlessCompanion() || bridge().hasRootPrivileges)
        }
    }.getOrNull()

    private fun companionBusy(device: AppleDevice): Boolean = callActive() ||
        microphoneActive() || captureBlocked() || settingsRefreshActive() ||
        nearbyConnecting() || device.state.value.recordingState.isRecording ||
        device.state.value.microphoneState.isActive || gameController(device.macAddress)?.state?.value?.let {
            it.restoring || it.remoteRestorePending
        } == true

    suspend fun companionMedia(address: String): CompanionMediaSample {
        var diagnostic = MediaDiagnostic(at = SystemClock.elapsedRealtime(), reason = MediaInspectionReason.PENDING)
        companionMediaDiagnostic.set(diagnostic)
        fun record(next: MediaDiagnostic) {
            // A late read from a retired connection cannot overwrite the newer connection's report.
            companionMediaDiagnostic.compareAndSet(diagnostic, next)
            diagnostic = next
        }
        fun failed(reason: MediaInspectionReason): CompanionMediaSample {
            record(diagnostic.copy(at = SystemClock.elapsedRealtime(), reason = reason))
            return CompanionMediaSample()
        }
        return try {
            val device = devices.value[MacAddress(address)] as? AppleDevice
            record(diagnostic.copy(devicePresent = device != null, holdReady = holds.ready,
                bridgeConnected = bridge().state.value.connected, root = bridge().hasRootPrivileges,
                permission = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED))
            if (!diagnostic.permission) return failed(MediaInspectionReason.PERMISSION_DENIED)
            record(diagnostic.copy(bonded = device?.bluetoothDevice?.bondState == BluetoothDevice.BOND_BONDED,
                adapter = context.getSystemService(BluetoothManager::class.java).adapter.isEnabled))
            record(diagnostic.copy(stage = MediaInspectionStage.OUTPUT))
            val outputs = context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val connected = outputs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && it.address.equals(address, true) }
            record(diagnostic.copy(stage = MediaInspectionStage.ADMISSION, outputObserved = true, outputConnected = connected))
            val admission = when {
                device == null -> MediaInspectionReason.DEVICE_MISSING
                destroying() -> MediaInspectionReason.DESTROYING
                !holds.ready -> MediaInspectionReason.HOLD_UNAVAILABLE
                !diagnostic.bonded -> MediaInspectionReason.BOND_MISSING
                !diagnostic.adapter -> MediaInspectionReason.BLUETOOTH_OFF
                !BluetoothIdentitySettings.allowsRootlessCompanion() && !bridge().hasRootPrivileges -> MediaInspectionReason.ROOT_UNAVAILABLE
                else -> null
            }
            if (admission != null) return failed(admission)
            record(diagnostic.copy(stage = MediaInspectionStage.CLIENT))
            val value = bridge().inspectCompanionMedia(address)
            record(diagnostic.copy(stage = CompanionMediaInspection.stage(value), reason = CompanionMediaInspection.reason(value)))
            if (companionDevice(address) !== device) return failed(MediaInspectionReason.DEVICE_CHANGED)
            val known = value.getBoolean("known")
            val playing = value.getBoolean("playing")
            val busy = known && (companionBusy(checkNotNull(device)) || operations.isNotEmpty())
            val dongle = known && !busy && dongleInhibits()
            val available = known && !busy && !dongle
            record(diagnostic.copy(at = SystemClock.elapsedRealtime(), known = known, playing = playing, available = available,
                reason = when {
                    !known -> diagnostic.reason
                    busy -> MediaInspectionReason.BUSY
                    dongle -> MediaInspectionReason.DONGLE_SELECTED
                    else -> diagnostic.reason
                }))
            CompanionMediaSample(known, playing, connected, available,
                value.getString("player").orEmpty(), value.getBoolean("eligible"))
        } catch (cancelled: CancellationException) {
            failed(MediaInspectionReason.CANCELLED)
            throw cancelled
        } catch (error: Exception) { failed(CompanionMediaInspection.failureReason(error)) }
    }

    fun companionCallAllowed(address: String): Boolean = companionDevice(address)?.settings?.value?.let {
        it.callTakeoverEnabled && it.callTakeoverConsent &&
            context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    } == true

    fun companionCallReturnAllowed(address: String) = companionCallAllowed(address) &&
        companionDevice(address)?.settings?.value?.callReturnEnabled == true

    private fun companionCallBusy(device: AppleDevice): Boolean = !companionCallAllowed(device.macAddress.value) ||
        calls().state !in setOf("ringing", "active") ||
        context.getSystemService(AudioManager::class.java).mode !in setOf(AudioManager.MODE_NORMAL, AudioManager.MODE_RINGTONE, AudioManager.MODE_IN_CALL) ||
        microphoneActive() || captureBlocked() || settingsRefreshActive() || nearbyConnecting() ||
        device.state.value.recordingState.isRecording || device.state.value.microphoneState.isActive ||
        runCatching {
            context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.any {
                it.clientAudioSource !in setOf(android.media.MediaRecorder.AudioSource.VOICE_CALL,
                    android.media.MediaRecorder.AudioSource.VOICE_UPLINK, android.media.MediaRecorder.AudioSource.VOICE_DOWNLINK)
            }
        }.getOrDefault(true)

    suspend fun companionCallReceipt(address: String, requested: () -> Boolean): Int {
        val device = companionDevice(address) ?: return 0
        if (!requested() || !companionCallAllowed(address)) return 0
        bridge().connect()
        if (withTimeoutOrNull(2000) { bridge().state.first { it.connected } } == null) return 0
        return bridge().nativeCall(address, false) { requested() && companionDevice(address) === device && companionCallAllowed(address) }
    }

    suspend fun companionCallStatus(address: String, requested: () -> Boolean): CompanionStatus {
        val device = companionDevice(address) ?: return CompanionStatus(false, false, false)
        if (!requested() || companionCallBusy(device) || operations.isNotEmpty()) return CompanionStatus(false, true, false)
        val receipt = companionCallReceipt(address) { requested() && !companionCallBusy(device) }
        return CompanionStatus(receipt and NativeCallConnection.READY == NativeCallConnection.READY,
            companionCallBusy(device), receipt != 0)
    }

    suspend fun companionCallAcquire(address: String, requested: () -> Boolean): String {
        val device = companionDevice(address) ?: return "unavailable"
        if (!requested() || companionCallBusy(device)) return "busy"
        val operation = Any()
        if (operations.putIfAbsent(device.macAddress, operation) != null) return "busy"
        fun current() = requested() && companionDevice(address) === device &&
            operations[device.macAddress] === operation && !companionCallBusy(device)
        try {
            if (!current() || !holds.set(device.macAddress, false)) return "unavailable"
            bridge().connect()
            if (withTimeoutOrNull(2000) { bridge().state.first { it.connected } } == null || !current()) return "unavailable"
            val flags = bridge().nativeCall(address, true, ::current)
            return if (current() && flags and NativeCallConnection.READY == NativeCallConnection.READY) "hfp_ready" else "failed"
        } finally { operations.remove(device.macAddress, operation) }
    }

    fun allCompanionCallsIdle(): Boolean = calls().state == "idle" && !callActive() && runCatching {
            !context.getSystemService(TelecomManager::class.java).isInCall
        }.getOrDefault(false)

    suspend fun companionPhoneQuiet(address: String, requested: () -> Boolean): Boolean {
        fun sameEnd() = requested() && calls().state == "idle" &&
            context.getSystemService(AudioManager::class.java).mode != AudioManager.MODE_IN_COMMUNICATION
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (sameEnd() && SystemClock.elapsedRealtime() < deadline) {
            if (allCompanionCallsIdle() && operations.isEmpty()) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                val receipt = withTimeoutOrNull(remaining.coerceAtLeast(1)) {
                    companionCallReceipt(address) { sameEnd() && allCompanionCallsIdle() }
                } ?: 0
                if (sameEnd() && allCompanionCallsIdle() && operations.isEmpty() &&
                    receipt and NativeCallConnection.SCO_IDLE != 0) return true
            }
            delay(80)
        }
        return false
    }

    suspend fun companionStatus(address: String, current: () -> Boolean): CompanionStatus {
        val device = companionDevice(address) ?: return CompanionStatus(false, false, false)
        val connected = isAudioConnected(device)
        if (!current() || companionBusy(device) || operations.isNotEmpty()) return CompanionStatus(connected, true, false)
        val available = try {
            bridge().connect()
            withTimeoutOrNull(2_000) { bridge().state.first { it.connected } } != null &&
                current() && companionDevice(address) === device && !companionBusy(device) &&
                bridge().inspectNativePlayback(address) { current() && companionDevice(address) === device && !companionBusy(device) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        return CompanionStatus(isAudioConnected(device), companionBusy(device), available)
    }

    suspend fun companionOperation(phase: String, address: String, requested: () -> Boolean): String {
        val device = companionDevice(address) ?: return "unavailable"
        if (!requested()) return "superseded"
        if (companionBusy(device)) return "busy"
        retireWearMedia()
        val operation = Any()
        if (operations.putIfAbsent(device.macAddress, operation) != null) return "busy"
        val game = gameController(device.macAddress)
        var suspendedGame = false
        val releaseStarted = AtomicBoolean(false)
        fun current() = requested() && companionDevice(address) === device &&
            operations[device.macAddress] === operation && !callActive() &&
            !microphoneActive() && !captureBlocked() && !settingsRefreshActive() &&
            (gameController(device.macAddress) === game || releaseStarted.get() && gameController(device.macAddress) == null) &&
            !device.state.value.recordingState.isRecording && !device.state.value.microphoneState.isActive
        try {
            if (!current()) return "superseded"
            if (phase == "acquire") {
                if (!holds.set(device.macAddress, false)) return "unavailable"
                val acquired = connectNearby(device, ::current)
                return if (acquired && current() && isAudioConnected(device)) "acquired" else "failed"
            }
            if (phase != "release") return "invalid_state"
            bridge().connect()
            if (withTimeoutOrNull(2_000) { bridge().state.first { it.connected } } == null || !current()) return "unavailable"
            // The companion reservation blocks new Game Mode work while its owned cleanup drains.
            if (game != null) {
                suspendedGame = true
                if (!game.suspendForHandover() || !current()) return "busy"
            }
            // Retire only this pair's control work before releasing its two native profiles.
            if (!holds.set(device.macAddress, true)) return "unavailable"
            if (!current()) return "superseded"
            // The owned ACL release also retires this pair's Game Mode controller.
            releaseStarted.set(true)
            device.aacp.disconnect()
            Log.i("CompanionHandoff", "phase=release event=control_closed closed=${device.aacp.captureAudioSocket() == null}")
            if (!current() || !bridge().releaseNativePlayback(address, ::current)) return "failed"
            fun outputsReleased(): Boolean = runCatching {
                context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).none {
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
            operations.remove(device.macAddress, operation)
            if (suspendedGame && gameController(device.macAddress) === game && !microphoneActive() &&
                !microphoneStarting(device.macAddress)) game?.resumeAfterMicrophone()
        }
    }
}
