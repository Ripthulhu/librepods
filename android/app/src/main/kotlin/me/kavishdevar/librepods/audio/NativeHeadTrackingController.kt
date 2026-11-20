/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.audio

import android.bluetooth.BluetoothSocket
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.Bundle
import android.system.Os
import android.system.OsConstants
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.ComponentStatus
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.DeviceComponent
import me.kavishdevar.librepods.core.utils.HeadTracking
import me.kavishdevar.librepods.core.utils.MotionSample
import me.kavishdevar.librepods.shizuku.HeadTrackerUhidProtocol
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import me.kavishdevar.librepods.shizuku.SpatialBackendSnapshot

data class NativeHeadTrackingState(val enabled: Boolean? = null, val calibrated: Boolean = false,
    val available: Boolean = false, val active: Boolean = false, val busy: Boolean = false,
    val reason: String = "loading", val spatialEnabled: Boolean? = null,
    val spatialAvailable: Boolean = false, val spatialBusy: Boolean = false,
    val spatialSupported: Boolean = false, val actualAudio: Boolean = false,
    val renderedSpatial: Boolean? = null, val renderedTracking: Boolean? = null,
    val appliedGeneration: Long = 0, val desiredSpatialEnabled: Boolean? = null,
    val desiredTrackingEnabled: Boolean? = null)

