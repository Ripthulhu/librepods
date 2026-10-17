package me.kavishdevar.librepods.shizuku

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.currentCoroutineContext
import me.kavishdevar.librepods.BuildConfig
import rikka.shizuku.Shizuku
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class BridgeState(
    val availability: String = "Shizuku is not running",
    val connected: Boolean = false,
    val probing: Boolean = false,
    val report: String = "",
    val session: String = "stopped",
    val detail: String = "",
)

/** Owned by the foreground service. Nothing is captured or routed just by creating this client. */
class ShizukuAudioClient(context: Context) : Closeable {
    internal class InjectionRequest(
        val remote: IAudioBridge,
        val sampleRate: Int,
        val channels: Int,
        val targetPackage: String,
        val forCall: Boolean,
        val selectedAddress: String,
        val stillCurrent: () -> Boolean,
    ) {
        val owner = Binder()
        var pcm: ParcelFileDescriptor? = null
    }
    private class PlaybackRequest(val remote: IAudioBridge, val address: String,
        private val stillCurrent: () -> Boolean, private val job: Job?) {
        val active = AtomicBoolean(true)
        val owner = object : IPlaybackRecoveryOwner.Stub() {
            override fun isCurrent() = Binder.getCallingUid() == 2000 && active.get() &&
                job?.isActive != false && runCatching { stillCurrent() }.getOrDefault(false)
        }
    }

    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(BridgeState())
    val state: StateFlow<BridgeState> = mutableState.asStateFlow()
    private val lock = Any()
    private val ipc = Mutex()
    private val pendingStops = ArrayDeque<InjectionRequest>()
    private var current: InjectionRequest? = null
    private var playbackRequest: PlaybackRequest? = null
    private var pairingRequest: PlaybackRequest? = null
    @Volatile private var bridge: IAudioBridge? = null
    @Volatile private var closed = false
    private var connecting = false
    private var bound = false
    private var settingsRefresh = false
    private val args = Shizuku.UserServiceArgs(ComponentName(this.context, ShizukuAudioService::class.java))
        .daemon(false)
        .processNameSuffix("audio_bridge")
        .tag("librepods.audio.bridge.${android.os.Process.myUid()}.${UUID.randomUUID()}")
        .version(BuildConfig.VERSION_CODE)

    private fun callback(request: InjectionRequest) = object : IAudioBridgeCallback.Stub() {
        override fun onReport(json: String) = Unit
        override fun onSessionState(state: String, detail: String) {
            synchronized(lock) {
                if (closed || current !== request || bridge !== request.remote) return
                if (state in listOf("stopped", "failed")) {
                    runCatching { request.pcm?.close() }
                    request.pcm = null
                    current = null
                }
                mutableState.update { it.copy(session = state, detail = detail) }
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            synchronized(lock) {
                if (closed) {
                    runCatching { Shizuku.unbindUserService(args, this, true) }
                    return
                }
                bridge = IAudioBridge.Stub.asInterface(service)
                connecting = false
                mutableState.update { it.copy(connected = true, availability = "Connected through Shizuku") }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) = disconnected("Shizuku audio service stopped")
    }
    private val received = Shizuku.OnBinderReceivedListener {
        refreshAvailability()
        if (connecting) connect()
    }
    private val dead = Shizuku.OnBinderDeadListener { disconnected("Shizuku stopped; restart it and connect again") }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == PERMISSION_REQUEST && !closed) {
            if (result == PackageManager.PERMISSION_GRANTED) connect()
            else mutableState.update { it.copy(availability = "Shizuku permission denied") }
        }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
    }

