package me.kavishdevar.librepods.shizuku

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import me.kavishdevar.librepods.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean
import rikka.shizuku.SystemServiceHelper

/** App-owned native audio controls and selected-pair media recovery. */
class ShizukuAudioService @JvmOverloads constructor(context: Context,
    private val exitOnDestroy: Boolean = true,
) : IAudioBridge.Stub(), AutoCloseable {
    init {
        // Both libsu and root-mode Shizuku are app-owned clients, never system_server.
        if (Process.myUid() == 0) RootAppProcess.prepare()
    }
    private val originalContext = context
    private val expectedUid = context.packageManager.getApplicationInfo(BuildConfig.APPLICATION_ID, 0).uid
    private val shellContext by lazy { ShellAudioContext(context).also { it.installProcessAttribution() } }
    private val lock = Any()
    private val cleanupFailed = AtomicBoolean(false)
    private var playbackRecovery: A2dpPlaybackRecovery? = null
    private var playbackRelease: NativePlaybackRelease? = null
    private var callConnection: NativeCallConnection? = null
    private var mediaPause: Pair<IPlaybackRecoveryOwner, OwnedMediaPause>? = null
    private var volumeChecking = false
    private var volumeRepairing = false
    private var spatialMutating = false
    private val usbVolume = RootUsbAbsoluteVolume(originalContext)
    private val dongleHid by lazy { RootDongleHid(originalContext, expectedUid, ::enforceCaller) }
    private var microphoneProbe: Pair<IPlaybackRecoveryOwner, RootMicrophoneProbe>? = null
    private var communication: Pair<IPlaybackRecoveryOwner, RootCommunicationRoute>? = null
    private var spatialAudio: SpatialAudioAccess? = null
    @Volatile private var headTrackerProbe: RootHeadTrackerProbe? = null
    @Volatile private var headTrackerSink: RootHeadTrackerSink? = null
    @Volatile private var atmosSpatial: RootSpatialControl? = null
    private val volumeSync by lazy {
        BluetoothVolumeSync(shellContext, audio = volumeRepairAudio(ShizukuVolumeSyncAudio(shellContext,
            binder = { checkNotNull(SystemServiceHelper.getSystemService(it)) },
            packageName = shellContext.packageName)), granted = { !cleanupFailed.get() })
    }

    private fun volumeRepairAudio(audio: BluetoothVolumeSync.Audio) = object : BluetoothVolumeSync.Audio by audio {
        private fun beforeWrite() = synchronized(lock) {
            check(volumeChecking)
            if (!volumeRepairing) {
                volumeRepairing = true
                // Retire locally before even a sub-poll-period repair; never stop for observation alone.
            }
        }
        override fun setVolume(index: Int) { beforeWrite(); audio.setVolume(index) }
        override fun setAbsolute(route: BluetoothVolumeSync.Route) { beforeWrite(); audio.setAbsolute(route) }
    }

    override fun ensureOwnBackgroundAudio(): Boolean {
        enforceCaller()
        if (Build.VERSION.SDK_INT < 37) return true
        val identity = Binder.clearCallingIdentity()
        return try {
            check(!cleanupFailed.get())
            allowOwnBackgroundAudio(expectedUid, BuildConfig.APPLICATION_ID) {
                checkNotNull(SystemServiceHelper.getSystemService(Context.APP_OPS_SERVICE))
            }
            @Suppress("DEPRECATION")
            val applied = originalContext.getSystemService(AppOpsManager::class.java)
                .unsafeCheckOpRawNoThrow("android:control_audio", expectedUid, BuildConfig.APPLICATION_ID) ==
                AppOpsManager.MODE_ALLOWED
            android.util.Log.i("BluetoothAccess", "Own background audio allowed=$applied")
            applied
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    override fun checkVolumeSync(address: String, owner: IPlaybackRecoveryOwner): Boolean {
        enforceCaller()
        check(originalContext.packageManager.checkPermission(Manifest.permission.BLUETOOTH_CONNECT,
            BuildConfig.APPLICATION_ID) == PackageManager.PERMISSION_GRANTED)
        val selected = A2dpRecoveryGuard(address).address
        synchronized(lock) {
            check(!cleanupFailed.get() && !volumeChecking && !spatialMutating && playbackRecovery == null &&
                playbackRelease == null && callConnection == null && probeIsQuiet())
            volumeChecking = true
        }
        val identity = Binder.clearCallingIdentity()
        return try {
            if (!owner.isCurrent) return false
            volumeSync.selectRoute(selected)
            volumeSync.check(selected, owner.asBinder()) {
                !cleanupFailed.get() && runCatching { owner.isCurrent }.getOrDefault(false)
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
            synchronized(lock) { volumeRepairing = false; volumeChecking = false }
        }
    }

    override fun forgetVolumeSync(owner: IPlaybackRecoveryOwner) {
        enforceCaller()
        volumeSync.forget(owner.asBinder())
    }

    override fun inspectUsbAbsoluteVolume(audioDeviceId: Int, boardSerial: String): Bundle {
        enforceCaller()
        val identity = Binder.clearCallingIdentity()
        return try {
            check(!cleanupFailed.get())
            UsbAbsoluteVolumeInspection.inspect(shellContext, audioDeviceId, boardSerial)
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    override fun inspectUsbAbsoluteVolumePolicy(policy: IUsbAbsoluteVolumePolicy, audioDeviceId: Int,
        boardSerial: String): Bundle {
        enforceCaller(); check(android.os.Process.myUid() == 0)
        val identity = Binder.clearCallingIdentity()
        return try {
            check(!cleanupFailed.get())
            val selected = if (audioDeviceId == 0) UsbAbsoluteVolumeInspection.resolveDeviceId(originalContext, boardSerial) ?: 0 else audioDeviceId
            if (selected <= 0) Bundle().apply {
                putBoolean("available", false); putString("reason", "root_identity_unavailable")
            } else usbVolume.inspect(policy, selected, boardSerial)
        }
        finally { Binder.restoreCallingIdentity(identity) }
    }

    override fun openDongleHid(deviceName: String, boardSerial: String, interfaceId: Int,
        owner: IPlaybackRecoveryOwner): IUsbHidConnection {
        enforceCaller()
        check(Process.myUid() == 0) { "Shared USB controls require root access" }
        val pid = Binder.getCallingPid()
        val identity = Binder.clearCallingIdentity()
        return try {
            check(!cleanupFailed.get())
            val device = originalContext.getSystemService(android.hardware.usb.UsbManager::class.java)
                .deviceList[deviceName] ?: error("USB dongle detached")
            dongleHid.open(DongleHidIdentity.Target(deviceName, boardSerial, device.productId, interfaceId), pid, owner)
        } catch (error: Exception) {
            throw if (error is RuntimeException) error else
                IllegalStateException("Shared USB controls could not open: ${error.message}", error)
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    override fun retireOwner(owner: IPlaybackRecoveryOwner) {
        enforceCaller()
        atmosSpatial?.retire(owner)
        headTrackerProbe?.finish(owner)
        spatialAudio?.retire(owner)
        releaseUsbAbsoluteVolume(owner)
        finishCommunication(owner, false)
        finishMicrophoneProbe(owner)
        finishOwnedMedia(owner, false)
        volumeSync.forget(owner.asBinder())
    }

    override fun inspectSpatialAudio(address: String, owner: IPlaybackRecoveryOwner): Bundle =
        spatialAudioCall(false) { it.inspect(address, owner) }

    override fun setFixedSpatialEnabled(address: String, owner: IPlaybackRecoveryOwner, enabled: Boolean): Bundle =
        spatialAudioCall(true) { it.setFixedEnabled(address, owner, enabled) }

    override fun restoreSpatialAudio(address: String, owner: IPlaybackRecoveryOwner): Bundle =
        spatialAudioCall(true) { it.restore(address, owner) }

    override fun inspectAtmosSpatial(address: String, owner: IPlaybackRecoveryOwner): Bundle =
        atmosSpatialCall { it.inspect(address, owner) }

    override fun setAtmosSpatial(address: String, owner: IPlaybackRecoveryOwner, spatialEnabled: Boolean,
        headTrackingEnabled: Boolean, choiceEpoch: Long): Bundle =
        atmosSpatialCall { it.set(address, owner, spatialEnabled, headTrackingEnabled, choiceEpoch) }

    private fun atmosSpatialCall(action: (RootSpatialControl) -> Bundle): Bundle {
        enforceCaller()
        if (Process.myUid() != 0) return SpatialBackendSnapshot.unavailable("root_required")
        if (originalContext.packageManager.checkPermission(Manifest.permission.BLUETOOTH_CONNECT,
                BuildConfig.APPLICATION_ID) != PackageManager.PERMISSION_GRANTED)
            return SpatialBackendSnapshot.unavailable("permission_denied")
        val identity = Binder.clearCallingIdentity()
        return try {
            val manager = synchronized(lock) {
                if (cleanupFailed.get()) return SpatialBackendSnapshot.unavailable("helper_unavailable")
                atmosSpatial ?: RootSpatialControl(shellContext).also { atmosSpatial = it }
            }
            action(manager)
        } catch (_: Exception) { SpatialBackendSnapshot.unavailable("unavailable") }
        finally { Binder.restoreCallingIdentity(identity) }
    }

    override fun startHeadTrackerProbe(address: String, owner: IPlaybackRecoveryOwner,
        poses: ParcelFileDescriptor, events: IHeadTrackerEvents, seconds: Int): Bundle =
        openHeadTracker(address, owner, poses, events, seconds, false)

    override fun openHeadTracking(address: String, owner: IPlaybackRecoveryOwner,
        poses: ParcelFileDescriptor, events: IHeadTrackerEvents, enablePreference: Boolean): Bundle =
        openHeadTracker(address, owner, poses, events, null, enablePreference)

    private fun headTrackerAccess(): RootHeadTrackerProbe = synchronized(lock) {
        check(!cleanupFailed.get()) { "helper_unavailable" }
        headTrackerProbe ?: RootHeadTrackerProbe(SpatialAudioAccess.Framework(shellContext),
            { selected, selectedOwner, input ->
                val sink = synchronized(lock) {
                    headTrackerSink ?: RootHeadTrackerSink {}.also { headTrackerSink = it }
                }
                sink.open(selected, selectedOwner, input) {}
            }, ::headTrackerMutation, ::headTrackerPermission).also { headTrackerProbe = it }
    }

    private fun openHeadTracker(address: String, owner: IPlaybackRecoveryOwner,
        poses: ParcelFileDescriptor, events: IHeadTrackerEvents, seconds: Int?, enablePreference: Boolean): Bundle {
        try { enforceCaller() } catch (error: Exception) { poses.close(); throw error }
        if (!AudioBridgeRules.mayOpenHeadTracker(Process.myUid() == 0, seconds != null, BuildConfig.DEBUG) ||
            !headTrackerPermission()) {
            poses.close()
            return RootHeadTrackerProbe.failure("probe_unavailable")
        }
        val identity = Binder.clearCallingIdentity()
        return try {
            val probe = headTrackerAccess()
            if (seconds != null) probe.start(address, owner, poses, events, seconds)
            else probe.openNormal(address, owner, poses, events, enablePreference)
        } catch (_: Exception) { poses.close(); RootHeadTrackerProbe.failure("unavailable") }
        finally { Binder.restoreCallingIdentity(identity) }
    }

    override fun disableHeadTracking(address: String, owner: IPlaybackRecoveryOwner): Bundle {
        enforceCaller()
        if (Process.myUid() != 0 || !headTrackerPermission()) return RootHeadTrackerProbe.failure("permission_denied")
        val identity = Binder.clearCallingIdentity()
        return try { headTrackerAccess().disable(address, owner) }
        catch (_: Exception) { RootHeadTrackerProbe.failure("unavailable") }
        finally { Binder.restoreCallingIdentity(identity) }
    }

    override fun headTrackerProbeState(owner: IPlaybackRecoveryOwner): Bundle {
        enforceCaller()
        if (Process.myUid() != 0) return RootHeadTrackerProbe.failure("probe_unavailable")
        val identity = Binder.clearCallingIdentity()
        return try { headTrackerProbe?.state(owner) ?: RootHeadTrackerProbe.failure("no_owned_probe") }
        finally { Binder.restoreCallingIdentity(identity) }
    }

    override fun finishHeadTrackerProbe(owner: IPlaybackRecoveryOwner): Bundle {
        enforceCaller()
        if (Process.myUid() != 0) return RootHeadTrackerProbe.failure("probe_unavailable")
        val identity = Binder.clearCallingIdentity()
        return try { headTrackerProbe?.finish(owner) ?: RootHeadTrackerProbe.failure("no_owned_probe") }
        finally { Binder.restoreCallingIdentity(identity) }
    }

    private fun headTrackerPermission() = originalContext.packageManager.checkPermission(
        Manifest.permission.BLUETOOTH_CONNECT, BuildConfig.APPLICATION_ID) == PackageManager.PERMISSION_GRANTED

    private fun headTrackerMutation(cleanup: Boolean, action: () -> Unit) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 3_000
        while (true) {
            val reserved = synchronized(lock) {
                if (!cleanup) check(!cleanupFailed.get() && !volumeChecking && playbackRecovery == null &&
                    playbackRelease == null && callConnection == null && probeIsQuiet()) { "busy" }
                if (spatialMutating) false else { spatialMutating = true; true }
            }
            if (reserved) break
            check(cleanup && android.os.SystemClock.elapsedRealtime() < deadline) { "busy" }
            Thread.sleep(10)
        }
        try { action() } finally { synchronized(lock) { spatialMutating = false } }
    }

    private fun spatialAudioCall(mutation: Boolean, action: (SpatialAudioAccess) -> Bundle): Bundle {
        enforceCaller()
        if (Process.myUid() != 0) return SpatialAudioAccess.failure("root_required")
        if (originalContext.packageManager.checkPermission(Manifest.permission.BLUETOOTH_CONNECT,
                BuildConfig.APPLICATION_ID) != PackageManager.PERMISSION_GRANTED)
            return SpatialAudioAccess.failure("permission_denied")
        val identity = Binder.clearCallingIdentity()
        var reserved = false
        return try {
            val access = synchronized(lock) {
                if (cleanupFailed.get()) return SpatialAudioAccess.failure("helper_unavailable")
                if (mutation && (spatialMutating || volumeChecking || playbackRecovery != null || playbackRelease != null ||
                        callConnection != null || !probeIsQuiet())) return SpatialAudioAccess.failure("busy")
                val access = spatialAudio ?: SpatialAudioAccess(shellContext).also { spatialAudio = it }
                if (mutation) { spatialMutating = true; reserved = true }
                access
            }
            action(access)
        } catch (_: ReflectiveOperationException) { SpatialAudioAccess.failure("method_unavailable") }
        catch (_: SecurityException) { SpatialAudioAccess.failure("permission_denied") }
        catch (_: Exception) { SpatialAudioAccess.failure("unavailable") }
        finally {
            if (reserved) synchronized(lock) { spatialMutating = false }
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun startUsbAbsoluteVolume(owner: IPlaybackRecoveryOwner, policy: IUsbAbsoluteVolumePolicy,
        events: IUsbAbsoluteVolumeEvents, proof: Bundle): Bundle {
        enforceCaller()
        val identity = Binder.clearCallingIdentity()
        return try { check(!cleanupFailed.get()); usbVolume.start(owner, policy, events, proof) }
        finally { Binder.restoreCallingIdentity(identity) }
    }
    override fun renewUsbAbsoluteVolume(owner: IPlaybackRecoveryOwner, proof: Bundle): Bundle {
        enforceCaller(); val identity = Binder.clearCallingIdentity()
        return try { usbVolume.renew(owner, proof) } finally { Binder.restoreCallingIdentity(identity) }
    }
    override fun notifyUsbAbsoluteVolume(owner: IPlaybackRecoveryOwner, proof: Bundle, acknowledgedRequestSequence: Long): Bundle {
        enforceCaller(); val identity = Binder.clearCallingIdentity()
        return try { usbVolume.notify(owner, proof, acknowledgedRequestSequence) } finally { Binder.restoreCallingIdentity(identity) }
    }
    override fun releaseUsbAbsoluteVolume(owner: IPlaybackRecoveryOwner): Bundle {
        enforceCaller(); val identity = Binder.clearCallingIdentity()
        return try { usbVolume.release(owner) } finally { Binder.restoreCallingIdentity(identity) }
    }

    override fun nativeCall(address: String, owner: IPlaybackRecoveryOwner, connect: Boolean): Int {
        enforceCaller()
        for (permission in listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.READ_PHONE_STATE))
            check(originalContext.packageManager.checkPermission(permission, BuildConfig.APPLICATION_ID) ==
                PackageManager.PERMISSION_GRANTED) { "LibrePods call access is unavailable" }
        val operation = synchronized(lock) {
            check(!cleanupFailed.get() && !volumeChecking && !spatialMutating && playbackRecovery == null &&
                playbackRelease == null && callConnection == null && probeIsQuiet()) { "Another audio operation is active" }
            NativeCallConnection(shellContext, address, owner).also { callConnection = it }
        }
        val identity = Binder.clearCallingIdentity()
        return try { operation.run(connect) } finally {
            Binder.restoreCallingIdentity(identity)
            synchronized(lock) { if (callConnection === operation) callConnection = null }
        }
    }

    override fun pauseOwnedMedia(address: String, owner: IPlaybackRecoveryOwner): Boolean {
        enforceCaller()
        val identity = Binder.clearCallingIdentity()
        return try { synchronized(lock) {
            check(!cleanupFailed.get() && !volumeChecking && !spatialMutating && playbackRecovery == null &&
                playbackRelease == null && callConnection == null && probeIsQuiet()) { "Another audio operation is active" }
            mediaPause?.second?.close(); mediaPause = null
            OwnedMediaPause.acquire(shellContext, address, owner)?.let { mediaPause = owner to it; true } ?: false
        } } finally { Binder.restoreCallingIdentity(identity) }
    }

    override fun finishOwnedMedia(owner: IPlaybackRecoveryOwner, resume: Boolean): Boolean {
        enforceCaller()
        val lease = synchronized(lock) {
            mediaPause?.takeIf { it.first.asBinder() == owner.asBinder() }?.also { mediaPause = null }?.second
        } ?: return false
        val identity = Binder.clearCallingIdentity()
        return try { if (resume) lease.resume() else { lease.close(); false } }
        finally { Binder.restoreCallingIdentity(identity) }
    }

    private fun enforceCaller() {
        AudioBridgeRules.requireCaller(expectedUid, Binder.getCallingUid())
    }

    private fun probeIsQuiet() = microphoneProbe?.second?.isCleanupComplete != false &&
        communication?.second?.isCleanupComplete != false

    override fun inspectCommunication(address: String, kind: Int, targetPackage: String): Bundle {
        enforceCaller()
        for (permission in listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.BLUETOOTH_CONNECT))
            check(originalContext.packageManager.checkPermission(permission, BuildConfig.APPLICATION_ID) ==
                PackageManager.PERMISSION_GRANTED) { "LibrePods call permission is unavailable" }
        val identity = Binder.clearCallingIdentity()
        return try {
            check(!cleanupFailed.get())
            RootCommunicationRoute.inspect(shellContext, A2dpRecoveryGuard(address).address, kind, targetPackage)
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    override fun prepareCommunication(pcm: ParcelFileDescriptor, owner: IPlaybackRecoveryOwner,
        address: String, kind: Int, targetPackage: String, recordingSession: Int,
        allowMedia: Boolean, mediaPackage: String, routeProtocol: Int, hfpControl: IHfpCallControl?): Bundle {
        try {
            enforceCaller()
            require(routeProtocol == 2 && hfpControl != null) { "Unsupported call routing protocol" }
        } catch (failure: Exception) { pcm.close(); throw failure }
        val identity = Binder.clearCallingIdentity()
        var operation: RootCommunicationRoute? = null
        try {
            check(Process.myUid() == 0)
            for (permission in listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.BLUETOOTH_CONNECT))
                check(originalContext.packageManager.checkPermission(permission, BuildConfig.APPLICATION_ID) ==
                    PackageManager.PERMISSION_GRANTED) { "LibrePods call permission is unavailable" }
            check(!microphonePrivacyBlocked()) { "Microphone privacy is enabled" }
            val selected = A2dpRecoveryGuard(address).address
            operation = synchronized(lock) {
                check(!cleanupFailed.get() && !volumeChecking && !spatialMutating && playbackRecovery == null && playbackRelease == null &&
                    callConnection == null && mediaPause == null && microphoneProbe == null && communication == null) {
                    "Another audio operation is active"
                }
                RootCommunicationRoute(shellContext, pcm, owner, selected, kind, targetPackage, recordingSession,
                    allowMedia, mediaPackage, checkNotNull(hfpControl))
                    .also { communication = owner to it }
            }
            return operation.prepare()
        } catch (failure: Exception) {
            runCatching { operation?.close() ?: pcm.close() }
            synchronized(lock) {
                if (communication?.second === operation) {
                    if (operation?.isCleanupComplete != false) communication = null else cleanupFailed.set(true)
                }
            }
            throw failure
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    override fun setCommunicationMuted(owner: IPlaybackRecoveryOwner, muted: Boolean): Boolean {
        enforceCaller()
        val operation = synchronized(lock) {
            communication?.takeIf { it.first.asBinder() == owner.asBinder() }?.second
        } ?: return false
        val identity = Binder.clearCallingIdentity()
        return try { operation.setMuted(muted) } finally { Binder.restoreCallingIdentity(identity) }
    }

    override fun communicationState(owner: IPlaybackRecoveryOwner): Bundle {
        enforceCaller()
        return synchronized(lock) {
            communication?.takeIf { it.first.asBinder() == owner.asBinder() }?.second
        }?.snapshot() ?: Bundle().apply { putBoolean("owned", false); putBoolean("cleanupComplete", true) }
    }

    override fun finishCommunication(owner: IPlaybackRecoveryOwner, inputQuiet: Boolean): Bundle {
        enforceCaller()
        val operation = synchronized(lock) {
            communication?.takeIf { it.first.asBinder() == owner.asBinder() }?.second
        } ?: return Bundle().apply { putBoolean("owned", false); putBoolean("cleanupComplete", true) }
        val identity = Binder.clearCallingIdentity()
        return try { operation.finish(inputQuiet) } finally {
            synchronized(lock) {
                if (communication?.second === operation) {
                    if (operation.isCleanupComplete) communication = null
                    else if (inputQuiet) cleanupFailed.set(true)
                }
            }
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun inspectCompanionMedia(address: String): Bundle {
        enforceCaller()
        check(originalContext.packageManager.checkPermission(Manifest.permission.BLUETOOTH_CONNECT,
            BuildConfig.APPLICATION_ID) == PackageManager.PERMISSION_GRANTED)
        val identity = Binder.clearCallingIdentity()
        return try {
            if (cleanupFailed.get()) return CompanionMediaInspection.failure(MediaInspectionStage.HELPER_CONTEXT, MediaInspectionReason.CLEANUP_FAILED)
            CompanionMediaInspection.read(shellContext, address)
        } catch (error: Exception) {
            CompanionMediaInspection.failure(MediaInspectionStage.HELPER_CONTEXT, CompanionMediaInspection.failureReason(error))
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    override fun inspectCallMedia(address: String): Bundle {
        enforceCaller()
        check(originalContext.packageManager.checkPermission(Manifest.permission.BLUETOOTH_CONNECT,
            BuildConfig.APPLICATION_ID) == PackageManager.PERMISSION_GRANTED)
        val identity = Binder.clearCallingIdentity()
        return try {
            check(Process.myUid() == 0 && !cleanupFailed.get())
            Bundle().apply {
                try {
                    val selected = CallMediaLease.inspectSelection(shellContext, A2dpRecoveryGuard(address).address)
                    putBoolean("eligible", selected != null)
                    putString("package", selected.orEmpty())
                    putString("label", selected?.let {
                        originalContext.packageManager.getApplicationLabel(
                            originalContext.packageManager.getApplicationInfo(it, 0)).toString()
                    }.orEmpty())
                    putString("reason", if (selected == null) "Play music through the AirPods to select a player" else "")
                } catch (failure: Exception) {
                    putBoolean("eligible", false); putString("reason", failure.message ?: "Media focus isolation is unavailable")
                }
            }
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    override fun prepareMicrophoneProbe(pcm: ParcelFileDescriptor, owner: IPlaybackRecoveryOwner): Bundle {
        try { enforceCaller() } catch (failure: Exception) { pcm.close(); throw failure }
        val identity = Binder.clearCallingIdentity()
        var operation: RootMicrophoneProbe? = null
        try {
            check(BuildConfig.DEBUG && Process.myUid() == 0 && Build.VERSION.SDK_INT == 37) {
                "The microphone route probe is unavailable"
            }
            check(originalContext.packageManager.checkPermission(Manifest.permission.RECORD_AUDIO,
                BuildConfig.APPLICATION_ID) == PackageManager.PERMISSION_GRANTED)
            check(!microphonePrivacyBlocked()) { "Microphone privacy is enabled" }
            operation = synchronized(lock) {
                check(!cleanupFailed.get() && !volumeChecking && !spatialMutating && playbackRecovery == null &&
                    playbackRelease == null && callConnection == null && microphoneProbe == null && communication == null) {
                    "Another audio operation is active"
                }
                RootMicrophoneProbe(shellContext, pcm, owner).also { microphoneProbe = owner to it }
            }
            return operation.prepare()
        } catch (failure: Exception) {
            runCatching { operation?.close() ?: pcm.close() }
            synchronized(lock) {
                if (microphoneProbe?.second === operation) {
                    if (operation?.isCleanupComplete != false) microphoneProbe = null
                    else cleanupFailed.set(true)
                }
            }
            throw failure
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    override fun finishMicrophoneProbe(owner: IPlaybackRecoveryOwner): Bundle {
        enforceCaller()
        val operation = synchronized(lock) {
            microphoneProbe?.takeIf { it.first.asBinder() == owner.asBinder() }?.second
        } ?: return Bundle().apply { putBoolean("cleanupComplete", true); putBoolean("owned", false) }
        val identity = Binder.clearCallingIdentity()
        return try { operation.finish() } finally {
            synchronized(lock) {
                if (microphoneProbe?.second === operation) {
                    if (operation.isCleanupComplete) microphoneProbe = null else cleanupFailed.set(true)
                }
            }
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun isMicrophonePrivacyBlocked(): Boolean {
        enforceCaller()
        return microphonePrivacyBlocked()
    }

    private fun microphonePrivacyBlocked(): Boolean {
        val identity = Binder.clearCallingIdentity()
        return try {
            ShellMicrophonePrivacy.isBlocked(shellContext)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun restoreNativePlayback(address: String, owner: IPlaybackRecoveryOwner): Boolean =
        nativePlayback(address, owner, connectOnly = false)

    override fun connectNativePlayback(address: String, owner: IPlaybackRecoveryOwner): Boolean =
        nativePlayback(address, owner, connectOnly = true)

    override fun releaseNativePlayback(address: String, owner: IPlaybackRecoveryOwner): Boolean =
        releaseNativePlayback(address, owner, inspectOnly = false)

    override fun inspectNativePlayback(address: String, owner: IPlaybackRecoveryOwner): Boolean =
        releaseNativePlayback(address, owner, inspectOnly = true)

    private fun releaseNativePlayback(address: String, owner: IPlaybackRecoveryOwner, inspectOnly: Boolean): Boolean {
        enforceCaller()
        check(originalContext.packageManager.checkPermission("android.permission.BLUETOOTH_CONNECT", BuildConfig.APPLICATION_ID) ==
            PackageManager.PERMISSION_GRANTED) { "LibrePods Bluetooth permission was revoked" }
        val release = synchronized(lock) {
            check(!cleanupFailed.get() && !volumeChecking && !spatialMutating && playbackRecovery == null && playbackRelease == null && callConnection == null && probeIsQuiet()) {
                "Another audio operation is active"
            }
            NativePlaybackRelease(shellContext, address, owner).also { playbackRelease = it }
        }
        val identity = Binder.clearCallingIdentity()
        return try { release.run(inspectOnly) } finally {
            Binder.restoreCallingIdentity(identity)
            synchronized(lock) { if (playbackRelease === release) playbackRelease = null }
        }
    }

    private fun nativePlayback(address: String, owner: IPlaybackRecoveryOwner, connectOnly: Boolean): Boolean {
        enforceCaller()
        check(originalContext.packageManager.checkPermission("android.permission.BLUETOOTH_CONNECT", BuildConfig.APPLICATION_ID) ==
            PackageManager.PERMISSION_GRANTED) { "LibrePods Bluetooth permission was revoked" }
        val recovery = synchronized(lock) {
            check(!cleanupFailed.get() && !volumeChecking && !spatialMutating && playbackRecovery == null && playbackRelease == null && callConnection == null && probeIsQuiet()) { "Another audio operation is active" }
            A2dpPlaybackRecovery(shellContext, address, owner, connectOnly).also { playbackRecovery = it }
        }
        val identity = Binder.clearCallingIdentity()
        try {
            return recovery.run()
        } finally {
            Binder.restoreCallingIdentity(identity)
            synchronized(lock) { if (playbackRecovery === recovery) playbackRecovery = null }
        }
    }

    override fun confirmPairing(address: String, owner: IPlaybackRecoveryOwner): Boolean {
        enforceCaller()
        if (cleanupFailed.get() || originalContext.packageManager.checkPermission(
                "android.permission.BLUETOOTH_CONNECT", BuildConfig.APPLICATION_ID) != PackageManager.PERMISSION_GRANTED)
            return false
        if (synchronized(lock) { !probeIsQuiet() }) return false
        val identity = Binder.clearCallingIdentity()
        return try {
            ShellPairing.confirm(shellContext, address, owner)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun destroy() {
        val caller = Binder.getCallingUid()
        check(caller == expectedUid || caller == 2000 || caller == 0) { "Invalid bridge owner" }
        close()
        if (exitOnDestroy) kotlin.system.exitProcess(0)
    }

    override fun close() {
        if (!cleanupFailed.compareAndSet(false, true)) return
        synchronized(lock) { headTrackerProbe }?.close()
        headTrackerSink?.close()
        atmosSpatial?.close()
        val routes = synchronized(lock) {
            playbackRecovery?.cancel(); playbackRelease?.cancel(); callConnection?.binderDied()
            mediaPause?.second?.close(); mediaPause = null
            (microphoneProbe?.second to communication?.second).also { microphoneProbe = null; communication = null }
        }
        routes.first?.close()
        routes.second?.close()
        volumeSync.close()
        usbVolume.close()
        dongleHid.close()
    }
}