/** Native preference is the user's choice; reporting owns only this controller's motion source. */
class NativeHeadTrackingController internal constructor(
    private val device: AppleDevice,
    parentScope: CoroutineScope,
    private val hostBlocked: () -> Boolean,
    private val deviceCurrent: () -> Boolean,
    private val access: ShizukuAudioClient,
    private val profiles: SpatialPoseProfileStore,
    private val motion: Flow<MotionSample?>,
    dispatcher: CoroutineDispatcher,
    private val enableAtmos: Boolean = false,
) : AutoCloseable {
    constructor(device: AppleDevice, parentScope: CoroutineScope, hostBlocked: () -> Boolean,
        deviceCurrent: () -> Boolean) : this(device, parentScope, hostBlocked, deviceCurrent,
        ShizukuAudioClient(device.context, requireRoot = true), SpatialPoseProfileStore(device.context),
        HeadTracking.motionSamples, Dispatchers.Default, enableAtmos = true)

    private data class Choice(val epoch: Long, val enabled: Boolean, val started: Long, val spatial: Boolean = false)
    private val preferences = device.context.getSharedPreferences("spatial-controls", android.content.Context.MODE_PRIVATE)
    private val preferencePrefix = device.macAddress.value + ":"
    @Volatile private var desiredSpatial: Boolean? = preferences.getBoolean(preferencePrefix + "spatial", false)
        .takeIf { preferences.contains(preferencePrefix + "spatial") }
    @Volatile private var desiredTracking: Boolean? = preferences.getBoolean(preferencePrefix + "tracking", false)
        .takeIf { preferences.contains(preferencePrefix + "tracking") }
    private val mutableState = MutableStateFlow(NativeHeadTrackingState())
    val state = mutableState.asStateFlow()
    private val closed = AtomicBoolean()
    private val hostIdle = AtomicBoolean()
    private val hostCheckedAt = AtomicLong(Long.MIN_VALUE)
    private val choiceEpoch = AtomicLong(preferences.getLong(preferencePrefix + "epoch", 0).coerceAtLeast(0))
    private val acceptedAtmosEpoch = AtomicLong(-1)
    private val acceptedNativeEpoch = AtomicLong(-1)
    @Volatile private var atmosControls: Bundle? = null
    @Volatile private var inspectedSpatial: Bundle? = null
    private val choiceLock = Any()
    private val pending = AtomicReference<Choice?>()
    private val offRequests = Channel<Choice>(Channel.CONFLATED)
    private val calibrationOwners = mutableSetOf<Any>()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var session: Job? = null
    @Volatile private var sessionLive: AtomicBoolean? = null
    private var sessionSocket: BluetoothSocket? = null
    private var sessionKey: SpatialPoseProfileKey? = null
    @Volatile private var currentBasis: SpatialPoseProfile? = null
    private var sessionBasis: SpatialPoseProfile? = null
    @Volatile private var failedSocket: BluetoothSocket? = null
    @Volatile private var failedKey: SpatialPoseProfileKey? = null
    @Volatile private var poseFaultReason: String? = null
    @Volatile private var nativeOn: Boolean? = null
    @Volatile private var nativeSpatialOn: Boolean? = null
    private val discontinuity = AtomicInteger()
    private val externalMotion = AtomicBoolean()
    private val forceInspect = AtomicBoolean(true)
    private val retryAfter = AtomicLong()
    private val gapRecoveries = HeadMotionGapRecovery.Scopes()
    @Volatile private var fallbackSocket: BluetoothSocket? = null
    @Volatile private var timing = HeadTrackingTiming()
    @Volatile private var previousSessionTiming: Bundle? = null
    @Volatile private var activeLease: ShizukuAudioClient.HeadTrackerLease? = null
    @Volatile private var sinkTiming: Bundle? = null
    @Volatile private var sinkTimingAt = 0L

    internal fun timingSnapshot(): Bundle = timing.snapshot().apply {
        putString("reason", state.value.reason)
        putBoolean("enabled", state.value.enabled == true); putBoolean("active", state.value.active)
        putBoolean("calibrated", state.value.calibrated); putBoolean("available", state.value.available)
        putBoolean("busy", state.value.busy)
        val controls = state.value
        fun choice(name: String, value: Boolean?) { if (value == null) putString(name, null) else putBoolean(name, value) }
        choice("spatial_enabled", controls.spatialEnabled); choice("head_tracking_enabled", controls.enabled)
        choice("desired_spatial", desiredSpatial); choice("desired_tracking", desiredTracking)
        putBoolean("spatial_supported", controls.spatialSupported); putBoolean("spatial_available", controls.spatialAvailable)
        putBoolean("spatial_busy", controls.spatialBusy); putBoolean("actual_audio", controls.actualAudio)
        choice("rendered_spatial", controls.renderedSpatial); choice("rendered_tracking", controls.renderedTracking)
        putLong("applied_generation", controls.appliedGeneration); putLong("choice_epoch", choiceEpoch.get())
        putString("pose_fault_reason", poseFaultReason)
        putBundle("source", Bundle().apply {
            device.aacp.motionSourceTimingSnapshot().forEach { (key, value) -> putLong(key, value) }
        })
        sinkTiming?.let { putBundle("sink", Bundle(it)) }
        putLong("sink_snapshot_age_ms", if (sinkTimingAt == 0L) -1 else SystemClock.elapsedRealtime() - sinkTimingAt)
        previousSessionTiming?.let { putBundle("previous_session", Bundle(it)) }
    }

    private fun replaceSessionTiming(): HeadTrackingTiming {
        // The runner joins the old session before replacement; retain one scalar summary, never nested history.
        timing.snapshot().takeIf { it.getLong("source_epochs") > 0 }?.let {
            it.putLong("retained_at_nanos", SystemClock.elapsedRealtimeNanos())
            previousSessionTiming = it
        }
        return HeadTrackingTiming().also { timing = it; sinkTiming = null; sinkTimingAt = 0 }
    }
    // Bluetooth source cleanup can block in a socket write; it must not delay the user's native Off.
    private val offWriter = scope.launch {
        for (choice in offRequests) writeRequestedChoice(choice)
    }

    private suspend fun writeRequestedChoice(choice: Choice) {
        try {
            if (pending.get() !== choice || !current()) return
            access.connect()
            while (!access.hasRootPrivileges && choiceStillPendingWithinTimeout(choice)) delay(POLL_INTERVAL_MILLISECONDS)
            val owns = {
                current() && pending.get() === choice && choiceEpoch.get() == choice.epoch
            }
            val atmosApplied = applyAtmosChoice(choice, owns)
            if (choice.enabled && !choice.spatial) {
                if (!atmosApplied) failTrackingRequest(choice)
                return // The existing native runner owns preparation and the head-tracker lease.
            }
            val result = writeNativeChoice(choice, owns)
            withChoice(choice.epoch) {
                if (pending.compareAndSet(choice, null)) publishNativeChoiceResult(choice, result, atmosApplied)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            failChoice(choice)
        } finally { forceInspect.set(true) }
    }

    /** Spatial choices set fixed spatial audio (turning head tracking off first when disabling); others turn tracking off. */
    private suspend fun writeNativeChoice(choice: Choice, owns: () -> Boolean): Bundle {
        if (!choice.spatial) return access.disableHeadTracking(device.macAddress.value, owns)
        if (!choice.enabled) access.disableHeadTracking(device.macAddress.value, owns)
        return access.setFixedSpatialEnabled(device.macAddress.value, choice.enabled, owns)
    }

    private fun failTrackingRequest(choice: Choice) = withChoice(choice.epoch) {
        if (pending.compareAndSet(choice, null)) mutableState.update {
            it.copy(enabled = displayedTracking(), busy = false, reason = "request_failed")
        }
    }

    private fun failChoice(choice: Choice) = withChoice(choice.epoch) {
        if (pending.compareAndSet(choice, null)) mutableState.update {
            it.copy(enabled = nativeOn, busy = false, spatialEnabled = nativeSpatialOn,
                spatialBusy = false, reason = "request_failed")
        }
    }

    private fun choiceStillPendingWithinTimeout(choice: Choice) = current() && pending.get() === choice &&
        SystemClock.elapsedRealtime() - choice.started < PENDING_CHOICE_TIMEOUT_MILLISECONDS

    /** Runs under [choiceLock] after this writer retired [choice] from [pending]. */
    private fun publishNativeChoiceResult(choice: Choice, result: Bundle, atmosApplied: Boolean) {
        val nativeApplied = nativeChoiceApplied(choice, result)
        if (nativeApplied) acceptedNativeEpoch.set(choice.epoch)
        val applied = atmosApplied && nativeApplied
        nativeOn = if (choice.spatial) result.flag("head_tracker_enabled") else if (applied) false else null
        if (choice.spatial) publishSpatial(result)
        if (nativeOn == false) clearPoseFault()
        mutableState.update { it.copy(enabled = displayedTracking(), busy = false, spatialBusy = false, active = false,
            reason = if (applied) { if (choice.enabled) "ok" else "off" } else "request_failed") }
    }

    private fun nativeChoiceApplied(choice: Choice, result: Bundle): Boolean {
        if (!choice.spatial) return result.getBoolean("applied")
        if (!result.getBoolean("applied") || result.spatialEnabled() != choice.enabled) return false
        val headTrackerEnabled = result.flag("head_tracker_enabled") ?: return false
        return choice.enabled || !headTrackerEnabled
    }

    private fun clearPoseFault() { failedSocket = null; failedKey = null; poseFaultReason = null }

    private fun poseFaultRecordedFor(socket: BluetoothSocket?, key: SpatialPoseProfileKey?) =
        failedSocket === socket && failedKey == key

    private fun needsAtmos(backend: Bundle? = atmosControls) =
        enableAtmos && !(backend?.getInt("version") == SUPPORTED_SNAPSHOT_VERSION &&
            backend.getString("reason") == SpatialBackendSnapshot.ABSENT)

    private fun atmosPolicyCurrent(): Boolean = !needsAtmos() || atmosControls?.let {
        it.getInt("version", 0) == SUPPORTED_SNAPSHOT_VERSION && it.flag("supported") == true && it.flag("known") == true &&
            (desiredSpatial == null || it.getLong("accepted_generation", -1) == choiceEpoch.get() &&
                it.flag("desired_spatial") == desiredSpatial && it.flag("desired_tracking") == desiredTracking)
    } == true
    private fun displayedTracking() = pending.get()?.let { !it.spatial && it.enabled } ?: nativeOn.takeIf {
        atmosPolicyCurrent() && (!needsAtmos() || atmosControls?.flag("desired_tracking") == it)
    }
    private fun displayedSpatial() = pending.get()?.takeIf { it.spatial }?.enabled ?: nativeSpatialOn.takeIf {
        atmosPolicyCurrent() && (!needsAtmos() || atmosControls?.flag("desired_spatial") == it)
    }

    private fun saveChoice(spatial: Boolean, tracking: Boolean, epoch: Long) {
        desiredSpatial = spatial; desiredTracking = tracking
        savedRearmed = true
        mutableState.update { it.copy(desiredSpatialEnabled = spatial, desiredTrackingEnabled = tracking) }
        preferences.edit().putBoolean(preferencePrefix + "spatial", spatial)
            .putBoolean(preferencePrefix + "tracking", tracking).putLong(preferencePrefix + "epoch", epoch).apply()
    }

    private suspend fun applyAtmosChoice(choice: Choice, owns: () -> Boolean): Boolean {
        if (!enableAtmos) return true
        val pair = synchronized(choiceLock) { (desiredSpatial == true) to (desiredTracking == true) }
        val ownerCurrent = { current() && choiceEpoch.get() == choice.epoch }
        if (acceptedAtmosEpoch.get() == choice.epoch && atmosControls?.let {
                acceptedAtmosSpatial(it, pair.first, pair.second, choice.epoch)
            } == true && ownerCurrent()) return true
        val result = try {
            access.setAtmosSpatial(device.macAddress.value, pair.first, pair.second, choice.epoch, ownerCurrent)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return false }
        if (!owns()) return false
        atmosControls = result
        if (!needsAtmos(result)) return true
        val accepted = result.getBoolean("applied") && acceptedAtmosSpatial(result, pair.first, pair.second, choice.epoch)
        if (accepted) {
            acceptedAtmosEpoch.set(choice.epoch)
            inspectedSpatial?.let { snapshot -> withChoice(choice.epoch) { publishSpatial(snapshot) } }
        }
        return accepted
    }

    internal fun isCalibrationActive() = synchronized(calibrationOwners) { calibrationOwners.isNotEmpty() }

    private inline fun withChoice(epoch: Long, action: () -> Unit) = synchronized(choiceLock) {
        if (choiceEpoch.get() == epoch) action()
    }

    fun setCalibrationActive(owner: Any, active: Boolean) {
        synchronized(calibrationOwners) { if (active) calibrationOwners += owner else calibrationOwners -= owner }
        if (active) sessionLive?.set(false)
        else clearPoseFault()
    }

    fun setEnabled(enabled: Boolean) {
        synchronized(choiceLock) {
            if (closed.get()) return
            val choice = Choice(choiceEpoch.incrementAndGet(), enabled, SystemClock.elapsedRealtime())
            saveChoice(if (enabled) true else desiredSpatial ?: nativeSpatialOn ?: false, enabled, choice.epoch)
            pending.set(choice)
            // Retire the old owner immediately; its late startup cannot override a newer choice.
            sessionLive?.set(false)
            mutableState.update { it.copy(enabled = enabled, active = false, busy = true,
                spatialEnabled = nativeSpatialOn, spatialBusy = false) }
            if (!enabled || enableAtmos) offRequests.trySend(choice)
        }
    }

    fun setSpatialEnabled(enabled: Boolean) {
        synchronized(choiceLock) {
            if (spatialChoiceRejected(mutableState.value, enabled)) return
            val choice = Choice(choiceEpoch.incrementAndGet(), enabled, SystemClock.elapsedRealtime(), spatial = true)
            saveChoice(enabled, false, choice.epoch)
            pending.set(choice)
            sessionLive?.set(false)
            mutableState.update { it.copy(spatialEnabled = enabled, spatialBusy = true,
                enabled = false, busy = false, active = false) }
            offRequests.trySend(choice)
        }
    }

    private fun spatialChoiceRejected(currentState: NativeHeadTrackingState, enabled: Boolean): Boolean {
        if (!current() || !currentState.spatialSupported) return true
        val enablingUnavailable = enabled && (!currentState.spatialAvailable || currentState.spatialEnabled == null)
        return enablingUnavailable || currentState.spatialEnabled == enabled
    }

    private fun headTrackingRequested(): Boolean = if (desiredSpatial == false || desiredTracking == false) false else pending.get()?.let {
        if (it.spatial) it.enabled && nativeOn == true else it.enabled
    } ?: (desiredTracking ?: (nativeOn == true))

    private fun publishSpatial(snapshot: Bundle) {
        val backend = atmosControls
        val optionalReady = !needsAtmos(backend) || backend?.let(::atmosRouteReady) == true
        val writable = optionalReady && fixedSpatialWritable(snapshot)
        nativeSpatialOn = snapshot.spatialEnabled()
        mutableState.update {
            val supported = it.spatialSupported || writable
            val actual = backend?.let { value -> backendRendersActualAudio(value) } == true
            it.copy(spatialSupported = supported, spatialEnabled = displayedSpatial().takeIf { supported },
                spatialAvailable = writable && nativeSpatialOn != null,
                actualAudio = actual, renderedSpatial = if (actual) backend.flag("actual_spatial") else null,
                renderedTracking = if (actual) backend.flag("actual_tracking") else null,
                appliedGeneration = backend?.getLong("applied_generation", 0) ?: 0,
                desiredSpatialEnabled = desiredSpatial, desiredTrackingEnabled = desiredTracking)
        }
    }

    private fun atmosRouteReady(backend: Bundle): Boolean = backend.getInt("version") == SUPPORTED_SNAPSHOT_VERSION &&
        backend.flag("supported") == true && backend.flag("available") == true && backend.flag("route_verified") == true

    private fun fixedSpatialWritable(snapshot: Bundle): Boolean = snapshot.knownSnapshot() &&
        snapshot.flag("fixed_supported") == true && snapshot.flag("route_selected") == true

    /** The backend reports committed output whose applied generation is the accepted, current choice. */
    private fun backendRendersActualAudio(backend: Bundle): Boolean {
        if (backend.flag("actual_audio") != true || backend.flag("output_committed") != true) return false
        if (backend.getLong("applied_generation", 0) <= 0) return false
        if (backend.getLong("applied_generation") != backend.getLong("accepted_generation", -1)) return false
        return desiredSpatial == null || backend.getLong("accepted_generation") == choiceEpoch.get()
    }

    private suspend fun migrateNativeChoice(snapshot: Bundle, eligible: Boolean): Boolean {
        val backend = atmosControls ?: return false
        if (!enableAtmos || !eligible) return false
        if (!atmosRouteReady(backend) || !fixedSpatialRouteSelected(snapshot)) return false
        val spatial = snapshot.spatialEnabled() ?: return false
        val head = snapshot.flag("head_tracker_enabled") ?: return false
        val choice = synchronized(choiceLock) {
            if (hasSavedOrPendingChoice() || !current()) return false
            val epoch = choiceEpoch.incrementAndGet()
            saveChoice(spatial, spatial && head, epoch)
            acceptedNativeEpoch.set(epoch) // The migration carries an observed preference; it never writes Android.
            savedRearmed = true
            Choice(epoch, spatial && head, SystemClock.elapsedRealtime())
        }
        applyAtmosChoice(choice) { current() && choiceEpoch.get() == choice.epoch && pending.get() == null }
        return true
    }

    private fun fixedSpatialRouteSelected(snapshot: Bundle): Boolean =
        snapshot.flag("route_selected") == true && snapshot.flag("fixed_supported") == true

    private fun hasSavedOrPendingChoice() = desiredSpatial != null || desiredTracking != null || pending.get() != null

    private fun current() = !closed.get() && runCatching(deviceCurrent).getOrDefault(false) &&
        device.connectionState.value == ConnectionState.CONNECTED

    private var savedRearmed = false
    private var lastRearmMs = -1L
    private fun rearmSavedChoice(eligible: Boolean) = synchronized(choiceLock) {
        if (!readyToRearm()) return@synchronized
        if (desiredSpatial == null || desiredTracking == null || pending.get() != null) return@synchronized
        if (desiredTracking == true && !eligible) return@synchronized
        if (savedRearmed && atmosPolicyCurrent()) return@synchronized
        val now = SystemClock.elapsedRealtime()
        if (rearmedRecently(now)) return@synchronized
        lastRearmMs = now
        savedRearmed = true
        val tracking = desiredTracking == true
        val choice = Choice(choiceEpoch.get(), if (tracking) true else desiredSpatial == true,
            now, spatial = !tracking)
        pending.set(choice)
        mutableState.update { it.copy(enabled = tracking, busy = tracking, spatialBusy = !tracking) }
        sessionLive?.set(false)
        offRequests.trySend(choice)
    }

    private fun readyToRearm() = current() && access.hasRootPrivileges && mutableState.value.spatialAvailable && hostIdle.get()

    private fun rearmedRecently(now: Long) = lastRearmMs >= 0 && now - lastRearmMs < REARM_MINIMUM_INTERVAL_MILLISECONDS

    private fun reconcileAtmosChoice(eligible: Boolean) = synchronized(choiceLock) {
        val backend = atmosControls ?: return@synchronized
        val spatial = desiredSpatial ?: return@synchronized
        val tracking = desiredTracking ?: return@synchronized
        val epoch = choiceEpoch.get()
        if (!current() || !acceptedAtmosSpatial(backend, spatial, tracking, epoch)) return@synchronized
        acceptedAtmosEpoch.set(epoch)
        if (acceptedNativeEpoch.get() == epoch || pending.get() != null) return@synchronized
        if (!savedRearmed || tracking && !eligible) return@synchronized
        val choice = Choice(epoch, if (tracking) true else spatial, SystemClock.elapsedRealtime(), spatial = !tracking)
        pending.set(choice)
        mutableState.update { it.copy(enabled = tracking, busy = tracking, spatialBusy = !tracking) }
        offRequests.trySend(choice)
    }

    private fun key() = spatialPoseProfileKey(device)

    internal fun offersSpatialMotion(): Boolean =
        nativeSpatialVersion && current() && desiredSpatial == true && desiredTracking == true && atmosPolicyCurrent()

    internal fun openSpatialMotion(scope: CoroutineScope, owner: android.os.IBinder, uid: Int): ISpatialMotionLease? {
        if (!offersSpatialMotion()) return null
        val key = key() ?: return null
        val profile = profiles.load(key) ?: return null
        val socket = device.aacp.captureAudioSocket() ?: return null
        val choice = choiceEpoch.get()
        val epoch = SystemClock.elapsedRealtimeNanos()
        val reset = (epoch and DISCONTINUITY_COUNTER_MASK.toLong()).toInt()
        val allowed = { offersSpatialMotion() && choiceEpoch.get() == choice &&
            currentBasis?.headFromRaw == profile.headFromRaw && sourceCurrent(socket, key) }
        if (!allowed() || !externalMotion.compareAndSet(false, true)) return null
        return try {
            val basis = java.nio.ByteBuffer.allocate(HEAD_FROM_RAW_MATRIX_BYTES).apply { profile.headFromRaw.flatten().forEach(::putDouble) }.array()
            val identity = Bundle().apply {
                putInt("version", EXTERNAL_MOTION_IDENTITY_VERSION)
                putString("peer", device.macAddress.value)
                putLong("choice_epoch", choice)
                putByteArray("full_role", key.rawRole.map(Int::toByte).toByteArray())
                putString("model", key.modelNumber)
                putString("firmware", key.firmware)
                putInt("profile_schema", key.formatVersion)
                putByteArray("profile_digest", java.security.MessageDigest.getInstance("SHA-256").digest(basis))
                putLong("source_epoch", epoch)
                putLong("reference_epoch", epoch)
                putInt("discontinuity", reset)
            }
            ExternalSpatialMotion(scope, owner, uid, identity, allowed, produce = { submit ->
                streamCalibratedHeadMotion(device, motion, profile, reset, allowed,
                    tryFast = fallbackSocket !== socket,
                    onFallback = { fallbackSocket = socket }, submit = submit)
            }, release = { externalMotion.set(false) }).takeIf { it.watch() }
        } catch (failure: Exception) {
            externalMotion.set(false)
            throw failure
        }
    }

    private fun sourceCurrent(socket: BluetoothSocket, key: SpatialPoseProfileKey) = sourceRejection(socket, key) == null

    private fun sourceRejection(reason: String, checkedAt: Long = hostCheckedAt.get(),
        now: Long = SystemClock.elapsedRealtime(), idle: Boolean = hostIdle.get()) =
        HeadTrackingSourceRejection(reason, now, checkedAt, idle)

    private fun sourceRejection(socket: BluetoothSocket, key: SpatialPoseProfileKey): HeadTrackingSourceRejection? {
        if (!current()) return sourceRejection("device_not_current")
        if (!access.hasRootPrivileges) return sourceRejection("root_unavailable")
        val idle = hostIdle.get()
        if (!idle) return sourceRejection("host_blocked", idle = idle)
        val checkedAt = hostCheckedAt.get(); val now = SystemClock.elapsedRealtime()
        if (checkedAt == Long.MIN_VALUE || now - checkedAt !in 0..HOST_CHECK_MAXIMUM_AGE_MILLISECONDS)
            return sourceRejection("host_check_stale", checkedAt, now, idle)
        if (isCalibrationActive()) return sourceRejection("calibration_active")
        if (!device.aacp.ownsAudioSocket(socket)) return sourceRejection("socket_changed")
        if (key() != key) return sourceRejection("profile_key_changed")
        if (listOf(DeviceComponent.LEFT, DeviceComponent.RIGHT).all { side ->
            device.state.value.componentState.any { it.component == side && it.status == ComponentStatus.IN_CASE }
        }) return sourceRejection("buds_in_case")
        return null
    }

    private suspend fun stopSession() {
        sessionLive?.set(false)
        session?.cancelAndJoin()
        session = null; sessionLive = null; sessionSocket = null; sessionKey = null; sessionBasis = null
        mutableState.update { it.copy(active = false) }
    }

    /** The running session no longer matches the requested choice, source, calibration basis or its own liveness. */
    private fun sessionStale(eligible: Boolean, socket: BluetoothSocket?, key: SpatialPoseProfileKey?,
        profile: SpatialPoseProfile?): Boolean {
        if (!eligible || !headTrackingRequested()) return true
        if (sessionSocket !== socket || sessionKey != key || sessionBasis?.headFromRaw != profile?.headFromRaw) return true
        return sessionLive?.get() != true || session?.isActive != true
    }

    private fun canDriveNativeHeadTracking() = !isCalibrationActive() && current() &&
        nativeSpatialVersion && device.metadata.value.model == AirPodsModel.AIRPODS_PRO_3

    private fun fixedSpatialPrepared(result: Bundle): Boolean {
        if (!result.getBoolean("applied") || result.spatialEnabled() != true) return false
        return fixedSpatialRouteSelected(result) && result.flag("head_tracker_enabled") == false
    }

    private fun automaticRestartAllowed(socket: BluetoothSocket?, key: SpatialPoseProfileKey?) = nativeOn == true &&
        SystemClock.elapsedRealtime() >= retryAfter.get() && !poseFaultRecordedFor(socket, key)

    private val runner = scope.launch {
        // Audio/telephony Binder queries stay outside root ownership and per-pose callbacks.
        val guard = launch(Dispatchers.IO) {
            while (isActive) {
                hostIdle.set(runCatching { !hostBlocked() }.getOrDefault(false))
                hostCheckedAt.set(SystemClock.elapsedRealtime())
                delay(HOST_CHECK_INTERVAL_MILLISECONDS)
            }
        }
        var inspectedAt = Long.MIN_VALUE
        var spatialEligible = false
        var spatialReady = false
        try {
            while (isActive) {
                try {
                    val key = if (current()) key() else null
                    val profile = key?.let(profiles::load)
                    currentBasis = profile
                    val socket = device.aacp.captureAudioSocket()
                    val eligible = nativeSpatialVersion && profile != null && socket != null &&
                        sourceCurrent(socket, checkNotNull(key))
                    mutableState.update { it.copy(calibrated = profile != null) }
                    if (session != null && sessionStale(eligible, socket, key, profile)) stopSession()
                    if (!poseFaultRecordedFor(socket, key)) clearPoseFault()
                    val epoch = choiceEpoch.get()
                    val choice = pending.get()
                    if (choice != null && choice.epoch != epoch) { delay(POLL_INTERVAL_MILLISECONDS); continue }
                    if (choice != null && SystemClock.elapsedRealtime() - choice.started >= PENDING_CHOICE_TIMEOUT_MILLISECONDS) {
                        withChoice(epoch) {
                            if (pending.compareAndSet(choice, null)) mutableState.update {
                                it.copy(enabled = nativeOn, busy = false, spatialEnabled = nativeSpatialOn,
                                    spatialBusy = false, reason = "request_failed")
                            }
                        }
                        delay(POLL_INTERVAL_MILLISECONDS)
                        continue
                    }
                    if (choice?.spatial == true) { delay(POLL_INTERVAL_MILLISECONDS); continue }
                    if (choice?.enabled == true && needsAtmos() && acceptedAtmosEpoch.get() != epoch) { delay(POLL_INTERVAL_MILLISECONDS); continue }
                    if (choice?.enabled != false && canDriveNativeHeadTracking()) {
                        access.connect() // Reconnects existing authorization; it never requests a root grant.
                        if (access.hasRootPrivileges) {
                            val now = SystemClock.elapsedRealtime()
                            if (forceInspect.getAndSet(false) || inspectedAt == Long.MIN_VALUE || now - inspectedAt >= INSPECTION_INTERVAL_MILLISECONDS) {
                                val snapshot = access.inspectSpatialAudio(device.macAddress.value, ::current)
                                inspectedSpatial = Bundle(snapshot)
                                if (enableAtmos) atmosControls = access.inspectAtmosSpatial(device.macAddress.value, ::current)
                                activeLease?.let { lease ->
                                    lease.state().getBundle("sink_stats")?.let { stats ->
                                        if (activeLease === lease) { sinkTiming = stats; sinkTimingAt = SystemClock.elapsedRealtime() }
                                    }
                                }
                                if (choiceEpoch.get() != epoch) { forceInspect.set(true); delay(POLL_INTERVAL_MILLISECONDS); continue }
                                inspectedAt = now
                                spatialEligible = false
                                spatialReady = false
                                withChoice(epoch) { publishSpatial(snapshot) }
                                if (snapshot.knownSnapshot()) {
                                    @Suppress("DEPRECATION")
                                    val headPreference = snapshot.flag("head_tracker_enabled")
                                    withChoice(epoch) {
                                        if (nativeOn == false && headPreference == true) clearPoseFault()
                                        nativeOn = headPreference
                                        spatialEligible = headPreference != null && snapshot.flag("device_enabled") == true &&
                                            snapshot.flag("enabled") == true && snapshot.flag("route_selected") == true
                                        spatialReady = headPreference != null && snapshot.flag("fixed_supported") == true &&
                                            snapshot.flag("route_selected") == true
                                        mutableState.update { it.copy(available = eligible && spatialReady,
                                            enabled = displayedTracking(),
                                            reason = snapshot.getString("reason") ?: "unavailable") }
                                    }
                                    if (nativeOn == false && choice == null) stopSession()
                                }
                            }
                            inspectedSpatial?.let { snapshot ->
                                if (migrateNativeChoice(snapshot, eligible)) forceInspect.set(true)
                            }
                            if (choiceEpoch.get() != epoch) { delay(POLL_INTERVAL_MILLISECONDS); continue }
                            rearmSavedChoice(eligible)
                            reconcileAtmosChoice(eligible)
                            val poseFault = failedSocket != null && poseFaultRecordedFor(socket, key)
                            mutableState.update { it.copy(available = eligible && spatialReady && !poseFault,
                                reason = if (poseFault) "motion_changed" else it.reason) }
                            val explicitOn = choice?.enabled == true
                            val fixedSpatialNeedsPreparation = spatialReady && !spatialEligible
                            if (explicitOn && eligible && fixedSpatialNeedsPreparation) {
                                val ownsPreparation = {
                                    current() && pending.get() === choice && choiceEpoch.get() == epoch &&
                                        sourceCurrent(checkNotNull(socket), checkNotNull(key))
                                }
                                val result = access.setFixedSpatialEnabled(device.macAddress.value, true, ownsPreparation)
                                if (!ownsPreparation()) { forceInspect.set(true); delay(POLL_INTERVAL_MILLISECONDS); continue }
                                spatialEligible = fixedSpatialPrepared(result)
                                withChoice(epoch) {
                                    publishSpatial(result)
                                    if (spatialEligible) nativeOn = result.flag("head_tracker_enabled")
                                    else if (pending.compareAndSet(choice, null)) mutableState.update {
                                        it.copy(enabled = nativeOn, busy = false, available = false, reason = "request_failed")
                                    }
                                }
                                if (!spatialEligible) { forceInspect.set(true); delay(POLL_INTERVAL_MILLISECONDS); continue }
                            }
                            val combinedReady = !needsAtmos() || desiredTracking != true || acceptedAtmosEpoch.get() == choiceEpoch.get()
                            val sessionCanStart = session == null && eligible && combinedReady &&
                                headTrackingRequested() && mutableState.value.available && spatialEligible
                            if (sessionCanStart && (explicitOn || automaticRestartAllowed(socket, key))) {
                                if (explicitOn) clearPoseFault()
                                val live = AtomicBoolean(true)
                                val recovery = gapRecoveries.forSource(checkNotNull(socket), checkNotNull(key),
                                    checkNotNull(profile), epoch)
                                sessionLive = live; sessionSocket = socket; sessionKey = key; sessionBasis = profile
                                session = launch { runSensor(checkNotNull(socket), checkNotNull(key),
                                    checkNotNull(profile), live, choice.takeIf { explicitOn }, epoch, recovery) }
                            } else if (explicitOn && (!eligible || !mutableState.value.available)) {
                                withChoice(epoch) {
                                    if (pending.compareAndSet(choice, null)) mutableState.update {
                                        it.copy(enabled = nativeOn, busy = false, reason = "unavailable")
                                    }
                                }
                            }
                        } else { forceInspect.set(true); mutableState.update { it.copy(available = false, spatialAvailable = false) } }
                    } else { forceInspect.set(true); mutableState.update { it.copy(available = false, spatialAvailable = false) } }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutableState.update { it.copy(available = false, spatialAvailable = false, active = false, reason = "unavailable") } }
                delay(POLL_INTERVAL_MILLISECONDS)
            }
        } finally {
            hostIdle.set(false); guard.cancel()
            withContext(NonCancellable) { stopSession(); access.close() }
            mutableState.update { it.copy(active = false, available = false, busy = false, spatialAvailable = false, spatialBusy = false) }
            scope.cancel()
        }
    }
    private val parentCompletion = parentScope.coroutineContext[Job]?.invokeOnCompletion { close() }

    private suspend fun runSensor(socket: BluetoothSocket, key: SpatialPoseProfileKey, profile: SpatialPoseProfile,
        live: AtomicBoolean, choice: Choice?, epoch: Long, recovery: HeadMotionGapRecovery) {
        var pipe: Array<ParcelFileDescriptor>? = null
        var lease: ShizukuAudioClient.HeadTrackerLease? = null
        val reporting = MutableStateFlow(false)
        val stopped = kotlinx.coroutines.CompletableDeferred<String>()
        val sessionTiming = replaceSessionTiming()
        fun owns() = live.get() && choiceEpoch.get() == epoch &&
            (choice != null || pending.get() == null) && sourceCurrent(socket, key)
        try {
            val descriptors = ParcelFileDescriptor.createPipe().also { pipe = it }
            val output = descriptors[1].fileDescriptor
            withContext(Dispatchers.IO) {
                Os.fcntlInt(output, OsConstants.F_SETFL, Os.fcntlInt(output, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
            }
            lease = access.openHeadTracking(device.macAddress.value, descriptors[0], choice != null, ::owns,
                { enabled, _ -> reporting.value = enabled }, { stopped.complete(it) })
            activeLease = lease
            withChoice(epoch) {
                nativeOn = true
                acceptedNativeEpoch.set(epoch)
                if (choice == null || pending.compareAndSet(choice, null))
                    mutableState.update { it.copy(enabled = pending.get()?.enabled ?: true, busy = pending.get() != null) }
            }
            kotlinx.coroutines.coroutineScope {
                val sender = launch(Dispatchers.IO) {
                    reporting.collectLatest { enabled ->
                        recovery.withheld()
                        withChoice(epoch) { mutableState.update { it.copy(active = false) } }
                        if (enabled) {
                            streamCalibratedHeadMotion(device, motion, profile,
                                discontinuity.getAndIncrement() and DISCONTINUITY_COUNTER_MASK, ::owns, sessionTiming,
                                tryFast = fallbackSocket !== socket, onFallback = { fallbackSocket = socket },
                                recovery = recovery, recoveryActive = { reporting.value && owns() }) { pose, arrivalNs ->
                                val frame = HeadTrackerUhidProtocol.little(ByteArray(HeadTrackerUhidProtocol.FRAME_BYTES))
                                    .putInt(HeadTrackerUhidProtocol.FRAME_MAGIC).putInt(pose.discontinuity)
                                    .putLong(arrivalNs)
                                (pose.rotationVector + pose.angularVelocity).forEach { frame.putFloat(it) }
                                check(Os.write(output, frame.array(), 0, frame.capacity()) == frame.capacity()) { "pose_pipe_stalled" }
                                sessionTiming.submitted()
                                withChoice(epoch) { mutableState.update { it.copy(active = true) } }
                            }
                        }
                    }
                }
                try {
                    val reason = stopped.await()
                    check(reason in setOf("finished", "owner_changed", "owner_died", "native_disabled",
                        "route_changed", "spatial_disabled", "state_changed", "permission_denied", "cancelled")) { "sensor_stopped" }
                    withChoice(epoch) {
                        if (reason == "native_disabled") nativeOn = false
                        mutableState.update { it.copy(enabled = pending.get()?.enabled ?: nativeOn,
                            active = false, available = false, reason = reason) }
                    }
                }
                finally { sender.cancelAndJoin() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            withChoice(epoch) {
                if (error is CalibratedHeadMotionFault && owns()) {
                    if (recovery.failed(error.verifiedSensorGap)) retryAfter.set(SystemClock.elapsedRealtime() + SENSOR_RETRY_DELAY_MILLISECONDS)
                    else { failedSocket = socket; failedKey = key; poseFaultReason = error.message }
                }
                else retryAfter.set(SystemClock.elapsedRealtime() + SENSOR_RETRY_DELAY_MILLISECONDS)
                if (choice != null) pending.compareAndSet(choice, null)
                mutableState.update { it.copy(enabled = pending.get()?.enabled ?: nativeOn,
                    active = false, busy = pending.get() != null, available = false,
                    reason = if (poseFaultRecordedFor(socket, key)) "motion_changed" else "sensor_unavailable") }
            }
        } finally {
            recovery.withheld()
            live.set(false)
            forceInspect.set(true)
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { lease?.finish()?.getBundle("sink_stats") }.getOrNull()?.let {
                    if (activeLease === lease) { sinkTiming = it; sinkTimingAt = SystemClock.elapsedRealtime() }
                }
                if (activeLease === lease) activeLease = null
                pipe?.forEach { runCatching { it.close() } }
            }
            withChoice(epoch) { mutableState.update { it.copy(active = false) } }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        sessionLive?.set(false)
        offRequests.close(); offWriter.cancel()
        parentCompletion?.dispose()
        runner.cancel()
    }

    private companion object {
        /** Shared poll cadence of the runner loop and of the off writer waiting for root. */
        const val POLL_INTERVAL_MILLISECONDS = 100L
        /** Cadence of the host audio/telephony idle check. */
        const val HOST_CHECK_INTERVAL_MILLISECONDS = 100L
        /** Oldest host idle check that still lets the head-tracking source run. */
        const val HOST_CHECK_MAXIMUM_AGE_MILLISECONDS = 500L
        /** A pending choice not applied by then is reported as failed; also caps the off writer waiting for root. */
        const val PENDING_CHOICE_TIMEOUT_MILLISECONDS = 5_000L
        /** Minimum spacing between native spatial audio inspections unless one is forced. */
        const val INSPECTION_INTERVAL_MILLISECONDS = 1_000L
        /** Minimum spacing between attempts to re-arm the saved choice. */
        const val REARM_MINIMUM_INTERVAL_MILLISECONDS = 1_000L
        /** Back-off before the runner restarts a sensor session that failed without a pose fault. */
        const val SENSOR_RETRY_DELAY_MILLISECONDS = 2_000L
        /** Discontinuity counters wrap within one byte. */
        const val DISCONTINUITY_COUNTER_MASK = 0xFF
        /** The head-from-raw basis is a 3x3 matrix of doubles. */
        const val HEAD_FROM_RAW_MATRIX_BYTES = 3 * 3 * Double.SIZE_BYTES
        const val EXTERNAL_MOTION_IDENTITY_VERSION = 1
    }
}

@Suppress("DEPRECATION")
internal val nativeSpatialVersion get() = BuildConfig.DEBUG || android.os.Build.VERSION.SDK_INT == NATIVE_HEAD_TRACKING_SDK_VERSION

/** The one Android API level on which release builds drive native spatial head tracking. */
private const val NATIVE_HEAD_TRACKING_SDK_VERSION = 37
/** Schema version of the spatial audio and Atmos snapshots this controller understands. */
private const val SUPPORTED_SNAPSHOT_VERSION = 1

private fun Bundle.flag(name: String) = get(name) as? Boolean

private fun Bundle.knownSnapshot() = getInt("version", 0) == SUPPORTED_SNAPSHOT_VERSION && flag("known") == true

private fun Bundle.spatialEnabled(): Boolean? {
    if (!knownSnapshot() || flag("enabled") == null || flag("device_enabled") == null) return null
    return flag("enabled") == true && flag("device_enabled") == true
}

internal data class HeadTrackingSourceRejection(val reason: String, val atMs: Long,
    val hostCheckedAtMs: Long? = null, val hostIdle: Boolean? = null) {
    fun snapshot() = Bundle().apply {
        putString("reason", reason); putLong("at_ms", atMs)
        hostCheckedAtMs?.let { putLong("host_checked_at_ms", it)
            putLong("host_age_ms", if (it == Long.MIN_VALUE) -1 else atMs - it) }
        hostIdle?.let { putBoolean("host_idle", it) }
    }
}

internal fun acceptedAtmosSpatial(result: Bundle, spatial: Boolean, tracking: Boolean, epoch: Long): Boolean =
    epoch > 0 && (!tracking || spatial) && result.getInt("version", 0) == SUPPORTED_SNAPSHOT_VERSION &&
        result.flag("supported") == true && result.flag("known") == true &&
        result.flag("desired_spatial") == spatial && result.flag("desired_tracking") == tracking &&
        result.getLong("accepted_generation", -1) == epoch