    fun requestPermission() {
        if (closed) return
        runCatching {
            check(Shizuku.pingBinder()) { "Start Shizuku before connecting" }
            check(!Shizuku.isPreV11()) { "Update Shizuku to version 13 or newer" }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) connect()
            else Shizuku.requestPermission(PERMISSION_REQUEST)
        }.onFailure { error -> mutableState.update { it.copy(availability = error.message.orEmpty()) } }
    }

    fun connect() {
        runCatching {
            synchronized(lock) {
                if (closed || bridge != null || bound) return
                check(Shizuku.pingBinder()) { "Start Shizuku before connecting" }
                check(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { "Allow LibrePods in Shizuku first" }
                check(Shizuku.getVersion() >= 13) { "Update Shizuku to version 13 or newer" }
                connecting = true
                bound = true
                mutableState.update { it.copy(availability = "Starting audio diagnostics service") }
            }
            Shizuku.bindUserService(args, connection)
        }.onFailure { error ->
            synchronized(lock) {
                connecting = false
                bound = false
                if (!closed) mutableState.update { it.copy(availability = error.message.orEmpty()) }
            }
        }
    }

    fun probe() {
        scope.launch {
            val remote = synchronized(lock) {
                if (closed || settingsRefresh) return@launch
                mutableState.update { it.copy(probing = true) }
                bridge
            }
            val callback = object : IAudioBridgeCallback.Stub() {
                override fun onReport(json: String) {
                    synchronized(lock) {
                        if (!closed && bridge === remote) mutableState.update { it.copy(report = json, probing = false) }
                    }
                }
                override fun onSessionState(state: String, detail: String) = Unit
            }
            runCatching { checkNotNull(remote) { "Connect Shizuku first" }.probe(callback) }
                .onFailure { error ->
                    synchronized(lock) {
                        if (!closed && bridge === remote) mutableState.update { it.copy(probing = false, detail = error.message.orEmpty()) }
                    }
                }
        }
    }

    /** Returns signed 16-bit little-endian PCM input. The caller must close it and stop on disconnect. */
    suspend fun startMicrophoneInjection(
        sampleRate: Int,
        channels: Int,
        targetPackage: String,
        forCall: Boolean = false,
        selectedAddress: String = "",
    ): ParcelFileDescriptor? = try {
        startPreparedInjection(prepareMicrophoneInjection(sampleRate, channels, targetPackage, forCall, selectedAddress))
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        synchronized(lock) {
            if (!closed && current == null) mutableState.update { it.copy(session = "failed", detail = error.message.orEmpty()) }
        }
        null
    }

    /** Reserve synchronously with the controller's generation; Stop can then retire an unopened route. */
    internal fun prepareMicrophoneInjection(
        sampleRate: Int, channels: Int, targetPackage: String, forCall: Boolean,
        selectedAddress: String = "", stillCurrent: () -> Boolean = { true },
    ): InjectionRequest = synchronized(lock) {
        check(!closed) { "Audio bridge was closed" }
        AudioBridgeRules.validateFormat(sampleRate, channels)
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Grant microphone permission before starting injection"
        }
        check(current == null) { "Stop the current microphone route first" }
        check(!settingsRefresh) { "Wait for the settings refresh to finish" }
        check(!mutableState.value.probing) { "Wait for audio diagnostics to finish" }
        val address = if (forCall) A2dpRecoveryGuard(selectedAddress).address else {
            require(selectedAddress.isEmpty()) { "A recording application does not select a Bluetooth output" }
            ""
        }
        check(stillCurrent()) { "The AirPods connection changed. Please retry." }
        cancelPlaybackRecoveryLocked()
        InjectionRequest(checkNotNull(bridge) { "Connect Shizuku first" }, sampleRate, channels, targetPackage, forCall,
            address, stillCurrent).also {
            current = it
            mutableState.update { state -> state.copy(session = "starting", detail = "Preparing microphone injection") }
        }
    }

    internal suspend fun startPreparedInjection(next: InjectionRequest): ParcelFileDescriptor? {
        return try {
            withContext(Dispatchers.IO) {
                ipc.withLock {
                    drainStops()
                    if (!isCurrent(next)) return@withLock null
                    check(next.stillCurrent()) { "The AirPods connection changed. Please retry." }
                    val descriptor = next.remote.startMicrophoneInjection(next.sampleRate, next.channels, next.targetPackage, next.forCall,
                        next.selectedAddress, next.owner, callback(next))
                    val accepted = synchronized(lock) {
                        if (isCurrent(next) && runCatching { next.stillCurrent() }.getOrDefault(false)) {
                            next.pcm = descriptor; true
                        } else false
                    }
                    if (accepted) descriptor else {
                        runCatching { descriptor?.close() }
                        runCatching { next.remote.stopSession(next.owner) }
                        val wasCurrent = retire(next)
                        synchronized(lock) {
                            if (!closed && wasCurrent && current == null) mutableState.update {
                                it.copy(session = "failed", detail = "The AirPods connection changed. Please retry.")
                            }
                        }
                        null
                    }
                }
            }
        } catch (error: Exception) {
            val wasCurrent = retire(next)
            if (error is CancellationException) throw error
            synchronized(lock) {
                if (!closed && wasCurrent && current == null) {
                    mutableState.update { it.copy(session = "failed", detail = error.message.orEmpty()) }
                }
            }
            null
        }
    }

    private fun isCurrent(request: InjectionRequest): Boolean = synchronized(lock) {
        !closed && current === request && bridge === request.remote
    }

    /** Called with ipc held, so a new start cannot overtake a previous Stop. */
    private fun drainStops() {
        while (true) {
            val retired = synchronized(lock) { pendingStops.removeFirstOrNull() } ?: return
            runCatching { retired.remote.stopSession(retired.owner) }
        }
    }

    private fun retire(request: InjectionRequest): Boolean {
        val wasCurrent = synchronized(lock) {
            runCatching { request.pcm?.close() }
            request.pcm = null
            val matched = current === request
            if (matched) current = null
            pendingStops.addLast(request)
            matched
        }
        scope.launch { ipc.withLock { drainStops() } }
        return wasCurrent
    }

    fun stopSession() {
        synchronized(lock) {
            cancelPlaybackRecoveryLocked()
            val retired = current ?: return
            // Retire the captured owner now; queued IPC must never look up a later mutable session.
            current = null
            runCatching { retired.pcm?.close() }
            retired.pcm = null
            pendingStops.addLast(retired)
            mutableState.update { it.copy(session = "stopped", detail = "Microphone route stopped") }
        }
        scope.launch { ipc.withLock { drainStops() } }
    }

    /** Restarts only the selected pair's native A2DP profile after the AirPods uplink has stopped. */
    suspend fun restoreNativePlayback(address: String, stillCurrent: () -> Boolean = { true }): Boolean = try {
        withTimeoutOrNull(6500) {
            val callerJob = currentCoroutineContext()[Job]
            suspendCancellableCoroutine { continuation ->
                val request = synchronized(lock) {
                    check(!closed && !settingsRefresh && current == null && !mutableState.value.probing) { "Another audio operation is active" }
                    cancelPlaybackRecoveryLocked()
                    PlaybackRequest(checkNotNull(bridge) { "Connect Shizuku to restore playback" },
                        A2dpRecoveryGuard(address).address, stillCurrent, callerJob).also { playbackRequest = it }
                }
                continuation.invokeOnCancellation {
                    request.active.set(false)
                    synchronized(lock) { if (playbackRequest === request) playbackRequest = null }
                }
                scope.launch {
                    val result = try {
                        ipc.withLock {
                            drainStops()
                            if (!request.active.get()) false
                            else request.remote.restoreNativePlayback(request.address, request.owner)
                        }
                    } catch (error: Exception) {
                        synchronized(lock) {
                            if (!closed && playbackRequest === request) mutableState.update { it.copy(detail = error.message.orEmpty()) }
                        }
                        false
                    } finally {
                        request.active.set(false)
                        synchronized(lock) { if (playbackRequest === request) playbackRequest = null }
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
            }
        } ?: false
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        synchronized(lock) { if (!closed) mutableState.update { it.copy(detail = error.message.orEmpty()) } }
        false
    }

    /** Optional consent assistance: never waits for binding, asks permission or starts an audio route. */
    suspend fun confirmPairing(address: String, stillCurrent: () -> Boolean): Boolean {
        val remote = bridge
        if (remote == null) {
            android.util.Log.i("NearbyPairing", "Consent bridge unavailable: connected=false")
            return false
        }
        val allowed = pairingAllowed(remote)
        val ownerCurrent = if (allowed) runCatching(stillCurrent).getOrDefault(false) else null
        if (ownerCurrent != true) {
            android.util.Log.i("NearbyPairing", "Consent bridge declined: allowed=$allowed owner=$ownerCurrent")
            return false
        }
        val selected = runCatching { A2dpRecoveryGuard(address).address }.getOrNull() ?: return false
        return try {
            withTimeoutOrNull(1_200) {
                val callerJob = currentCoroutineContext()[Job]
                suspendCancellableCoroutine { continuation ->
                    val request = synchronized(lock) {
                        if (closed || bridge !== remote) null else {
                            pairingRequest?.active?.set(false)
                            PlaybackRequest(remote, selected,
                                { pairingAllowed(remote) && stillCurrent() }, callerJob).also { pairingRequest = it }
                        }
                    }
                    if (request == null) { continuation.resume(false); return@suspendCancellableCoroutine }
                    continuation.invokeOnCancellation { request.active.set(false) }
                    scope.launch {
                        val result = try {
                            if (!request.active.get() || !pairingAllowed(remote)) false
                            else request.remote.confirmPairing(request.address, request.owner)
                        } catch (error: Exception) {
                            android.util.Log.i("NearbyPairing", "Consent bridge failed: ${error.javaClass.simpleName}")
                            false
                        }
                        finally {
                            request.active.set(false)
                            synchronized(lock) { if (pairingRequest === request) pairingRequest = null }
                        }
                        if (continuation.isActive) continuation.resume(result)
                    }
                }
            } ?: false
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            false
        }
    }

    internal fun canConfirmPairing(): Boolean = bridge?.let(::pairingAllowed) == true

    private fun pairingAllowed(remote: IAudioBridge): Boolean = !closed && bridge === remote && runCatching {
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun cancelPlaybackRecovery() = synchronized(lock) { cancelPlaybackRecoveryLocked() }

    fun canRefreshSettings(): Boolean = synchronized(lock) {
        !closed && !settingsRefresh && current == null && pendingStops.isEmpty() && playbackRequest == null &&
            !mutableState.value.probing && !ipc.isLocked
    }

    fun tryBeginSettingsRefresh(): Boolean = synchronized(lock) {
        if (!canRefreshSettings()) false else { settingsRefresh = true; true }
    }

    fun endSettingsRefresh() = synchronized(lock) { settingsRefresh = false }

    /** AACP does not use AudioRecord, so Android's software sensor privacy must be checked explicitly. */
    fun isMicrophonePrivacyBlocked(): Boolean {
        val remote = bridge ?: return true
        return runCatching {
            if (closed) true else remote.isMicrophonePrivacyBlocked() || bridge !== remote || closed
        }.getOrDefault(true)
    }

    private fun cancelPlaybackRecoveryLocked() {
        playbackRequest?.active?.set(false)
        playbackRequest = null
    }

    private fun refreshAvailability() {
        if (closed) return
        val message = runCatching {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) "Shizuku is available; connect to run diagnostics"
            else "Shizuku is available; permission required"
        }.getOrDefault("Shizuku is not running")
        mutableState.update { it.copy(availability = message) }
    }

    private fun disconnected(reason: String) {
        synchronized(lock) {
            pairingRequest?.active?.set(false)
            pairingRequest = null
            cancelPlaybackRecoveryLocked()
            current?.let {
                runCatching { it.pcm?.close() }
                it.pcm = null
                pendingStops.addLast(it)
            }
            current = null
            bridge = null
            connecting = false
            bound = false
            mutableState.update { it.copy(connected = false, probing = false, session = "stopped", availability = reason, detail = reason) }
        }
        scope.launch { ipc.withLock { drainStops() } }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            pairingRequest?.active?.set(false)
            pairingRequest = null
            cancelPlaybackRecoveryLocked()
            runCatching { current?.pcm?.close() }
            current = null
            pendingStops.clear()
            // The tag is unique to this foreground-service instance; a late close cannot kill its replacement.
            if (bound) runCatching { Shizuku.unbindUserService(args, connection, true) }
            bound = false
            bridge = null
        }
        Shizuku.removeBinderReceivedListener(received)
        Shizuku.removeBinderDeadListener(dead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        scope.cancel()
    }

    private companion object {
        const val PERMISSION_REQUEST = 18570
    }
}
